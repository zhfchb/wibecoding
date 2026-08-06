package com.example.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.agent.config.RagProperties;
import com.example.agent.domain.chat.RagChatResponse;
import com.example.agent.domain.chat.RagSource;
import com.example.agent.domain.chat.SessionMessage;
import com.example.agent.entity.ChatHistoryEntity;
import com.example.agent.mapper.ChatHistoryMapper;
import com.example.agent.rag.RerankModel;
import com.example.agent.rag.SparseEmbedding;
import com.example.agent.rag.SparseEmbeddingModel;
import com.example.agent.rag.VectorMatch;
import com.example.agent.rag.VectorStore;
import com.example.agent.service.RagChatService;
import com.example.agent.service.SessionStore;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RagChatServiceImpl implements RagChatService {

    private static final String SYSTEM_PROMPT = """
            你是一个智能知识库助手。请优先根据知识库内容回答问题。
            如果知识库内容不足以确认答案，请明确说明无法从现有知识中得到结论，不要编造。
            """;

    private final OpenAiChatModel openAiChatModel;
    private final OpenAiStreamingChatModel openAiStreamingChatModel;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final SessionStore sessionStore;
    private final ChatHistoryMapper chatHistoryMapper;
    private final SparseEmbeddingModel sparseEmbeddingModel;
    private final RerankModel rerankModel;

    @Override
    public RagChatResponse chat(String message, String sessionId) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("message 不能为空");
        }

        String session = resolveSession(sessionId);
        List<VectorMatch> matches = retrieve(message);
        if (matches.isEmpty()) {
            // 无命中也记录用户消息，便于审计
            saveHistory(session, SessionMessage.user(message), null);
            return new RagChatResponse(session,
                    "知识库中暂时没有检索到相关内容，请先上传相关文档。", List.of());
        }

        List<ChatMessage> messages = buildMessages(session, message, matches);
        ChatRequest request = ChatRequest.builder().messages(messages).build();
        String answer = openAiChatModel.chat(request).aiMessage().text();

        // 保存多轮会话上下文 + 持久化对话历史
        saveHistory(session, SessionMessage.user(message), answer);

        List<RagSource> sources = matches.stream()
                .map(match -> new RagSource(match.text(), match.fileName(), match.chunkIndex(), match.score()))
                .toList();
        return new RagChatResponse(session, answer, sources);
    }

    @Override
    public SseEmitter streamChat(String message, String sessionId) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("message 不能为空");
        }

        String session = resolveSession(sessionId);
        SseEmitter emitter = new SseEmitter(120_000L);
        List<VectorMatch> matches = retrieve(message);

        if (matches.isEmpty()) {
            saveHistory(session, SessionMessage.user(message), null);
            try {
                emitter.send(SseEmitter.event().data("知识库中暂时没有检索到相关内容，请先上传相关文档。"));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        List<ChatMessage> messages = buildMessages(session, message, matches);

        // 流式输出时累积完整回答，onCompleteResponse 时保存历史
        StringBuilder answerBuilder = new StringBuilder();
        openAiStreamingChatModel.chat(messages, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                try {
                    answerBuilder.append(partialResponse);
                    emitter.send(SseEmitter.event().data(partialResponse));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                saveHistory(session, SessionMessage.user(message), answerBuilder.toString());
                emitter.complete();
            }

            @Override
            public void onError(Throwable error) {
                log.error("流式对话出错", error);
                emitter.completeWithError(error);
            }
        });
        return emitter;
    }

    @Override
    public List<ChatHistoryEntity> getHistory(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return List.of();
        }
        return chatHistoryMapper.selectList(
                new LambdaQueryWrapper<ChatHistoryEntity>()
                        .eq(ChatHistoryEntity::getSessionId, sessionId)
                        .orderByAsc(ChatHistoryEntity::getId));
    }

    private String resolveSession(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId : UUID.randomUUID().toString();
    }

    private List<ChatMessage> buildMessages(String sessionId, String message, List<VectorMatch> matches) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(SYSTEM_PROMPT + "\n\n" + buildContext(matches)));

        // 加载多轮历史，拼到 system 之后、当前 user 之前
        List<SessionMessage> history = sessionStore.load(sessionId);
        for (SessionMessage msg : history) {
            if (msg.isUser()) {
                messages.add(UserMessage.from(msg.content()));
            } else if (msg.isAssistant()) {
                messages.add(AiMessage.from(msg.content()));
            }
        }

        messages.add(UserMessage.from(message));
        return messages;
    }

    private List<VectorMatch> retrieve(String message) {
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

        // 阶段一：召回（混合检索 or 纯稠密）
        List<VectorMatch> candidates = hybridEnabled
                ? vectorStore.search(dense, sparseEmbeddingModel.embed(message), recallTopK, minScore, rrfK)
                : vectorStore.search(dense, recallTopK, minScore);

        // 阶段二：精排（rerank or 截断）
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
            builder.append('[').append(i + 1).append("] ").append(matches.get(i).text()).append('\n');
        }
        return builder.toString();
    }

    /**
     * 保存会话上下文（Redis/内存）+ 持久化对话历史（MySQL）。
     */
    private void saveHistory(String sessionId, SessionMessage userMessage, String assistantAnswer) {
        sessionStore.append(sessionId, userMessage);
        persistHistory(sessionId, "user", userMessage.content());
        if (StringUtils.hasText(assistantAnswer)) {
            sessionStore.append(sessionId, SessionMessage.assistant(assistantAnswer));
            persistHistory(sessionId, "assistant", assistantAnswer);
        }
    }

    private void persistHistory(String sessionId, String role, String content) {
        try {
            ChatHistoryEntity entity = new ChatHistoryEntity();
            entity.setSessionId(sessionId);
            entity.setRole(role);
            entity.setContent(content);
            entity.setCreatedAt(LocalDateTime.now());
            chatHistoryMapper.insert(entity);
        } catch (Exception e) {
            // 持久化失败不影响主流程，只记日志
            log.error("保存对话历史失败 sessionId={} role={}", sessionId, role, e);
        }
    }
}
