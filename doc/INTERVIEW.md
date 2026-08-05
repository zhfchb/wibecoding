# 面试问答手册

> 本文档基于本项目可能被问到的面试题 + 参考答案,直接服务求职。
> 答案标注项目里对应的代码位置,面试时能结合代码讲更有说服力。
> 建议复习顺序:先看 PRINCIPLES.md 理解原理,再用本文档模拟问答。

## 一、项目整体

### Q1:介绍一下你的项目
**答**:这是一个基于 RAG 的智能知识库系统,用户上传文档后,可以用自然语言提问,系统检索相关段落交给大模型生成答案。

技术栈:Spring Boot 3 + LangChain4j + DeepSeek + Qdrant 向量库 + MySQL + Redis。

核心模块:
- **RAG 检索**:文档上传→切分→Embedding→向量检索→LLM 生成
- **Agent 工具调用**:基于 Function Calling 实现时间/计算工具
- **多轮会话**:Redis 维护上下文,支持连续对话
- **流式输出**:SSE 实现打字机效果

亮点:检索质量评估(Recall@3=80%)、多环境配置分离、全栈 Docker 部署。

### Q2:为什么用 LangChain4j 不用原生 HTTP 调用?
**答**:LangChain4j 封装了 LLM 调用、Embedding、向量检索、Function Calling 等通用能力,避免重复造轮子。比如 Function Calling 需要处理"LLM 决策→执行工具→结果回传"的多轮交互,自己写容易出错,LangChain4j 的 `AiServices` 用动态代理自动编排。

代码见 [AgentServiceImpl.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/service/impl/AgentServiceImpl.java)。

### Q3:为什么用 DeepSeek 不用 OpenAI?
**答**:三个原因:
1. DeepSeek 兼容 OpenAI 协议,LangChain4j 的 `OpenAiChatModel` 直接能用,无切换成本
2. 国产模型,国内访问稳定,延迟低
3. 价格便宜,适合个人项目开发

---

## 二、RAG 模块

### Q4:RAG 的完整流程是什么?
**答**:6 步:
1. **上传文档**:用户上传 txt/md
2. **切分**:按 chunk-size 切分,带 overlap 重叠
3. **Embedding**:每段文本转成向量
4. **存向量库**:Qdrant 存储 (向量, 原文, metadata)
5. **检索**:用户提问→Embedding→向量库找 topK 最相近的
6. **生成**:检索结果拼进 prompt→LLM 生成答案

代码见 [KnowledgeServiceImpl.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/service/impl/KnowledgeServiceImpl.java) 和 [RagChatServiceImpl.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/service/impl/RagChatServiceImpl.java)。

### Q5:为什么要切分?直接整篇文档塞给 LLM 不行吗?
**答**:三个原因:
1. **上下文长度限制**:LLM 有 token 上限(如 8k/32k),长文档塞不下
2. **省 token 成本**:只塞相关段落比塞整篇便宜
3. **检索精度**:整篇文档里可能只有一小段相关,整篇塞反而干扰 LLM

### Q6:切分时 overlap 有什么用?
**答**:避免把一个完整意思切断。比如"Spring Boot 的优点是..."被切到两个块里,两个块单独看都不完整。加 overlap(如块大小 100,重叠 20),让相邻块有交叉,保证语义连续。

项目配置 `rag.chunking.overlap=10`,代码见 [DocumentChunker.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/rag/DocumentChunker.java)。

### Q7:Embedding 是什么?为什么能做语义检索?
**答**:Embedding 是把文本映射到高维向量空间(如 768 维)的函数。语义相近的文本向量也相近(余弦相似度高),所以"狗"和"犬"虽然字面不同,向量却接近。

项目里 hash 版只匹配字面相同词,真实 BGE-M3 模型能理解语义。代码见 [LexicalHashEmbeddingModel.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/rag/LexicalHashEmbeddingModel.java)。

### Q8:向量检索用什么距离?为什么?
**答**:用余弦相似度(cosine similarity),衡量向量夹角。值域 [-1,1],越接近 1 越相似。

