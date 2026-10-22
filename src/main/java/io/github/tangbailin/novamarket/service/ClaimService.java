package io.github.tangbailin.novamarket.service;

import io.github.tangbailin.novamarket.domain.ClaimCode;
import io.github.tangbailin.novamarket.domain.ClaimStatus;
import io.github.tangbailin.novamarket.domain.DeductCode;
import io.github.tangbailin.novamarket.domain.DeductResult;
import io.github.tangbailin.novamarket.config.NovaMarketProperties;
import io.github.tangbailin.novamarket.port.MessagePort;
import io.github.tangbailin.novamarket.port.Repository;
import io.github.tangbailin.novamarket.port.StockPort;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * 领取链路的核心。
 *
 * <p>整条链路只有 6 步，但每一步的顺序都是被推理出来的，改顺序就会破坏某个保证：
 *
 * <pre>
 *   ① 幂等快路径（查 DB）—— 性能优化，挡住绝大多数重复请求
 *   ② 原子扣减（Redis/内存 Lua 语义）—— 幂等标记 + 库存 + 限领 + WAL，一次原子完成
 *   ③ 落库（本地事务：claim_record + stock_shard + local_message 一起写）
 *   ④ 投递 MQ —— 失败不影响正确性，本地消息表会兜住
 *   ⑤ 消费发奖 —— 靠 benefit_grant 唯一索引做幂等
 *   ⑥ 补偿任务兜底
 * </pre>
 *
 * <p><b>为什么③要写三张表</b>：claim_record 是业务凭据、stock_shard 是账、local_message 是待办。
 * 三者必须同生共死 —— 只写 claim_record 不写 local_message，消息就永远发不出去；
 * 只扣分片不写 claim_record，库存就凭空消失。同一个本地事务是唯一能保证这件事的手段。
 */
