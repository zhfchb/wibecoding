# 智能招聘助手（AI Recruiting Copilot）业务规划文档

> 版本：v1.0
> 日期：2026-08-10
> 基于现有项目：wibecoding RAG + Agent 平台
> 目标：为现有技术平台注入真实业务场景，形成完整可讲、可演示、可量化的垂直领域产品

---

## 一、为什么是「智能招聘」

### 1.1 现状问题
当前平台是「通用知识库问答」：
- ✅ 技术栈完整：RAG管道、混合检索、Rerank、多Agent工作流、HITL审核、Web搜索、MCP式工具调用
- ❌ 没有业务锚点：面试官问"这系统解决什么问题"，只能回答"能问答文档"
- ❌ 无法量化价值：没有业务指标（匹配准确率、筛选耗时下降等）

### 1.2 招聘场景的契合度

| 招聘业务痛点 | 对应平台已有能力 |
|---|---|
| 简历多、筛选慢（HR平均看一份简历 10-30 秒，几百份简历耗时长） | RAG 向量检索 + Rerank 粗排精排 |
| HR 不了解技术岗最新技能行情 | WebSearchTools（Tavily 实时搜索） |
| 筛选标准主观、无法追溯 | 多Agent工作流 + HITL 人工审核 |
| 面试题拍脑袋、不针对候选人 | LLM 定制生成 + 知识库（岗位技能库） |
| 各环节信息割裂（简历/面试/offer） | 会话管理 + 持久化 + 评估报告 |

### 1.3 一句话定位

> 面向中小企业 HR 的 AI 招聘副驾：上传 JD 和简历后，自动完成「人岗匹配排序 → 市场行情校准 → 定制面试题 → 候选人评估报告」，每个关键节点 HR 可介入审核。

---

## 二、用户角色与核心旅程

### 2.1 角色

| 角色 | 职责 | 使用功能 |
|---|---|---|
| **HR**（主要用户） | 发布岗位、筛简历、安排面试 | JD管理、简历库、匹配看板、审核流 |
| **面试官**（次要） | 看候选人评估报告、补充面试反馈 | 面试题、评估报告 |
| **系统管理员** | 用户管理、全局配置 | 现有 Admin 模块 |

### 2.2 核心用户旅程（Happy Path）

```
[HR] 创建岗位JD（表单 + JD文档上传）
        │
        ▼
[HR] 批量上传候选人简历（PDF/Word，10~500份）
        │  系统自动：解析 → 结构化抽取 → 分块 → Embedding → 入向量库
        ▼
[HR] 点击「开始智能匹配」
        │
        ▼
┌─────────────── 多Agent工作流（后台异步执行）───────────────┐
│                                                            │
│  Stage 1  简历筛选 Agent                                    │
│    · RAG检索：JD需求 ↔ 简历库向量匹配                       │
│    · 混合检索+Rerank 精排，输出 Top N 候选人+匹配评分        │
│              │                                             │
│              ▼  ── HITL中断：HR审核候选人名单 ──             │
│  [HR] 批准/剔除/调整排名                                    │
│              │                                             │
│  Stage 2  市场行情 Agent                                    │
│    · 调用 WebSearchTools 搜薪资行情、技能热度               │
│    · 生成薪资建议区间 + 技能稀缺度提示                       │
│              │                                             │
│              ▼  ── HITL中断：HR确认薪资预算 ──              │
│  Stage 3  面试题 Agent                                     │
│    · 针对每位候选人的技能缺口定制面试题                      │
│    · 从「岗位技能知识库」检索考察点                          │
│              │                                             │
│              ▼  ── HITL中断：HR/面试官审核题目 ──           │
│  Stage 4  评估报告 Agent                                    │
│    · 汇总：匹配度 + 薪资建议 + 面试题 + 风险提示             │
│    · 生成最终候选人推荐报告                                  │
└────────────────────────────────────────────────────────────┘
        │
        ▼
[HR] 在看板查看最终推荐名单，导出报告 / 推送面试邀约
```

