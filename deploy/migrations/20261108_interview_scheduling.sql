-- Additive scheduling; UTC wall-clock DATETIME values are mapped explicitly to UTC instants.
CREATE TABLE IF NOT EXISTS recruitment_interview_schedule (
    assignment_id BIGINT PRIMARY KEY,
    interviewer_id BIGINT NOT NULL,
    resume_id BIGINT NOT NULL,
    version INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    start_utc DATETIME(6) NOT NULL,
    end_utc DATETIME(6) NOT NULL,
    time_zone VARCHAR(80) NOT NULL,
    mode VARCHAR(20) NOT NULL,
    location VARCHAR(500) NOT NULL,
    meeting_url VARCHAR(1000),
    reason VARCHAR(500),
    updated_by BIGINT NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_interview_time_person (interviewer_id,status,start_utc,end_utc),
    INDEX idx_interview_time_resume (resume_id,status,start_utc,end_utc)
);
CREATE TABLE IF NOT EXISTS recruitment_interview_schedule_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    assignment_id BIGINT NOT NULL,
    version INT NOT NULL,
    status VARCHAR(20) NOT NULL,
    start_utc DATETIME(6) NOT NULL,
    end_utc DATETIME(6) NOT NULL,
    time_zone VARCHAR(80) NOT NULL,
    mode VARCHAR(20) NOT NULL,
    location VARCHAR(500) NOT NULL,
    meeting_url VARCHAR(1000),
    reason VARCHAR(500),
    actor_id BIGINT NOT NULL,
    changed_at DATETIME(6) NOT NULL,
    UNIQUE (assignment_id,version)
);
