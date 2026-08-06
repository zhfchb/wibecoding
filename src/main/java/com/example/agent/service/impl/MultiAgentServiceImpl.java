package com.example.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agent.config.RagProperties;
import com.example.agent.domain.chat.RagSource;
import com.example.agent.entity.MultiAgentTaskEntity;
import com.example.agent.mapper.MultiAgentTaskMapper;
import com.example.agent.rag.MysqlCheckpointSaver;
import com.example.agent.rag.RerankModel;
import com.example.agent.rag.SparseEmbeddingModel;
import com.example.agent.rag.VectorMatch;
import com.example.agent.rag.VectorStore;
import com.example.agent.security.SecurityUtils;
import com.example.agent.service.MultiAgentService;
import com.example.agent.workflow.multiagent.MultiAgentGraphFactory;
import com.example.agent.workflow.multiagent.MultiAgentTask;
import com.example.agent.workflow.multiagent.MultiAgentState;
import com.example.agent.workflow.multiagent.TaskStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.example.agent.workflow.multiagent.MultiAgentGraphFactory.*;
import static com.example.agent.workflow.multiagent.MultiAgentState.*;

/**
 * 多 Agent 协作服务实现（M9）。
 *
 * <p>三 Agent + HITL 审核：
 * <ul>
 *   <li>检索 Agent：复用 M7 混合检索 + rerank</li>
 *   <li>生成 Agent：基于检索结果生成草稿；rejected 时带反馈重写</li>
 *   <li>审核节点：HITL 中断点，等人工审批后 resume</li>
 * </ul>
 *
 * <p>持久化：checkpoint 用 {@link MysqlCheckpointSaver}（MySQL），任务表用 {@code multi_agent_task}。
 * 进程重启后可从 MySQL 恢复正在审核中的任务。
 */
@Slf4j
@Service
public class MultiAgentServiceImpl implements MultiAgentService {

    private static final String GENERATE_PROMPT_TEMPLATE = """
            你是一个智能知识库助手。请根据知识库内容回答问题。
            如果知识库内容不足以确认答案，请明确说明无法从现有知识中得到结论，不要编造。

            知识库内容：
            %s

            问题：%s""";

    private static final String REGENERATE_PROMPT_TEMPLATE = """
            你是一个智能知识库助手。请根据知识库内容回答问题。

            知识库内容：
            %s

            问题：%s

            注意：上一版草稿被人工审核拒绝，拒绝原因："%s"。请根据反馈改进回答。""";

    private final OpenAiChatModel openAiChatModel;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final SparseEmbeddingModel sparseEmbeddingModel;
    private final RerankModel rerankModel;
    private final RagProperties.MultiAgent multiAgentConfig;
    private final CompiledGraph<MultiAgentState> graph;
    private final BaseCheckpointSaver checkpointSaver;
    private final MultiAgentTaskMapper taskMapper;
    private final ObjectMapper objectMapper;

    @Autowired
    public MultiAgentServiceImpl(
            OpenAiChatModel openAiChatModel,
            EmbeddingModel embeddingModel,
            VectorStore vectorStore,
            RagProperties ragProperties,
            SparseEmbeddingModel sparseEmbeddingModel,
            RerankModel rerankModel,
            MultiAgentGraphFactory graphFactory,
            com.example.agent.mapper.LangGraphCheckpointMapper checkpointMapper,
            MultiAgentTaskMapper taskMapper) {
        this(openAiChatModel, embeddingModel, vectorStore, ragProperties,
                sparseEmbeddingModel, rerankModel, graphFactory,
                new MysqlCheckpointSaver(checkpointMapper), taskMapper);
    }

    /**
     * 测试专用构造器：允许注入自定义 checkpointSaver（如 MemorySaver）。
     * 不加 @Autowired，避免 Spring 选中此构造器；仅供测试直接 new。
     */
    public MultiAgentServiceImpl(
            OpenAiChatModel openAiChatModel,
            EmbeddingModel embeddingModel,
            VectorStore vectorStore,
            RagProperties ragProperties,
            SparseEmbeddingModel sparseEmbeddingModel,
            RerankModel rerankModel,
            MultiAgentGraphFactory graphFactory,
            BaseCheckpointSaver checkpointSaver,
            MultiAgentTaskMapper taskMapper) {
        this.openAiChatModel = openAiChatModel;
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.ragProperties = ragProperties;
        this.sparseEmbeddingModel = sparseEmbeddingModel;
        this.rerankModel = rerankModel;
        this.taskMapper = taskMapper;
        this.objectMapper = new ObjectMapper();
        this.multiAgentConfig = ragProperties.multiAgent() != null
                ? ragProperties.multiAgent()
                : new RagProperties.MultiAgent(false, 2);
        this.checkpointSaver = checkpointSaver;
        this.graph = buildGraph(graphFactory);
        log.info("多 Agent 工作流图已构建: enabled={} maxRejects={} checkpointSaver={}",
                multiAgentConfig.enabled(), multiAgentConfig.maxRejects(),
                checkpointSaver.getClass().getSimpleName());
    }

