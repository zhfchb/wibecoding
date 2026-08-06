package com.example.agent.workflow.multiagent;

import com.example.agent.rag.VectorMatch;
import org.bsc.langgraph4j.state.AgentState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;

import java.util.List;
import java.util.Map;

/**
 * 多 Agent 协作工作流的状态对象（M9）。
 *
 * <p>字段说明：
 * <ul>
 *   <li>{@link #QUESTION}：用户原始问题（不变）</li>
 *   <li>{@link #DOCUMENTS}：检索 Agent 找到的知识库切片</li>
 *   <li>{@link #DRAFT}：生成 Agent 产出的草稿答案（可能被拒后重新生成）</li>
 *   <li>{@link #REVIEW_FEEDBACK}：审核反馈，rejected 时携带拒绝原因，供下轮生成参考</li>
 *   <li>{@link #FINAL_ANSWER}：审核通过后的最终答案</li>
 *   <li>{@link #REJECT_COUNT}：已被拒绝的次数，用于限制重试轮数</li>
 *   <li>{@link #APPROVED}：人工审批结果（true=通过 / false=拒绝）</li>
 * </ul>
 *
 * <p>所有字段采用覆写语义（{@link Channels#base}）。
 */
public class MultiAgentState extends AgentState {

    public static final String QUESTION = "question";
    public static final String DOCUMENTS = "documents";
    public static final String DRAFT = "draft";
    public static final String REVIEW_FEEDBACK = "review_feedback";
    public static final String FINAL_ANSWER = "final_answer";
    public static final String REJECT_COUNT = "reject_count";
    public static final String APPROVED = "approved";

    public static final Map<String, Channel<?>> SCHEMA = Map.of(
            QUESTION, Channels.base(() -> ""),
            DOCUMENTS, Channels.base(() -> List.of()),
            DRAFT, Channels.base(() -> ""),
            REVIEW_FEEDBACK, Channels.base(() -> ""),
            FINAL_ANSWER, Channels.base(() -> ""),
            REJECT_COUNT, Channels.base(() -> 0),
            APPROVED, Channels.base(() -> false));

    public MultiAgentState(Map<String, Object> initData) {
        super(initData);
    }

    public String question() {
        return this.<String>value(QUESTION).orElse("");
    }

    @SuppressWarnings("unchecked")
    public List<VectorMatch> documents() {
        return this.<List<VectorMatch>>value(DOCUMENTS).orElse(List.of());
    }

    public String draft() {
        return this.<String>value(DRAFT).orElse("");
    }

    public String reviewFeedback() {
        return this.<String>value(REVIEW_FEEDBACK).orElse("");
    }

    public String finalAnswer() {
        return this.<String>value(FINAL_ANSWER).orElse("");
    }

    public int rejectCount() {
        return this.<Integer>value(REJECT_COUNT).orElse(0);
    }

    public boolean approved() {
        return this.<Boolean>value(APPROVED).orElse(false);
    }
}
