-- 本地宝塔 MySQL 初始化脚本(由 root 执行一次)
-- 用途:为 wibecoding 项目创建 agent 数据库 + agent 业务账号

CREATE DATABASE IF NOT EXISTS agent
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

-- MySQL 8.0 必须先 CREATE USER 再 GRANT
CREATE USER IF NOT EXISTS 'agent'@'localhost' IDENTIFIED BY 'agent123';
CREATE USER IF NOT EXISTS 'agent'@'%'         IDENTIFIED BY 'agent123';

GRANT ALL PRIVILEGES ON agent.* TO 'agent'@'localhost';
GRANT ALL PRIVILEGES ON agent.* TO 'agent'@'%';

FLUSH PRIVILEGES;
