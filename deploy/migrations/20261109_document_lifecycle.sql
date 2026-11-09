-- Durable cleanup/outbox; existing visible documents remain unchanged.
CREATE TABLE IF NOT EXISTS document_vector_cleanup (
 document_id VARCHAR(64) PRIMARY KEY,
 state VARCHAR(20) NOT NULL,
 next_attempt DATETIME(6) NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 lease_token VARCHAR(36),
 INDEX idx_vector_cleanup_due (next_attempt)
);
