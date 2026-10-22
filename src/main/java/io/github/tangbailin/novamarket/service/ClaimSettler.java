package io.github.tangbailin.novamarket.service;

import io.github.tangbailin.novamarket.domain.ClaimStatus;
import io.github.tangbailin.novamarket.domain.DeductResult;
import io.github.tangbailin.novamarket.port.Repository;

import java.time.Instant;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 落库组件：把一次已完成的预扣减落成数据库事实。
 *
 * <p><b>为什么单独抽一个类而不是写成 {@code ClaimService} 的私有方法</b>：
 * Spring 的 {@code @Transactional} 依赖代理，**同类内部自调用不会走代理，事务不生效**。
 * 这是个很隐蔽的坑 —— 代码看起来加了注解，实际上三张表不在一个事务里，
 * 一旦中间某步失败就会留下"扣了库存但没有领取单"的脏数据。
 * 把它放在独立 bean 里，事务边界才是真实存在的。
 *
 * <p>事务里的三张表是一个不可拆分的最小集合：
 * <ul>
 *   <li>{@code stock_shard} —— 账（DB 侧的库存）</li>
 *   <li>{@code claim_record} —— 凭据（幂等的最终兜底，唯一索引在这里）</li>
 *   <li>{@code local_message} —— 待办（发奖消息，靠它保证"消息一定不会丢"）</li>
 * </ul>
 * 少写任何一张，都会让某条保证失效。
 */
@Component
public class ClaimSettler {

    private final Repository repository;

    public ClaimSettler(Repository repository) {
        this.repository = repository;
    }

    /**
     * 落库。任一步失败整事务回滚，由调用方负责回滚缓存侧库存。
     *
     * @throws IllegalStateException DB 分片扣减失败，或并发重复请求（requestId 唯一索引冲突）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void settle(Repository.ActivityRow activity, String userId, String requestId,
                       DeductResult deduct, String grantTopic) {
        if (!repository.reserveUserQuota(activity.id(), userId, activity.perUserLimit())) {
            throw new QuotaExceededException();
        }
        // 1) DB 侧分片扣减：影响行数为 0 表示扣不到。
        //    注意这里必须抛异常而不是"当作成功" —— 否则缓存与 DB 的账就此错开。
        int affected = repository.deductShard(activity.id(), deduct.shardNo());
        if (affected == 0) {
            throw new IllegalStateException("DB 分片 " + deduct.shardNo() + " 扣减失败（库存不足或分片缺失）");
        }

        // 2) 领取单：唯一索引 request_id 是并发幂等的最终保障
        boolean inserted = repository.insertClaim(new Repository.ClaimRow(0L, requestId, deduct.claimNo(),
                activity.id(), userId, deduct.shardNo(), ClaimStatus.PROCESSING.name(), null));
        if (!inserted) {
            // 并发下另一个请求已插入同一条：本次的 DB 分片扣减属于多扣，必须补回。
            // 抛出异常回滚整个事务，包括本次库存扣减和用户额度；无需额外手动加库存。
            throw new IllegalStateException("并发重复请求：requestId 已存在，本次 DB 扣减已回补");
        }

        // 3) 本地消息与业务表同事务写入 —— "消息不丢"的唯一依据
        repository.insertMessage(new Repository.LocalMessageRow(0L, deduct.claimNo(), grantTopic,
                "claimNo=" + deduct.claimNo() + ";activityId=" + activity.id() + ";userId=" + userId,
                "PENDING", 0, Instant.now()));
    }

    public static class QuotaExceededException extends IllegalStateException {
        public QuotaExceededException() { super("超出数据库用户限领"); }
    }
}
