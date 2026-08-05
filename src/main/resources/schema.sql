-- 知识库文档元数据
CREATE TABLE IF NOT EXISTS document (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id VARCHAR(64)  NOT NULL,
    file_name   VARCHAR(255) NOT NULL,
    chunk_count INT          NOT NULL,
    file_size   BIGINT       NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0
);

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

