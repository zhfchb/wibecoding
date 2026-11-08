# 文档导航

所有命令默认从项目根目录执行。历史文档保留用于理解演进，框架、接口和启动方式以当前代码、根 README 和迁移说明为准。

| 分类 | 内容 |
| --- | --- |
| 产品 | [用户定位与角色权限](product/用户定位与角色权限.md)、[当前产品定位](product/PRODUCT.md)、[招聘业务规划](product/智能招聘助手业务规划.md) |
| 开发 | [环境配置](development/环境配置说明.md)、[本地 Docker](development/LOCAL_DOCKER.md)、[AI 迁移说明](development/LangChain分阶段迁移.md) |
| 开发参考 | [架构大纲](development/项目架构大纲.md)、[使用文档](development/使用文档.md)、[工作约定](development/项目工作约定.md)、[Qdrant 迁移](development/qdrant-migration.md) |
| 验收 | [面试官分配与评价](acceptance/INTERVIEW_ASSIGNMENTS.md)、[招聘流程协作](acceptance/POSITION_OPERATIONS.md)、[岗位只读协作](acceptance/POSITION_COLLABORATION.md)、[岗位部门关联](acceptance/POSITION_DEPARTMENTS.md)、[部门管理](acceptance/DEPARTMENTS.md)、[招聘流程可靠性](acceptance/RECRUITMENT_ACCEPTANCE.md)、[持久化匹配任务](acceptance/MATCH_TASKS.md)、[失败简历重解析](acceptance/RESUME_PARSE_TASKS.md)、[题目与报告任务](acceptance/MATERIAL_TASKS.md) |
| 历史与学习 | [学习路线](archive/学习路线.md)、[原理笔记](archive/核心原理笔记.md)、[面试手册](archive/面试问答手册.md)、[早期选型需求](archive/开源项目选型需求文档.md) |
| 变更 | [变更日志](变更日志.md) |

招聘表结构参考放在 `development/sql/`；实际应用初始化脚本为 `src/main/resources/schema.sql`，本地 Docker 初始化脚本为 `deploy/local/01-schema.sql`（路径均相对项目根目录）。
