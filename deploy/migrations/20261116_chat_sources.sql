-- Idempotent MySQL migration: preserve existing conversations.
SET @chat_sources_ddl = IF(
 (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='chat_history' AND column_name='sources_json')=0,
 'ALTER TABLE chat_history ADD COLUMN sources_json MEDIUMTEXT NULL', 'SELECT 1');
PREPARE chat_sources_stmt FROM @chat_sources_ddl;
EXECUTE chat_sources_stmt;
DEALLOCATE PREPARE chat_sources_stmt;
