-- MySQL 8: additive, repeatable upgrade; legacy profiles remain nullable.
SET @company_profile_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema=DATABASE() AND table_name='recruitment_company' AND column_name='profile_json');
SET @company_profile_ddl = IF(@company_profile_exists=0,
    'ALTER TABLE recruitment_company ADD COLUMN profile_json TEXT NULL', 'SELECT 1');
PREPARE company_profile_stmt FROM @company_profile_ddl;
EXECUTE company_profile_stmt;
DEALLOCATE PREPARE company_profile_stmt;
