package io.github.tangbailin.novamarket.service;

import io.github.tangbailin.novamarket.config.NovaMarketProperties;
import io.github.tangbailin.novamarket.port.Repository;
import io.github.tangbailin.novamarket.port.StockPort;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 活动的创建、预热与查询。 */
@Service
public class ActivityService {

    private static final Logger log = LoggerFactory.getLogger(ActivityService.class);

    private final Repository repository;
    private final StockPort stockPort;
    private final NovaMarketProperties properties;

    public ActivityService(Repository repository, StockPort stockPort, NovaMarketProperties properties) {
        this.repository = repository;
        this.stockPort = stockPort;
        this.properties = properties;
    }

    /**
     * 创建活动并预热库存。
     *
     * <p>预热顺序刻意是"先落 DB 分片、再写缓存"：
     * 反过来的话，如果 DB 分片写入失败，缓存里已经有了库存，用户能领到但账上不存在，
     * 对账时会被判定为"缓存多算"然后被修正掉 —— 那些已经发出去的权益就成了坏账。
     * 先写 DB 保证"账本先立起来"，缓存只是加速层。
     */
    @Transactional
    public Repository.ActivityRow create(String name, int totalStock, Duration duration, int perUserLimit) {
        if (name == null || name.isBlank() || name.length() > 128) throw new IllegalArgumentException("活动名称须为 1-128 字符");
        if (perUserLimit < 1 || perUserLimit > 1000) throw new IllegalArgumentException("单人限领须为 1-1000");
        if (duration.isNegative() || duration.isZero() || duration.compareTo(Duration.ofDays(7)) > 0)
            throw new IllegalArgumentException("活动有效期须为 1 秒至 7 天");
        if (totalStock <= 0 || totalStock > 1000000) {
            throw new IllegalArgumentException("总库存必须大于 0");
        }
        // 分片数只有一个来源：配置。缓存实现与 DB 分片必须用同一个值，
        // 否则会出现"缓存在扣 0-7 号分片、DB 只建了 0-3 号"这种一开始就不平账的情况。
        int shardCount = properties.shardCount();
        if (shardCount <= 0) {
            throw new IllegalStateException("novamarket.shard-count 必须为正数，实际: " + shardCount);
        }

        String activityId = "ACT-" + UUID.randomUUID().toString().toUpperCase();
        Instant start = Instant.now();
        Instant end = start.plus(duration);

        Repository.ActivityRow activity = new Repository.ActivityRow(activityId, name, "RUNNING",
                start, end, totalStock, shardCount, perUserLimit);
        repository.insertActivity(activity);
        repository.initShards(activityId, shardCount, totalStock);

        // 预热：把 DB 的分片分布原样搬到缓存，保证一开始两边就平账
        List<Integer> shardStocks = new ArrayList<>();
        for (Repository.ShardRow row : repository.listShards(activityId)) {
            shardStocks.add(row.stock());
        }
        stockPort.resetStock(activityId, shardStocks);

        log.info("活动已创建并预热：id={} totalStock={} shardCount={} perUserLimit={} stockImpl={}",
                activityId, totalStock, shardCount, perUserLimit, stockPort.implName());
        return activity;
    }

    public Repository.ActivityRow find(String activityId) {
        return repository.findActivity(activityId);
    }

    /** 活动详情：同时返回缓存侧与 DB 侧库存，便于直接观察对账差异。 */
    public ActivityDetail detail(String activityId) {
        Repository.ActivityRow activity = repository.findActivity(activityId);
        if (activity == null) {
            return null;
        }
        return new ActivityDetail(
                activity.id(), activity.name(), activity.status(), activity.totalStock(),
                stockPort.totalStock(activityId, activity.shardCount()),
                repository.totalStock(activityId),
                activity.perUserLimit(), stockPort.implName(), repository.countClaims(activityId, "SUCCESS"));
    }

    public List<Repository.ActivityRow> listRunning() {
        return repository.listActivitiesByStatus("RUNNING");
    }

    public record ActivityDetail(String activityId, String name, String status, int initTotalStock,
                                 int cacheStock, int dbStock, int perUserLimit, String stockImpl,
                                 int successClaims) {
    }
}
