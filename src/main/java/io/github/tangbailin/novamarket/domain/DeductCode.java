package io.github.tangbailin.novamarket.domain;

/**
 * 扣减结果。
 *
 * <p>注意 {@link #DUPLICATE}：同一 requestId 重复请求时返回的是**首次**的结果，
 * 而不是"再扣一次"。这是"用户等待超时后重试"能被正确处理的关键 —— 详见 DESIGN.md 3.3。
 */
public enum DeductCode {
    /** 扣减成功 */
    SUCCESS,
    /** 库存不足 */
    SOLD_OUT,
    /** 超出单用户限领（库存已在脚本内回滚，未泄漏） */
    LIMIT_EXCEEDED,
    /** 同一 requestId 重复请求，返回首次结果，未重复扣减 */
    DUPLICATE,
    IDEMPOTENCY_CONFLICT,
    /** 分片库存未初始化（活动未预热或预热失败，不能当作售罄） */
    SHARD_NOT_READY,
    /** 存储层异常 */
    STORAGE_ERROR
}
