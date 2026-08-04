package com.example.agent.service.impl;

import com.example.agent.config.RagProperties;
import com.example.agent.domain.chat.RagChatResponse;
import com.example.agent.domain.chat.RagSource;
import com.example.agent.rag.VectorMatch;
import com.example.agent.rag.VectorStore;
import com.example.agent.service.RagChatService;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;

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

    @Override
    public RagChatResponse chat(String message) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("message 不能为空");
        }

        List<VectorMatch> matches = retrieve(message);
        if (matches.isEmpty()) {
            return new RagChatResponse("知识库中暂时没有检索到相关内容，请先上传相关文档。", List.of());
        }

        String context = buildContext(matches);
        ChatRequest request = ChatRequest.builder()
                .messages(
                        SystemMessage.from(SYSTEM_PROMPT + "\n\n" + context),
                        UserMessage.from(message))
                .build();
        String answer = openAiChatModel.chat(request).aiMessage().text();

        List<RagSource> sources = matches.stream()
                .map(match -> new RagSource(match.text(), match.score()))
                .toList();
        return new RagChatResponse(answer, sources);
    }

    @Override
    public SseEmitter streamChat(String message) {
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("message 不能为空");
        }

        SseEmitter emitter = new SseEmitter(120_000L);
        List<VectorMatch> matches = retrieve(message);

        // 无命中时推送提示并结束，不调用 LLM
        if (matches.isEmpty()) {
            try {
                emitter.send(SseEmitter.event().data("知识库中暂时没有检索到相关内容，请先上传相关文档。"));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        String context = buildContext(matches);
        List<ChatMessage> messages = List.of(
                SystemMessage.from(SYSTEM_PROMPT + "\n\n" + context),
                UserMessage.from(message));

        // DeepSeek 流式接口：generate 内部异步回调，不阻塞当前线程
        openAiStreamingChatModel.generate(messages, new StreamingResponseHandler<AiMessage>() {
            @Override
            public void onNext(String partialResponse) {
                try {
                    emitter.send(SseEmitter.event().data(partialResponse));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            }

            @Override
            public void onComplete(Response<AiMessage> completeResponse) {
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

    private List<VectorMatch> retrieve(String message) {
        Embedding queryEmbedding = embeddingModel.embed(message).content();
        return vectorStore.search(
                queryEmbedding.vector(),
                ragProperties.retrieval().topK(),
                ragProperties.retrieval().minScore());
    }

    private String buildContext(List<VectorMatch> matches) {
        StringBuilder builder = new StringBuilder("知识库内容：\n");
        for (int i = 0; i < matches.size(); i++) {
            builder.append('[').append(i + 1).append("] ").append(matches.get(i).text()).append('\n');
        }
        return builder.toString();
    }
}
