package io.github.tangbailin.novamarket.config;

import io.github.tangbailin.novamarket.adapter.InMemoryStockAdapter;
import io.github.tangbailin.novamarket.adapter.InProcessMessageBus;
import io.github.tangbailin.novamarket.adapter.RedisStockAdapter;
import io.github.tangbailin.novamarket.port.MessagePort;
import io.github.tangbailin.novamarket.port.StockPort;
import io.github.tangbailin.novamarket.service.ClaimService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 装配层。这里的每一个 {@code @Bean} 都在回答同一个问题：**这个项目在别人机器上跑得起来吗？**
 *
 * <p>{@code novamarket.stock} 三档语义，刻意区分"自动降级"与"强制失败"：
 * <ul>
 *   <li>{@code auto}（默认）—— 能连上 Redis 就用 Redis，连不上退回内存。保证 clone 下来零依赖可运行；</li>
 *   <li>{@code memory} —— 强制内存。正确性测试用它，避免依赖外部服务导致测试不稳定；</li>
 *   <li>{@code redis} —— 强制 Redis，连不上**启动就失败**。压测必须用这一档，
 *       否则会出现"以为在压 Redis，其实压的是内存"这种最坏情况 —— 数据全错还看不出来。</li>
 * </ul>
 */
@Configuration
@EnableConfigurationProperties(NovaMarketProperties.class)
public class NovaMarketConfiguration {

    private static final Logger log = LoggerFactory.getLogger(NovaMarketConfiguration.class);

    /**
     * 消息端口。
     *
     * <p><b>这里刻意不注入 ClaimService</b>：MessagePort 需要 ClaimService 来注册消费者，
     * 而 ClaimService 的构造函数又需要 MessagePort —— 那就成了循环依赖。
     * 解法是把"注册消费者"从**工厂方法**挪到**装配完成之后**（见下面的 MessageConsumerBinder）。
     * 这也是为什么这个类里没有一个方法同时依赖业务服务与消息端口。
     *
     * <p>带 {@link ConditionalOnMissingBean} 是为了让 profile 里声明同类型 bean 即可接管
     * （例如接真实 RocketMQ），不需要改这个类。
     */
    @Bean
    @ConditionalOnMissingBean(MessagePort.class)
    public MessagePort messagePort() {
        InProcessMessageBus bus = new InProcessMessageBus();
        log.info("消息端口：{}（默认实现，替代 RocketMQ）", bus.implName());
        return bus;
    }

    /**
     * 消费端绑定：容器装配完成后，把业务处理器注册到消息总线上。
     *
     * <p>放在这里而不是 Bean 工厂方法里，是为了切断 MessagePort ↔ ClaimService 的循环依赖。
     * 真实 MQ 场景下这一步由 {@code @RocketMQMessageListener} 注解承担，同样不需要工厂方法参与。
     */
    @Bean
    public MessageConsumerBinder messageConsumerBinder(MessagePort messagePort, ClaimService claimService) {
        return new MessageConsumerBinder(messagePort, claimService);
    }

    /** 把"发奖"主题的消费逻辑绑定到消息端口上。 */
    public static class MessageConsumerBinder {
        public MessageConsumerBinder(MessagePort messagePort, ClaimService claimService) {
            messagePort.subscribe(ClaimService.TOPIC_GRANT, message ->
                    claimService.handleGrantMessage(message.claimNo(), message.payload()));
        }
    }

    /**
     * 库存端口。
     *
     * <p>用 {@code ObjectProvider} 而不是直接注入 {@code StringRedisTemplate}：
     * 不引入 Redis 依赖时该 bean 不存在，直接注入会启动失败。
     */
    @Bean
    @ConditionalOnMissingBean(StockPort.class)
    public StockPort stockPort(NovaMarketProperties properties,
                               ObjectProvider<StringRedisTemplate> redisProvider) {
        String mode = properties.stock() == null ? "auto" : properties.stock().toLowerCase();
        if (!java.util.Set.of("auto", "memory", "redis").contains(mode)) throw new IllegalArgumentException("库存模式须为 auto、memory 或 redis");
        if (properties.shardCount() < 1 || properties.shardCount() > 64 || properties.idemTtlSeconds() < 1)
            throw new IllegalArgumentException("分片数须为 1-64，幂等 TTL 须大于零");
        StringRedisTemplate redis = redisProvider.getIfAvailable();

        if ("memory".equals(mode)) {
            return memoryAdapter(properties, "配置指定 memory");
        }
        if ("redis".equals(mode)) {
            if (redis == null) {
                throw new IllegalStateException(
                        "novamarket.stock=redis 但容器里没有 StringRedisTemplate（检查 spring-boot-starter-data-redis 依赖）");
            }
            RedisStockAdapter adapter = new RedisStockAdapter(redis, properties.shardCount(),
                    properties.idemTtlSeconds(), properties.walTtlSeconds());
            if (!adapter.ping()) {
                // 强制模式下不能悄悄降级：宁可启动失败，也不要产出错误来源的数据
                throw new IllegalStateException(
                        "novamarket.stock=redis 但连不上 Redis。若只想本地跑通，请设 novamarket.stock=auto 或 memory");
            }
            log.info("库存端口：{}（强制模式，已确认 Redis 可达）", adapter.implName());
            return adapter;
        }

        // auto：优先 Redis，探测失败退回内存
        if (redis != null) {
            RedisStockAdapter adapter = new RedisStockAdapter(redis, properties.shardCount(),
                    properties.idemTtlSeconds(), properties.walTtlSeconds());
            if (adapter.ping()) {
                log.info("库存端口：{}（auto 模式探测到 Redis 可用）", adapter.implName());
                return adapter;
            }
            log.warn("检测到 Redis 连接配置但探活失败，回退内存实现。压测请显式使用 novamarket.stock=redis");
        }
        return memoryAdapter(properties, redis == null ? "未配置 Redis" : "Redis 不可用");
    }

    private StockPort memoryAdapter(NovaMarketProperties properties, String reason) {
        InMemoryStockAdapter adapter = new InMemoryStockAdapter(properties.shardCount());
        log.info("库存端口：{}（原因：{}）。注意：内存实现只能验证逻辑正确性，吞吐数据不代表 Redis",
                adapter.implName(), reason);
        return adapter;
    }
}
