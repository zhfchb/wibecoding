-- 知识库文档元数据
CREATE TABLE IF NOT EXISTS document (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id VARCHAR(64)  NOT NULL,
    file_name   VARCHAR(255) NOT NULL,
    chunk_count INT          NOT NULL,
    file_size   BIGINT       NOT NULL,
    user_id     BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_document_user_id ON document(user_id);

-- 文档切片持久化：存 chunk 文本 + 向量，进程重启后可从 MySQL 恢复内存向量库
CREATE TABLE IF NOT EXISTS document_chunk (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    chunk_id        VARCHAR(64)  NOT NULL,
    document_id     VARCHAR(64)  NOT NULL,
    file_name       VARCHAR(255) NOT NULL,
    chunk_index     INT          NOT NULL,
    text            TEXT         NOT NULL,
    dense_vector    TEXT         NOT NULL,
    sparse_indices  TEXT,
    sparse_values   TEXT,
    user_id         BIGINT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chunk_document_id ON document_chunk(document_id);
CREATE INDEX IF NOT EXISTS idx_chunk_user_id ON document_chunk(user_id);

-- 对话历史（user_id 用于用户级数据隔离）
CREATE TABLE IF NOT EXISTS chat_history (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT,
    session_id VARCHAR(64) NOT NULL,
    role       VARCHAR(16) NOT NULL,
    content    TEXT        NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_chat_history_user ON chat_history(user_id);

-- 用户表(RBAC:单字段 role 存角色,ADMIN/USER)
-- 表名用 sys_user 避免 user 在 H2/MySQL 中的保留字冲突
CREATE TABLE IF NOT EXISTS sys_user (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    username   VARCHAR(64)  NOT NULL UNIQUE,
    password   VARCHAR(100) NOT NULL,
    role       VARCHAR(16)  NOT NULL DEFAULT 'USER',
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted    INT          NOT NULL DEFAULT 0
);

-- M9 多 Agent 协作工作流持久化表（checkpoint + 任务表）
-- 进程重启后可从 MySQL 恢复正在审核中的任务
-- 注意：用 TEXT/TIMESTAMP 保持 H2 测试环境兼容

-- LangGraph4j checkpoint 持久化表
CREATE TABLE IF NOT EXISTS langgraph_checkpoint (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    thread_id     VARCHAR(64) NOT NULL,
    checkpoint_id VARCHAR(64) NOT NULL,
    node_id       VARCHAR(64),
    next_node_id  VARCHAR(64),
    state_json    TEXT NOT NULL,
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_langgraph_thread_id ON langgraph_checkpoint(thread_id);

-- 多 Agent 任务表
CREATE TABLE IF NOT EXISTS multi_agent_task (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         VARCHAR(64) NOT NULL,
    thread_id       VARCHAR(64) NOT NULL,
    question        TEXT NOT NULL,
    status          VARCHAR(32) NOT NULL,
    draft           TEXT,
    final_answer    TEXT,
    sources_json    TEXT,
    reject_count    INT NOT NULL DEFAULT 0,
    review_feedback TEXT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_mat_task_id ON multi_agent_task(task_id);
CREATE INDEX IF NOT EXISTS idx_mat_status ON multi_agent_task(status);
CREATE INDEX IF NOT EXISTS idx_mat_thread_id ON multi_agent_task(thread_id);

-- ============================================================
-- 智能招聘助手 业务表（与 docs/development/sql/recruitment_schema.sql 对应，
-- 此处为 MySQL + H2 双兼容版本：TEXT/TIMESTAMP，无 COMMENT/ENGINE）
-- ============================================================

-- 1. 岗位表：一个 HR 可发布多个岗位
CREATE TABLE IF NOT EXISTS job_position (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id          BIGINT       NOT NULL,
    title            VARCHAR(200) NOT NULL,
    department       VARCHAR(100),
    city             VARCHAR(50),
    salary_min       INT,
    salary_max       INT,
    required_skills  TEXT,
    preferred_skills TEXT,
    min_years        INT DEFAULT 0,
    education        VARCHAR(20),
    description      TEXT,
    doc_id           VARCHAR(64),
    status           VARCHAR(20) DEFAULT 'OPEN',
    created_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_position_user ON job_position(user_id);
CREATE INDEX IF NOT EXISTS idx_position_status ON job_position(status);

-- 2. 候选人简历表
CREATE TABLE IF NOT EXISTS candidate_resume (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    position_id     BIGINT       NOT NULL,
    user_id         BIGINT       NOT NULL,
    candidate_name  VARCHAR(100),
    phone           VARCHAR(30),
    email           VARCHAR(100),
    skills          TEXT,
    years_exp       INT,
    education       VARCHAR(50),
    last_company    VARCHAR(200),
    raw_text        TEXT,
    doc_id          VARCHAR(64),
    parse_status    VARCHAR(20) DEFAULT 'PENDING',
    parse_error     VARCHAR(500),
    created_at      TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_resume_position ON candidate_resume(position_id);
CREATE INDEX IF NOT EXISTS idx_resume_user ON candidate_resume(user_id);

-- 3. 人岗匹配结果表
CREATE TABLE IF NOT EXISTS match_result (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    position_id  BIGINT NOT NULL,
    resume_id    BIGINT NOT NULL,
    match_score  DECIMAL(5,2),
    skill_hits   TEXT,
    skill_gaps   TEXT,
    score_detail TEXT,
    hr_decision  VARCHAR(20) DEFAULT 'PENDING',
    rank_no      INT,
    task_id      BIGINT,
    created_at   TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_match_position ON match_result(position_id);
CREATE INDEX IF NOT EXISTS idx_match_resume ON match_result(resume_id);

-- 4. 定制面试题表
CREATE TABLE IF NOT EXISTS interview_question (
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    resume_id        BIGINT NOT NULL,
    position_id      BIGINT NOT NULL,
    category         VARCHAR(50),
    question         TEXT,
    assess_point     VARCHAR(500),
    reference_answer TEXT,
    difficulty       VARCHAR(10),
    hr_decision      VARCHAR(20) DEFAULT 'PENDING',
    created_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_question_resume ON interview_question(resume_id);

-- 5. 候选人评估报告表
CREATE TABLE IF NOT EXISTS candidate_report (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    resume_id       BIGINT NOT NULL,
    position_id     BIGINT NOT NULL,
    summary         TEXT,
    strength        TEXT,
    risk            TEXT,
    salary_suggest  VARCHAR(100),
    market_ref      TEXT,
    final_recommend VARCHAR(20),
    report_md       TEXT,
    hr_decision     VARCHAR(20) DEFAULT 'PENDING',
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_report_resume ON candidate_report(resume_id);


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