@Service
public class ClaimService {

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);

    public static final String TOPIC_GRANT = "novamarket.benefit.grant";

    private final StockPort stockPort;
    private final Repository repository;
    private final MessagePort messagePort;
    private final NovaMarketProperties properties;
    private final ClaimMetrics metrics;
    private final ClaimSettler settler;
    private final RateLimiter rateLimiter;
    private final MaintenanceGate gate;
    private final GrantService grants;

    public ClaimService(StockPort stockPort, Repository repository, MessagePort messagePort,
                        NovaMarketProperties properties, ClaimMetrics metrics, ClaimSettler settler,
                        RateLimiter rateLimiter, MaintenanceGate gate, GrantService grants) {
        this.stockPort = stockPort;
        this.repository = repository;
        this.messagePort = messagePort;
        this.properties = properties;
        this.metrics = metrics;
        this.settler = settler;
        this.rateLimiter = rateLimiter;
        this.gate = gate; this.grants = grants;
    }

    /**
     * 执行一次领取。
     *
     * @param requestId 幂等键，客户端生成并在重试时复用；为空直接拒绝
     */
    public ClaimOutcome claim(String activityId, String userId, String requestId) {
        metrics.enter();
        try {
            return gate.claim(() -> doClaim(activityId, userId, requestId));
        } catch (DataAccessException e) {
            metrics.storageError();
            log.warn("领取存储异常 activity={} requestId={}", activityId, requestId, e);
            return ClaimOutcome.of(ClaimCode.STORAGE_ERROR, null, "存储暂不可用，请复用原请求 ID 查询或重试");
        } finally {
            metrics.leave();
        }
    }

    private ClaimOutcome doClaim(String activityId, String userId, String requestId) {
        validateIdentity(userId, requestId);
        // An idempotent retry must still work after the activity expires or the limiter is full.
        Repository.ClaimRow existing = repository.findClaimByRequestId(requestId);
        if (existing != null) {
            if (!existing.activityId().equals(activityId) || !existing.userId().equals(userId))
                return ClaimOutcome.of(ClaimCode.IDEMPOTENCY_CONFLICT, null, "幂等键已绑定其他活动或用户");
            metrics.hitFastPathIdempotent();
            return fromExisting(existing);
        }
        if (!rateLimiter.tryAcquire(activityId)) {
            metrics.rateLimited();
            return ClaimOutcome.of(ClaimCode.RATE_LIMITED, null, "请求过于频繁，请稍后重试");
        }
        Repository.ActivityRow activity = repository.findActivity(activityId);
        if (activity == null) return ClaimOutcome.of(ClaimCode.ACTIVITY_NOT_FOUND, null, null);
        Instant now = Instant.now();
        if (!"RUNNING".equals(activity.status()) || now.isBefore(activity.startAt()) || !now.isBefore(activity.endAt()))
            return ClaimOutcome.of(ClaimCode.ACTIVITY_NOT_RUNNING, null, "活动未开始或已结束");

        // ② 原子扣减
        DeductResult deduct = stockPort.deduct(activityId, userId, requestId, activity.perUserLimit());
        if (deduct.code() == DeductCode.IDEMPOTENCY_CONFLICT)
            return ClaimOutcome.of(ClaimCode.IDEMPOTENCY_CONFLICT, null, deduct.detail());
        if (deduct.code() == DeductCode.SOLD_OUT) {
            metrics.soldOut();
            return ClaimOutcome.of(ClaimCode.SOLD_OUT, null, null);
        }
        if (deduct.code() == DeductCode.LIMIT_EXCEEDED) {
            metrics.limitExceeded();
            return ClaimOutcome.of(ClaimCode.LIMIT_EXCEEDED, null, null);
        }
        if (deduct.code() == DeductCode.SHARD_NOT_READY) {
            metrics.storageError();
            return ClaimOutcome.of(ClaimCode.STORAGE_ERROR, null, deduct.detail());
        }
        if (deduct.code() == DeductCode.DUPLICATE) {
            // 扣减层发现这是重复请求：说明首次请求还在处理中（尚未落库）。
            // 这里必须返回"处理中"而不是"成功" —— 因为发奖还没发生。
            metrics.hitDeductIdempotent();
            return ClaimOutcome.of(ClaimCode.PROCESSING, deduct.claimNo(), "重复请求，首次仍在处理中");
        }
        if (!deduct.ok()) {
            metrics.storageError();
            return ClaimOutcome.of(ClaimCode.STORAGE_ERROR, null, deduct.detail());
        }

        // ③ 落库：DB 分片扣减 + 领取单 + 本地消息，三者在同一事务里（ClaimSettler）
        try {
            settler.settle(activity, userId, requestId, deduct, TOPIC_GRANT);
        } catch (DataAccessException | IllegalStateException e) {
            // A commit acknowledgement can fail after the DB committed. Never blindly return stock.
            Repository.ClaimRow committed;
            try { committed = repository.findClaimByRequestId(requestId); }
            catch (RuntimeException dbUnavailable) {
                metrics.storageError();
                return ClaimOutcome.of(ClaimCode.STORAGE_ERROR, deduct.claimNo(), "数据库暂不可用，保留预扣记录等待补偿");
            }
            if (committed != null && !"ROLLED_BACK".equals(committed.status()) && !"FAILED".equals(committed.status())) {
                stockPort.clearWal(requestId);
                return committed.activityId().equals(activityId) && committed.userId().equals(userId)
                        ? fromExisting(committed)
                        : ClaimOutcome.of(ClaimCode.IDEMPOTENCY_CONFLICT, null, "幂等键冲突");
            }
            boolean rolledBack;
            try { rolledBack = stockPort.rollback(activityId, userId, requestId); }
            catch (RuntimeException cacheUnavailable) {
                metrics.storageError();
                return ClaimOutcome.of(ClaimCode.STORAGE_ERROR, deduct.claimNo(), "缓存回滚失败，等待预扣补偿");
            }
            metrics.settleFailedRolledBack(rolledBack);
            if (e instanceof ClaimSettler.QuotaExceededException)
                return ClaimOutcome.of(ClaimCode.LIMIT_EXCEEDED, null, "超出数据库用户限领");
            log.error("落库失败，requestId={}，库存回滚={}", requestId, rolledBack, e);
            if (rolledBack) recordRolledBack(activity, userId, requestId, deduct, e.getMessage());
            return ClaimOutcome.of(ClaimCode.INTERNAL_ERROR, deduct.claimNo(), "领取落库失败，请查询状态后重试");

        }

        // The DB committed: the reservation must no longer be eligible for rollback.
        try { stockPort.clearWal(requestId); }
        catch (RuntimeException ignored) { log.warn("WAL 清理失败，将由补偿重试 requestId={}", requestId); }
        // ④ 投递消息。失败不影响正确性：本地消息表里已经是 PENDING，补偿任务会重投。
        trySend(TOPIC_GRANT, deduct.claimNo(),
                payload(activityId, userId, deduct.claimNo()));

        metrics.success();
        return ClaimOutcome.of(repository.benefitGranted(deduct.claimNo()) ? ClaimCode.SUCCESS : ClaimCode.PROCESSING,
                deduct.claimNo(), "领取已受理，请查询发奖状态");
    }

    /** 记录一次"落库失败已回滚"的痕迹，供对账与问题排查。 */
    private void recordRolledBack(Repository.ActivityRow activity, String userId, String requestId,
                                  DeductResult deduct, String reason) {
        try {
            repository.insertClaim(new Repository.ClaimRow(0L, requestId, deduct.claimNo(),
                    activity.id(), userId, deduct.shardNo(), ClaimStatus.ROLLED_BACK.name(),
                    truncate(reason)));
        } catch (RuntimeException ignore) {
            // 记录失败不能掩盖主流程的失败
        }
    }

    /** 投递消息；失败只记日志与指标，正确性由本地消息表 + 补偿任务保证。 */
    public boolean trySend(String topic, String claimNo, String payload) {
        try {
            boolean sent = messagePort.send(claimNo, topic, payload);
            if (!sent) {
                metrics.messageSendFailed();
                log.warn("消息投递失败，等待补偿重投：claimNo={} topic={}", claimNo, topic);
            }
            return sent;
        } catch (RuntimeException e) {
            metrics.messageSendFailed();
            log.warn("消息投递异常，等待补偿重投：claimNo={} err={}", claimNo, e.getMessage());
            return false;
        }
    }

    /**
     * 消费发奖消息。
     *
     * <p>幂等靠 {@code benefit_grant.claim_no} 唯一索引：MQ 重复投递时插入失败，
     * 视为已发奖并直接 ACK，不报错（报错会让 MQ 无限重投）。
     */
    public void handleGrantMessage(String claimNo, String payload) {
        grants.grant(claimNo);
    }

    /** 把已存在的领取单映射成对外的返回码。 */
    private ClaimOutcome fromExisting(Repository.ClaimRow row) {
        ClaimStatus status = ClaimStatus.valueOf(row.status());
        return switch (status) {
            case SUCCESS -> ClaimOutcome.of(ClaimCode.SUCCESS, row.claimNo(), null);
            case PROCESSING -> ClaimOutcome.of(ClaimCode.PROCESSING, row.claimNo(), "处理中");
            case ROLLED_BACK -> ClaimOutcome.of(ClaimCode.INTERNAL_ERROR, row.claimNo(), row.failReason());
            case FAILED -> ClaimOutcome.of(ClaimCode.SOLD_OUT, row.claimNo(), row.failReason());
        };
    }

    private static void validateIdentity(String userId, String requestId) {
        if (userId == null || !userId.matches("[A-Za-z0-9_.@-]{1,64}"))
            throw new IllegalArgumentException("userId 须为 1-64 位字母、数字或 _.@-");
        if (requestId == null || !requestId.matches("[A-Za-z0-9_.-]{1,64}"))
            throw new IllegalArgumentException("X-Request-Id 须为 1-64 位字母、数字或 _.-");
    }

    private String payload(String activityId, String userId, String claimNo) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("claimNo", claimNo);
        map.put("activityId", activityId);
        map.put("userId", userId);
        map.put("ts", Instant.now().toEpochMilli());
        return map.toString();
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** 领取结果。{@code code} 为 {@code PROCESSING} 时**不代表成功**，客户端应轮询查询。 */
    public record ClaimOutcome(ClaimCode code, String claimNo, String detail) {
        public static ClaimOutcome of(ClaimCode code, String claimNo, String detail) {
            return new ClaimOutcome(code, claimNo, detail);
        }

        public boolean isFinalSuccess() {
            return code == ClaimCode.SUCCESS;
        }
    }
}
