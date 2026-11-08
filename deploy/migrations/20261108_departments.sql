-- Additive department directory. Does not change any existing job ownership or department text.
CREATE TABLE IF NOT EXISTS recruitment_department (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    company_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    name_key VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    UNIQUE (company_id, name_key),
    INDEX idx_department_company_status (company_id, status)
);
