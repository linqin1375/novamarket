package io.github.tangbailin.novamarket.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import org.springframework.stereotype.Component;

/**
 * 入口与链路的计数器。
 *
 * <p>不用 Micrometer / Prometheus：本项目要展示的是"每个关键分支都有可观测点"这个习惯，
 * 而不是引入监控框架。刻意给每个**失败分支**都留了独立计数器 ——
 * 把 {@code SOLD_OUT}（业务正常）和 {@code STORAGE_ERROR}（系统故障）分开统计，
 * 否则一个混在一起的"失败率"既不能报警也不能定位。
 */
@Component
public class ClaimMetrics {

    private final LongAdder total = new LongAdder();
    private final LongAdder success = new LongAdder();
    private final LongAdder soldOut = new LongAdder();
    private final LongAdder limitExceeded = new LongAdder();
    private final LongAdder fastPathIdempotent = new LongAdder();
    private final LongAdder deductIdempotent = new LongAdder();
    private final LongAdder storageError = new LongAdder();
    private final LongAdder rateLimited = new LongAdder();
    private final LongAdder settleFailed = new LongAdder();
    private final LongAdder settleFailedRolledBack = new LongAdder();
    private final LongAdder messageSendFailed = new LongAdder();
    private final LongAdder benefitGranted = new LongAdder();
    private final LongAdder duplicateGrantIgnored = new LongAdder();
    private final LongAdder walSettled = new LongAdder();
    private final LongAdder walRolledBack = new LongAdder();
    private final LongAdder messageRetried = new LongAdder();
    private final LongAdder messageDead = new LongAdder();
    private final LongAdder reconcileMismatch = new LongAdder();

    private final AtomicLong peakInFlight = new AtomicLong();
    private final AtomicLong inFlight = new AtomicLong();

    void enter() {
        total.increment();
        long now = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(now, Math::max);
    }

    public void leave() {
        inFlight.decrementAndGet();
    }

    public void success() { success.increment(); }
    public void soldOut() { soldOut.increment(); }
    public void limitExceeded() { limitExceeded.increment(); }
    public void hitFastPathIdempotent() { fastPathIdempotent.increment(); }
    public void hitDeductIdempotent() { deductIdempotent.increment(); }
    public void storageError() { storageError.increment(); }
    public void rateLimited() { rateLimited.increment(); }

    public void settleFailedRolledBack(boolean rolledBack) {
        settleFailed.increment();
        if (rolledBack) {
            settleFailedRolledBack.increment();
        }
    }

    public void messageSendFailed() { messageSendFailed.increment(); }
    public void benefitGranted() { benefitGranted.increment(); }
    public void duplicateGrantIgnored() { duplicateGrantIgnored.increment(); }
    public void walSettled() { walSettled.increment(); }
    public void walRolledBack() { walRolledBack.increment(); }
    public void messageRetried() { messageRetried.increment(); }
    public void messageDead() { messageDead.increment(); }
    public void reconcileMismatch() { reconcileMismatch.increment(); }

    /** 快照，供 /api/metrics 返回。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalRequests", total.sum());
        m.put("success", success.sum());
        m.put("soldOut", soldOut.sum());
        m.put("limitExceeded", limitExceeded.sum());
        m.put("idempotentFastPath", fastPathIdempotent.sum());
        m.put("idempotentAtDeduct", deductIdempotent.sum());
        m.put("storageError", storageError.sum());
        m.put("rateLimited", rateLimited.sum());
        m.put("settleFailed", settleFailed.sum());
        m.put("settleFailedRolledBack", settleFailedRolledBack.sum());
        m.put("messageSendFailed", messageSendFailed.sum());
        m.put("benefitGranted", benefitGranted.sum());
        m.put("duplicateGrantIgnored", duplicateGrantIgnored.sum());
        m.put("walSettled", walSettled.sum());
        m.put("walRolledBack", walRolledBack.sum());
        m.put("messageRetried", messageRetried.sum());
        m.put("messageDead", messageDead.sum());
        m.put("reconcileMismatch", reconcileMismatch.sum());
        m.put("inFlight", inFlight.get());
        m.put("peakInFlight", peakInFlight.get());
        return m;
    }
}
