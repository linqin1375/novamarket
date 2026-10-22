package io.github.tangbailin.novamarket.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 全部可调项集中在这里。压测时要调的（分片数、限流阈值、补偿延迟）
 * 都是配置而不是散落的常量 —— 否则"调参验证"就变成改代码。
 */
@ConfigurationProperties(prefix = "novamarket")
public record NovaMarketProperties(
        String stock,
        int shardCount,
        int perUserLimit,
        int idemTtlSeconds,
        int walTtlSeconds,
        Compensate compensate,
        RateLimit rateLimit
) {
    public record Compensate(boolean enabled, long walSettleDelaySeconds, long messageRetryDelaySeconds,
                             int maxMessageRetry, long reconcileIntervalSeconds) {
    }

    public record RateLimit(boolean enabled, int permitsPerSecond) {
    }
}
