package io.github.tangbailin.novamarket.adapter;
import io.github.tangbailin.novamarket.domain.*;
import io.github.tangbailin.novamarket.port.StockPort;
import java.util.*;

/** Local correctness implementation: one monitor covers check, reserve, limit and WAL. */
public final class InMemoryStockAdapter implements StockPort {
    private final int shardCount;
    private final Map<String, Integer> stock = new HashMap<>();
    private final Map<String, Integer> shardUsed = new HashMap<>();
    private final Map<String, Integer> userClaimed = new HashMap<>();
    private final Map<String, WalEntry> idempotency = new HashMap<>();
    private final Map<String, WalEntry> wal = new HashMap<>();
    public InMemoryStockAdapter(int shardCount) {
        if (shardCount <= 0) throw new IllegalArgumentException("shardCount must be positive");
        this.shardCount = shardCount;
    }
    public String implName() { return "in-memory"; }
    public synchronized DeductResult deduct(String activityId, String userId, String requestId, int limit) {
        WalEntry previous = idempotency.get(requestId);
        if (previous != null) {
            if (!previous.activityId().equals(activityId) || !previous.userId().equals(userId))
                return DeductResult.failed(DeductCode.IDEMPOTENCY_CONFLICT, "幂等键已绑定其他活动或用户");
            return DeductResult.duplicate(previous.shardNo(), previous.claimNo());
        }
        int first = Math.floorMod(userId.hashCode(), shardCount);
        int selected = -1;
        // Probe all shards: one empty preferred shard does not mean the activity is sold out.
        for (int offset = 0; offset < shardCount; offset++) {
            int n = (first + offset) % shardCount;
            Integer left = stock.get(key(activityId, n));
            if (left == null) return DeductResult.failed(DeductCode.SHARD_NOT_READY, "分片未初始化");
            if (left > 0 && selected < 0) selected = n;
        }
        String userKey = activityId + ":" + userId;
        int used = userClaimed.getOrDefault(userKey, 0);
        if (used >= limit) return DeductResult.failed(DeductCode.LIMIT_EXCEEDED, "超出用户限领");
        if (selected < 0) return DeductResult.failed(DeductCode.SOLD_OUT, "库存不足");
        stock.merge(key(activityId, selected), -1, Integer::sum);
        shardUsed.merge(key(activityId, selected), 1, Integer::sum);
        userClaimed.put(userKey, used + 1);
        WalEntry entry = new WalEntry(requestId, activityId, userId, selected, "CLM" + requestId, System.currentTimeMillis());
        wal.put(requestId, entry); idempotency.put(requestId, entry);
        return DeductResult.success(selected, entry.claimNo());
    }
    public synchronized boolean rollback(String activityId, String userId, String requestId) {
        WalEntry entry = wal.get(requestId);
        if (entry == null || !entry.activityId().equals(activityId) || !entry.userId().equals(userId)) return false;
        wal.remove(requestId); idempotency.remove(requestId);
        stock.merge(key(activityId, entry.shardNo()), 1, Integer::sum);
        shardUsed.computeIfPresent(key(activityId, entry.shardNo()), (k, v) -> Math.max(0, v - 1));
        userClaimed.computeIfPresent(activityId + ":" + userId, (k, v) -> Math.max(0, v - 1));
        return true;
    }
    public synchronized List<WalEntry> staleWalEntries(long seconds, int limit) {
        long deadline = System.currentTimeMillis() - seconds * 1000;
        return wal.values().stream().filter(e -> e.atMillis() <= deadline).limit(limit).toList();
    }
    public synchronized void clearWal(String requestId) { wal.remove(requestId); }
    public synchronized int totalStock(String activityId, int count) {
        int total = 0; for (int i = 0; i < count; i++) total += stock.getOrDefault(key(activityId, i), 0); return total;
    }
    public synchronized List<Integer> shardStocks(String activityId, int count) {
        List<Integer> values = new ArrayList<>(); for (int n=0;n<count;n++) values.add(stock.getOrDefault(key(activityId,n),0)); return values;
    }
    public synchronized void resetStock(String activityId, List<Integer> stocks) {
        for (int i = 0; i < stocks.size(); i++) stock.put(key(activityId, i), stocks.get(i));
    }
    public synchronized void restoreState(String activityId, List<Integer> stocks, Map<String, Integer> counts) {
        resetStock(activityId, stocks);
        userClaimed.keySet().removeIf(k -> k.startsWith(activityId + ":"));
        counts.forEach((u, c) -> userClaimed.put(activityId + ":" + u, c));
        wal.values().removeIf(e -> e.activityId().equals(activityId));
        idempotency.values().removeIf(e -> e.activityId().equals(activityId));
    }
    public synchronized int walSize() { return wal.size(); }
    public synchronized int shardStock(String a, int n) { return stock.getOrDefault(key(a, n), -1); }
    public synchronized Set<String> usedShards(String a) { return Set.copyOf(shardUsed.keySet()); }
    public synchronized int usedCount(String a, int n) { return shardUsed.getOrDefault(key(a, n), 0); }
    private String key(String a, int n) { return a + ":" + n; }
}
