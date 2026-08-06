package com.example.agent.rag.adaptive;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.EdgeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

/**
 * 自适应 RAG 状态图工厂：负责把节点动作和路由逻辑组装成 {@link CompiledGraph}。
 *
 * <p>图结构：
 * <pre>
 * START → rewrite_query → retrieve → grade_documents ─┬─[accepted]→ generate ──→ END
 *      ↑                                       │       │
 *      └─[not accepted & retry<=max]──────────┘       └─[not accepted & retry>max]→ generate_fallback → END
 * </pre>
 *
 * <p>工厂只负责图结构编排，节点逻辑（查询重写、检索、评分、生成）由
 * {@link com.example.agent.service.impl.AdaptiveRagServiceImpl} 提供，便于独立测试。
 */
@Component
public class AdaptiveRagGraphFactory {

    /** 节点名常量，路由逻辑和测试用例引用 */
    public static final String REWRITE_QUERY = "rewrite_query";
    public static final String RETRIEVE = "retrieve";
    public static final String GRADE_DOCUMENTS = "grade_documents";
    public static final String GENERATE = "generate";
    public static final String GENERATE_FALLBACK = "generate_fallback";

    /** 条件路由的目标节点映射 */
    private static final Map<String, String> ROUTE_MAP = Map.of(
            GENERATE, GENERATE,
            REWRITE_QUERY, REWRITE_QUERY,
            GENERATE_FALLBACK, GENERATE_FALLBACK);

    /**
     * 构建并编译自适应 RAG 状态图。
     *
     * @param rewrite        查询重写节点（首次用原问题，重试时调 LLM 重写）
     * @param retrieve       检索节点（embedding + vectorStore 混合检索 + rerank）
     * @param grade          评分节点（评估 top1 分数是否达标，更新 retryCount）
     * @param generate       生成节点（基于检索结果调 LLM 生成回答）
     * @param fallback       兜底节点（检索质量不足且重试耗尽时的回答）
     * @param routeAfterGrade 评分后路由：返回 {@link #GENERATE} / {@link #REWRITE_QUERY} / {@link #GENERATE_FALLBACK}
     * @return 编译后的可执行状态图
     */
    public CompiledGraph<AdaptiveRagState> build(
            NodeAction<AdaptiveRagState> rewrite,
            NodeAction<AdaptiveRagState> retrieve,
            NodeAction<AdaptiveRagState> grade,
            NodeAction<AdaptiveRagState> generate,
            NodeAction<AdaptiveRagState> fallback,
            EdgeAction<AdaptiveRagState> routeAfterGrade) {

        try {
            return new StateGraph<>(AdaptiveRagState.SCHEMA, AdaptiveRagState::new)
                    .addNode(REWRITE_QUERY, node_async(rewrite))
                    .addNode(RETRIEVE, node_async(retrieve))
                    .addNode(GRADE_DOCUMENTS, node_async(grade))
                    .addNode(GENERATE, node_async(generate))
                    .addNode(GENERATE_FALLBACK, node_async(fallback))
                    .addEdge(START, REWRITE_QUERY)
                    .addEdge(REWRITE_QUERY, RETRIEVE)
                    .addEdge(RETRIEVE, GRADE_DOCUMENTS)
                    .addConditionalEdges(GRADE_DOCUMENTS, edge_async(routeAfterGrade), ROUTE_MAP)
                    .addEdge(GENERATE, END)
                    .addEdge(GENERATE_FALLBACK, END)
                    .compile();
        } catch (Exception e) {
            throw new IllegalStateException("构建自适应 RAG 状态图失败", e);
        }
    }
}
