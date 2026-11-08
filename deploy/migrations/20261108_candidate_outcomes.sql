CREATE TABLE IF NOT EXISTS recruitment_candidate_outcome (
 resume_id BIGINT PRIMARY KEY,
 version INT NOT NULL,
 outcome VARCHAR(20) NOT NULL,
 archived BOOLEAN NOT NULL DEFAULT FALSE,
 reason VARCHAR(2000) NOT NULL,
 actor_id BIGINT NOT NULL,
 updated_at DATETIME(6) NOT NULL
);
CREATE TABLE IF NOT EXISTS recruitment_candidate_outcome_event (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 resume_id BIGINT NOT NULL,
 version INT NOT NULL,
 action VARCHAR(20) NOT NULL,
 outcome VARCHAR(20) NOT NULL,
 archived BOOLEAN NOT NULL,
 reason VARCHAR(2000) NOT NULL,
 actor_id BIGINT NOT NULL,
 changed_at DATETIME(6) NOT NULL,
 UNIQUE (resume_id,version)
);