### 2.3 次要旅程

- **对话式查询**：HR 在聊天窗口问"Java 岗位候选人里谁会 K8s？"→ RAG 检索简历库作答（复用现有 Chat 模块）
- **市场情报**："现在 AI Agent 工程师市场价多少？" → Web 搜索兜底回答
- **岗位复盘**：面试结束后回填反馈，沉淀为知识库，优化下一轮匹配

---

## 三、系统架构

### 3.1 分层架构图

```
┌──────────────────────────────────────────────────────────┐
│  前端 React + Ant Design                                   │
│  ├─ 招聘看板（候选人卡片墙、匹配度排序、状态流转）           │
│  ├─ JD管理页（表单 + JD文档上传）                           │
│  ├─ 简历库页（批量上传、解析状态、标签筛选）                 │
│  ├─ 审核中心（复用现有 HITL 任务列表）                      │
│  └─ 智能对话（复用现有 Chat 页，上下文切到招聘域）           │
└────────────────────────┬─────────────────────────────────┘
                         │ REST + SSE
┌────────────────────────▼─────────────────────────────────┐
│  后端 Spring Boot                                          │
│  ├─ RecruitmentController（新增）                          │
│  ├─ RecruitmentWorkflowService（新增，编排4阶段Agent流）    │
│  ├─ 现有复用层                                             │
│  │   ├─ MultiAgentGraphFactory（扩展为招聘专用图）          │
│  │   ├─ RagChatService（对话查询简历库）                    │
│  │   ├─ KnowledgeService（简历/JD 文档入库）                │
│  │   └─ AgentService + Tools                               │
│  ├─ 新增 Agent 工具                                        │
│  │   ├─ ResumeParserTool（简历结构化解析）                  │
│  │   ├─ JdMatcherTool（人岗匹配评分）                       │
│  │   └─ SalaryBenchmarkTool（薪资基准查询）                 │
│  └─ 复用 WebSearchTools / TimeTools                        │
└──────┬──────────┬──────────┬──────────┬───────────────────┘
       │          │          │          │
   ┌───▼───┐  ┌───▼───┐  ┌───▼────┐  ┌─▼─────────┐
   │ MySQL │  │ Redis │  │ Qdrant │  │ 外部API    │
   │ 业务表 │  │ 会话  │  │ 向量库 │  │ DeepSeek   │
   │ +checkpoint│     │  │        │  │ Tavily     │
   └───────┘  └───────┘  └────────┘  │ 硅基流动   │
                                      └───────────┘
```

### 3.2 复用与新增对照表

| 模块 | 复用 | 新增 | 改造 |
|---|---|---|---|
| 认证授权 | ✅ JWT 全套 | HR角色标识（复用USER角色+权限位） | — |
| 文档解析 | ✅ PDFTextExtractor | Word(.docx) 解析 | — |
| RAG 管道 | ✅ 分块/Embedding/混合检索/Rerank | — | 检索时按 position_id 过滤 |
| 向量库 | ✅ InMemory + Qdrant 双实现 | — | payload 增加 position_id |
| 多Agent | ✅ LangGraph4j 图引擎、Checkpoint、HITL | 招聘4阶段图 | MultiAgentGraphFactory 参数化 |
| 任务审核 | ✅ MultiAgentController 任务流 | 审核负载携带候选人/题目数据 | 审核通过后驱动下一阶段 |
| Web搜索 | ✅ WebSearchTools | — | — |
| 会话聊天 | ✅ RagChatService + SSE | — | 系统提示词切招聘域 |
| — | — | 3个业务 Agent 工具 | — |
| — | — | 5张业务表 + Mapper | — |
| — | — | 招聘看板等4个前端页面 | — |

---

## 四、数据模型

### 4.1 新增表（5张）

