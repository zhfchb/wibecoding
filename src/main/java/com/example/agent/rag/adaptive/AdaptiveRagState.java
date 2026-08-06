package com.example.agent.rag.adaptive;

import com.example.agent.rag.VectorMatch;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.List;
import java.util.Map;

/**
 * 自适应 RAG 状态图的状态对象。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@link #QUESTION}：用户原始问题（不变）</li>
 *   <li>{@link #REWRITTEN_QUESTION}：每轮重写后的问题（首次等于原问题）</li>
 *   <li>{@link #DOCUMENTS}：当前轮检索到的切片</li>
 *   <li>{@link #RETRIEVAL_SCORE}：当前轮 top1 检索分（rerank 分或 RRF 分）</li>
 *   <li>{@link #GENERATION}：最终回答</li>
 *   <li>{@link #RETRY_COUNT}：已重试次数（grade 节点每次 +1）</li>
 *   <li>{@link #ACCEPTED}：检索质量是否通过阈值</li>
 * </ul>
 *
 * <p>所有字段均采用覆写语义（{@link Channels#base}），每轮节点返回新值直接覆盖旧值。
 */
public class AdaptiveRagState extends AgentState {

    public static final String QUESTION = "question";
    public static final String REWRITTEN_QUESTION = "rewritten_question";
    public static final String DOCUMENTS = "documents";
    public static final String RETRIEVAL_SCORE = "retrieval_score";
    public static final String GENERATION = "generation";
    public static final String RETRY_COUNT = "retry_count";
    public static final String ACCEPTED = "accepted";

    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            QUESTION, Channels.base(() -> ""),
            REWRITTEN_QUESTION, Channels.base(() -> ""),
            DOCUMENTS, Channels.base(() -> List.of()),
            RETRIEVAL_SCORE, Channels.base(() -> 0.0),
            GENERATION, Channels.base(() -> ""),
            RETRY_COUNT, Channels.base(() -> 0),
            ACCEPTED, Channels.base(() -> false));

    public AdaptiveRagState(Map<String, Object> initData) {
        super(initData);
    }

    public String question() {
        return this.<String>value(QUESTION).orElse("");
    }

    public String rewrittenQuestion() {
        return this.<String>value(REWRITTEN_QUESTION).orElse("");
    }

    @SuppressWarnings("unchecked")
    public List<VectorMatch> documents() {
        return this.<List<VectorMatch>>value(DOCUMENTS).orElse(List.of());
    }

    public double retrievalScore() {
        return this.<Double>value(RETRIEVAL_SCORE).orElse(0.0);
    }

    public String generation() {
        return this.<String>value(GENERATION).orElse("");
    }

    public int retryCount() {
        return this.<Integer>value(RETRY_COUNT).orElse(0);
    }

    public boolean accepted() {
        return this.<Boolean>value(ACCEPTED).orElse(false);
    }
}
