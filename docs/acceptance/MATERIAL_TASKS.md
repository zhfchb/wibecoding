# 面试题与评估报告后台任务

## 本批范围

面试题与评估报告使用同一个持久化任务机制，类型为 `QUESTIONS` 和 `REPORT`。首次简历上传仍同步；匹配、失败简历重解析沿用各自已有任务接口。

- 提交后返回 202，抽屉每 2 秒查询任务阶段，关闭抽屉不取消；重新打开或刷新页面后再打开可继续查看。
- 同一候选人同一类型存在活动任务时复用；所有已接受请求 key 都保存映射，任务终止后重放仍返回原任务。明确重试使用新 key，不自动再次消费模型额度。
- 状态为 `QUEUED → RUNNING → SUCCEEDED / FAILED / CANCELLED`；阶段包括准备、报告参考搜索、生成与保存，不编造模型 token 级百分比进度。
- 与匹配、重解析共享现有 2 线程后台池。排队和执行均计入 `MATERIAL_TASK_TIMEOUT_SECONDS`，默认 600 秒。
- 排队任务可在重启后继续调度；执行中任务不自动重放，进程中断后最晚在截止时间失败，由用户明确重试。
- 搜索/模型请求不占数据库事务。成功后在一个短事务中替换内容并提交成功状态，失败、取消、超时、回滚时保留旧内容。
- 取消不能保证立即中断已发出的 HTTP 请求或停止计费，但迟到响应不能写入或覆盖新一轮结果。

## 输入与内容约束

- 生成前和写入前检查岗位、简历、匹配和人工决策的指纹；中途修改、重匹配、关闭岗位、删除候选人或淘汰候选人会阻止提交。
- 保持原业务边界：题目要求已解析且未被淘汰；报告还要求已有匹配。没有额外强制 `APPROVED`，与原行为一致。
- 题目严格校验 6 道、不重复、技术3/项目2/行为1、合法难度、字段类型和长度；报告校验结论枚举、必要内容与存储长度。非法值不再默默转换为默认类别或推荐。
- 成功内容的输入已变化时，查询返回 `stale=true`，页面提示内容来自旧版本。
- 新报告显示生成时的匹配分快照，不读取当前匹配分冒充旧报告依据；无法追溯分数的历史旧报告显示“未记录”，不会自动回填当前分数。
- 任务表仅存哈希、状态、来源分数与结果ID，不重复保存简历正文。错误消息不包含原始模型响应或密钥。
- 成功后若读取内容暂时失败，保留页面已有内容并提供重新加载入口，不把请求失败当成没有内容。

## API 与旧接口

前缀 `/api/v1/recruitment`，kind 为大写 `QUESTIONS` 或 `REPORT`：

| 方法和路径 | 用途 |
| --- | --- |
| POST `/resumes/{resumeId}/materials/{kind}/tasks` | body：`{"requestKey":"唯一请求标识"}`，返回202 |
| GET `/resumes/{resumeId}/materials/{kind}/tasks/latest` | 最新任务；没有任务时为空 |
| GET `/materials/tasks/{taskId}` | 单个任务与过期内容标记 |
| POST `/materials/tasks/{taskId}/cancel` | 取消活动任务；终态不反转 |

原 `/questions/generate` 和 `/report/generate` 同步接口保留，但经由相同任务保护与提交事务；存在同类型活动任务时返回409。内容读取接口保持原路径。用户接口及后台执行均校验明确的 ownerId，不能跨账号读取、提交或取消。

## 数据库升级

已有数据库启动新版前执行：`deploy/migrations/20261107_material_tasks.sql`。它仅创建任务和请求映射表，可重复执行，不重置业务数据。此前匹配和重解析的迁移仍需保留并执行。

新库的 `src/main/resources/schema.sql` 和 `deploy/local/01-schema.sql` 已包含新表。已有 Docker 数据卷不会重新执行初始化脚本，不删除卷来升级。

本地 Docker 示例（项目根目录，MySQL已启动）：

```powershell
Get-Content deploy/migrations/20261107_material_tasks.sql -Raw |
  docker compose --env-file .env.local -f deploy/docker-compose.local.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"'
```

## 验证与限制

2026-11-07 实际结果：启用模拟跨服务测试的全量 `mvn verify` 共89项，84通过、5项按既有外部环境条件跳过；后续字段校验与跨服务13项、来源分数/排序等12项定向复测通过，JAR打包成功。前端构建与三套Chrome fixture浏览器回归通过，390px无横向溢出；Compose本地/生产配置解析、文档链接、迁移与初始化SQL一致性检查通过。保留现有前端大包体积警告。

- `MaterialTaskTest`：12项H2数据库测试覆盖两种资料、无请求线程身份、事务外模型调用、幂等及请求别名、并发提交、失败保留、取消/超时迟到响应、输入和淘汰状态变化、非法内容、提交回滚、报告来源分数、历史分数未知和权限/同步接口保护。
- `PythonAiE2ETest`：真实 Java/Python HTTP 配合本地模拟模型，保留旧同步接口验证，新增两种异步任务202、调度完成、幂等、最新任务读取及跨用户读取/取消隔离。
- `scripts/verify-material-ui.py`：Chrome fixture API 检查提交不确定时复用 key、关闭/刷新后恢复、旧内容保留、明确重试、取消、报告阶段和过期提示、读取失败重载、查询故障、390px抽屉和切换候选人不串内容。
- 原匹配与简历重解析浏览器脚本同步回归。

浏览器脚本依赖 Python Playwright 和已安装Chrome。先在 frontend 启动 `npm run dev -- --host 127.0.0.1 --port 5188 --strictPort`，再从根目录执行 `python scripts/verify-material-ui.py`。

真实模型内容质量、真实MySQL锁行为、容器重启恢复与生产部署未验收。报告的建议仍需人工核实；搜索关闭/失败时没有市场引用，不代表完成了市场调研。暂未实现首次上传整体异步化、批量跨候选人生成、历史资料版本列表或真实模型效果评测。
