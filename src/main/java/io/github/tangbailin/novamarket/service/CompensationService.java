package io.github.tangbailin.novamarket.service;

import io.github.tangbailin.novamarket.config.NovaMarketProperties;
import io.github.tangbailin.novamarket.domain.ClaimStatus;
import io.github.tangbailin.novamarket.port.Repository;
import io.github.tangbailin.novamarket.port.StockPort;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 三类补偿任务 —— 这是"最终一致性"这句话能落地的唯一原因。
 *
 * <p>常见写法只有一个"定时扫失败记录重试"的任务，那在下面两种情况下是不够的：
 * <ol>
 *   <li><b>进程在"缓存已扣、DB 未落"之间挂掉</b> —— 没有任何失败记录可扫，库存凭空消失；</li>
 *   <li><b>消息已发出但消费者挂掉</b> —— 本地消息表状态是 SENT，普通"扫失败记录"扫不到。</li>
 * </ol>
 *
 * <p>所以这里拆成三个职责单一的任务，最关键的是第一个：它是
 * {@code StockPort} 里那份"扣减写前日志（WAL）"的配套消费方（见 DESIGN.md 3.3 / 3.4）。
 * <b>WAL 与这个任务必须同时存在</b>，只有 WAL 没有消费方等于白记，只有任务没有 WAL 则无从判断。
 */
@Service
public class CompensationService {

    private static final Logger log = LoggerFactory.getLogger(CompensationService.class);

    private final StockPort stockPort;
    private final Repository repository;
    private final ClaimService claimService;
    private final ClaimMetrics metrics;
    private final NovaMarketProperties properties;
    private final MaintenanceGate gate;

    public CompensationService(StockPort stockPort, Repository repository, ClaimService claimService,
                               ClaimMetrics metrics, NovaMarketProperties properties, MaintenanceGate gate) {
        this.stockPort = stockPort;
        this.repository = repository;
        this.claimService = claimService;
        this.metrics = metrics;
        this.properties = properties;
        this.gate = gate;
    }

    // ==================================================================
    // 任务一：WAL 结算器
    // 缓存侧已扣减、但超过阈值仍未落库的 requestId —— 要么补落库，要么回滚库存。
    // ==================================================================

    @Scheduled(fixedDelayString = "${novamarket.compensate.wal-settle-delay-seconds:30}000")
    public void settleStaleWalEntries() { gate.maintain(this::settleStaleWalEntriesLocked); }

    private void settleStaleWalEntriesLocked() {
        if (!properties.compensate().enabled()) {
            return;
        }
        List<StockPort.WalEntry> stale =
                stockPort.staleWalEntries(properties.compensate().walSettleDelaySeconds(), 200);
        for (StockPort.WalEntry entry : stale) {
            try {
                settleOne(entry);
            } catch (RuntimeException e) {
                log.error("WAL 结算失败 requestId={} err={}", entry.requestId(), e.getMessage());
            }
        }
    }

    /**
     * 处理一条"状态不明"的预扣减。
     *
     * <p>这里做的判断就是整个 WAL 机制存在的意义：
     * <ul>
     *   <li>DB 里**已有**领取单 → 落库成功了、只是 WAL 没清 → 清掉即可</li>
     *   <li>DB 里**没有**领取单 → 落库失败或进程中断 → 用 WAL 里的上下文把库存退回原账户</li>
     * </ul>
     * 没有 WAL 的话这两种情况都无从判断：前者会被重复回滚（超卖），后者会永久漏账。
     */
    void settleOne(StockPort.WalEntry entry) {
        Repository.ClaimRow claim = repository.findClaimByRequestId(entry.requestId());
        if (claim != null && ("SUCCESS".equals(claim.status()) || "PROCESSING".equals(claim.status()))) {
            stockPort.clearWal(entry.requestId());
            metrics.walSettled();
            log.debug("WAL 结算：DB 已有领取单，仅清理 WAL requestId={}", entry.requestId());
            return;
        }
        // 没有业务事实 → 这笔预扣减必须退回。
        // 退到哪里由 WAL 里的 activityId / userId 决定 —— 这正是 WAL 要记全上下文的原因。
        boolean rolledBack = stockPort.rollback(entry.activityId(), entry.userId(), entry.requestId());
        if (rolledBack) {
            metrics.walRolledBack();
            log.warn("WAL 检测到未落库的预扣减，已回滚库存 requestId={} activity={} userId={} shard={}",
                    entry.requestId(), entry.activityId(), entry.userId(), entry.shardNo());
        } else {
            // 回滚没成功：可能是幂等缓存已过期（TTL 到了）导致无法定位分片。
            // 这种情况下无法安全回补（不知道退到哪个分片），只清理 WAL 并让对账任务去发现差异。
            log.error("WAL 回滚失败，保留记录供排查 requestId={}", entry.requestId());
        }
    }

    // ==================================================================
    // 任务二：本地消息重投
    // PENDING 或 SENT 超时未确认的，重新投递；超过上限标记 DEAD 并告警。
    // ==================================================================

