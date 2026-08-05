-- 本地 MySQL 初始化默认用户(由 agent 账号执行)
-- 默认账号:
--   admin / admin123  (ADMIN 角色)
--   user  / user123   (USER 角色)
-- 密码用 BCrypt(强度 10)加密,可通过 BCryptPasswordEncoder.matches 验证
-- 注意:BCrypt 每次生成的盐不同,如需重新生成哈希可用 BCryptPasswordEncoder.encode("新密码")

INSERT INTO sys_user (username, password, role)
SELECT 'admin', '$2a$10$DJ04TJ.3/ah658qnAe1EtO6aHdxqeMh0x1ojn7UwqaW6fSqy2VWcq', 'ADMIN'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'admin');

INSERT INTO sys_user (username, password, role)
SELECT 'user', '$2a$10$uOsfla4NTOIdlr4hcLluX.se2a00qRa7Lm/lwNncgWHItkwCtrLWG', 'USER'
WHERE NOT EXISTS (SELECT 1 FROM sys_user WHERE username = 'user');
