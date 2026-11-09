# 数据库增量升级

更新于2026-11-09。适用于已有招聘基础表的MySQL 8数据库；先备份，再停写升级，检查成功后更新前后端。

新本地数据卷使用 `deploy/local/01-schema.sql` 初始化，已包含当前表结构，不需要再跑以下增量。已有卷不会重新执行初始化文件；不要通过删除卷来升级。

## 执行顺序

| 顺序 | 脚本 | 内容 |
| --- | --- | --- |
| 1 | [20261107_match_tasks.sql](20261107_match_tasks.sql) | 持久化匹配任务 |
| 2 | [20261107_resume_parse_tasks.sql](20261107_resume_parse_tasks.sql) | 失败简历后台重新解析 |
| 3 | [20261107_material_tasks.sql](20261107_material_tasks.sql) | 面试题/报告任务 |
| 4 | [20261108_company_foundation.sql](20261108_company_foundation.sql) | 公司与成员 |
| 5 | [20261108_company_profile.sql](20261108_company_profile.sql) | 公司扩展资料 |
| 6 | [20261108_departments.sql](20261108_departments.sql) | 部门目录 |
| 7 | [20261108_position_departments.sql](20261108_position_departments.sql) | 岗位公司/部门关联 |
| 8 | [20261108_position_collaboration.sql](20261108_position_collaboration.sql) | 岗位共享授权 |
| 9 | [20261108_collaboration_operations.sql](20261108_collaboration_operations.sql) | EDITOR、授权版本、任务责任与操作记录 |
| 10 | [20261108_interview_assignments.sql](20261108_interview_assignments.sql) | 面试分配与评价 |
| 11 | [20261108_interview_scheduling.sql](20261108_interview_scheduling.sql) | 排期与历史 |
| 12 | [20261108_candidate_outcomes.sql](20261108_candidate_outcomes.sql) | 招聘结果与历史 |
| 13 | [20261109_document_lifecycle.sql](20261109_document_lifecycle.sql) | 文档上传租约、向量清理与失败重试 |

按数据库已完成的升级选择缺失脚本，保持依赖顺序；不要按文件名简单排序。脚本设计为可重复执行，但MySQL DDL可能隐式提交，失败后应检查已执行结果再恢复，不假设整批可以事务回滚。

## 本地Compose示例

在项目根目录执行，确保本地MySQL已启动、备份完成并停止应用写入。示例使用容器已有数据库环境变量，不在命令中填写真实密码。

```powershell
$migrationFiles = @(
  '20261107_match_tasks.sql',
  '20261107_resume_parse_tasks.sql',
  '20261107_material_tasks.sql',
  '20261108_company_foundation.sql',
  '20261108_company_profile.sql',
  '20261108_departments.sql',
  '20261108_position_departments.sql',
  '20261108_position_collaboration.sql',
  '20261108_collaboration_operations.sql',
  '20261108_interview_assignments.sql',
  '20261108_interview_scheduling.sql',
  '20261108_candidate_outcomes.sql',
  '20261109_document_lifecycle.sql'
)
foreach ($migrationFile in $migrationFiles) {
  Get-Content (Join-Path 'deploy/migrations' $migrationFile) -Raw |
    docker compose --env-file .env.local -f deploy/docker-compose.local.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" exec mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"'
  if ($LASTEXITCODE -ne 0) { throw "迁移失败：$migrationFile" }
}
```

其他部署通过自己的MySQL客户端执行同一顺序，并使用对应备份/恢复流程。生产部署需单独验证。

## 旧数据边界

脚本不自动合并公司账号，不推断旧岗位部门，不补造历史面试时间或招聘结果。旧授权默认只读，旧任务保留owner兼容边界。

[review_position_departments.sql](review_position_departments.sql) 仅供人工核对，不是迁移脚本，不应自动据此更新归属。功能和验收详情见 [docs/README.md](../../docs/README.md)。
