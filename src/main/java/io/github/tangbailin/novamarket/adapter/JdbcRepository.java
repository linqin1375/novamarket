package io.github.tangbailin.novamarket.adapter;

import io.github.tangbailin.novamarket.port.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/** JdbcTemplate 实现。所有"是否成功"都通过**影响行数**判断，不依赖异常。 */
@Component
public class JdbcRepository implements Repository {

    private final JdbcTemplate jdbc;

    public JdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<ActivityRow> ACTIVITY_MAPPER = (rs, i) -> new ActivityRow(
            rs.getString("id"), rs.getString("name"), rs.getString("status"),
            rs.getTimestamp("start_at").toInstant(), rs.getTimestamp("end_at").toInstant(),
            rs.getInt("total_stock"), rs.getInt("shard_count"), rs.getInt("per_user_limit"));

    private static final RowMapper<ClaimRow> CLAIM_MAPPER = (rs, i) -> new ClaimRow(
            rs.getLong("id"), rs.getString("request_id"), rs.getString("claim_no"),
            rs.getString("activity_id"), rs.getString("user_id"), rs.getInt("shard_no"),
            rs.getString("status"), rs.getString("fail_reason"));

    private static final RowMapper<LocalMessageRow> MSG_MAPPER = (rs, i) -> new LocalMessageRow(
            rs.getLong("id"), rs.getString("claim_no"), rs.getString("topic"), rs.getString("payload"),
            rs.getString("status"), rs.getInt("retry_count"),
            rs.getTimestamp("next_retry_at").toInstant());

    // ------------------------------------------------------------------ 活动

    @Override
    public void insertActivity(ActivityRow a) {
        jdbc.update("""
                insert into activity(id, name, status, start_at, end_at, total_stock, shard_count,
                                     per_user_limit, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, a.id(), a.name(), a.status(), Timestamp.from(a.startAt()), Timestamp.from(a.endAt()),
                a.totalStock(), a.shardCount(), a.perUserLimit(), Timestamp.from(Instant.now()));
    }

    @Override
    public ActivityRow findActivity(String activityId) {
        return jdbc.query("select * from activity where id = ?", ACTIVITY_MAPPER, activityId)
                .stream().findFirst().orElse(null);
    }

    @Override
    public List<ActivityRow> listActivitiesByStatus(String status) {
        return jdbc.query("select * from activity where status = ? order by id",
                ACTIVITY_MAPPER, status);
    }

    @Override
    public void updateActivityStatus(String activityId, String status) {
        jdbc.update("update activity set status = ? where id = ?", status, activityId);
    }

    // ------------------------------------------------------------------ 库存分片

    @Override
    public List<ActivityRow> listActivities() { return jdbc.query("select * from activity order by created_at desc", ACTIVITY_MAPPER); }

    @Override
    public java.util.Map<String, Integer> userClaimCounts(String activityId) {
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        jdbc.query("select user_id, count(*) as n from claim_record where activity_id=? and status in ('PROCESSING','SUCCESS') group by user_id",
                rs -> { counts.put(rs.getString("user_id"), rs.getInt("n")); }, activityId);
        return counts;
    }

    @Override
    public void rebuildUserQuotas(String activityId) {
        jdbc.update("delete from user_quota where activity_id=?", activityId);
        userClaimCounts(activityId).forEach((user, count) -> jdbc.update(
                "insert into user_quota(activity_id,user_id,claimed) values (?,?,?)", activityId, user, count));
    }

    @Override
    public boolean reserveUserQuota(String activityId, String userId, int limit) {
        try { jdbc.update("insert into user_quota(activity_id,user_id,claimed) values (?,?,0)", activityId, userId); }
        catch (DuplicateKeyException alreadyExists) { /* Existing user counter. */ }
        return jdbc.update("update user_quota set claimed=claimed+1 where activity_id=? and user_id=? and claimed < ?",
                activityId, userId, limit) == 1;
    }

    @Override
    public void initShards(String activityId, int shardCount, int totalStock) {
        int base = totalStock / shardCount;
        int remainder = totalStock % shardCount;
        for (int i = 0; i < shardCount; i++) {
            // 余数摊到前几个分片，保证 sum(shard) == totalStock，否则对账一开始就不平
            int stock = base + (i < remainder ? 1 : 0);
            jdbc.update("insert into stock_shard(activity_id, shard_no, stock, version) values (?, ?, ?, 0)",
                    activityId, i, stock);
        }
    }

    @Override
    public int deductShard(String activityId, int shardNo) {
        // 条件更新的影响行数就是"是否扣到了"的唯一真相：
        // 返回 0 说明库存不足，不需要再查一次（少一次往返，也避免查改之间的竞态）。
        return jdbc.update("""
                update stock_shard set stock = stock - 1, version = version + 1
                where activity_id = ? and shard_no = ? and stock > 0
                """, activityId, shardNo);
    }

    @Override
    public int restoreShard(String activityId, int shardNo) {
        return jdbc.update("""
                update stock_shard set stock = stock + 1, version = version + 1
                where activity_id = ? and shard_no = ?
                """, activityId, shardNo);
    }

    @Override
    public int totalStock(String activityId) {
        Integer total = jdbc.queryForObject(
                "select coalesce(sum(stock), 0) from stock_shard where activity_id = ?",
                Integer.class, activityId);
        return total == null ? 0 : total;
    }

    @Override
    public List<ShardRow> listShards(String activityId) {
        return jdbc.query("select shard_no, stock, version from stock_shard where activity_id = ? order by shard_no",
                (rs, i) -> new ShardRow(rs.getInt("shard_no"), rs.getInt("stock"), rs.getLong("version")),
                activityId);
    }

    // ------------------------------------------------------------------ 领取单

    @Override
    public boolean insertClaim(ClaimRow claim) {
        Instant now = Instant.now();
        try {
            jdbc.update("""
                    insert into claim_record(request_id, claim_no, activity_id, user_id, shard_no,
                                             status, fail_reason, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, claim.requestId(), claim.claimNo(), claim.activityId(), claim.userId(),
                    claim.shardNo(), claim.status(), claim.failReason(),
                    Timestamp.from(now), Timestamp.from(now));
            return true;
        } catch (DuplicateKeyException e) {
            // request_id 唯一索引冲突 = 并发重复请求。这不是错误，是幂等生效。
            return false;
        }
    }