```sql
-- 1. 岗位表：一个HR可发布多个岗位
CREATE TABLE job_position (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id         BIGINT NOT NULL COMMENT '发布HR',
  title           VARCHAR(200) NOT NULL COMMENT '岗位名称',
  department      VARCHAR(100) COMMENT '部门',
  city            VARCHAR(50) COMMENT '工作城市',
  salary_min      INT COMMENT '薪资下限(k)',
  salary_max      INT COMMENT '薪资上限(k)',
  required_skills TEXT COMMENT '必须技能JSON数组',
  preferred_skills TEXT COMMENT '加分技能JSON数组',
  min_years       INT DEFAULT 0 COMMENT '最低工作年限',
  education       VARCHAR(20) COMMENT '学历要求',
  description     TEXT COMMENT 'JD全文',
  doc_id          BIGINT COMMENT '关联上传的JD文档ID',
  status          VARCHAR(20) DEFAULT 'OPEN' COMMENT 'DRAFT/OPEN/MATCHING/CLOSED',
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  INDEX idx_user (user_id),
  INDEX idx_status (status)
) COMMENT '招聘岗位';

-- 2. 简历表：候选人与岗位关联
CREATE TABLE candidate_resume (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  position_id     BIGINT NOT NULL COMMENT '应聘岗位',
  user_id         BIGINT NOT NULL COMMENT '上传HR（冗余，便于权限过滤）',
  candidate_name  VARCHAR(100) COMMENT '候选人姓名',
  phone           VARCHAR(30) COMMENT '联系方式（脱敏存储）',
  email           VARCHAR(100),
  skills          TEXT COMMENT '技能标签JSON数组',
  years_exp       INT COMMENT '工作年限',
  education       VARCHAR(50) COMMENT '最高学历',
  last_company    VARCHAR(200) COMMENT '最近公司',
  raw_text        MEDIUMTEXT COMMENT '简历全文（供LLM分析）',
  doc_id          BIGINT COMMENT '关联document表（复用解析/分块/向量化）',
  parse_status    VARCHAR(20) DEFAULT 'PENDING' COMMENT 'PENDING/PARSED/FAILED',
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_position (position_id),
  INDEX idx_user (user_id)
) COMMENT '候选人简历';

-- 3. 匹配结果表：一次匹配任务的产物
CREATE TABLE match_result (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  position_id     BIGINT NOT NULL,
  resume_id       BIGINT NOT NULL,
  match_score     DECIMAL(5,2) COMMENT '综合匹配分0-100',
  skill_hits      TEXT COMMENT '命中技能JSON',
  skill_gaps      TEXT COMMENT '缺失技能JSON',
  score_detail    TEXT COMMENT '评分明细JSON（经验/技能/学历分项）',
  hr_decision     VARCHAR(20) COMMENT 'PENDING/APPROVED/REJECTED',
  rank_no         INT COMMENT '排名',
  task_id         BIGINT COMMENT '产生该结果的Agent任务ID',
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_position (position_id),
  INDEX idx_resume (resume_id)
) COMMENT '人岗匹配结果';

-- 4. 面试题表
CREATE TABLE interview_question (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  resume_id       BIGINT NOT NULL,
  position_id     BIGINT NOT NULL,
  category        VARCHAR(50) COMMENT '技术/项目/行为',
  question        TEXT COMMENT '题目',
  assess_point    VARCHAR(500) COMMENT '考察点',
  reference_answer TEXT COMMENT '参考答案要点',
  difficulty      VARCHAR(10) COMMENT 'EASY/MEDIUM/HARD',
  hr_decision     VARCHAR(20) DEFAULT 'PENDING',
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_resume (resume_id)
) COMMENT '定制面试题';

-- 5. 候选人评估报告表
CREATE TABLE candidate_report (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  resume_id       BIGINT NOT NULL,
  position_id     BIGINT NOT NULL,
  summary         TEXT COMMENT '一句话总评',
  strength        TEXT COMMENT '优势',
  risk            TEXT COMMENT '风险提示',
  salary_suggest  VARCHAR(100) COMMENT '建议薪资区间',
  market_ref      TEXT COMMENT '市场行情引用（来源URL）',
  final_recommend VARCHAR(20) COMMENT 'STRONG_YES/YES/NO',
  report_md       MEDIUMTEXT COMMENT '完整报告Markdown',
  hr_decision     VARCHAR(20) DEFAULT 'PENDING',
  created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
  INDEX idx_resume (resume_id)
) COMMENT '候选人评估报告';
```

