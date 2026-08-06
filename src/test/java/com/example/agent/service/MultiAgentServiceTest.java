package com.example.agent.service;

import com.example.agent.config.RagProperties;
import com.example.agent.entity.MultiAgentTaskEntity;
import com.example.agent.mapper.LangGraphCheckpointMapper;
import com.example.agent.mapper.MultiAgentTaskMapper;
import com.example.agent.rag.HashingSparseModel;
import com.example.agent.rag.NoopRerankModel;
import com.example.agent.rag.VectorMatch;
import com.example.agent.rag.VectorStore;
import com.example.agent.service.impl.MultiAgentServiceImpl;
import com.example.agent.workflow.multiagent.MultiAgentTask;
import com.example.agent.workflow.multiagent.MultiAgentGraphFactory;
import com.example.agent.workflow.multiagent.TaskStatus;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多 Agent 协作 + HITL 测试（M9）。
 *
 * <p>覆盖 5 条流转路径：
 * <ol>
 *   <li>提交 → AWAITING_REVIEW（图执行到审核节点前 interrupt，草稿已生成）</li>
 *   <li>approve → COMPLETED（1 次 LLM 调用，即首次生成）</li>
 *   <li>reject 1 次后 approve → COMPLETED（2 次 LLM 调用：首生 + 重写）</li>
 *   <li>reject 达 maxRejects → 强制 COMPLETED（2 次 LLM 调用：首生 + 重写后强制通过）</li>
 *   <li>空问题 → 抛 IllegalArgumentException</li>
 * </ol>
 */
class MultiAgentServiceTest {

    private static final VectorMatch HIT = new VectorMatch("id", "doc", "相关知识", "file.md", 0, 0.90);