    @Override
    public MultiAgentTask submit(String question) {
        if (!StringUtils.hasText(question)) {
            throw new IllegalArgumentException("question 不能为空");
        }
        String taskId = UUID.randomUUID().toString();
        String threadId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();

        // 初始任务落库
        MultiAgentTaskEntity entity = new MultiAgentTaskEntity();
        entity.setTaskId(taskId);
        entity.setThreadId(threadId);
        entity.setQuestion(question);
        entity.setStatus(TaskStatus.RUNNING.name());
        entity.setRejectCount(0);
        entity.setReviewFeedback("");
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        taskMapper.insert(entity);

        // 同步执行图：retrieve_agent → generate_agent → [interrupt before review_node]
        RunnableConfig config = RunnableConfig.builder().threadId(threadId).build();
        Map<String, Object> inputs = Map.of(QUESTION, question);
        try {
            graph.stream(inputs, config).forEach(output ->
                    log.debug("任务 {} 执行到节点 {}", taskId, output.node()));
            updateTaskFromState(taskId, config, TaskStatus.AWAITING_REVIEW);
        } catch (Exception e) {
            log.error("任务 {} 执行失败", taskId, e);
            updateTaskStatus(taskId, TaskStatus.FAILED);
        }
        return toTask(taskMapper.selectOne(
                new LambdaQueryWrapper<MultiAgentTaskEntity>().eq(MultiAgentTaskEntity::getTaskId, taskId)));
    }

