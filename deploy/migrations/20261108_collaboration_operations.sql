-- MySQL 8 repeatable upgrade; existing grants remain VIEWER, old task ownership is preserved.
SET @collaboration_column_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='recruitment_position_viewer' AND column_name='permission');
SET @collaboration_ddl=IF(@collaboration_column_exists=0,'ALTER TABLE recruitment_position_viewer ADD COLUMN permission VARCHAR(20) NOT NULL DEFAULT ''VIEWER''','SELECT 1');
PREPARE collaboration_stmt FROM @collaboration_ddl;
EXECUTE collaboration_stmt;
DEALLOCATE PREPARE collaboration_stmt;
SET @collaboration_column_exists=(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='recruitment_position_viewer' AND column_name='revision');
SET @collaboration_ddl=IF(@collaboration_column_exists=0,'ALTER TABLE recruitment_position_viewer ADD COLUMN revision VARCHAR(36) NULL','SELECT 1');
PREPARE collaboration_stmt FROM @collaboration_ddl;
EXECUTE collaboration_stmt;
DEALLOCATE PREPARE collaboration_stmt;

CREATE TABLE IF NOT EXISTS recruitment_task_actor (
    kind VARCHAR(20) NOT NULL,
    task_id VARCHAR(36) NOT NULL,
    position_id BIGINT NOT NULL,
    owner_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    grant_revision VARCHAR(36),
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY(kind,task_id),
    INDEX idx_task_actor_position (position_id)
);
CREATE TABLE IF NOT EXISTS recruitment_position_activity (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    position_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    action VARCHAR(40) NOT NULL,
    resource_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    INDEX idx_position_activity (position_id,id)
);
