# 脚本导航

命令均从仓库根目录执行。为兼容现有 CI、文档和脚本调用，本轮保留脚本路径，通过此导航分类。

| 用途 | 入口 | 注意 |
| --- | --- | --- |
| 标准回归 | verify-release.py | 汇总 Java、前端构建和页面回归；Vite 需先启动 |
| 定向页面检查 | verify-*-ui.py、verify-library-improvements.py | 多数使用模拟接口；以文件说明为准 |
| 结构接口比较 | verify-structure.py | 需要整理前的 OpenAPI 快照；只读、无模型调用，不属于新克隆必跑项 |
| 结构运行页面复核 | verify-structure-ui.py | 依赖本地已有 interview-demo 和 chat-title-demo 状态文件；只读业务数据 |
| 面试练习 | seed-interview-demo.py、verify-interview-demo.py | 前者写入虚构演示数据，后者复核既有数据 |
| 演示数据 | generate-demo-data.py、seed-demo-data.py、seed-positions.ps1 | 生成素材或写入演示数据，执行前阅读脚本参数 |
| 初始化维护 | init-local-mysql.sql、init-users.sql、qdrant-setup.ps1 | 数据库升级优先遵循 deploy/migrations/README.md，不重复初始化已有库 |
| 真实验收 | verify-live-*.py | 部分脚本写入测试数据并调用付费模型；按验收文档执行并复用 state.json |

```powershell
python scripts/verify-release.py
python scripts/verify-release.py --skip-browser
python scripts/verify-structure.py --help
```

日志、截图、测试账号、契约快照和报告保存在被 Git 忽略的 outputs/ 下。不要提交真实配置或测试凭据。真实验收的范围与步骤见 [验收导航](../docs/README.md)。