    @Override
    public ClaimRow findClaimByRequestId(String requestId) {
        return jdbc.query("select * from claim_record where request_id = ?", CLAIM_MAPPER, requestId)
                .stream().findFirst().orElse(null);
    }

    @Override
    public ClaimRow findClaimByClaimNo(String claimNo) {
        return jdbc.query("select * from claim_record where claim_no = ?", CLAIM_MAPPER, claimNo)
                .stream().findFirst().orElse(null);
    }

    @Override
    public ClaimRow lockClaimByClaimNo(String claimNo) {
        return jdbc.query("select * from claim_record where claim_no=? for update", CLAIM_MAPPER, claimNo)
                .stream().findFirst().orElse(null);
    }

    @Override
    public void updateClaimStatus(String claimNo, String status, String failReason) {
        jdbc.update("update claim_record set status = ?, fail_reason = ?, updated_at = ? where claim_no = ?",
                status, failReason, Timestamp.from(Instant.now()), claimNo);
    }

    @Override
    public int countClaims(String activityId, String status) {
        Integer n = jdbc.queryForObject(
                "select count(*) from claim_record where activity_id = ? and status = ?",
                Integer.class, activityId, status);
        return n == null ? 0 : n;
    }

    // ------------------------------------------------------------------ 本地消息

    @Override
    public void insertMessage(LocalMessageRow m) {
        Instant now = Instant.now();
        jdbc.update("""
                insert into local_message(claim_no, topic, payload, status, retry_count, next_retry_at,
                                          created_at, updated_at)
                values (?, ?, ?, ?, 0, ?, ?, ?)
                """, m.claimNo(), m.topic(), m.payload(), m.status(),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    @Override
    public List<LocalMessageRow> findMessagesForRetry(int limit) {
        return jdbc.query("""
                select * from local_message
                where status in ('PENDING', 'SENT')
                  and next_retry_at <= ?
                order by id
                limit ?
                """, MSG_MAPPER, Timestamp.from(Instant.now()), limit);
    }

    @Override
    public void markMessageStatus(long id, String status, String lastError) {
        jdbc.update("update local_message set status = ?, last_error = ?, updated_at = ? where id = ? and status <> 'CONFIRMED'",
                status, lastError, Timestamp.from(Instant.now()), id);
    }

    @Override
    public void increaseRetry(long id, int delaySeconds) {
        jdbc.update("""
                update local_message
                set retry_count = retry_count + 1,
                    next_retry_at = ?,
                    updated_at = ?
                where id = ? and status <> 'CONFIRMED'
                """, Timestamp.from(Instant.now().plusSeconds(delaySeconds)),
                Timestamp.from(Instant.now()), id);
    }

    // ------------------------------------------------------------------ 发奖

    @Override
    public void confirmMessage(String claimNo) {
        jdbc.update("update local_message set status='CONFIRMED', last_error=null, updated_at=? where claim_no=?",
                Timestamp.from(Instant.now()), claimNo);
    }

    @Override
    public java.util.Map<String, Integer> messageCounts() {
        java.util.Map<String, Integer> result = new java.util.LinkedHashMap<>();
        jdbc.query("select status,count(*) as n from local_message group by status", rs -> { result.put(rs.getString("status"), rs.getInt("n")); });
        return result;
    }

    @Override
    public boolean grantBenefit(String claimNo, String activityId, String userId) {
        try {
            jdbc.update("insert into benefit_grant(claim_no, activity_id, user_id, granted_at) values (?, ?, ?, ?)",
                    claimNo, activityId, userId, Timestamp.from(Instant.now()));
            return true;
        } catch (DuplicateKeyException e) {
            // 重复投递：唯一索引挡住重复发奖，直接 ACK
            return false;
        }
    }

    @Override
    public boolean benefitGranted(String claimNo) {
        Integer n = jdbc.queryForObject("select count(*) from benefit_grant where claim_no = ?",
                Integer.class, claimNo);
        return n != null && n > 0;
    }

    // ------------------------------------------------------------------ 对账日志

    @Override
    public void insertReconcileLog(Instant checkedAt, int redisTotal, int dbTotal,
                                   int diff, String action, String detail) {
        jdbc.update("""
                insert into reconcile_log(checked_at, redis_total, db_total, diff, action, detail)
                values (?, ?, ?, ?, ?, ?)
                """, Timestamp.from(checkedAt), redisTotal, dbTotal, diff, action, detail);
    }

    /** 供诊断接口使用：最近一次对账记录。 */
    public Optional<String> lastReconcileDetail() {
        return jdbc.query("select detail from reconcile_log order by id desc limit 1",
                        (rs, i) -> rs.getString("detail"))
                .stream().findFirst();
    }
}
