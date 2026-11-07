# 持久化匹配任务

## 本批范围

招聘工作台的批量匹配改为提交任务、查询进度、完成后读取结果。简历上传解析、面试题和报告生成仍使用原同步接口；本批不把所有 AI 操作统一任务化。

后续阶段已增加[失败简历后台重解析](RESUME_PARSE_TASKS.md)，首次上传依然同步。两类任务共享现有有界后台池。

## 使用行为

- 提交后即返回任务，页面每 2 秒查询岗位最新任务。刷新页面、关闭后重新进入均从数据库读取状态，不依赖浏览器保存任务 ID。
- 状态：`QUEUED → RUNNING → SUCCEEDED / FAILED / CANCELLED`。进度按候选人批次展示，阶段包括规则评分、语义评分和保存。
- 同岗位存在活动任务时复用它；相同岗位和 `requestKey` 对已落库的请求幂等。显式重试使用新 key，不覆盖旧任务记录。
- 服务端最多同时运行 2 个后台匹配任务，容量不足的任务保存在数据库等待调度。
- 语义评分失败仍可完成，但返回 `degradedCount`，页面提示哪些结果仅使用规则分，不将其显示为完整 AI 评分。
- 取消后阻止后续批次和结果提交。已发出的模型 HTTP 请求不保证立即中断，也不保证免除模型费用。
- 模型调用在数据库事务外。结果写入与任务成功状态在同一个事务中提交，原匹配 ID 和 HR 决策保留。
- 提交时保存岗位与已解析简历的 SHA-256 指纹，不额外保存简历正文。执行前和提交结果前检查输入，发现变化则失败，保留旧结果。
- GET、提交和取消均校验任务所有者及岗位归属。后台使用持久化的 ownerId，不继承 Web 请求线程的登录上下文。

## 超时和进程恢复

`MATCH_TASK_TIMEOUT_SECONDS` 默认 600 秒，从提交开始计时，包含排队时间。

- 仍在期限内的排队任务在服务重新启动后继续被调度。
- 已开始执行的任务不会自动再次调用模型。进程中断后，任务最晚在截止时间后标为 `FAILED/TIMEOUT`，由用户明确重试。
- 过期或取消的旧任务即使随后拿到模型响应，也不能提交结果或覆盖新任务。
- 轮询间隔默认 1 秒；页面查询也会检查过期状态。系统时间与数据库时间配置应在部署实例间保持一致。
- 这是有期限的持久化任务与手动重试机制，不是模型执行现场的断点续跑。

## API

| 方法和路径（前缀 `/api/v1/recruitment`） | 用途 |
| --- | --- |
| POST `/positions/{id}/match/tasks` | body 为 `{"requestKey":"唯一请求标识"}`，返回 202 与任务 |
| GET `/positions/{id}/match/tasks/latest` | 最新任务；没有任务时为空 |
| GET `/match/tasks/{taskId}` | 单个任务详情 |
| POST `/match/tasks/{taskId}/cancel` | 取消活动任务，重复取消保持终态 |
| POST `/positions/{id}/match/run` | 保留同步兼容接口，使用相同任务保护；已有活动任务时返回 409 |

同岗位的不同 key 在活动期间可以返回同一任务；所有已接受的 key 都保存到请求映射表，任务终止后重放任一 key 仍返回原任务，显式重试需新 key。

## 已有数据库升级

新建数据库使用更新后的 `src/main/resources/schema.sql` 或 `deploy/local/01-schema.sql`。
已有 MySQL 数据卷不会重新运行初始化脚本，**启动新版本前先执行**：

`deploy/migrations/20261107_match_tasks.sql`

该脚本只创建任务表和请求映射表，可重复执行，不删除或重置业务数据。本地 Docker 示例（项目根目录，先确保 MySQL 已启动）：

```powershell
Get-Content deploy/migrations/20261107_match_tasks.sql -Raw |
  docker compose --env-file .env.local -f deploy/docker-compose.local.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"'
```

不要通过删除数据卷来升级表结构。生产环境使用对应数据库的变更流程执行相同 SQL。

## 验证

2026-11-07 本轮实际结果：启用 `RUN_AI_E2E=true` 的 `mvn verify` 通过，67 项中 62 通过、5 项按原外部环境条件跳过；JAR 打包成功。前端生产构建通过，保留现有大包体积警告。Chrome fixture 浏览器脚本通过，390px 页面无横向溢出。Compose 本地/生产配置解析、当前文档链接与初始化/迁移 SQL 一致性检查通过。

- `MatchTaskTest`：11 项数据库测试，覆盖并发提交复用、原请求及复用请求 key 的幂等、竞争领取、无请求线程身份执行、降级计数、取消后的迟到结果隔离、运行中超时及手动重试、线程中断、输入变化、账号隔离和同步接口保护。
- `MatchReliabilityTest`：保留上一批 6 项评分、人工决策与事务回滚测试。
- `PythonAiE2ETest`：真实 Java/Python HTTP 进程配合本地模拟模型，新增异步任务 202、幂等、调度完成、最新任务恢复查询和跨用户 GET/取消隔离。
- `scripts/verify-match-task-ui.py`：Chrome 无头浏览器配合 fixture API，检查提交结果不确定时复用请求 key、刷新恢复、进度、降级提示、状态查询失败、显式重试、取消和 390px 渲染。

浏览器脚本需 Python Playwright 和已安装 Chrome。先启动前端：

```powershell
# frontend 目录
npm run dev -- --host 127.0.0.1 --port 5188 --strictPort
# 另一个终端，项目根目录
python scripts/verify-match-task-ui.py
```

上述模拟验证不代表真实模型效果、真实 MySQL 行锁行为、容器重启恢复或生产部署验收；这些仍需在真实环境补验。