    @Test
    void submit_stopsAtReviewWithDraft() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        when(vectorStore.search(any(), anyInt(), anyDouble(), any())).thenReturn(List.of(HIT));
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("草稿答案")).build());

        MultiAgentServiceImpl service = newService(chatModel, embeddingModel, vectorStore, 2);
        MultiAgentTask task = service.submit("问题");

        assertThat(task.status()).isEqualTo(TaskStatus.AWAITING_REVIEW);
        assertThat(task.draft()).isEqualTo("草稿答案");
        assertThat(task.sources()).hasSize(1);
        assertThat(task.rejectCount()).isZero();
        // 仅生成 Agent 调了 1 次 LLM
        verify(chatModel, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void approve_completesTask() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        when(vectorStore.search(any(), anyInt(), anyDouble(), any())).thenReturn(List.of(HIT));
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("草稿答案")).build());

        MultiAgentServiceImpl service = newService(chatModel, embeddingModel, vectorStore, 2);
        MultiAgentTask submitted = service.submit("问题");
        MultiAgentTask approved = service.approve(submitted.taskId());

        assertThat(approved.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(approved.finalAnswer()).isEqualTo("草稿答案");
        // approve 不再调 LLM，仍是提交时的 1 次
        verify(chatModel, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void rejectOnce_thenApprove_completesWithRegeneratedDraft() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        when(vectorStore.search(any(), anyInt(), anyDouble(), any())).thenReturn(List.of(HIT));
        // 第 1 次：首生；第 2 次：拒绝后重写
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("草稿 v1")).build())
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("改进后草稿 v2")).build());

        MultiAgentServiceImpl service = newService(chatModel, embeddingModel, vectorStore, 2);
        MultiAgentTask submitted = service.submit("问题");
        assertThat(submitted.draft()).isEqualTo("草稿 v1");

        MultiAgentTask rejected = service.reject(submitted.taskId(), "太简略");
        assertThat(rejected.status()).isEqualTo(TaskStatus.AWAITING_REVIEW);
        assertThat(rejected.rejectCount()).isEqualTo(1);
        assertThat(rejected.draft()).isEqualTo("改进后草稿 v2");

        MultiAgentTask approved = service.approve(submitted.taskId());
        assertThat(approved.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(approved.finalAnswer()).isEqualTo("改进后草稿 v2");
        // 2 次 LLM：首生 + 重写
        verify(chatModel, times(2)).chat(any(ChatRequest.class));
    }

    @Test
    void rejectReachesMaxRejects_forceCompletes() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        when(vectorStore.search(any(), anyInt(), anyDouble(), any())).thenReturn(List.of(HIT));
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("草稿 v1")).build());

        // maxRejects=1：拒绝 1 次即达到上限，强制通过（不重写），最终答案为首生草稿
        MultiAgentServiceImpl service = newService(chatModel, embeddingModel, vectorStore, 1);
        MultiAgentTask submitted = service.submit("问题");
        MultiAgentTask rejected = service.reject(submitted.taskId(), "不行");

        assertThat(rejected.status()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(rejected.rejectCount()).isEqualTo(1);
        assertThat(rejected.finalAnswer()).isEqualTo("草稿 v1");
        // 仅提交时 1 次 LLM 调用；达到 maxRejects 强制通过，不重写
        verify(chatModel, times(1)).chat(any(ChatRequest.class));
    }

    @Test
    void submit_emptyQuestion_throws() {
        MultiAgentServiceImpl service = newService(
                mock(OpenAiChatModel.class), mock(EmbeddingModel.class), mock(VectorStore.class), 2);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.submit(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void listPendingTasks_returnsAwaitingReviewTasks() {
        OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        VectorStore vectorStore = mock(VectorStore.class);

        when(embeddingModel.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[768])));
        when(vectorStore.search(any(), anyInt(), anyDouble(), any())).thenReturn(List.of(HIT));
        when(chatModel.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("草稿")).build());

        MultiAgentServiceImpl service = newService(chatModel, embeddingModel, vectorStore, 2);
        service.submit("问题 A");
        service.submit("问题 B");

        List<MultiAgentTask> pending = service.listPendingTasks();
        assertThat(pending).hasSize(2);
        assertThat(pending).allMatch(t -> t.status() == TaskStatus.AWAITING_REVIEW);
        // 审批通过一条后，pending 应只剩 1 条
        service.approve(pending.get(0).taskId());
        List<MultiAgentTask> pendingAfterApprove = service.listPendingTasks();
        assertThat(pendingAfterApprove).hasSize(1);
    }

    private MultiAgentServiceImpl newService(OpenAiChatModel chatModel,
                                              EmbeddingModel embeddingModel,
                                              VectorStore vectorStore,
                                              int maxRejects) {
        MultiAgentTaskMapper taskMapper = mock(MultiAgentTaskMapper.class);
        // 用 Answer 模拟内存任务表：insert 时存入，selectOne 时按 taskId 取回
        java.util.Map<String, MultiAgentTaskEntity> taskTable = new java.util.LinkedHashMap<>();
        when(taskMapper.insert(any(MultiAgentTaskEntity.class))).thenAnswer(inv -> {
            MultiAgentTaskEntity e = inv.getArgument(0);
            taskTable.put(e.getTaskId(), e);
            return 1;
        });
        when(taskMapper.selectOne(any())).thenAnswer(inv -> {
            // 返回最后插入的一条（submit 后立即 selectOne 取刚 insert 的任务）
            MultiAgentTaskEntity last = null;
            for (MultiAgentTaskEntity e : taskTable.values()) last = e;
            return last;
        });
        when(taskMapper.selectList(any())).thenAnswer(inv ->
                new java.util.ArrayList<>(taskTable.values()));
        when(taskMapper.updateById(any(MultiAgentTaskEntity.class))).thenAnswer(inv -> {
            MultiAgentTaskEntity e = inv.getArgument(0);
            MultiAgentTaskEntity old = taskTable.get(e.getTaskId());
            if (old != null) {
                e.setCreatedAt(old.getCreatedAt());
                taskTable.put(e.getTaskId(), e);
            }
            return 1;
        });

        // 测试用 MemorySaver（HITL interrupt/resume 需要真实 checkpoint 存储）
        org.bsc.langgraph4j.checkpoint.MemorySaver memorySaver = new org.bsc.langgraph4j.checkpoint.MemorySaver();

        return new MultiAgentServiceImpl(
                chatModel,
                embeddingModel,
                vectorStore,
                properties(maxRejects),
                new HashingSparseModel(),
                new NoopRerankModel(),
                new MultiAgentGraphFactory(),
                memorySaver,
                taskMapper);
    }

    private RagProperties properties(int maxRejects) {
        return new RagProperties(
                new RagProperties.Chunking(50, 10),
                new RagProperties.Retrieval(3, 0),
                new RagProperties.VectorStore("memory", "http://localhost:6333", "knowledge"),
                new RagProperties.Embedding("hash", 768, "text-embedding-3-small", "http://localhost", ""),
                new RagProperties.Upload(1024, List.of("txt", "md", "markdown")),
                new RagProperties.Hybrid(false, 20, 60),
                new RagProperties.Rerank(false, "noop", "", "", "", 0),
                new RagProperties.Adaptive(false, 1, 0.35),
                new RagProperties.MultiAgent(true, maxRejects));
    }
}
