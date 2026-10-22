package io.github.tangbailin.novamarket.adapter;

import io.github.tangbailin.novamarket.port.MessagePort;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 进程内消息总线 —— 默认实现，替代 RocketMQ 用于本地开发与测试。
 *
 * <p>刻意保留真实 MQ 的关键语义，否则补偿逻辑就没法被真正验证：
 * <ul>
 *   <li><b>异步投递</b>：{@code send} 只入队并立刻返回，消费在单独线程池里发生；</li>
 *   <li><b>可注入失败</b>：{@link #failNext(int)} 让接下来的 N 次投递失败，用于验证补偿任务；</li>
 *   <li><b>可重复投递</b>：{@link #replay(String)} 模拟 MQ 重复投递，用于验证消费端幂等。</li>
 * </ul>
 * 没有真正的持久化与跨进程投递 —— 这两点由本地消息表 + 补偿任务在业务层兜住（见 DESIGN.md 3.4）。
 */
public final class InProcessMessageBus implements MessagePort {

    private final Map<String, List<Consumer<Message>>> subscribers = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "novamarket-mq");
        t.setDaemon(true);
        return t;
    });

    private final AtomicInteger failNext = new AtomicInteger();
    private final AtomicInteger sent = new AtomicInteger();
    private final AtomicInteger consumed = new AtomicInteger();

    @Override
    public String implName() {
        return "in-process-bus";
    }

    @Override
    public boolean send(String claimNo, String topic, String payload) {
        if (failNext.getAndUpdate(v -> v > 0 ? v - 1 : 0) > 0) {
            // 模拟"消息发送失败"：调用方应把本地消息表留在 PENDING，等补偿任务重投
            return false;
        }
        List<Consumer<Message>> handlers = subscribers.get(topic);
        if (handlers == null || handlers.isEmpty()) {
            return false;
        }
        sent.incrementAndGet();
        Message message = new Message(claimNo, topic, payload);
        executor.submit(() -> handlers.forEach(h -> {
            try {
                h.accept(message);
                consumed.incrementAndGet();
            } catch (RuntimeException e) {
                // 消费失败不吞掉：留给补偿任务重投，这里只记录
                System.err.println("[mq] 消费失败 claimNo=" + claimNo + " err=" + e.getMessage());
            }
        }));
        return true;
    }

    @Override
    public void subscribe(String topic, Consumer<Message> handler) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(handler);
    }

    // ------------------------------------------------------------------ 测试钩子

    /** 让接下来 n 次 send 返回 false，用于验证"消息发送失败 → 补偿重投"。 */
    public void failNext(int n) {
        failNext.set(n);
    }

    /** 模拟 MQ 重复投递同一条消息，用于验证消费端幂等。 */
    public void replay(String topic, String claimNo, String payload) {
        send(claimNo, topic, payload);
    }

    public int sentCount() {
        return sent.get();
    }

    public int consumedCount() {
        return consumed.get();
    }

    @jakarta.annotation.PreDestroy
    public void close() { executor.shutdown(); }

    /** 等待异步消费完成（测试用，避免 sleep 式等待）。 */
    public void awaitConsumed(int expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline && consumed.get() < expected) {
            Thread.sleep(10);
        }
    }
}
