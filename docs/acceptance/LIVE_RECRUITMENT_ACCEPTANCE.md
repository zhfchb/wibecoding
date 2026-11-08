# 真实招聘流程验收（2026-11-08）

## 执行方式

用户明确要求使用已配置环境跑通全流程。本轮启动既有wibecoding-local栈（不重建数据卷），先备份MySQL，再创建独立测试公司、部门、岗位、HR/只读HR/面试官及一份虚构简历。未使用现有用户的真实简历或修改原有岗位。

真实执行入口：

```powershell
python scripts/verify-live-recruitment.py
# 中断后使用首次输出的state.json恢复，不重复新建流程或付费任务
python scripts/verify-live-recruitment.py --resume outputs/acceptance/live-时间戳/state.json
python scripts/verify-live-ui.py --state outputs/acceptance/live-时间戳/state.json
```

该入口会创建持久测试数据并调用真实付费模型，不纳入默认verify-release脚本。仅用于本机部署。state.json保留专用测试账号密码和阶段结果，目录被Git/Docker忽略；不存储真实供应商密钥或登录令牌。付费任务保存taskId、复用requestKey后查询进度，不因轮询重复提交。

## 已核实的真实链路

- Compose五服务启动，Java/Python健康；容器加载DeepSeek deepseek-chat、硅基流动BAAI/bge-m3，Java embedding provider为openai。
- 公司资料省市验证、部门、岗位、EDITOR/VIEWER授权与四角色登录成功。
- 虚构简历真实上传、向量化和解析成功，提取姓名“林验收”。Qdrant knowledge集合dense为1024维，该测试owner有1个点；MySQL中仅此测试岗位新增1份简历。
- 后台匹配成功，评分状态COMPLETE，语义评分92、总分98，degradedCount为0。该数值仅为本次虚构样本输出，不代表招聘准确率。
- HR初筛通过，真实生成6道面试题及1份评估报告，后台任务均SUCCEEDED。报告推荐STRONG_YES仅为模型建议；marketRefs为0，本轮未验证外部市场检索，不将报告作为真实市场薪资证据。
- 真实RAG问答返回1条简历来源，验证Java/Python/检索/DeepSeek回答链路。
- EDITOR安排明确时区的五分钟面试；保存私有草稿，招聘端不能读取，VIEWER不能修改招聘结果，面试官不能读取岗位总览。
- 重复使用原匹配/题目/报告requestKey均返回原taskId，未创建额外付费任务；只读成员发起任务、面试官生成资料及读取最终结果、招聘成员读取私有面试详情均被拒绝。
- 浏览器未mock任何API，四角色实际登录，真实题目/报告可读、只读成员没有写入口、面试官工作台正确展示安排与已有题目。

服务器在计划结束前实际返回409拒绝评价提交；等待真实结束时间后，评价提交成功，分配状态SUBMITTED、排期COMPLETED。HR记录HIRED，归档、重新开启、再次归档均成功，最终版本4且历史4条，总览显示已录用/已归档和1条已提交评价。全程未修改系统时钟或写SQL绕过规则。最终四角色浏览器再次验证通过，面试官已提交评价不可再编辑。

安全摘要为本地 `outputs/acceptance/live-20261108-184700/report.json`；浏览器结果在同目录browser-report.json，额外幂等/越权检查在boundary-report.json。test-accounts.txt仅含本轮生成的专用测试账号，用户可据此登录复查；这些文件均未提交Git。

## 留存与范围

数据位于独立测试公司，标题带“真实链路验收”，保留用于用户复查；服务保持运行。测试账号及结果在本地忽略目录 `outputs/acceptance/live-20261108-184700/`。备份在 `demo-data/local/startup-backups/`，仅用于恢复保障，未执行恢复。

本轮验证单样本正常链路和关键权限，不等于压力测试、生产发布、备份恢复演练或真实人员面试。首次上传失败重试、任务取消等其他异常由现有自动化验证覆盖，本轮未人为制造真实供应商故障。
