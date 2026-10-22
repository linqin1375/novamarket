package io.github.tangbailin.novamarket.adapter;
import io.github.tangbailin.novamarket.domain.*;
import io.github.tangbailin.novamarket.port.StockPort;
import java.util.*;
import java.time.Duration;
import org.springframework.data.redis.core.*;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.core.io.ClassPathResource;

/** Standalone Redis implementation. Dedicated namespace; no global FLUSHDB/KEYS. */
public final class RedisStockAdapter implements StockPort {
    private static final String PREFIX = "novamarket:";
    private final StringRedisTemplate redis;
    private final int shards, idemTtl;
    private final DefaultRedisScript<List> deductScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<Long> rollbackScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<Long> clearScript = new DefaultRedisScript<>(
        "redis.call('DEL', KEYS[1]); redis.call('ZREM', KEYS[2], ARGV[1]); redis.call('EXPIRE', KEYS[3], ARGV[2]); return 1", Long.class);
    public RedisStockAdapter(StringRedisTemplate redis, int shards, int idemTtl, int ignoredWalTtl) {
        this.redis = redis; this.shards = shards; this.idemTtl = idemTtl;
        deductScript.setLocation(new ClassPathResource("lua/stock_deduct.lua")); deductScript.setResultType(List.class);
        rollbackScript.setLocation(new ClassPathResource("lua/stock_rollback.lua")); rollbackScript.setResultType(Long.class);
    }
    public String implName() { return "redis-lua"; }
    public boolean ping() {
        try { return "PONG".equalsIgnoreCase(redis.execute((RedisCallback<String>) c -> c.ping())); }
        catch (RuntimeException e) { return false; }
    }
    public DeductResult deduct(String a, String u, String r, int limit) {
        List<String> keys = new ArrayList<>(List.of(limit(a,u), idem(r), wal(r), index(), used(a)));
        for (int n=0; n<shards; n++) keys.add(stock(a,n));
        List<?> result = redis.execute(deductScript, keys, r, ""+limit, ""+Math.floorMod(u.hashCode(),shards), a,u,""+idemTtl);
        if (result == null || result.size()<3) return DeductResult.failed(DeductCode.STORAGE_ERROR,"Lua 返回异常");
        int code = Integer.parseInt(result.get(0).toString());
        String claim = result.get(1).toString(); int shard = Integer.parseInt(result.get(2).toString());
        return switch(code) {
            case 0 -> DeductResult.success(shard,claim);
            case 1 -> DeductResult.failed(DeductCode.SOLD_OUT,"库存不足");
            case 2 -> DeductResult.failed(DeductCode.LIMIT_EXCEEDED,"超出用户限领");
            case 3 -> DeductResult.duplicate(shard,claim);
            case 4 -> DeductResult.failed(DeductCode.SHARD_NOT_READY,"分片未初始化");
            case 5 -> DeductResult.failed(DeductCode.IDEMPOTENCY_CONFLICT,"幂等键已绑定其他活动或用户");
            default -> DeductResult.failed(DeductCode.STORAGE_ERROR,"未知 Lua 返回码");
        };
    }
    public boolean rollback(String a, String u, String r) {
        Long result=redis.execute(rollbackScript,List.of(wal(r),limit(a,u),idem(r),index(),used(a),idem(r)+":owner"),a,u,r,PREFIX);
        return Long.valueOf(1).equals(result);
    }
    public List<WalEntry> staleWalEntries(long seconds,int max) {
        var ids=redis.opsForZSet().rangeByScore(index(),0,System.currentTimeMillis()-seconds*1000,0,max);
        List<WalEntry> out=new ArrayList<>();
        if(ids==null) return out;
        for(String r:ids) {
            var w=redis.opsForHash().entries(wal(r));
            if(w.isEmpty()) { redis.opsForZSet().remove(index(),r); continue; }
            out.add(new WalEntry(r,w.get("activityId").toString(),w.get("userId").toString(),Integer.parseInt(w.get("shardNo").toString()),
                w.get("claimNo").toString(),Long.parseLong(w.get("at").toString())));
        }
        return out;
    }
    public void clearWal(String r) { redis.execute(clearScript,List.of(wal(r),index(),idem(r)+":owner"),r,""+idemTtl); }
    public int totalStock(String a,int count) {
        List<String> keys=new ArrayList<>(); for(int n=0;n<count;n++) keys.add(stock(a,n));
        var values=redis.opsForValue().multiGet(keys);
        return values==null?0:values.stream().filter(Objects::nonNull).mapToInt(Integer::parseInt).sum();
    }
    public void resetStock(String a,List<Integer> values) {
        Map<String,String> update=new HashMap<>(); for(int n=0;n<values.size();n++) update.put(stock(a,n),""+values.get(n));
        redis.opsForValue().multiSet(update);
    }
    public List<Integer> shardStocks(String a,int count) {
        List<String> keys=new ArrayList<>(); for(int n=0;n<count;n++) keys.add(stock(a,n));
        var values=redis.opsForValue().multiGet(keys);
        return values==null?Collections.nCopies(count,0):values.stream().map(v->v==null?0:Integer.parseInt(v)).toList();
    }
    public void restoreState(String a,List<Integer> values,Map<String,Integer> counts) {
        // Called only on startup or behind the exclusive single-instance maintenance fence.
        try(var cursor=redis.scan(ScanOptions.scanOptions().match(PREFIX+"limit:act:"+a+":user:*").count(200).build())) {
            while(cursor.hasNext()) redis.delete(cursor.next());
        }
        var ids=redis.opsForZSet().range(index(),0,-1);
        if(ids!=null) for(String r:ids) {
            if(a.equals(redis.opsForHash().get(wal(r),"activityId"))) {
                clearWal(r); redis.delete(List.of(idem(r),idem(r)+":owner"));
            }
        }
        resetStock(a,values); redis.delete(used(a));
        counts.forEach((u,c)->redis.opsForValue().set(limit(a,u),""+c));
    }
    private String stock(String a,int n) { return PREFIX+"stock:act:"+a+":shard:"+n; }
    private String limit(String a,String u) { return PREFIX+"limit:act:"+a+":user:"+u; }
    private String idem(String r) { return PREFIX+"idem:claim:"+r; }
    private String wal(String r) { return PREFIX+"wal:claim:"+r; }
    private String index() { return PREFIX+"wal:index"; }
    private String used(String a) { return PREFIX+"stock:shard:used:"+a; }
}
