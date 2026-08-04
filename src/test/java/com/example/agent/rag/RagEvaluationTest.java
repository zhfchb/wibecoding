package com.example.agent.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RAG 检索质量评估测试：构建小型知识库 + 评测集，计算 Recall@K 与 MRR。
 *
 * 默认用 LexicalHashEmbeddingModel 跑 baseline，验证检索链路 work；
 * 配置真实 Embedding（rag.embedding.provider=openai）后可手动切换 model 字段对比效果。
 *
 * 简历素材：这里跑出的 Recall@3 数值可以直接写进简历作为效果指标。
 */
class RagEvaluationTest {

    private static final int TOP_K = 3;
    private static final int DIMENSION = 1024;

    @Test
    void evaluateRetrievalQuality() {
        EmbeddingModel model = new LexicalHashEmbeddingModel(DIMENSION);
        InMemoryVectorStore store = new InMemoryVectorStore();

        // 1. 构建知识库：5 篇技术文档，每篇有明确主题
        List<EvalDoc> docs = buildCorpus();
        for (EvalDoc doc : docs) {
            Embedding emb = model.embed(doc.text()).content();
            store.add(new ChunkRecord(UUID.randomUUID().toString(), doc.id(), doc.text(), emb.vector()));
        }

        // 2. 评测集：5 个自然语言查询 + 期望命中的文档 ID
        List<EvalQuery> queries = buildQueries();

        // 3. 跑检索，统计 Recall@K 与 MRR
        int hits = 0;
        double mrr = 0;
        List<String> report = new ArrayList<>();
        report.add("======= RAG 检索质量评估 =======");
        report.add(String.format("Embedding: %s | 维度: %d | TopK: %d | 文档数: %d | 查询数: %d",
                model.getClass().getSimpleName(), DIMENSION, TOP_K, docs.size(), queries.size()));
        report.add("-----------------------------------");

        for (EvalQuery query : queries) {
            Embedding queryEmb = model.embed(query.question()).content();
            List<VectorMatch> matches = store.search(queryEmb.vector(), TOP_K, 0.0);

            int rank = -1;
            for (int i = 0; i < matches.size(); i++) {
                if (matches.get(i).documentId().equals(query.expectedDocId())) {
                    rank = i + 1;
                    break;
                }
            }
            boolean hit = rank > 0;
            if (hit) {
                hits++;
                mrr += 1.0 / rank;
            }

            report.add(String.format("Q: %s", query.question()));
            report.add(String.format("   期望: %s | %s",
                    query.expectedDocId(),
                    docs.stream().filter(d -> d.id().equals(query.expectedDocId()))
                            .findFirst().map(EvalDoc::title).orElse("?")));
            report.add(String.format("   命中: %s (rank=%s)", hit ? "YES" : "NO", rank > 0 ? rank : "-"));
            for (int i = 0; i < matches.size(); i++) {
                VectorMatch m = matches.get(i);
                String title = docs.stream().filter(d -> d.id().equals(m.documentId()))
                        .findFirst().map(EvalDoc::title).orElse(m.text().substring(0, Math.min(20, m.text().length())));
                report.add(String.format("   top%d: %.4f | %s | %s", i + 1, m.score(), m.documentId(), title));
            }
            report.add("");
        }

        double recall = (double) hits / queries.size();
        mrr /= queries.size();
        report.add("-----------------------------------");
        report.add(String.format("Recall@%d: %.2f%% (%d/%d)", TOP_K, recall * 100, hits, queries.size()));
        report.add(String.format("MRR: %.4f", mrr));
        report.add("===================================");

        String fullReport = String.join("\n", report);
        System.out.println(fullReport);

        // hash embedding 在关键词重合场景下应能命中大部分，baseline 设 60%
        assertThat(recall).as("Recall@%d baseline", TOP_K).isGreaterThanOrEqualTo(0.6);
    }

    private List<EvalDoc> buildCorpus() {
        return List.of(
                new EvalDoc("doc-spring", "Spring Boot 自动配置",
                        "Spring Boot 通过 @EnableAutoConfiguration 开启自动配置，" +
                                "它基于 spring.factories 文件加载自动配置类。@ConditionalOnClass 等条件注解控制配置是否生效。"),
                new EvalDoc("doc-rag", "RAG 检索增强生成",
                        "RAG 检索增强生成流程：先将文档切分成块，用 Embedding 模型转成向量存入向量库，" +
                                "查询时把问题也转成向量检索相关片段，拼到 Prompt 上下文里再交给 LLM 生成答案。"),
                new EvalDoc("doc-qdrant", "Qdrant 向量数据库",
                        "Qdrant 是开源向量数据库，支持 REST 和 gRPC 接口。创建集合时指定向量维度和距离度量（如 Cosine），" +
                                "用 PUT /collections/{name} 建集合，POST /collections/{name}/points/search 做检索。"),
                new EvalDoc("doc-docker", "Docker 容器部署",
                        "Docker 通过 Dockerfile 描述镜像构建过程，用 docker build 打镜像，docker run 启动容器。" +
                                "docker-compose.yml 用于编排多容器应用，一键启动数据库、缓存等依赖服务。"),
                new EvalDoc("doc-java17", "Java 17 新特性",
                        "Java 17 是 LTS 版本，主要新增：密封类 sealed 限制继承、模式匹配 instanceof、" +
                                "文本块 text block 用三个引号定义多行字符串、Switch 表达式增强。")
        );
    }

    private List<EvalQuery> buildQueries() {
        return List.of(
                new EvalQuery("Spring Boot 怎么实现自动配置", "doc-spring"),
                new EvalQuery("RAG 是怎么工作的", "doc-rag"),
                new EvalQuery("Qdrant 怎么创建集合", "doc-qdrant"),
                new EvalQuery("怎么用 Docker 部署应用", "doc-docker"),
                new EvalQuery("Java 17 有什么新特性", "doc-java17")
        );
    }

    private record EvalDoc(String id, String title, String text) {
    }

    private record EvalQuery(String question, String expectedDocId) {
    }
}
