package io.github.tangbailin.novamarket.domain;

/** 对外返回的业务码。与 HTTP 状态码分离：业务失败用 200 + 业务码，系统错误才用 5xx。 */
public enum ClaimCode {
    SUCCESS,
    PROCESSING,
    SOLD_OUT,
    LIMIT_EXCEEDED,
    DUPLICATE,
    IDEMPOTENCY_CONFLICT,
    ACTIVITY_NOT_FOUND,
    ACTIVITY_NOT_RUNNING,
    RATE_LIMITED,
    STORAGE_ERROR,
    INTERNAL_ERROR
}
