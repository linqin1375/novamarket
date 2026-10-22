package io.github.tangbailin.novamarket;

import io.github.tangbailin.novamarket.adapter.InMemoryStockAdapter;
import io.github.tangbailin.novamarket.adapter.InProcessMessageBus;
import io.github.tangbailin.novamarket.config.NovaMarketProperties;
import io.github.tangbailin.novamarket.domain.ClaimStatus;
import io.github.tangbailin.novamarket.port.MessagePort;
import io.github.tangbailin.novamarket.port.Repository;
import io.github.tangbailin.novamarket.port.StockPort;
import io.github.tangbailin.novamarket.service.ActivityService;
import io.github.tangbailin.novamarket.service.ClaimMetrics;
import io.github.tangbailin.novamarket.service.ClaimService;
import io.github.tangbailin.novamarket.service.ClaimSettler;
import io.github.tangbailin.novamarket.service.CompensationService;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 六个行为测试 —— 这个项目的价值全部压在这里。
 *
 * <p>每一条都对应简历上一句承诺，而且断言必须"有牙"：
 * 不能只断言"没抛异常"，要断言**账户是不是平的**（缓存库存、DB 库存、领取单数、发奖数四者对账）。
 * 之前有过一次教训：测试全绿但步骤实际全挂，因为断言只看行为不看结果。
 *
 * <p>{@code allow-bean-definition-overriding=true} 是**刻意为之**：本测试类要用
 * "可注入失败的消息总线"和"强制内存的库存实现"替换生产 Bean，而 Spring 默认禁止同名 Bean 覆盖。
 * 更"优雅"的条件装配（{@code @ConditionalOnMissingBean}）在这里不可靠 ——
 * 它的评估结果取决于配置类的注册顺序，测试会因此时红时绿，比直白的属性覆盖更糟。
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(properties = "spring.main.allow-bean-definition-overriding=true")
@ActiveProfiles("test")
class ClaimFlowTest {

    @TestConfiguration
    static class TestBeans {
        /**
         * 用"可注入失败"的消息总线替换生产实现，才能验证"发送失败 → 补偿重投"这条路径。
         *
         * <p>这里必须加 {@code @Primary}，原因是：生产装配里那个 {@code messagePort} bean 的
         * **运行期类型也是** {@code InProcessMessageBus}，于是按具体类型注入时会看到两个候选
         * （{@code messagePort} 与 {@code messageBus}），Spring 无法自己选。
         * {@code @Primary} 是给"多个同类型候选"定优先级用的，正好对症。
         */
        @Bean
        @Primary
        public InProcessMessageBus messageBus() {
            return new InProcessMessageBus();
        }

        /**
         * 消费端绑定放在独立 Bean 里，而不是塞进 messagePort 工厂方法 ——
         * 否则 MessagePort 依赖 ClaimService、ClaimService 又依赖 MessagePort，
         * 会直接构成循环依赖导致上下文启动失败。
         */
        @Bean
        public Object messageConsumerBinder(MessagePort messagePort, ClaimService claimService) {
            messagePort.subscribe(ClaimService.TOPIC_GRANT, m ->
                    claimService.handleGrantMessage(m.claimNo(), m.payload()));
            return new Object();
        }

        /** 内存库存实现：测试不依赖外部 Redis，避免测试因环境不稳定而假红。 */
        @Bean
        public StockPort stockPort(NovaMarketProperties properties) {
            return new InMemoryStockAdapter(properties.shardCount());
        }
    }

    @Autowired
    private ActivityService activityService;
    @Autowired
    private ClaimService claimService;
    @Autowired
    private CompensationService compensationService;
    @Autowired
    private Repository repository;
    @Autowired
    private StockPort stockPort;
    @Autowired
    private InProcessMessageBus bus;
    @Autowired
    private ClaimMetrics metrics;

    private String activityId;

    @BeforeEach
    void setUp() {
        // 每个用例一个新活动：库存、限领、幂等缓存互不干扰
        activityId = activityService.create("压测活动-" + System.nanoTime(), 100,
                Duration.ofMinutes(30), 2).id();
    }

