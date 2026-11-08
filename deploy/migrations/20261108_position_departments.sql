-- MySQL 8 additive upgrade. No inferred associations or ownership rewrites.
SET @position_column_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='job_position' AND column_name='company_id');
SET @position_ddl = IF(@position_column_exists=0,
    'ALTER TABLE job_position ADD COLUMN company_id BIGINT NULL', 'SELECT 1');
PREPARE position_stmt FROM @position_ddl;
EXECUTE position_stmt;
DEALLOCATE PREPARE position_stmt;
SET @position_column_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='job_position' AND column_name='department_id');
SET @position_ddl = IF(@position_column_exists=0,
    'ALTER TABLE job_position ADD COLUMN department_id BIGINT NULL', 'SELECT 1');
PREPARE position_stmt FROM @position_ddl;
EXECUTE position_stmt;
DEALLOCATE PREPARE position_stmt;
