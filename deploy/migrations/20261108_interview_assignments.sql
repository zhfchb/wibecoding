-- Additive interview assignments and private drafts; no automatic assignments.
CREATE TABLE IF NOT EXISTS recruitment_interview_assignment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    position_id BIGINT NOT NULL,
    resume_id BIGINT NOT NULL,
    interviewer_id BIGINT NOT NULL,
    assigned_by BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ASSIGNED',
    version INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    INDEX idx_interview_assignee (interviewer_id,status),
    INDEX idx_interview_candidate (position_id,resume_id,interviewer_id)
);
CREATE TABLE IF NOT EXISTS recruitment_interview_evaluation (
    assignment_id BIGINT PRIMARY KEY,
    score INT,
    recommendation VARCHAR(20),
    strengths TEXT,
    concerns TEXT,
    summary TEXT,
    submitted_at TIMESTAMP(6)
);