    /**
     * 等待某个领取单最终变成"已发奖"。
     *
     * <p><b>为什么不用消息总线的 consumedCount 做等待条件</b>：那是跨用例累加的全局计数，
     * 前一个用例消费过消息之后，{@code awaitConsumed(1)} 会**立刻返回**，等于没等 ——
     * 测试于是变成偶发失败（更糟的情况是偶发通过，掩盖真实问题）。
     * 正确做法是轮询可观测的业务状态，这是异步系统唯一可靠的断言方式。
     */
    private void awaitGranted(String claimNo, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (repository.benefitGranted(claimNo)) {
                return;
            }
            Thread.sleep(10);
        }
    }

    private void awaitClaimStatus(String claimNo, ClaimStatus expected, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Repository.ClaimRow row = repository.findClaimByClaimNo(claimNo);
            if (row != null && expected.name().equals(row.status())) {
                return;
            }
            Thread.sleep(10);
        }
    }

    /**
     * 等待"成功领取数"达到期望值。
     *
     * <p>用轮询 DB 计数而不是在线程池上等：并发测试里有上百条异步发奖消息要处理，
     * 消息总线自己的线程池必须保持空闲去消费它们；一旦在业务线程里做阻塞等待，
     * 就可能把消费线程占满，形成"等的人挡住了被等的人"这种自锁。
     */
    private void awaitSuccessClaims(String activityId, int expected, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (repository.countClaims(activityId, ClaimStatus.SUCCESS.name()) >= expected) {
                return;
            }
            Thread.sleep(20);
        }
    }

    // ==================================================================
    // 1. 并发扣减不超卖 —— 这是"高并发"三个字唯一的证据
    // ==================================================================

    @Test
    void concurrentClaimsNeverOversell() throws Exception {
        int threads = 200;
        int perThread = 5;                 // 1000 次请求抢 100 件库存
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger claimed = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        AtomicInteger limited = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            final int threadNo = t;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        // 每个请求用独立 userId（避免限领干扰）+ 独立 requestId
                        String userId = "u-" + threadNo + "-" + i;
                        var outcome = claimService.claim(activityId, userId,
                                "req-" + threadNo + "-" + i);
                        switch (outcome.code()) {
                            case SUCCESS, PROCESSING -> claimed.incrementAndGet();
                            case SOLD_OUT -> soldOut.incrementAndGet();
                            case LIMIT_EXCEEDED -> limited.incrementAndGet();
                            default -> other.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).as("并发任务未在 60s 内完成").isTrue();
        pool.shutdownNow();
        // 等待所有异步发奖完成：轮询业务状态，而不是等全局计数器
        // （全局计数器跨用例累加，拿它当等待条件等于没等）
        awaitSuccessClaims(activityId, 100, 30_000);

        // 核心断言：恰好卖出 100 件，且四本账互相对得上
        assertThat(claimed.get()).as("成功领取数必须等于总库存").isEqualTo(100);
        assertThat(soldOut.get()).as("其余请求应全部是售罄").isEqualTo(900);
        assertThat(limited.get()).isZero();
        assertThat(other.get()).as("出现非预期返回码，说明有分支异常").isZero();

        assertThat(repository.totalStock(activityId)).as("DB 库存必须归零且不为负").isZero();
        assertThat(stockPort.totalStock(activityId, 8)).as("缓存库存必须归零").isZero();
        assertThat(repository.countClaims(activityId, ClaimStatus.SUCCESS.name()))
                .as("发奖成功的领取单数必须等于库存").isEqualTo(100);
    }

    // ==================================================================
    // 2. 同 requestId 并发重复请求 —— 只扣一次（幂等的核心证据）
    // ==================================================================

    @Test
    void sameRequestIdConcurrentlyDeductsOnlyOnce() throws Exception {
        int threads = 200;
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        String sharedRequestId = "dup-request-001";
        AtomicInteger success = new AtomicInteger();
        AtomicInteger duplicate = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    var outcome = claimService.claim(activityId, "same-user", sharedRequestId);
                    switch (outcome.code()) {
                        case SUCCESS -> success.incrementAndGet();
                        case PROCESSING, DUPLICATE -> duplicate.incrementAndGet();
                        default -> { /* 其他码在下面的断言里会被间接发现 */ }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();
        // 等待这唯一一笔的异步发奖完成：轮询业务状态，而不是等全局计数器
        // （全局计数器在前一个用例消费过消息后就不再是"这次"的计数了）
        awaitSuccessClaims(activityId, 1, 5_000);

        // 200 次并发只有 1 次真正扣减
        assertThat(repository.totalStock(activityId)).as("只应扣掉 1 件库存").isEqualTo(99);
        assertThat(success.get() + duplicate.get()).as("全部请求都应有明确结果").isEqualTo(threads);

        var claim = repository.findClaimByRequestId(sharedRequestId);
        assertThat(claim).as("必须只产生一条领取单").isNotNull();
        assertThat(repository.countClaims(activityId, ClaimStatus.SUCCESS.name())).isEqualTo(1);
    }

    // ==================================================================
    // 3. 超出限领 —— 必须回滚库存，不能泄漏
    // ==================================================================

    @Test
    void exceedingPerUserLimitRollsBackStock() {
        int limit = 2;
        for (int i = 0; i < limit; i++) {
            var ok = claimService.claim(activityId, "limit-user", "limit-req-" + i);
            assertThat(ok.code().name()).as("第 %d 次应成功", i + 1).isIn("PROCESSING", "SUCCESS");
        }
        var third = claimService.claim(activityId, "limit-user", "limit-req-3");
        assertThat(third.code().name()).isEqualTo("LIMIT_EXCEEDED");

        // 关键：第 3 次被拒后库存必须回到"只扣了 2 件"的状态，而不是被多扣
        assertThat(repository.totalStock(activityId)).as("限领拒绝后库存不能被多扣").isEqualTo(98);
        assertThat(stockPort.totalStock(activityId, 8)).as("缓存侧同样只能少 2 件").isEqualTo(98);
    }

    // ==================================================================
    // 4. 落库失败 —— 缓存库存必须回滚，且留下可查的痕迹
    // ==================================================================

    @Test
    void settleFailureRollsBackCacheStock() {
        // 构造落库失败：DB 分片被别人扣空（模拟"缓存有货但账上没货"的错位），
        // 条件更新 where stock > 0 会返回 0 行，从而触发整条回滚路径。
        int shardNo = Math.floorMod("fail-user".hashCode(), 8);
        int drained = repository.totalStock(activityId);
        for (Repository.ShardRow row : repository.listShards(activityId)) {
            if (row.shardNo() == shardNo) {
                // 把这个分片在 DB 里扣空
                for (int i = 0; i < row.stock(); i++) {
                    repository.deductShard(activityId, shardNo);
                }
            }
        }
        int cacheBefore = stockPort.totalStock(activityId, 8);

        var outcome = claimService.claim(activityId, "fail-user", "fail-req-1");

        assertThat(outcome.code().name()).as("落库失败必须对外报错").isEqualTo("INTERNAL_ERROR");
        assertThat(stockPort.totalStock(activityId, 8))
                .as("失败后缓存库存必须回滚到原值，否则库存会静默丢失")
                .isEqualTo(cacheBefore);
        assertThat(drained).isPositive(); // 前置条件成立（原本有库存）
    }

    // ==================================================================
    // 5. 消息发送失败 → 补偿重投 → 最终发奖成功
    // ==================================================================

    @Test
    void failedMessageIsRetriedByCompensationAndEventuallyGranted() throws Exception {
        bus.failNext(1);   // 让这条领取的消息投递失败

        var outcome = claimService.claim(activityId, "retry-user", "retry-req-1");
        assertThat(outcome.code().name()).as("扣减与落库应成功，失败只发生在投递环节")
                .isIn("PROCESSING", "SUCCESS");

        // 此刻：领取单是 PROCESSING，未发奖 —— 这正是本地消息表要兜住的状态
        assertThat(repository.benefitGranted(outcome.claimNo()))
                .as("消息投递失败时不应已经发奖").isFalse();

        // 补偿任务重投
        compensationService.retryPendingMessages();
        awaitGranted(outcome.claimNo(), 5_000);

        assertThat(repository.benefitGranted(outcome.claimNo()))
                .as("补偿重投后必须最终发奖成功").isTrue();
        assertThat(repository.findClaimByClaimNo(outcome.claimNo()).status())
                .isEqualTo(ClaimStatus.SUCCESS.name());
    }

    // ==================================================================
    // 6. MQ 重复投递 —— 发奖必须幂等
    // ==================================================================

    @Test
    void duplicateGrantMessagesGrantOnlyOnce() throws Exception {
        var outcome = claimService.claim(activityId, "dup-grant-user", "dup-grant-req-1");
        assertThat(outcome.code().name()).isIn("PROCESSING", "SUCCESS");
        awaitGranted(outcome.claimNo(), 5_000);

        // 模拟 MQ 重投 4 次
        for (int i = 0; i < 4; i++) {
            bus.replay(ClaimService.TOPIC_GRANT, outcome.claimNo(),
                    "claimNo=" + outcome.claimNo());
        }
        awaitClaimStatus(outcome.claimNo(), ClaimStatus.SUCCESS, 5_000);

        // 发奖表靠唯一索引 claim_no 挡住重复，因此成功领取数仍为 1
        assertThat(repository.countClaims(activityId, ClaimStatus.SUCCESS.name()))
                .as("重复投递不能产生第二笔成功领取").isEqualTo(1);
        assertThat(repository.benefitGranted(outcome.claimNo())).isTrue();
    }

    // ==================================================================
    // 附加：对账任务必须能发现"缓存与 DB 不一致"
    // ==================================================================

    @Test
    void reconcileDetectsAndFixesCacheDbMismatch() {
        claimService.claim(activityId, "recon-user", "recon-req-1");
        assertThat(repository.totalStock(activityId)).isEqualTo(99);

        // 人为制造不一致：把缓存库存改错（模拟"缓存扣了但账没记"）
        stockPort.resetStock(activityId, java.util.List.of(100, 0, 0, 0, 0, 0, 0, 0));
        assertThat(stockPort.totalStock(activityId, 8)).isEqualTo(100);

        compensationService.reconcileStock();

        // 对账应以 DB 为准把缓存修回来
        assertThat(stockPort.totalStock(activityId, 8))
                .as("对账后缓存必须与 DB 一致").isEqualTo(99);
        assertThat(metrics.snapshot().get("reconcileMismatch"))
                .as("对账不一致必须被计数（否则告警发不出去）")
                .isEqualTo(1L);
    }
}
