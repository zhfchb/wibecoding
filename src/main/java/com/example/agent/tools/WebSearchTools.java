package com.example.agent.tools;

import com.example.agent.config.RagProperties;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Web 搜索工具，供 Agent 通过 Function Calling 调用 Tavily API 获取外部信息。
 */
@Slf4j
public class WebSearchTools {

    private static final String TAVILY_API = "https://api.tavily.com/search";

    private final String apiKey;
    private final int maxResults;
    private final HttpClient httpClient;

    public WebSearchTools(RagProperties.WebSearch config) {
        this.apiKey = config.apiKey();
        this.maxResults = config.maxResults();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Tool("搜索互联网获取实时信息。当用户询问实时信息（如新闻、招聘、天气、股价等）或知识库中没有的内容时，调用此工具。")
    public String search(@P("搜索关键词，例如：北京 AI Agent 招聘") String query) {
        try {
            String body = String.format(
                    "{\"api_key\":\"%s\",\"query\":\"%s\",\"max_results\":%d,\"search_depth\":\"basic\"}",
                    apiKey, query.replace("\"", "\\\""), maxResults);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(TAVILY_API))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Tavily API 返回非 200: status={}, body={}", response.statusCode(), response.body());
                return "搜索服务暂时不可用，请稍后重试。";
            }

            return formatResponse(response.body());
        } catch (Exception e) {
            log.error("调用 Tavily 搜索失败: query={}", query, e);
            return "搜索服务调用失败：" + e.getMessage();
        }
    }

    /**
     * 将 Tavily JSON 响应格式化为 LLM 友好的纯文本。
     * 避免引入 Jackson 依赖，用简单字符串解析提取 title/content/url。
     */
    private String formatResponse(String json) {
        StringBuilder sb = new StringBuilder("搜索结果：\n");

        // 简单解析 results 数组中的每条记录
        // Tavily 返回格式: {"results": [{"title":"..","url":"..","content":"..","score":..}, ...]}
        int resultsStart = json.indexOf("\"results\"");
        if (resultsStart < 0) {
            return "搜索未返回有效结果。";
        }

        int arrayStart = json.indexOf('[', resultsStart);
        if (arrayStart < 0) {
            return "搜索未返回有效结果。";
        }

        int braceCount = 0;
        int recordStart = -1;
        int recordEnd = -1;
        int idx = arrayStart;

        while (idx < json.length()) {
            char c = json.charAt(idx);
            if (c == '{') {
                if (braceCount == 0) recordStart = idx;
                braceCount++;
            } else if (c == '}') {
                braceCount--;
                if (braceCount == 0 && recordStart >= 0) {
                    recordEnd = idx + 1;
                    String record = json.substring(recordStart, recordEnd);
                    sb.append(parseRecord(record)).append('\n');
                    recordStart = -1;
                }
            } else if (c == ']' && braceCount == 0) {
                break;
            }
            idx++;
        }

        return sb.toString();
    }

    private String parseRecord(String record) {
        String title = extractField(record, "title");
        String content = extractField(record, "content");
        String url = extractField(record, "url");

        StringBuilder sb = new StringBuilder();
        if (title != null && !title.isEmpty()) {
            sb.append("- ").append(title).append("\n");
        }
        if (content != null && !content.isEmpty()) {
            // 截断过长的 content
            if (content.length() > 500) {
                content = content.substring(0, 500) + "...";
            }
            sb.append("  摘要：").append(content).append("\n");
        }
        if (url != null && !url.isEmpty()) {
            sb.append("  链接：").append(url);
        }
        return sb.toString();
    }

    private String extractField(String json, String field) {
        String searchKey = "\"" + field + "\":\"";
        int start = json.indexOf(searchKey);
        if (start < 0) {
            // 可能是空值或数字
            searchKey = "\"" + field + "\":";
            start = json.indexOf(searchKey);
            if (start < 0) return null;
            int valueStart = start + searchKey.length();
            int valueEnd = json.indexOf(',', valueStart);
            if (valueEnd < 0) valueEnd = json.indexOf('}', valueStart);
            if (valueEnd < 0) return null;
            return json.substring(valueStart, valueEnd).trim().replace("\"", "");
        }

        int valueStart = start + searchKey.length();
        int valueEnd = json.indexOf("\"", valueStart);
        if (valueEnd < 0) return null;
        return json.substring(valueStart, valueEnd).replace("\\\"", "\"");
    }
}
