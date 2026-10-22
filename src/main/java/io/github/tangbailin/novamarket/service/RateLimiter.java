package io.github.tangbailin.novamarket.service;

import io.github.tangbailin.novamarket.config.NovaMarketProperties;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

/**
 * 入口限流：按活动维度的令牌桶。
 *
 * <p>单机内存实现，够用且不引入额外依赖。它的作用不是"防住恶意流量"
 * （那应该在网关层做），而是**保护自己**：当上游限流失效、流量突增时，
 * 让系统以稳定的速率拒绝，而不是被打到连接池耗尽、所有请求一起超时。
 *
 * <p>真实场景应换成 Redis 令牌桶（多实例共享配额）或网关限流。这个取舍写在 DESIGN.md 里。
 */
@Component
public class RateLimiter {

    private final NovaMarketProperties properties;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(NovaMarketProperties properties) {
        this.properties = properties;
    }

    /**
     * 尝试获取一个令牌。
     *
     * @return true 放行；false 应拒绝（调用方返回 RATE_LIMITED）
     */
    public boolean tryAcquire(String activityId) {
        if (!properties.rateLimit().enabled()) {
            return true;
        }
        int permits = properties.rateLimit().permitsPerSecond();
        if (permits <= 0) {
            return true;
        }
        return buckets.computeIfAbsent(activityId, k -> new Bucket(permits)).tryAcquire(permits);
    }

    /** 令牌桶：按经过时间补充令牌，容量等于每秒许可数（允许一秒的突发）。 */
    private static final class Bucket {
        private final AtomicLong tokens;
        private volatile long lastRefillMillis;

        Bucket(int capacity) {
            this.tokens = new AtomicLong(capacity);
            this.lastRefillMillis = System.currentTimeMillis();
        }

        boolean tryAcquire(int permitsPerSecond) {
            refill(permitsPerSecond);
            while (true) {
                long current = tokens.get();
                if (current <= 0) {
                    return false;
                }
                if (tokens.compareAndSet(current, current - 1)) {
                    return true;
                }
            }
        }

        private void refill(int permitsPerSecond) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillMillis;
            if (elapsed < 1000) {
                return;
            }
            // 只补充 elapsed 对应的时间片，且不超过容量（避免长时间空闲后一次性放行巨量请求）
            long toAdd = Math.min(permitsPerSecond, (elapsed / 1000) * permitsPerSecond);
            if (toAdd <= 0 || !tryMarkRefill(now)) {
                return;
            }
            tokens.updateAndGet(v -> Math.min(permitsPerSecond, v + toAdd));
        }

        private boolean tryMarkRefill(long now) {
            synchronized (this) {
                if (now - lastRefillMillis < 1000) {
                    return false;
                }
                lastRefillMillis = now;
                return true;
            }
        }
    }
}
