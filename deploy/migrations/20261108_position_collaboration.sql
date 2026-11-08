
-- Explicit position-scoped read grants. No company-wide automatic sharing.
CREATE TABLE IF NOT EXISTS recruitment_position_viewer (
    position_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    granted_by BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (position_id,user_id),
    INDEX idx_position_viewer_user (user_id,position_id)
);
