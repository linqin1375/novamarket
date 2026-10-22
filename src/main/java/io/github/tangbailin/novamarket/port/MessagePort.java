package io.github.tangbailin.novamarket.port;

/**
 * 消息投递端口。
 *
 * <p>默认实现是进程内消息总线（保证零依赖可运行）；真实环境接 RocketMQ 时只替换这一个实现。
 *
 * <p>注意语义：{@link #send} 返回 false **不代表消息丢了** —— 它代表"本次投递没成功"，
 * 调用方应把本地消息表里的记录留在 PENDING，等补偿任务重投。
 * 这是本地消息表模式的核心：**投递成功与否由本地消息表的状态决定，而不是由这一次调用的返回值决定**。
 */
public interface MessagePort {

    String implName();

    /**
     * 投递一条消息。
     *
     * @param claimNo 业务单号，消费端据此做幂等
     * @param topic   主题
     * @param payload 消息体
     * @return 本次投递是否被接收
     */
    boolean send(String claimNo, String topic, String payload);

    /** 注册消费者（进程内实现用它模拟 MQ 消费；真实 MQ 由注解式监听器承担）。 */
    void subscribe(String topic, java.util.function.Consumer<Message> handler);

    record Message(String claimNo, String topic, String payload) {
    }
}
