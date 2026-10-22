-- NovaMarket 数据库结构
-- 默认使用 H2（零安装）；生产用 MySQL 8，见 docker-compose.yml 与 profile=mysql
--
-- 设计要点：
--   1. claim_record.request_id 上的唯一索引是幂等的最终兜底 —— 前两层幂等检查
--      （幂等快路径、Redis 幂等缓存）都只是性能优化，"并发同时到达"只能靠唯一索引挡住。
--   2. local_message 与业务表在同一个本地事务里写入，这是"消息一定不会丢"的依据。
--   3. stock_shard 的条件更新（where stock > 0）保证 DB 侧也不会扣成负数。

CREATE TABLE IF NOT EXISTS activity (
    id            VARCHAR(64)  PRIMARY KEY,
    name          VARCHAR(128) NOT NULL,
    status        VARCHAR(16)  NOT NULL,          -- CREATED / RUNNING / CLOSED
    start_at      TIMESTAMP    NOT NULL,
    end_at        TIMESTAMP    NOT NULL,
    total_stock   INT          NOT NULL,
    shard_count   INT          NOT NULL,
    per_user_limit INT         NOT NULL,
    created_at    TIMESTAMP    NOT NULL
);

CREATE TABLE IF NOT EXISTS stock_shard (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    activity_id VARCHAR(64) NOT NULL,
    shard_no    INT         NOT NULL,
    stock       INT         NOT NULL,
    version     BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT uk_shard UNIQUE (activity_id, shard_no)
);

CREATE TABLE IF NOT EXISTS claim_record (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    -- 幂等键：由客户端生成并在重试时复用，唯一索引是并发幂等的最终保障
    request_id  VARCHAR(64)  NOT NULL,
    claim_no    VARCHAR(80)  NOT NULL,
    activity_id VARCHAR(64)  NOT NULL,
    user_id     VARCHAR(64)  NOT NULL,
    shard_no    INT          NOT NULL,
    status      VARCHAR(16)  NOT NULL,            -- PROCESSING / SUCCESS / FAILED / ROLLED_BACK
    fail_reason VARCHAR(200),
    created_at  TIMESTAMP    NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    CONSTRAINT uk_request UNIQUE (request_id),
    CONSTRAINT uk_claim_no UNIQUE (claim_no)
);
CREATE INDEX idx_claim_user ON claim_record (activity_id, user_id);
CREATE INDEX idx_claim_status ON claim_record (status, created_at);

CREATE TABLE IF NOT EXISTS local_message (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_no      VARCHAR(80)  NOT NULL,
    topic         VARCHAR(64)  NOT NULL,
    payload       VARCHAR(2000) NOT NULL,
    status        VARCHAR(16)  NOT NULL,          -- PENDING / SENT / CONFIRMED / DEAD
    retry_count   INT          NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMP    NOT NULL,
    last_error    VARCHAR(500),
    created_at    TIMESTAMP    NOT NULL,
    updated_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uk_msg_claim UNIQUE (claim_no, topic)
);
CREATE INDEX idx_msg_status ON local_message (status, next_retry_at);

CREATE TABLE IF NOT EXISTS benefit_grant (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    claim_no   VARCHAR(80) NOT NULL,
    activity_id VARCHAR(64) NOT NULL,
    user_id    VARCHAR(64) NOT NULL,
    granted_at TIMESTAMP   NOT NULL,
    -- 发奖侧也要幂等：MQ 重复投递时靠这个唯一索引挡住重复发奖
    CONSTRAINT uk_grant_claim UNIQUE (claim_no)
);

CREATE TABLE IF NOT EXISTS reconcile_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    checked_at  TIMESTAMP   NOT NULL,
    redis_total INT         NOT NULL,
    db_total    INT         NOT NULL,
    diff        INT         NOT NULL,
    action      VARCHAR(64) NOT NULL,
    detail      VARCHAR(500)
);

CREATE TABLE IF NOT EXISTS user_quota (
    activity_id VARCHAR(64) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    claimed INT NOT NULL,
    PRIMARY KEY (activity_id, user_id)
);
