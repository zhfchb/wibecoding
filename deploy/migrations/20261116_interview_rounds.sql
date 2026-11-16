SET @round_ddl = IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='recruitment_interview_assignment' AND column_name='round_no')=0,
 'ALTER TABLE recruitment_interview_assignment ADD COLUMN round_no INT NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE round_stmt FROM @round_ddl;
EXECUTE round_stmt;
DEALLOCATE PREPARE round_stmt;
