package io.github.tangbailin.novamarket.port;

import io.github.tangbailin.novamarket.port.MessagePort;
import io.github.tangbailin.novamarket.port.StockPort;

/**
 * 持久化端口（活动、库存分片、领取单、本地消息、发奖记录）。
 *
 * <p>用 JdbcTemplate 手写 SQL 而不是引入 MyBatis-Plus，是刻意的选择：
 * <ul>
 *   <li>要展示的是**条件更新的影响行数判断**（{@code update ... where stock > 0} 返回 0 就是扣减失败），
 *       这一点用 ORM 反而看不清；</li>
 *   <li>本项目 SQL 只有十来条，引入 ORM 的收益小于理解成本。</li>
 * </ul>
 * 真实项目里用 MyBatis-Plus / JPA 更常见，这个取舍在设计文档里也写了。
 */
public interface Repository {

    // ------------------------------------------------------------------ 活动

    void insertActivity(ActivityRow activity);

    ActivityRow findActivity(String activityId);

    /** 按状态列出活动（对账任务只关心进行中的活动）。 */
    java.util.List<ActivityRow> listActivitiesByStatus(String status);

    java.util.List<ActivityRow> listActivities();
    java.util.Map<String, Integer> userClaimCounts(String activityId);
    void rebuildUserQuotas(String activityId);
    boolean reserveUserQuota(String activityId, String userId, int limit);

    void updateActivityStatus(String activityId, String status);

    // ------------------------------------------------------------------ 库存分片

    void initShards(String activityId, int shardCount, int totalStock);

    /** 条件更新：只有 stock > 0 才扣减。返回影响行数，0 表示扣减失败。 */
    int deductShard(String activityId, int shardNo);

    /** 回滚一个分片（补偿与失败路径使用）。 */
    int restoreShard(String activityId, int shardNo);

    int totalStock(String activityId);

    java.util.List<ShardRow> listShards(String activityId);

    // ------------------------------------------------------------------ 领取单

    /**
     * 插入领取单。
     *
     * @return true 插入成功；false 表示 requestId 唯一索引冲突（并发重复请求）
     */
    boolean insertClaim(ClaimRow claim);

    ClaimRow findClaimByRequestId(String requestId);

    ClaimRow findClaimByClaimNo(String claimNo);

    ClaimRow lockClaimByClaimNo(String claimNo);

    void updateClaimStatus(String claimNo, String status, String failReason);

    int countClaims(String activityId, String status);

    // ------------------------------------------------------------------ 本地消息

    void insertMessage(LocalMessageRow message);

    java.util.List<LocalMessageRow> findMessagesForRetry(int limit);

    void markMessageStatus(long id, String status, String lastError);

    void increaseRetry(long id, int delaySeconds);

    void confirmMessage(String claimNo);
    java.util.Map<String, Integer> messageCounts();

    // ------------------------------------------------------------------ 发奖

    /** 发奖（唯一索引 claim_no 保证重复投递只发一次）。返回 false 表示已发过。 */
    boolean grantBenefit(String claimNo, String activityId, String userId);

    boolean benefitGranted(String claimNo);

    // ------------------------------------------------------------------ 对账日志

    void insertReconcileLog(java.time.Instant checkedAt, int redisTotal, int dbTotal,
                            int diff, String action, String detail);

    record ActivityRow(String id, String name, String status, java.time.Instant startAt,
                       java.time.Instant endAt, int totalStock, int shardCount, int perUserLimit) {
    }

    record ShardRow(int shardNo, int stock, long version) {
    }

    record ClaimRow(long id, String requestId, String claimNo, String activityId, String userId,
                    int shardNo, String status, String failReason) {
    }

    record LocalMessageRow(long id, String claimNo, String topic, String payload, String status,
                           int retryCount, java.time.Instant nextRetryAt) {
    }
}
