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
import com.example.agent.security.SecurityUtils;
import com.example.agent.service.AgentService;
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

    /** 无检索结果时的 system prompt：允许 LLM 正常闲聊，介绍自身定位和能力 */
    private static final String SYSTEM_PROMPT_NO_CONTEXT = """
            你是「智能知识库助手」，一个基于 RAG（检索增强生成）技术的智能问答系统。
            当用户问候或闲聊时，请简要介绍自己的定位和能力，例如：
            - 你可以基于用户上传的知识库文档（txt/md/markdown/pdf）进行检索增强问答
            - 你支持多轮对话，能记住之前的上下文
            - 你支持流式输出（打字机效果）
            - 你提供多 Agent 协作任务（检索 Agent → 生成 Agent → 人工审核）
            - 你支持自适应 RAG（查询重写 → 检索 → 质量评分 → 重试/生成）

            介绍要简洁自然，不要每次都列举全部功能，根据对话场景灵活回应。
            如果用户的问题涉及专业知识，建议用户先上传相关文档到知识库。
            """;

    /** 多轮对话加载的最大历史条数（与 ChatSessionProperties.maxHistory 默认值一致） */
    private static final int MAX_HISTORY = 20;

    private final OpenAiChatModel openAiChatModel;
    private final OpenAiStreamingChatModel openAiStreamingChatModel;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    private final RagProperties ragProperties;
    private final SessionStore sessionStore;
    private final ChatHistoryMapper chatHistoryMapper;
    private final SparseEmbeddingModel sparseEmbeddingModel;
    private final RerankModel rerankModel;
    private final AgentService agentService;

    @Override
    public RagChatResponse chat(String message, String sessionId) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("message 不能为空");
        }

        String session = resolveSession(sessionId);
        List<VectorMatch> matches = retrieve(message, SecurityUtils.getCurrentUserId());

        String answer;
        boolean useWebSearchFallback = shouldFallbackToWebSearch(message, matches);
        if (useWebSearchFallback && isWebSearchEnabled()) {
            log.info("RAG 检索不足（top1Score={}, matches={}），走 Agent + Web 搜索兜底: sessionId={}",
                    maxScore(matches), matches.size(), session);
            answer = agentService.chat(message);
        } else {
            // 常规路径：有检索结果用知识库上下文，无结果用 LLM 裸回答
            List<ChatMessage> messages = buildMessages(session, message, matches);
            ChatRequest request = ChatRequest.builder().messages(messages).build();
            answer = openAiChatModel.chat(request).aiMessage().text();
        }

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
        List<VectorMatch> matches = retrieve(message, SecurityUtils.getCurrentUserId());

        boolean useWebSearchFallback = shouldFallbackToWebSearch(message, matches);
        if (useWebSearchFallback && isWebSearchEnabled()) {
            log.info("RAG 检索不足（top1Score={}, matches={}），流式走 Agent + Web 搜索兜底: sessionId={}",
                    maxScore(matches), matches.size(), session);
            new Thread(() -> {
                try {
                    String answer = agentService.chat(message);
                    // 按完整 Unicode 代码点推送，避免 emoji（surrogate pair）被拆成乱码
                    int cp;
                    for (int i = 0; i < answer.length(); ) {
                        cp = answer.codePointAt(i);
                        emitter.send(SseEmitter.event().data(new String(Character.toChars(cp))));
                        i += Character.charCount(cp);
                    }
                    saveHistory(session, SessionMessage.user(message), answer);
                    emitter.complete();
                } catch (Exception e) {
                    log.error("Agent 流式兜底出错", e);
                    emitter.completeWithError(e);
                }
            }).start();
            return emitter;
        }

        // 常规流式路径
        List<ChatMessage> messages = buildMessages(session, message, matches);

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

    @Override
    public List<ChatHistoryEntity> listSessions() {
        return chatHistoryMapper.selectLatestOfEachSession();
    }

    @Override
    public void deleteSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
        // 1. 删除 MySQL 持久化历史
        chatHistoryMapper.deleteBySessionId(sessionId);
        // 2. 清除会话上下文缓存（Redis/内存）
        sessionStore.clear(sessionId);
        log.info("会话已删除: {}", sessionId);
    }

    private String resolveSession(String sessionId) {
        return StringUtils.hasText(sessionId) ? sessionId : UUID.randomUUID().toString();
    }

    private List<ChatMessage> buildMessages(String sessionId, String message, List<VectorMatch> matches) {
        List<ChatMessage> messages = new ArrayList<>();

        // 有检索结果时带知识库上下文，无结果时用纯对话 prompt（允许 LLM 正常闲聊）
        if (matches != null && !matches.isEmpty()) {
            messages.add(SystemMessage.from(SYSTEM_PROMPT + "\n\n" + buildContext(matches)));
        } else {
            messages.add(SystemMessage.from(SYSTEM_PROMPT_NO_CONTEXT));
        }

        // 从 MySQL 加载多轮历史（不依赖 Redis/sessionStore，进程重启或切换会话都能恢复）
        List<ChatHistoryEntity> all = getHistory(sessionId);
        int from = Math.max(0, all.size() - MAX_HISTORY);
        for (ChatHistoryEntity msg : all.subList(from, all.size())) {
            if ("user".equals(msg.getRole())) {
                messages.add(UserMessage.from(msg.getContent()));
            } else if ("assistant".equals(msg.getRole())) {
                messages.add(AiMessage.from(msg.getContent()));
            }
        }

        messages.add(UserMessage.from(message));
        return messages;
    }

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

        // 阶段一：召回（混合检索 or 纯稠密）
        List<VectorMatch> candidates = hybridEnabled
                ? vectorStore.search(dense, sparseEmbeddingModel.embed(message), recallTopK, minScore, rrfK, userId)
                : vectorStore.search(dense, recallTopK, minScore, userId);

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

    private boolean isWebSearchEnabled() {
        RagProperties.WebSearch ws = ragProperties.webSearch();
        return ws != null && ws.enabled() && ws.apiKey() != null && !ws.apiKey().isEmpty();
    }

    /**
     * 判断是否应该走 Web 搜索兜底。
     * 条件：(1) 检索结果为空，或 top1 分数低于阈值；且 (2) 问题看起来需要外部信息
     * （含时间/实时/搜索类关键词，或完全不像闲聊/致谢等简单问题）。
     */
    private boolean shouldFallbackToWebSearch(String message, List<VectorMatch> matches) {
        if (message == null) {
            return false;
        }
        double top1 = maxScore(matches);
        double threshold = 0.0;
        RagProperties.WebSearch ws = ragProperties.webSearch();
        if (ws != null && ws.fallbackMinScore() > 0) {
            threshold = ws.fallbackMinScore();
        }

        boolean retrievalInsufficient = matches.isEmpty() || (threshold > 0 && top1 < threshold);
        if (!retrievalInsufficient) {
            return false;
        }
        // 对简单闲聊/问候语不触发搜索，避免浪费调用量
        if (looksLikeCasualChat(message)) {
            return false;
        }
        return true;
    }

    private static final java.util.Set<String> SEARCH_KEYWORDS = java.util.Set.of(
            // 实时类
            "今天", "最新", "现在", "当前", "近期", "最近", "新闻", "消息", "报道",
            // 天气/金融/招聘/市场
            "天气", "气温", "降雨", "股票", "股价", "行情", "基金", "汇率", "金价", "油价",
            "招聘", "职位", "岗位", "薪资", "面试", "找工作", "就业", "市场", "销量", "份额",
            // 体育/赛事
            "比赛", "比分", "赛事", "世界杯", "奥运会", "NBA", "中超", "欧冠",
            // 旅游/出行
            "机票", "酒店", "票价", "高铁", "航班", "列车", "地铁", "路况",
            // 事件/活动/名人
            "发布会", "展会", "演唱会", "上映", "发售", "上市",
            // 编程/技术生态（可能有较新的文档）
            "版本", "发布", "更新", "changelog"
    );

    private static final java.util.Set<String> CASUAL_KEYWORDS = java.util.Set.of(
            "你好", "您好", "hi", "hello", "嗨", "哈喽",
            "谢谢", "感谢", "多谢", "thanks", "thank",
            "再见", "拜拜", "bye",
            "好的", "好", "嗯", "哦", "ok", "okay"
    );

    private boolean looksLikeCasualChat(String message) {
        String normalized = message.trim().toLowerCase();
        if (normalized.length() <= 8) {
            for (String k : CASUAL_KEYWORDS) {
                if (normalized.contains(k)) {
                    return true;
                }
            }
        }
        // 不含任何搜索关键词 + 很短（<=10字），视为简单对话，不触发搜索
        if (normalized.length() <= 10) {
            for (String k : SEARCH_KEYWORDS) {
                if (normalized.contains(k)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private double maxScore(List<VectorMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return 0.0;
        }
        double max = 0.0;
        for (VectorMatch m : matches) {
            if (m.score() > max) {
                max = m.score();
            }
        }
        return max;
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