### 4.2 与现有表的关系

```
user(1) ──< job_position(1) ──< candidate_resume(1) ──< match_result
                                    │                    interview_question
                                    │                    candidate_report
                                    └──> document(复用) ──> document_chunk(复用)
                                                              │
multi_agent_task(复用) ─── task_id 关联三张结果表，审核流复用
```

**关键设计**：简历复用现有 `document` + `document_chunk` 表做解析与向量化，仅在 `candidate_resume.doc_id` 挂引用；向量库 payload 增加 `position_id`，检索时实现"按岗位过滤的相似度搜索"。

---

## 五、Agent 工具设计（Function Calling）

新增 3 个 `@Tool`，与现有 `WebSearchTools` 同机制注册：

### 5.1 ResumeParserTool

```java
@Tool("解析候选人简历原文，抽取结构化信息：姓名、技能、年限、学历、公司经历")
public ResumeProfile parse(@P("简历全文文本") String resumeText) {
    // 用 LLM structured output 抽取 JSON，校验字段后落库
}
```

### 5.2 JdMatcherTool

```java
@Tool("计算候选人与岗位的匹配度。输入岗位技能要求和候选人技能，返回0-100分及分项明细")
public MatchDetail score(
    @P("岗位必须技能JSON") String requiredSkills,
    @P("岗位加分技能JSON") String preferredSkills,
    @P("候选人技能JSON") String candidateSkills,
    @P("候选人工作年限") int yearsExp) {
    // 规则打底（技能命中数/年限硬门槛）+ LLM 语义加权（相似技能如 K8s≈Docker）
}
```

### 5.3 SalaryBenchmarkTool

```java
@Tool("查询某城市某岗位的市场薪资基准。优先查本地知识库，无结果时联网搜索")
public SalaryRange benchmark(
    @P("城市") String city,
    @P("岗位名称") String title,
    @P("技能关键词") String skills) {
    // 先 RAG 检索内部薪酬报告库 → 命中则返回；未命中 → 调 WebSearchTools 搜实时行情
}
```

> 设计要点：**JdMatcherTool 用"规则+LLM"混合评分**而非纯LLM打分——规则保证可解释可复现（面试高频追问点），LLM负责语义等价识别（如"五年Java"≈"Java 5年经验"）。

---

## 六、多Agent工作流设计

### 6.1 招聘专用 LangGraph 图（扩展 MultiAgentGraphFactory）

```
START
  → parse_resumes        （批量解析简历，更新 parse_status）
  → screen_agent         （Stage1 筛选：RAG召回 + JdMatcherTool 评分排序）
  → [HITL中断①] review_candidates   HR 审核 Top N 名单
       ├─ approve → benchmark_agent
       └─ reject  → 重新筛（携带HR反馈，最多2轮）→ 仍拒 → END
  → benchmark_agent      （Stage2 行情：SalaryBenchmarkTool，薪资区间建议）
  → [HITL中断②] review_salary       HR 确认预算
  → question_agent       （Stage3 出题：按 skill_gaps 定制面试题）
  → [HITL中断③] review_questions    面试官审题
  → report_agent         （Stage4 汇总生成评估报告）
  → END
```

### 6.2 状态定义（RecruitmentState）

