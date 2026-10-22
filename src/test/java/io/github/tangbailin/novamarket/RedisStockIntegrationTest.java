package io.github.tangbailin.novamarket;
import io.github.tangbailin.novamarket.adapter.RedisStockAdapter;
import io.github.tangbailin.novamarket.domain.DeductCode;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import static org.assertj.core.api.Assertions.*;

/** Opt in to a dedicated local Redis. Only this test's random keys are deleted; never FLUSHDB. */
@EnabledIfEnvironmentVariable(named="NOVAMARKET_REDIS_TEST_PORT", matches="\\d+")
class RedisStockIntegrationTest {
    LettuceConnectionFactory connection; StringRedisTemplate redis; RedisStockAdapter stock;
    String token, activity;
    @BeforeEach void connect() {
        token=UUID.randomUUID().toString(); activity="RIT-"+token;
        connection=new LettuceConnectionFactory("127.0.0.1",Integer.parseInt(System.getenv("NOVAMARKET_REDIS_TEST_PORT")));
        connection.afterPropertiesSet();connection.start(); redis=new StringRedisTemplate(connection);redis.afterPropertiesSet();
        stock=new RedisStockAdapter(redis,8,1,1); assertThat(stock.ping()).isTrue();
        stock.resetStock(activity,List.of(2,2,2,2,2,2,2,2));
    }
    @AfterEach void disconnect() {
        if(redis!=null) {
            try(var cursor=redis.scan(ScanOptions.scanOptions().match("novamarket:*"+token+"*").count(100).build())) {
                List<String> keys=new ArrayList<>();while(cursor.hasNext())keys.add(cursor.next()); if(!keys.isEmpty())redis.delete(keys);
            }
            var ids=redis.opsForZSet().range("novamarket:wal:index",0,-1);
            if(ids!=null)ids.stream().filter(r->r.contains(token)).forEach(r->redis.opsForZSet().remove("novamarket:wal:index",r));
        }
        if(connection!=null)connection.destroy();
    }
    void race(int n,Runnable work) throws Exception {
        var pool=Executors.newFixedThreadPool(16); var start=new CountDownLatch(1); List<Future<?>> futures=new ArrayList<>();
        try {for(int i=0;i<n;i++)futures.add(pool.submit(()->{try{start.await();work.run();}catch(InterruptedException e){throw new RuntimeException(e);}}));start.countDown();for(var f:futures)f.get(20,TimeUnit.SECONDS);}
        finally {pool.shutdownNow();}
    }
    @Test void fourHundredDuplicatesAndRollbacksChangeStockOnce() throws Exception {
        var deducted=new AtomicInteger();var rolledBack=new AtomicInteger();String r=token+"-r";
        race(400,()->{if(stock.deduct(activity,"u",r,1000).ok())deducted.incrementAndGet();});
        assertThat(deducted.get()).isEqualTo(1);assertThat(stock.totalStock(activity,8)).isEqualTo(15);
        race(400,()->{if(stock.rollback(activity,"u",r))rolledBack.incrementAndGet();});
        assertThat(rolledBack.get()).isEqualTo(1);assertThat(stock.totalStock(activity,8)).isEqualTo(16);
    }
    @Test void pendingWalSurvivesExpiredIdempotencyTtl() throws Exception {
        String r=token+"-ttl";stock.deduct(activity,"u",r,5);Thread.sleep(1200);
        assertThat(stock.deduct(activity,"u",r,5).code()).isEqualTo(DeductCode.DUPLICATE);
        assertThat(stock.staleWalEntries(0,10000)).anyMatch(e->e.requestId().equals(r));
        assertThat(stock.rollback(activity,"u",r)).isTrue();assertThat(stock.totalStock(activity,8)).isEqualTo(16);
    }
    @Test void conflictAndWrongOwnerRollbackAreRejected() {
        String r=token+"-owner";stock.deduct(activity,"u",r,2);
        assertThat(stock.deduct(activity,"other",r,2).code()).isEqualTo(DeductCode.IDEMPOTENCY_CONFLICT);
        assertThat(stock.rollback(activity,"other",r)).isFalse();assertThat(stock.totalStock(activity,8)).isEqualTo(15);
    }
    @Test void settledClaimIsNotRolledBackUsingAnotherClaimCounter() {
        String r=token+"-committed"; stock.deduct(activity,"u",r,2);stock.clearWal(r);
        stock.deduct(activity,"u",token+"-pending",2);
        assertThat(stock.rollback(activity,"u",r)).isFalse();assertThat(stock.totalStock(activity,8)).isEqualTo(14);
    }
    @Test void emptyShardFallbackAndStateRecoveryPreserveLimit() {
        stock.restoreState(activity,List.of(1,0,0,0,0,0,0,0),Map.of());
        var result=stock.deduct(activity,"u",token+"-single",1);
        assertThat(result.ok()).isTrue();assertThat(result.shardNo()).isZero();
        stock.restoreState(activity,List.of(1,0,0,0,0,0,0,0),Map.of("u",1));
        assertThat(stock.deduct(activity,"u",token+"-over",1).code()).isEqualTo(DeductCode.LIMIT_EXCEEDED);
        assertThat(stock.totalStock(activity,8)).isEqualTo(1);
    }
}
