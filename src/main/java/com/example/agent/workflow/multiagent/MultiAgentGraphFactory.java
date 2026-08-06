package com.example.agent.workflow.multiagent;

import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.EdgeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * 多 Agent 协作工作流图工厂（M9）。
 *
 * <p>图结构（三 Agent + HITL 审核）：
 * <pre>
 * START → retrieve_agent → generate_agent → review_node ─interruptBefore─→ [HITL 暂停]
 *                                                          │
 *                                              人工审批后 resume
 *                                                          ↓
 *                                                    [approved]→ END
 *                                                    [rejected]→ generate_agent (重新生成)
 * </pre>
 *
 * <p>关键点：
 * <ul>
 *   <li>review_node 是空动作节点，仅作为 interruptBefore 的锚点</li>
 *   <li>图编译时配置 {@code interruptBefore(REVIEW_NODE)} + checkpointSaver，执行到 review 前自动暂停</li>
 *   <li>人工审批后通过 {@code updateState + stream(resume)} 恢复执行，路由根据 approved 决定走向</li>
 *   <li>rejectCount 超过 maxRejects 时强制通过，避免无限循环</li>
 * </ul>
 */
@Component
public class MultiAgentGraphFactory {

    /** 节点名常量 */
    public static final String RETRIEVE_AGENT = "retrieve_agent";
    public static final String GENERATE_AGENT = "generate_agent";
    public static final String REVIEW_NODE = "review_node";

    /** 条件路由目标 */
    private static final Map<String, String> ROUTE_AFTER_REVIEW = Map.of(
            "end", END,
            "regenerate", GENERATE_AGENT);

    /**
     * 构建并编译多 Agent 工作流图。
     *
     * @param retrieveAction  检索 Agent 节点动作（找资料）
     * @param generateAction  生成 Agent 节点动作（写草稿，rejected 时带反馈重写）
     * @param reviewAction    审核节点动作（空动作，仅作 interrupt 锚点）
     * @param routeAfterReview 审核后路由：返回 "end"（通过）或 "regenerate"（拒绝重生成）
     * @param checkpointSaver  checkpoint 保存器（HITL 必需，用于中断/恢复）
     * @return 编译后的可执行状态图
     */
    public CompiledGraph<MultiAgentState> build(
            NodeAction<MultiAgentState> retrieveAction,
            NodeAction<MultiAgentState> generateAction,
            NodeAction<MultiAgentState> reviewAction,
            EdgeAction<MultiAgentState> routeAfterReview,
            BaseCheckpointSaver checkpointSaver) {

        try {
            StateGraph<MultiAgentState> graph = new StateGraph<>(
                    MultiAgentState.SCHEMA, MultiAgentState::new)
                    .addNode(RETRIEVE_AGENT, node_async(retrieveAction))
                    .addNode(GENERATE_AGENT, node_async(generateAction))
                    .addNode(REVIEW_NODE, node_async(reviewAction))
                    .addEdge(START, RETRIEVE_AGENT)
                    .addEdge(RETRIEVE_AGENT, GENERATE_AGENT)
                    .addEdge(GENERATE_AGENT, REVIEW_NODE)
                    .addConditionalEdges(REVIEW_NODE, edge_async(routeAfterReview), ROUTE_AFTER_REVIEW);

            CompileConfig config = CompileConfig.builder()
                    .checkpointSaver(checkpointSaver)
                    .interruptBefore(REVIEW_NODE)
                    .build();

            return graph.compile(config);
        } catch (Exception e) {
            throw new IllegalStateException("构建多 Agent 工作流图失败", e);
        }
    }
}