为什么不用欧氏距离?因为 Embedding 向量的"模长"可能受文本长度影响,而"方向"才代表语义。余弦只看方向不看模长,更适合语义检索。

### Q9:怎么评估检索质量?
**答**:用两个指标,代码见 [RagEvaluationTest.java](file:///e:/Projects/wibecoding/src/test/java/com/example/agent/rag/RagEvaluationTest.java):
- **Recall@K**:前 K 条结果里有没有正确答案(命中率)
- **MRR**(Mean Reciprocal Rank):正确答案排在第几位(排得越前分越高)

项目 hash baseline:Recall@3 = 80%、MRR = 0.8。

### Q10:如果检索不到相关内容怎么办?
**答**:不调 LLM,直接返回"知识库中暂时没有检索到相关内容"。这样:
1. 省 LLM 调用成本
2. 避免 LLM 在无依据时胡编(幻觉)
3. 给用户明确提示,引导上传文档

代码见 [RagChatServiceImpl.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/service/impl/RagChatServiceImpl.java) 的 `chat()`,`matches.isEmpty()` 分支。

### Q11:为什么用 Qdrant 不用其他向量库(Milvus/Pinecone)?
**答**:
1. **轻量**:Qdrant 单容器就能跑,Milvus 依赖 etcd/MinIO 等多个组件
2. **API 友好**:REST + gRPC,Java SDK 成熟
3. **开源**:本地部署免费,Pinecone 是云服务要付费
4. **性能足够**:个人项目数据量小,Qdrant 完全够用

---

## 三、Agent 模块

### Q12:Function Calling 的执行流程?
**答**:
1. 用户问"现在几点?"
2. LLM 看到问题 + 工具描述,决定调用 `getCurrentTime`
3. LLM 输出结构化指令(调用哪个工具、参数是什么)
4. LangChain4j 执行对应的 Java 方法,拿到 "14:30"
5. 把结果塞回 LLM,LLM 组织成"现在是下午 2 点 30 分"

**关键点**:LLM 只决策不执行,执行靠代码,保证安全。

代码见 [AgentServiceImpl.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/service/impl/AgentServiceImpl.java) 和 [TimeTools.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/tools/TimeTools.java)。

### Q13:LLM 怎么知道有哪些工具可用?
**答**:LangChain4j 把 `@Tool` 注解的方法名和描述打包发给 LLM,LLM 根据描述判断该不该调。所以 `@Tool("获取当前时间")` 的描述要写清楚,描述越准 LLM 决策越准。

### Q14:为什么用 `@SystemMessage` 注解而不是 builder 链?
**答**:LangChain4j 0.35.0 的 `AiServices.builder()` 没有 `systemMessage(String)` 方法,只能用 `@SystemMessage` 注解在接口方法上。这是版本 API 约束。

代码见 [AgentServiceImpl.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/service/impl/AgentServiceImpl.java) 的 `Assistant` 接口。

### Q15:Agent 和 RAG 有什么区别?
**答**:
- **RAG**:检索静态文档 → 拼进 prompt → LLM 回答。知识是"写死"在文档里的。
- **Agent**:LLM 决策调用工具(如查时间、算数、查数据库)→ 拿结果 → 回答。能力是"动态"的,能调用代码。

本项目两个都做了:RAG 用于知识库问答,Agent 用于工具调用。

---

## 四、多轮会话

### Q16:多轮会话怎么实现的?
**答**:
1. 每轮对话存到 Redis(key=`chat:session:{sessionId}`,带 TTL)
2. 下次提问先从 Redis 加载历史几轮
3. 拼进 prompt:`[system] [历史轮次] [当前问题]`
4. LLM 看到"上文",就能理解"它"指代什么

代码见 [RagChatServiceImpl.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/service/impl/RagChatServiceImpl.java) 的 `buildMessages()`。

### Q17:为什么用 Redis 不用 MySQL 存会话?
**答**:
1. **快**:Redis 内存库,读写毫秒级;MySQL 磁盘库,慢
2. **自动过期**:Redis 有 TTL,会话 1 小时后自动清理;MySQL 要写定时任务
3. **适合临时数据**:会话上下文是"临时"的,Redis 天然适合

但 MySQL 也存了一份 chat_history 表,用于"永久"历史记录查询。

### Q18:历史轮次太多怎么办?
**答**:配置 `chat.session.max-history=20`,只保留最近 20 条。超过的裁剪掉旧的。原因:
1. LLM 上下文长度有限
2. 太久之前的对话通常无关当前问题
3. 省 token 成本

代码见 [RedisSessionStore.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/service/impl/RedisSessionStore.java) 的裁剪逻辑。

### Q19:sessionId 怎么生成?客户端要不要传?
**答**:
- 客户端可传可不传
- 不传时服务端用 `UUID.randomUUID()` 生成,在响应里返回
- 客户端拿到后保存,下次请求带上,实现连续对话

代码见 [RagChatServiceImpl.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/service/impl/RagChatServiceImpl.java) 的 `resolveSession()`。

---

## 五、流式输出

### Q20:为什么用 SSE 不用 WebSocket?
**答**:LLM 流式输出只需"服务器推给客户端"(单向),SSE 是 HTTP 长连接,简单够用。WebSocket 是双向,适合聊天室这种场景,对 LLM 输出来说过度设计。

代码见 [ChatController.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/controller/ChatController.java) 的 `streamChat()`。

### Q21:流式输出时怎么保存对话历史?
**答**:流式过程中答案是碎片化的,不能每来一段就存一次。在 `StreamingResponseHandler.onComplete()` 回调里,用 StringBuilder 拼完整答案后一次性保存。

代码见 [RagChatServiceImpl.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/service/impl/RagChatServiceImpl.java) 的 `streamChat()` 里 `answerBuilder`。

---

## 六、Java 后端基础

### Q22:为什么用 MyBatis-Plus 不用 MyBatis?
**答**:MyBatis-Plus 在 MyBatis 基础上增强了:
1. **CRUD 自动生成**:继承 `BaseMapper` 就有增删改查,不用写 XML
2. **分页插件**:配置一下就能用,不用手写 limit
3. **逻辑删除**:加 `@TableLogic`,删除变 update,数据不丢

代码见 [DocumentMapper.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/mapper/DocumentMapper.java) 和 [MybatisPlusConfig.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/config/MybatisPlusConfig.java)。

### Q23:逻辑删除是什么?为什么用?
**答**:逻辑删除不是真删,而是把 `deleted` 字段改成 1,查询时自动过滤 `deleted=0`。好处:
1. 数据可恢复(误删能找回)
2. 审计需要(保留历史)
3. 外键完整性(关联数据不会失效)

代码见 [DocumentEntity.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/entity/DocumentEntity.java) 的 `@TableLogic`。

### Q24:统一异常处理怎么做的?
**答**:用 `@RestControllerAdvice` + `@ExceptionHandler` 全局兜底。所有异常都转成统一的 `ApiErrorResponse` JSON 格式返回,前端不用处理各种异常格式。

代码见 [GlobalExceptionHandler.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/exception/GlobalExceptionHandler.java)。

### Q25:配置文件怎么分层?
**答**:
- `application.yml`:默认骨架(提交仓库)
- `application-dev.yml`:本地开发,含真实 key(gitignore)
- `application-test.yml`:测试用 H2 + memory(提交仓库)

用 `@ConditionalOnProperty` 按 profile 切换实现,如测试用 `MemorySessionStore`,生产用 `RedisSessionStore`。

代码见 [MemorySessionStore.java](file:///e:/Projects/wibecoding/src/main/java/com/example/agent/service/impl/MemorySessionStore.java) 的 `@ConditionalOnProperty`。

### Q26:测试为什么用 H2 不用 MySQL?
**答**:
1. **快**:H2 内存库启动毫秒级,MySQL 要几秒
2. **独立**:不依赖外部服务,CI 环境也能跑
3. **干净**:每次测试都是全新数据库,无脏数据

H2 兼容 MySQL 语法(`MODE=MySQL`),schema.sql 能直接跑。配置见 [application-test.yml](file:///e:/Projects/wibecoding/src/main/resources/application-test.yml)。

---

## 七、工程化

### Q27:Docker Compose 管了哪些服务?
**答**:三个,见 [docker-compose.yml](file:///e:/Projects/wibecoding/docker-compose.yml):
- **Qdrant**(6333/6334):向量库
- **MySQL 8.0**(3306):文档元数据 + 对话历史
- **Redis 7**(6379):会话上下文

用数据卷持久化,`docker compose up -d` 一键启动。

### Q28:为什么测试要 mock?
**答**:单元测试要"隔离",不能依赖外部服务(LLM、Redis、MySQL)。Mock 让我们只测"被测对象的逻辑",不测"依赖是否正常"。

比如 `RagChatServiceTest` mock 了 `OpenAiChatModel`,不真实调 LLM,只验证"检索到内容时调了 LLM""没检索到时没调 LLM"。

代码见 [RagChatServiceTest.java](file:///e:/Projects/wibecoding/src/test/java/com/example/agent/service/RagChatServiceTest.java)。

### Q29:.gitignore 忽略了哪些文件?为什么?
**答**:
- `application-dev.yml`:含真实 API Key,不能进仓库
- `target/`:编译产物,可重新生成
- `.idea/`:IDE 配置,个人环境

见 [.gitignore](file:///e:/Projects/wibecoding/.gitignore)。

---

## 八、进阶与思考

### Q30:这个项目还有什么可以改进的?
**答**(诚实回答,体现思考):
1. **混合检索**:当前只有向量检索,可加 BM25 关键词检索 + rerank 提升精度(M7 规划)
2. **真实 Embedding**:当前 hash 版语义能力弱,生产要切 BGE-M3
3. **认证授权**:当前无鉴权,M5 规划加 Spring Security + JWT
4. **自适应 RAG**:检索质量不达标时自动重写查询重试,用 LangGraph4j 实现(M8 规划)

### Q31:LangChain4j 和 LangGraph4j 有什么区别?
**答**:
- **LangChain4j** 是"能力接入层":LLM 调用、Embedding、Tool Calling,适合线性流程
- **LangGraph4j** 是"流程编排层":有向状态图,支持循环、多 Agent、人工审批,适合复杂工作流

不是竞品,是分层关系。本项目 M2/M3 是线性流程用 LangChain4j 够了,M8 做自适应 RAG(查询重写循环)才需要 LangGraph4j。

### Q32:如果数据量大了(百万文档),怎么优化?
**答**:
1. **向量库**:Qdrant 支持 HNSW 索引,百万级也能毫秒检索
2. **Embedding**:批量向量化,用 GPU 加速
3. **切分**:预处理阶段异步切分,用消息队列削峰
4. **缓存**:热门问题答案缓存到 Redis,不用每次调 LLM
5. **分库分表**:MySQL 按业务分表,如 chat_history 按 sessionId 哈希

### Q33:RAG 的幻觉问题怎么解决?
**答**:
1. **严格 prompt**:明确告诉 LLM"只根据知识库内容回答,不知道就说不知道"
2. **无命中不调 LLM**:检索不到直接返回提示(本项目已做)
3. **引用溯源**:返回 sources,让用户验证(本项目返回 score)
4. **rerank**:二次排序过滤不相关结果(M7 规划)
5. **人工审核**:关键场景加 Human-in-the-loop(M8 规划)

---

## 九、面试加分话术

### 怎么讲项目更打动面试官?

1. **讲决策**:不只讲"用了什么",讲"为什么用"。比如"为什么用 SSE 不用 WebSocket""为什么用 Qdrant 不用 Milvus"。

2. **讲数据**:用具体数字。比如"检索评估 Recall@3 = 80%""13 个测试全过"。

3. **讲取舍**:诚实讲项目不足和改进方向。比如"当前 hash Embedding 语义能力弱,生产要切 BGE-M3"。

4. **讲思考**:讲选型时的思考过程。比如"LangChain4j 解决线性流程,LangGraph4j 解决复杂编排,本项目 M2/M3 是线性的所以用前者,M8 才需要后者"。

5. **结合代码**:能说出具体文件和方法名,证明真的写过。比如"在 `RagChatServiceImpl.chat()` 里,检索为空时直接返回不调 LLM"。