```java
public class RecruitmentState {
    Long positionId;                    // 岗位
    List<Long> resumeIds;               // 输入简历
    List<CandidateRank> ranked;         // Stage1 产物
    List<Long> approvedResumeIds;       // HR①审核后
    SalarySuggestion salary;            // Stage2 产物
    Map<Long, List<Question>> questions;// Stage3 产物
    Map<Long, Report> reports;          // Stage4 产物
    String hrFeedback;                  // HR 拒绝时回填的意见
}
```

### 6.3 与现有多Agent模块的复用关系

| 现有能力 | 复用方式 |
|---|---|
| `MultiAgentGraphFactory` 图构建 | 新建 `RecruitmentGraphFactory`，节点更多但机制相同（node_async + conditionalEdges + interruptBefore） |
| `MysqlCheckpointSaver` | 直接复用，HITL中断状态持久化在 MySQL，重启不丢 |
| `multi_agent_task` 表 + approve/reject 接口 | 每次匹配创建一条主任务，3次HITL中断产生3条子审核记录 |
| 审核前端任务列表 | 复用现有"待审核任务"页，负载扩展候选人/题目渲染 |

---

## 七、API 设计

```
── 岗位管理 ──────────────────────────────
POST   /api/v1/recruitment/positions                创建岗位（表单）
POST   /api/v1/recruitment/positions/import         上传JD文档创建岗位
GET    /api/v1/recruitment/positions                我的岗位列表
GET    /api/v1/recruitment/positions/{id}           岗位详情
PUT    /api/v1/recruitment/positions/{id}           更新
DELETE /api/v1/recruitment/positions/{id}           删除（关闭）

── 简历管理 ──────────────────────────────
POST   /api/v1/recruitment/positions/{id}/resumes   批量上传简历（multipart，≤50份）
GET    /api/v1/recruitment/positions/{id}/resumes   岗位下简历列表（含解析状态）
DELETE /api/v1/recruitment/resumes/{id}             删除简历（连带向量）

── 匹配工作流 ────────────────────────────
POST   /api/v1/recruitment/positions/{id}/match     触发智能匹配（异步，返回taskId）
GET    /api/v1/recruitment/matches/{taskId}         匹配进度+候选人排名
GET    /api/v1/recruitment/tasks/{taskId}           复用现有任务详情（SSE进度）

── 审核流（复用现有，扩展负载）──────────────
POST   /api/v1/recruitment/tasks/{taskId}/approve   通过当前阶段
POST   /api/v1/recruitment/tasks/{taskId}/reject    驳回（携带feedback重跑）

── 面试与报告 ────────────────────────────
GET    /api/v1/recruitment/resumes/{id}/questions   某候选人面试题
GET    /api/v1/recruitment/resumes/{id}/report      某候选人评估报告
GET    /api/v1/recruitment/positions/{id}/export    导出岗位推荐名单(Excel/MD)

── 对话查询（复用现有chat，切招聘系统提示）──
POST   /api/v1/chat                                  sessionId绑定position上下文
```

---

## 八、前端页面规划

| 页面 | 路由 | 核心组件 |
|---|---|---|
| 岗位管理 | `/recruitment/positions` | 岗位卡片、创建表单（技能标签选择器）、JD上传 |
| 简历库 | `/recruitment/positions/:id/resumes` | 批量上传（进度条）、解析状态徽标、技能标签墙 |
| 匹配看板 | `/recruitment/positions/:id/board` | 候选人卡片墙（按匹配分排序）、四状态泳道（待审核/已批准/已出题/已完成）、雷达图（技能匹配可视化） |
| 审核中心 | `/recruitment/reviews` | 复用现有任务列表，扩展候选人对比视图、题目编辑 |
| 评估报告 | `/recruitment/resumes/:id/report` | Markdown报告渲染（复用现有react-markdown）、薪资对比条形图、导出按钮 |