    @Override
    public MultiAgentTask getTask(String taskId) {
        MultiAgentTaskEntity entity = taskMapper.selectOne(
                new LambdaQueryWrapper<MultiAgentTaskEntity>().eq(MultiAgentTaskEntity::getTaskId, taskId));
        if (entity == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        return toTask(entity);
    }

    @Override
    public List<MultiAgentTask> listPendingTasks() {
        // selectList(null) 查全表后 Java 层过滤；任务表数据量小（待审核通常几个），避免 LambdaQueryWrapper 在测试 mock 中难以解析
        List<MultiAgentTaskEntity> entities = taskMapper.selectList(null);
        if (entities == null || entities.isEmpty()) return List.of();
        return entities.stream()
                .filter(e -> TaskStatus.AWAITING_REVIEW.name().equals(e.getStatus()))
                .sorted(java.util.Comparator.comparing(MultiAgentTaskEntity::getCreatedAt))
                .map(this::toTask)
                .toList();
    }

    @Override
    public MultiAgentTask approve(String taskId) {
        MultiAgentTaskEntity entity = taskMapper.selectOne(
                new LambdaQueryWrapper<MultiAgentTaskEntity>().eq(MultiAgentTaskEntity::getTaskId, taskId));
        if (entity == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        if (!TaskStatus.AWAITING_REVIEW.name().equals(entity.getStatus())) {
            throw new IllegalStateException("任务不在待审核状态，当前状态: " + entity.getStatus());
        }
        RunnableConfig config = RunnableConfig.builder().threadId(entity.getThreadId()).build();
        try {
            RunnableConfig updatedConfig = graph.updateState(config, Map.of(APPROVED, true));
            graph.stream(GraphInput.resume(), updatedConfig).forEach(output ->
                    log.debug("任务 {} resume 执行到节点 {}", taskId, output.node()));
            updateTaskFromState(taskId, config, TaskStatus.COMPLETED);
        } catch (Exception e) {
            log.error("任务 {} 审批通过 resume 失败", taskId, e);
            updateTaskStatus(taskId, TaskStatus.FAILED);
        }
        return toTask(taskMapper.selectOne(
                new LambdaQueryWrapper<MultiAgentTaskEntity>().eq(MultiAgentTaskEntity::getTaskId, taskId)));
    }

    @Override
    public MultiAgentTask reject(String taskId, String feedback) {
        MultiAgentTaskEntity entity = taskMapper.selectOne(
                new LambdaQueryWrapper<MultiAgentTaskEntity>().eq(MultiAgentTaskEntity::getTaskId, taskId));
        if (entity == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }
        if (!TaskStatus.AWAITING_REVIEW.name().equals(entity.getStatus())) {
            throw new IllegalStateException("任务不在待审核状态，当前状态: " + entity.getStatus());
        }
        RunnableConfig config = RunnableConfig.builder().threadId(entity.getThreadId()).build();
        try {
            int newRejectCount = entity.getRejectCount() + 1;
            RunnableConfig updatedConfig = graph.updateState(config, Map.of(
                    APPROVED, false,
                    REVIEW_FEEDBACK, feedback,
                    REJECT_COUNT, newRejectCount));

            boolean forceApprove = newRejectCount >= multiAgentConfig.maxRejects();
            if (forceApprove) {
                log.info("任务 {} 达到 maxRejects={}，强制通过", taskId, multiAgentConfig.maxRejects());
                updatedConfig = graph.updateState(updatedConfig, Map.of(APPROVED, true));
            }

            graph.stream(GraphInput.resume(), updatedConfig).forEach(output ->
                    log.debug("任务 {} reject resume 执行到节点 {}", taskId, output.node()));

            if (forceApprove) {
                updateTaskFromState(taskId, config, TaskStatus.COMPLETED);
            } else {
                updateTaskFromState(taskId, config, TaskStatus.AWAITING_REVIEW);
            }
        } catch (Exception e) {
            log.error("任务 {} 拒绝 resume 失败", taskId, e);
            updateTaskStatus(taskId, TaskStatus.FAILED);
        }
        return toTask(taskMapper.selectOne(
                new LambdaQueryWrapper<MultiAgentTaskEntity>().eq(MultiAgentTaskEntity::getTaskId, taskId)));
    }

    // ==================== 图构建 ====================

    private CompiledGraph<MultiAgentState> buildGraph(MultiAgentGraphFactory factory) {
        NodeAction<MultiAgentState> retrieve = this::retrieveAgent;
        NodeAction<MultiAgentState> generate = this::generateAgent;
        NodeAction<MultiAgentState> review = state -> Map.of();
        var route = (org.bsc.langgraph4j.action.EdgeAction<MultiAgentState>) this::routeAfterReview;
        return factory.build(retrieve, generate, review, route, checkpointSaver);
    }

    // ==================== 节点动作 ====================

    private Map<String, Object> retrieveAgent(MultiAgentState state) {
        List<VectorMatch> documents = retrieve(state.question(), SecurityUtils.getCurrentUserId());
        log.debug("检索 Agent: hits={}", documents.size());
        return Map.of(DOCUMENTS, documents);
    }

    private Map<String, Object> generateAgent(MultiAgentState state) {
        String context = buildContext(state.documents());
        String prompt;
        if (StringUtils.hasText(state.reviewFeedback())) {
            prompt = String.format(REGENERATE_PROMPT_TEMPLATE, context, state.question(), state.reviewFeedback());
        } else {
            prompt = String.format(GENERATE_PROMPT_TEMPLATE, context, state.question());
        }
        String draft = chat(prompt);
        log.debug("生成 Agent: draftLength={} hasFeedback={}", draft.length(), StringUtils.hasText(state.reviewFeedback()));
        return Map.of(DRAFT, draft);
    }

    private String routeAfterReview(MultiAgentState state) {
        return state.approved() ? "end" : "regenerate";
    }

    // ==================== 检索与生成辅助 ====================

    private List<VectorMatch> retrieve(String message, Long userId) {
        Embedding queryEmbedding = embeddingModel.embed(message).content();
        float[] dense = queryEmbedding.vector();

        RagProperties.Hybrid hybrid = ragProperties.hybrid();
        RagProperties.Rerank rerank = ragProperties.rerank();
        boolean hybridEnabled = hybrid != null && hybrid.enabled();
        double minScore = ragProperties.retrieval().minScore();
        int recallTopK = hybridEnabled
                ? hybrid.recallTopK()
                : ragProperties.retrieval().topK();
        int rrfK = hybrid != null ? hybrid.rrfK() : 60;

        List<VectorMatch> candidates = hybridEnabled
                ? vectorStore.search(dense, sparseEmbeddingModel.embed(message), recallTopK, minScore, rrfK, userId)
                : vectorStore.search(dense, recallTopK, minScore, userId);

        int finalTopK = (rerank != null && rerank.topK() > 0)
                ? rerank.topK()
                : ragProperties.retrieval().topK();
        boolean rerankEnabled = rerank != null && rerank.enabled();
        if (rerankEnabled) {
            return rerankModel.rerank(message, candidates, finalTopK);
        }
        return candidates.size() <= finalTopK ? candidates : candidates.subList(0, finalTopK);
    }

    private String buildContext(List<VectorMatch> matches) {
        StringBuilder builder = new StringBuilder("知识库内容：\n");
        for (int i = 0; i < matches.size(); i++) {
            builder.append('[').append(i + 1).append("] ")
                    .append(matches.get(i).text()).append('\n');
        }
        return builder.toString();
    }

    private String chat(String prompt) {
        ChatRequest request = ChatRequest.builder()
                .messages(UserMessage.from(prompt))
                .build();
        return openAiChatModel.chat(request).aiMessage().text();
    }

    // ==================== 任务状态同步 ====================

    /**
     * 从图 checkpoint 状态同步到任务表。
     * 注意：checkpoint 反序列化后 documents 是 List<LinkedHashMap>，需转回 VectorMatch。
     */
    private void updateTaskFromState(String taskId, RunnableConfig config, TaskStatus newStatus) {
        MultiAgentTaskEntity entity = taskMapper.selectOne(
                new LambdaQueryWrapper<MultiAgentTaskEntity>().eq(MultiAgentTaskEntity::getTaskId, taskId));
        if (entity == null) return;

        MultiAgentState state = null;
        try {
            StateSnapshot<MultiAgentState> snapshot = graph.getState(config);
            state = snapshot.state();
        } catch (Exception e) {
            log.warn("任务 {} 取状态快照失败", taskId, e);
        }

        if (state != null) {
            entity.setDraft(state.draft());
            entity.setFinalAnswer(state.finalAnswer());
            entity.setRejectCount(state.rejectCount());
            entity.setReviewFeedback(state.reviewFeedback());
            // documents 从 checkpoint 反序列化后是 List<LinkedHashMap>，转回 RagSource JSON
            List<RagSource> sources = convertDocumentsToSources(state.<List<?>>value(DOCUMENTS).orElse(List.of()));
            try {
                entity.setSourcesJson(objectMapper.writeValueAsString(sources));
            } catch (Exception e) {
                log.warn("任务 {} 序列化 sources 失败", taskId, e);
            }
        }
        entity.setStatus(newStatus.name());
        entity.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(entity);
    }

    /**
     * checkpoint 反序列化后 documents 是 List<LinkedHashMap>（Jackson 默认行为），
     * 需手动转回 RagSource。泛型擦除导致运行时元素是 LinkedHashMap 但声明类型是 VectorMatch，
     * 所以用 List<?> 接收。
     */
    @SuppressWarnings("unchecked")
    private List<RagSource> convertDocumentsToSources(List<?> documents) {
        if (documents == null || documents.isEmpty()) return List.of();
        List<RagSource> sources = new ArrayList<>();
        for (Object m : documents) {
            if (m instanceof Map) {
                // 反序列化场景：m 是 LinkedHashMap
                Map<String, Object> map = (Map<String, Object>) m;
                sources.add(new RagSource(
                        (String) map.get("text"),
                        (String) map.get("fileName"),
                        map.get("chunkIndex") instanceof Number n ? n.intValue() : 0,
                        map.get("score") instanceof Number n2 ? n2.doubleValue() : 0.0));
            } else if (m instanceof VectorMatch vm) {
                // 正常运行场景：m 是 VectorMatch record
                sources.add(new RagSource(vm.text(), vm.fileName(), vm.chunkIndex(), vm.score()));
            }
        }
        return sources;
    }

    private void updateTaskStatus(String taskId, TaskStatus status) {
        MultiAgentTaskEntity entity = taskMapper.selectOne(
                new LambdaQueryWrapper<MultiAgentTaskEntity>().eq(MultiAgentTaskEntity::getTaskId, taskId));
        if (entity == null) return;
        entity.setStatus(status.name());
        entity.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(entity);
    }

    /**
     * Entity → DTO（含 sources JSON 反序列化）。
     */
    private MultiAgentTask toTask(MultiAgentTaskEntity entity) {
        if (entity == null) return null;
        List<RagSource> sources = List.of();
        if (StringUtils.hasText(entity.getSourcesJson())) {
            try {
                sources = objectMapper.readValue(entity.getSourcesJson(), new TypeReference<List<RagSource>>() {});
            } catch (Exception e) {
                log.warn("反序列化 sources 失败 taskId={}", entity.getTaskId(), e);
            }
        }
        String finalAnswer = StringUtils.hasText(entity.getFinalAnswer())
                ? entity.getFinalAnswer()
                : entity.getDraft();
        return new MultiAgentTask(
                entity.getTaskId(),
                entity.getThreadId(),
                entity.getQuestion(),
                TaskStatus.valueOf(entity.getStatus()),
                entity.getDraft(),
                finalAnswer,
                sources,
                entity.getRejectCount() != null ? entity.getRejectCount() : 0,
                entity.getReviewFeedback() != null ? entity.getReviewFeedback() : "",
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
