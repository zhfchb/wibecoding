-- Additive retry-task tables. Existing candidate and document data are preserved.
CREATE TABLE IF NOT EXISTS recruitment_resume_parse_task (
    id VARCHAR(36) PRIMARY KEY,
    position_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    request_key VARCHAR(80) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(40),
    error_message VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    deadline_at TIMESTAMP(6) NOT NULL,
    UNIQUE (position_id, request_key),
    INDEX idx_resume_parse_queue (status, created_at),
    INDEX idx_resume_parse_deadline (status, deadline_at)
);
CREATE TABLE IF NOT EXISTS recruitment_resume_parse_item (
    task_id VARCHAR(36) NOT NULL,
    resume_id BIGINT NOT NULL,
    ordinal_no INT NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_code VARCHAR(40),
    error_message VARCHAR(255),
    PRIMARY KEY (task_id, resume_id)
);
