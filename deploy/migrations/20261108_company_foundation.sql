CREATE TABLE IF NOT EXISTS recruitment_company (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    city VARCHAR(50),
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS recruitment_company_member (
    user_id BIGINT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    INDEX idx_company_member (company_id)
);
