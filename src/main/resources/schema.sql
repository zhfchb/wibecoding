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

-- 对话历史
CREATE TABLE IF NOT EXISTS chat_history (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(64) NOT NULL,
    role       VARCHAR(16) NOT NULL,
    content    TEXT        NOT NULL,
    created_at TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

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
