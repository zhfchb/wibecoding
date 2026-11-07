-- Additive tables for durable interview-question and report generation.
CREATE TABLE IF NOT EXISTS recruitment_material_task (
    id VARCHAR(36) PRIMARY KEY,
    resume_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    request_key VARCHAR(80) NOT NULL,
    input_hash VARCHAR(64) NOT NULL,
    source_score DECIMAL(5,2),
    result_id BIGINT,
    status VARCHAR(20) NOT NULL,
    phase VARCHAR(32) NOT NULL,
    error_code VARCHAR(40),
    error_message VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    deadline_at TIMESTAMP(6) NOT NULL,
    UNIQUE (resume_id,kind,request_key),
    INDEX idx_material_queue (status,created_at),
    INDEX idx_material_deadline (status,deadline_at),
    INDEX idx_material_result (kind,result_id)
);
CREATE TABLE IF NOT EXISTS recruitment_material_request (
    resume_id BIGINT NOT NULL,
    kind VARCHAR(20) NOT NULL,
    request_key VARCHAR(80) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    PRIMARY KEY (resume_id,kind,request_key)
);
