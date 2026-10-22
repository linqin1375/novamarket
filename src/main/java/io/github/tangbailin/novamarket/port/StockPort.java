package io.github.tangbailin.novamarket.port;

import io.github.tangbailin.novamarket.domain.DeductResult;

/**
 * 库存扣减端口。
 *
 * <p>把它抽成端口的直接原因：**让项目在没有 Redis 的机器上也能跑起来**。
 * 一个"必须先装 Redis 才能运行"的项目，在别人手里大概率是跑不起来的，
 * 而跑不起来的项目等于不存在。
 *
 * <p>代价也要说清楚：内存实现只能证明**逻辑正确**，不能证明**高并发能力**。
 * 所以压测分两组（内存 / 真 Redis），数据分别标注环境（见 DESIGN.md 第 6 节）。
 */
public interface StockPort {

    /** 实现标识，用于在接口返回和日志里标明当前跑的是哪套实现。 */
    String implName();

    /**
     * 原子扣减。
     *
     * @param activityId   活动 ID
     * @param userId       用户 ID
     * @param requestId    幂等键，由客户端生成并在重试时复用
     * @param perUserLimit 单用户限领数量
     * @return 扣减结果；任何情况下都不会让库存变成负数
     */
    DeductResult deduct(String activityId, String userId, String requestId, int perUserLimit);

    /**
     * 回滚一次扣减（落库失败、发奖失败、补偿任务使用）。
     *
     * @param activityId 活动 ID —— 必须显式传入，不能靠反查：
     *                   补偿场景下调用方只有 WAL 里的信息，反查是查不到的
     * @param userId     用户 ID —— 同上，用于回退用户限领计数
     * @param requestId  当初扣减用的幂等键
     * @return 是否确实回滚了一次（幂等：重复回滚返回 false）
     */
    boolean rollback(String activityId, String userId, String requestId);

    /**
     * 预扣减写前日志（WAL）中超过给定秒数仍未结算的记录。
     *
     * <p>返回的条目必须自带 {@code activityId} / {@code userId} / {@code shardNo} ——
     * 补偿任务要凭这些信息把库存退回去。只返回 requestId 是不够的，那样"检测到异常却修不了"。
     */
    java.util.List<WalEntry> staleWalEntries(long olderThanSeconds, int limit);

    /** 一条扣减写前日志。 */
    record WalEntry(String requestId, String activityId, String userId, int shardNo,
                    String claimNo, long atMillis) {
    }

    /** 清理 WAL 记录（结算完成或回滚完成后调用）。 */
    void clearWal(String requestId);

    /** 某活动在缓存侧的库存汇总（对账用）。 */
    int totalStock(String activityId, int shardCount);

    java.util.List<Integer> shardStocks(String activityId, int shardCount);

    default boolean hasPendingReservations(String activityId) {
        return staleWalEntries(0, Integer.MAX_VALUE).stream().anyMatch(e -> e.activityId().equals(activityId));
    }

    /** 把库存重置为指定分布（仅用于初始化与对账修正）。 */
    void resetStock(String activityId, java.util.List<Integer> shardStocks);

    /** Rebuild volatile stock and user limits before serving traffic, from committed DB facts. */
    void restoreState(String activityId, java.util.List<Integer> stocks, java.util.Map<String, Integer> counts);
}
