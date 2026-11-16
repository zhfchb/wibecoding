CREATE TABLE IF NOT EXISTS chat_session (
 user_id BIGINT NOT NULL,
 session_id VARCHAR(64) NOT NULL,
 title VARCHAR(80),
 pinned INT NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id, session_id)
);
