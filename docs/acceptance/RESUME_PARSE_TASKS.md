# 失败简历重新解析

## 本批完成的行为

- 修复首次上传的统计：只有 `PARSED` 才计入解析成功。模型解析失败时保留失败简历及原文，返回真实失败数量与 resumeId。
- 在简历表中提供单份“重新解析”和多选“批量重新解析”，一次 1～20 份，仅限已保存原文的 `FAILED` 简历。
- 请求快速返回 202；任务及逐份结果保存在现有数据库，页面每 2 秒查询，刷新后可恢复查看。
- 同岗位同 key 对相同的简历 ID 集合幂等；同 key 改用其他 ID 集合返回 409。新 key 在存在活动解析任务时返回 409，不创建第二批任务。
- 一份失败不影响其他简历。终态为 `SUCCEEDED`、`PARTIAL`、`FAILED` 或 `CANCELLED`，逐份展示成功、失败与错误原因。
- 成功简历不再允许重解析，失败部分可用新 key 再试；保留已有 docId，不重复上传文件、不重新调用 embedding 或写入向量。
- 模型调用不处于数据库事务内；结构化字段、清空原解析错误和单份成功状态在同一事务提交。
- 对技能列表、字段类型/长度和经验年限再次做 Java 边界校验；不把原始模型响应、异常文本或密钥写入错误消息。
- 写入前核对所有者、岗位、失败状态和原文指纹，已成功、已删除、原文已变化的简历不会被旧响应覆盖。

## 任务、取消及恢复

- 状态：`QUEUED → RUNNING → SUCCEEDED / PARTIAL / FAILED / CANCELLED`。原 candidate_resume 的 `FAILED` 状态在成功提交后才改为 `PARSED`。
- 与匹配任务共享现有的 2 线程有界执行池；队列保存在数据库，无需新增消息队列或外部服务。
- `RESUME_PARSE_TASK_TIMEOUT_SECONDS` 默认 600 秒，包含排队时间。任务超过截止时间后终止未完成项目，已提交成功的简历保留。
- 服务重启后，尚未开始且未过期的排队任务继续调度。执行中任务不自动重复调用模型，等截止时间后失败，再由用户明确重试。
- 取消同样保留已成功项目。已发送的模型请求不保证立即中断或停止计费，但取消/超时后的迟到响应不能写入；再试成功的结果不会被旧响应覆盖。
- 任务表仅保存 ID、哈希、状态和安全错误信息；解析仍读取既有 candidate_resume.raw_text，不额外复制原文。

## API 与升级

前缀为 `/api/v1/recruitment`：

| 方法与路径 | 用途 |
| --- | --- |
| POST `/positions/{id}/resumes/parse-tasks` | body：`{"requestKey":"唯一标识","resumeIds":[1,2]}`，返回 202 |
| GET `/positions/{id}/resumes/parse-tasks/latest` | 岗位最新解析任务，没有任务时为空 |
| GET `/resumes/parse-tasks/{taskId}` | 任务及逐份结果 |
| POST `/resumes/parse-tasks/{taskId}/cancel` | 取消未完成部分，终态不反转 |

简历列表新增 `retryable` 布尔字段，不包含原文。所有接口检查当前用户与岗位、任务归属，后台通过持久化 ownerId 校验，不继承请求线程身份。

已有数据库启动新版本前执行 `deploy/migrations/20261107_resume_parse_tasks.sql`。该脚本仅创建两张任务表，不删除/重置业务数据，可重复执行。若未安装上一阶段的匹配任务表，也需先执行其 migration。新数据库的主 schema 和 Docker 初始化脚本已包含这些表。

本地 Docker 升级命令（项目根目录，MySQL 已启动）：

```powershell
Get-Content deploy/migrations/20261107_resume_parse_tasks.sql -Raw |
  docker compose --env-file .env.local -f deploy/docker-compose.local.yml exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" "$MYSQL_DATABASE"'
```

## 验证与剩余范围

2026-11-07 实际验证：启用 `RUN_AI_E2E=true` 的全量 `mvn verify` 共 77 项，72 通过、5 项按既有外部环境条件跳过；随后对解析任务、首次上传统计和跨服务链路的 12 项定向验证通过，JAR 打包成功。前端生产构建、简历恢复和原匹配两套 Chrome fixture 浏览器回归通过；390px 页面无横向溢出。Compose 本地/生产配置、文档链接、迁移与初始化 SQL 一致性检查通过。前端仍有原有大包体积警告。

- `ResumeParseTaskTest`：10 项 H2 数据库测试覆盖部分失败、长期幂等、并发提交、取消与迟到响应、超时后重试、原文变化、成功状态保护、非法模型输出、事务回滚和账号隔离。
- `RecruitmentResumeParsingTest`：首次上传成功/失败计数及错误脱敏。
- `PythonAiE2ETest`：真实 Java/Python HTTP 配合本地模拟模型，先制造解析服务失败，再后台重试恢复并进入后续匹配/面试流程；校验重试不新增 embedding 请求，且跨用户无法读取或取消任务。
- `scripts/verify-resume-parse-ui.py`：Chrome fixture API 回归，覆盖多选、提交不确定时复用 key、刷新恢复、部分失败只重试失败项、取消、查询故障和 390px 无横向溢出；原匹配页面回归脚本同步适配并复测。

前端脚本依赖 Python Playwright 与本机 Chrome，先启动前端 5188 端口，命令与[匹配任务验证](MATCH_TASKS.md)一致，改用 `python scripts/verify-resume-parse-ui.py`。

首次上传仍是同步的文件入库/向量化/解析链路。若在原文落库之前失败，无法使用本功能，需修复文件或服务后重新上传。扫描版 PDF 的 OCR、首次上传整体异步化仍未实现；面试题/报告已在[后续阶段](MATERIAL_TASKS.md)完成任务化。真实模型效果、真实 MySQL 行锁、容器重启恢复和生产部署尚未验收，不用模拟结果替代。
