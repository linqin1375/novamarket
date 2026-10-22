package io.github.tangbailin.novamarket;

import io.github.tangbailin.novamarket.adapter.InMemoryStockAdapter;
import io.github.tangbailin.novamarket.domain.*;
import io.github.tangbailin.novamarket.port.*;
import io.github.tangbailin.novamarket.service.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class CorrectnessRegressionTest {
    @Autowired ActivityService activities;
    @Autowired ClaimService claims;
    @Autowired Repository repository;
    @Autowired StockPort stock;
    @Autowired StockRecovery recovery;
    @Autowired CompensationService compensation;
    @Autowired MaintenanceGate gate;
    private String id() { return UUID.randomUUID().toString(); }

    @Test void directConcurrentSameKeyIsOneAtomicReservation() throws Exception {
        var memory = new InMemoryStockAdapter(8);
        memory.resetStock("A",List.of(2,2,2,2,2,2,2,2));
        var workers=Executors.newFixedThreadPool(32);
        var start=new CountDownLatch(1); var successes=new AtomicInteger(); var done=new CountDownLatch(400);
        try {
            for(int i=0;i<400;i++) workers.submit(()->{ try { start.await(); if(memory.deduct("A","u","r",1000).ok()) successes.incrementAndGet(); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } finally { done.countDown(); } });
            start.countDown(); assertThat(done.await(10,TimeUnit.SECONDS)).isTrue();
            assertThat(successes.get()).isEqualTo(1); assertThat(memory.totalStock("A",8)).isEqualTo(15);
            assertThat(memory.walSize()).isEqualTo(1);
        } finally { workers.shutdownNow(); }
    }
    @Test void rollbackDoesNotRestoreOtherUsersOrCommittedReservations() {
        var memory=new InMemoryStockAdapter(2); memory.resetStock("A",List.of(2,2));
        assertThat(memory.deduct("A","u","r",2).ok()).isTrue();
        assertThat(memory.rollback("A","other","r")).isFalse();
        assertThat(memory.rollback("A","u","r")).isTrue();
        assertThat(memory.rollback("A","u","r")).isFalse();
        memory.deduct("A","u","r2",2); memory.clearWal("r2");
        assertThat(memory.rollback("A","u","r2")).isFalse();
        assertThat(memory.totalStock("A",2)).isEqualTo(3);
    }
    @Test void emptyPreferredShardFallsBackToRemainingStock() {
        var a=activities.create("仅一件",1,Duration.ofHours(1),1);
        var result=claims.claim(a.id(),"u",id()); // hash('u') targets shard 5, only shard 0 has stock
        assertThat(result.code()).isIn(ClaimCode.PROCESSING,ClaimCode.SUCCESS);
        assertThat(repository.totalStock(a.id())).isZero();
    }
    @Test void keyCannotBeReusedAcrossUsersOrActivities() {
        var a=activities.create("绑定",20,Duration.ofHours(1),2); var b=activities.create("绑定2",20,Duration.ofHours(1),2);
        String request=id(); claims.claim(a.id(),"owner",request);
        assertThat(claims.claim(a.id(),"other",request).code()).isEqualTo(ClaimCode.IDEMPOTENCY_CONFLICT);
        assertThat(claims.claim(b.id(),"owner",request).code()).isEqualTo(ClaimCode.IDEMPOTENCY_CONFLICT);
        assertThat(repository.totalStock(a.id())).isEqualTo(19); assertThat(repository.totalStock(b.id())).isEqualTo(20);
    }
    @Test void retryWorksAfterActivityCloses() {
        var a=activities.create("关闭重试",20,Duration.ofHours(1),1); String request=id();
        var result=claims.claim(a.id(),"u",request); repository.updateActivityStatus(a.id(),"CLOSED");
        assertThat(claims.claim(a.id(),"u",request).claimNo()).isEqualTo(result.claimNo());
        assertThat(claims.claim(a.id(),"u",id()).code()).isEqualTo(ClaimCode.ACTIVITY_NOT_RUNNING);
    }
    @Test void dbQuotaRejectsOverLimitEvenIfCacheCounterWasLost() {
        var a=activities.create("数据库限领",20,Duration.ofHours(1),1); claims.claim(a.id(),"u",id());
        stock.restoreState(a.id(),repository.listShards(a.id()).stream().map(Repository.ShardRow::stock).toList(),Map.of());
        assertThat(claims.claim(a.id(),"u",id()).code()).isEqualTo(ClaimCode.LIMIT_EXCEEDED);
        assertThat(repository.totalStock(a.id())).isEqualTo(19); assertThat(stock.totalStock(a.id(),8)).isEqualTo(19);
    }
    @Test void recoveryRebuildsStockAndUserLimitFromDb() {
        var a=activities.create("恢复限领",20,Duration.ofHours(1),1); claims.claim(a.id(),"u",id());
        stock.restoreState(a.id(),Collections.nCopies(8,0),Map.of()); recovery.recover();
        assertThat(stock.totalStock(a.id(),8)).isEqualTo(19);
        assertThat(claims.claim(a.id(),"u",id()).code()).isEqualTo(ClaimCode.LIMIT_EXCEEDED);
    }
    @Test void rolledBackClaimNeverReceivesBenefit() {
        var a=activities.create("失败不得发奖",20,Duration.ofHours(1),1); String r=id(); String n="CLM"+r;
        repository.insertClaim(new Repository.ClaimRow(0,r,n,a.id(),"u",0,"ROLLED_BACK","injected"));
        claims.handleGrantMessage(n,""); assertThat(repository.benefitGranted(n)).isFalse();
        assertThat(repository.findClaimByClaimNo(n).status()).isEqualTo("ROLLED_BACK");
    }
    @Test void consumedOutboxIsConfirmedAndNoLongerRetried() throws Exception {
        var a=activities.create("消息确认",20,Duration.ofHours(1),1); var result=claims.claim(a.id(),"u",id());
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!repository.benefitGranted(result.claimNo()) && System.nanoTime()<until) Thread.sleep(10);
        // Grant status / receipt / outbox ack share one transaction.
        assertThat(repository.benefitGranted(result.claimNo())).isTrue();
        assertThat(repository.findMessagesForRetry(10000)).noneMatch(m->m.claimNo().equals(result.claimNo()));
    }
    @Test void maintenanceWaitsForActiveReservationToFinish() throws Exception {
        var workers=Executors.newFixedThreadPool(2); var entered=new CountDownLatch(1); var release=new CountDownLatch(1); var maintained=new AtomicInteger();
        try {
            var claim=workers.submit(()->gate.claim(()->{ entered.countDown(); try { release.await(5,TimeUnit.SECONDS); } catch(InterruptedException e){Thread.currentThread().interrupt();} return true; }));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            var maintenance=workers.submit(()->gate.maintain(maintained::incrementAndGet));
            assertThatThrownBy(()->maintenance.get(150,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(maintained.get()).isZero(); release.countDown(); claim.get(2,TimeUnit.SECONDS); maintenance.get(2,TimeUnit.SECONDS);
            assertThat(maintained.get()).isEqualTo(1);
        } finally { release.countDown(); workers.shutdownNow(); }
    }
    @Test void invalidInputsAreRejectedBeforeReservation() {
        assertThatThrownBy(()->activities.create("bad",1,Duration.ofSeconds(-1),1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->activities.create("bad",1,Duration.ofSeconds(1),0)).isInstanceOf(IllegalArgumentException.class);
        var a=activities.create("校验",10,Duration.ofHours(1),1);
        assertThatThrownBy(()->claims.claim(a.id(),"u","x".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.totalStock(a.id())).isEqualTo(10);
    }
    @Test void reconciliationDetectsEqualTotalsWithWrongShardDistribution() {
        var a=activities.create("分片对账",16,Duration.ofHours(1),1);
        stock.resetStock(a.id(),List.of(16,0,0,0,0,0,0,0));
        assertThat(stock.totalStock(a.id(),8)).isEqualTo(repository.totalStock(a.id()));
        assertThat(compensation.report(List.of(a)).get(0).shardBalanced()).isFalse();
        compensation.reconcileStock();
        assertThat(stock.shardStocks(a.id(),8)).isEqualTo(List.of(2,2,2,2,2,2,2,2));
    }
}
