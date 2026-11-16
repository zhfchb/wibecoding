CREATE TABLE IF NOT EXISTS recruitment_candidate_note (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 resume_id BIGINT NOT NULL,
 position_id BIGINT NOT NULL,
 actor_id BIGINT NOT NULL,
 kind VARCHAR(24) NOT NULL,
 content TEXT NOT NULL,
 next_contact_at DATETIME(6),
 request_key VARCHAR(80) NOT NULL,
 created_at DATETIME(6) NOT NULL,
 UNIQUE(resume_id,request_key)
);
