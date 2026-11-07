-- Existing databases: apply once before starting the new Java version.
-- Additive and idempotent; no existing business tables or records are changed.
CREATE TABLE IF NOT EXISTS recruitment_match_task (
    id                VARCHAR(36) PRIMARY KEY,
    position_id       BIGINT NOT NULL,
    owner_id          BIGINT NOT NULL,
    request_key       VARCHAR(80) NOT NULL,
    input_hash        VARCHAR(64) NOT NULL,
    status            VARCHAR(20) NOT NULL,
    phase             VARCHAR(32) NOT NULL,
    completed_batches INT NOT NULL DEFAULT 0,
    total_batches     INT NOT NULL,
    candidate_count   INT NOT NULL,
    degraded_count    INT NOT NULL DEFAULT 0,
    error_code        VARCHAR(40),
    error_message     VARCHAR(255),
    created_at        TIMESTAMP(6) NOT NULL,
    updated_at        TIMESTAMP(6) NOT NULL,
    deadline_at       TIMESTAMP(6) NOT NULL,
    UNIQUE (position_id, request_key),
    INDEX idx_match_task_queue (status, created_at),
    INDEX idx_match_task_deadline (status, deadline_at)
);


-- Accepted duplicate request keys remain idempotent after the reused task finishes.
CREATE TABLE IF NOT EXISTS recruitment_match_request (
    position_id BIGINT NOT NULL,
    request_key VARCHAR(80) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (position_id, request_key)
);