交互亮点（面试演示用）：
- 匹配进度用 **SSE 实时推送**（复用现有 streamChat 机制），候选人卡片逐个"飞入"看板
- 技能匹配用 **雷达图/条形图**可视化 hit/gap
- 审核驳回时弹出 **反馈输入框**，反馈注入 Agent 下一轮（体现 Human-in-the-loop 闭环）

---

## 九、实施计划（4个里程碑）

### M1：数据与管道打通（基础）
- 建 5 张表 + Entity + Mapper
- 简历上传复用文档管道（解析→分块→向量化），payload 加 position_id
- JD 表单 CRUD + JD文档上传
- 验收：能上传简历并在岗位下看到解析结果（技能/年限/学历）

### M2：单Agent匹配 MVP（核心价值）
- JdMatcherTool（规则+LLM混合评分）
- 一键匹配：RAG召回 → 评分 → 排序落库
- 匹配看板页（列表版，无泳道）
- 验收：上传10份简历+1个JD，30秒内出排序+评分明细

### M3：多Agent全流程（差异化亮点）
- RecruitmentGraphFactory 四阶段图 + 3次HITL中断
- SalaryBenchmarkTool（RAG+Web搜索混合）
- 面试题生成、评估报告生成
- 审核中心扩展、SSE 进度推送
- 验收：完整走通"上传→匹配→审核→出题→审核→报告"，中途杀进程重启可恢复（Checkpoint）

### M4：打磨与量化（面试弹药）
- 看板泳道 + 雷达图 + 导出
- 简历脱敏（姓名/手机号打码存储）
- 构造评测集：20个JD×50份简历标注金标准，输出匹配准确率（Top5命中率/Precision@K）
- 压测：单岗100份简历匹配耗时
- 验收：README 补充业务指标数据

---

## 十、风险与对策

| 风险 | 影响 | 对策 |
|---|---|---|
| 简历格式千奇百怪，解析失败率高 | M1 阻塞 | PDF/Word 双通道；解析失败的标 FAILED 允许人工补录；LLM容错抽取（缺失字段置空不报错） |
| LLM 评分不稳定（同简历两次打分差异大） | 结果不可信 | 规则分占70%权重打底；LLM仅做语义等价映射；temperature=0；分项明细透明可解释 |
| 批量Embedding/LLM调用慢且贵 | 体验差、成本高 | 批量接口（现有 siliconflow batch）；简历解析异步队列；匹配阶段只对召回的Top50精排 |
| HITL中断状态与前端不同步 | 审核混乱 | 复用现有 checkpoint + 任务表双写；前端轮询/SSE监听任务状态 |
| 简历涉及个人信息合规 | 法律风险 | 明确定位"内部招聘辅助工具"；姓名手机号脱敏；报告不含敏感属性（性别/婚育/年龄歧视字段在提示词中显式禁止） |

---

## 十一、面试讲述要点（为什么这个项目好讲）

1. **有业务量化**："20份简历筛选从2小时 → 3分钟，Top5命中率82%"（M4产出）
2. **技术选型有理由**：为什么混合检索（简历技能词稀疏+经历描述稠密）；为什么规则+LLM打分（可解释+语义泛化）；为什么4阶段HITL（招聘决策必须留人）
3. **难点真实**：简历解析容错、LLM评分稳定性、Checkpoint断点恢复、批量性能
4. **闭环完整**：不是Demo——上传→匹配→审核→出题→报告→复盘沉淀知识库，全链路
5. **对齐岗位JD**：RAG/LangGraph式工作流/Tool Calling/多Agent/可观测/向量库 全覆盖 2026 高频技术栈

---

## 附录A：术语表

| 术语 | 含义 |
|---|---|
| JD | Job Description 岗位描述 |
| HITL | Human-In-The-Loop 人机协同审核 |
| RAG | 检索增强生成 |
| Rerank | 检索结果重排序 |
| Checkpoint | 工作流断点持久化 |
| Top-K 命中率 | 推荐前K个中包含合格候选人的比例 |
