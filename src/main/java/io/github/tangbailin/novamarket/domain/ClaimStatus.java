package io.github.tangbailin.novamarket.domain;

/**
 * 领取单状态机。
 *
 * <p>{@code PROCESSING} 是一个**独立可见**的状态，不是一个内部标记：
 * 客户端超时后重试时，系统必须能回答"这笔到底成了没有"，
 * 而"处理中"就是一个诚实的答案。绝不能把"没报错"当作成功。
 */
public enum ClaimStatus {
    /** 已预扣减，等待落库 / 发奖 */
    PROCESSING,
    /** 发奖完成 */
    SUCCESS,
    /** 业务失败（库存不足、超限等），无副作用 */
    FAILED,
    /** 落库或发奖失败，库存已回滚 */
    ROLLED_BACK
}