    @Scheduled(fixedDelayString = "${novamarket.compensate.message-retry-delay-seconds:60}000")
    public void retryPendingMessages() {
        if (!properties.compensate().enabled()) {
            return;
        }
        List<Repository.LocalMessageRow> messages = repository.findMessagesForRetry(200);
        for (Repository.LocalMessageRow m : messages) {
            if (repository.benefitGranted(m.claimNo())) {
                repository.confirmMessage(m.claimNo());
                continue;
            }
            if (m.retryCount() >= properties.compensate().maxMessageRetry()) {
                repository.markMessageStatus(m.id(), "DEAD", "重试超过上限 " + m.retryCount());
                metrics.messageDead();
                log.error("消息进入 DEAD 状态，需要人工介入 claimNo={} topic={}", m.claimNo(), m.topic());
                continue;
            }
            boolean sent = claimService.trySend(m.topic(), m.claimNo(), m.payload());
            if (sent) {
                repository.markMessageStatus(m.id(), "SENT", null);
                repository.increaseRetry(m.id(), retryBackoffSeconds(m.retryCount()));
                metrics.messageRetried();
            } else {
                repository.increaseRetry(m.id(), retryBackoffSeconds(m.retryCount()));
            }
        }
    }

    /**
     * 重试退避：1s → 2s → 4s → 8s，上限 60s。
     *
     * <p>不用固定间隔的原因：如果是下游整体故障，固定间隔重试只会持续加压；
     * 退避能让下游有时间恢复，同时给人工介入留出窗口。
     */
    private int retryBackoffSeconds(int retryCount) {
        return (int) Math.min(60, Math.pow(2, Math.max(0, retryCount)));
    }

    // ==================================================================
    // 任务三：库存对账
    // 缓存侧分片总和 vs DB 侧总和，不一致就告警并修正。
    // ==================================================================

    @Scheduled(fixedDelayString = "${novamarket.compensate.reconcile-interval-seconds:60}000")
    public void reconcileStock() { gate.maintain(this::reconcileStockLocked); }

    private void reconcileStockLocked() {
        if (!properties.compensate().enabled()) {
            return;
        }
        // 简化：对账所有活动。真实场景应只对账"进行中"的活动，并按分片增量比对。
        // No claim holds a reservation while this exclusive maintenance section runs.
        settleStaleWalEntriesLocked();
        List<Repository.ActivityRow> activities = listRunningActivities();
        for (Repository.ActivityRow activity : activities) {
            int cacheTotal = stockPort.totalStock(activity.id(), activity.shardCount());
            int dbTotal = repository.totalStock(activity.id());
            int diff = cacheTotal - dbTotal;
            List<Integer> shardStocks = repository.listShards(activity.id()).stream().map(Repository.ShardRow::stock).toList();
            boolean shardBalanced = shardStocks.equals(stockPort.shardStocks(activity.id(), activity.shardCount()));
            if (diff != 0 || !shardBalanced) {
                metrics.reconcileMismatch();
                log.error("库存对账不一致 activity={} 缓存={} DB={} 差异={}（以 DB 为准修正缓存）",
                        activity.id(), cacheTotal, dbTotal, diff);
                // 以 DB 为准修正缓存。注意这只是"让两边一致"，不代表 DB 一定是对的 ——
                // 生产上应双向核对并人工介入，这一点写在 DESIGN.md 第 7 节第 4 条。
                // Rebuild user limits too. A stock-only repair would allow users to bypass the limit.
                if (stockPort.hasPendingReservations(activity.id())) {
                    log.warn("存在未结算预扣，跳过对账修正 activity={}", activity.id());
                    continue;
                }
                stockPort.restoreState(activity.id(), shardStocks, repository.userClaimCounts(activity.id()));
                repository.insertReconcileLog(Instant.now(), cacheTotal, dbTotal, diff,
                        "RESET_CACHE_FROM_DB", "activity=" + activity.id());
            }
        }
    }

    /** 供对账报告接口使用：不做修正，只报告差异。 */
    public List<ReconcileRow> report(List<Repository.ActivityRow> activities) {
        List<ReconcileRow> rows = new java.util.ArrayList<>();
        for (Repository.ActivityRow a : activities) {
            int cacheTotal = stockPort.totalStock(a.id(), a.shardCount());
            int dbTotal = repository.totalStock(a.id());
            rows.add(new ReconcileRow(a.id(), cacheTotal, dbTotal, cacheTotal - dbTotal,
                    repository.countClaims(a.id(), ClaimStatus.SUCCESS.name()),
                    repository.listShards(a.id()).stream().map(Repository.ShardRow::stock).toList().equals(stockPort.shardStocks(a.id(), a.shardCount()))));
        }
        return rows;
    }

    private List<Repository.ActivityRow> listRunningActivities() {
        return repository.listActivitiesByStatus("RUNNING");
    }

    public record ReconcileRow(String activityId, int cacheTotal, int dbTotal, int diff, int successClaims, boolean shardBalanced) {
    }
}
