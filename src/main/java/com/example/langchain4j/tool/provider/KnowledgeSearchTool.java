package com.example.langchain4j.tool.provider;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.knowledge.VectorSearchService;
import com.example.langchain4j.repository.entity.KnowledgeChunk;
import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolEvidence;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识检索工具：包装现有混合检索，供任务 Agent 在需要资料时调用。
 */
@Component
public class KnowledgeSearchTool implements AgentTool {

    private static final int DEFAULT_TOP_K = 3;
    private static final int MAX_TOP_K = 5;
    private static final int MAX_SNIPPET = 300;

    private final VectorSearchService vectorSearchService;

    public KnowledgeSearchTool(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "search_knowledge",
                "检索知识库文档，返回与问题相关的资料片段",
                "{\"type\":\"object\",\"required\":[\"query\"],\"properties\":{"
                        + "\"query\":{\"type\":\"string\",\"description\":\"检索问题\"},"
                        + "\"topK\":{\"type\":\"integer\",\"description\":\"返回条数，1-" + MAX_TOP_K
                        + "\",\"default\":" + DEFAULT_TOP_K + "}}}",
                List.of("query"),
                ToolSideEffect.READ_ONLY,
                // 本地 BGE-M3 单次嵌入本机约 2s；留足冷启动、DB 查询与网络抖动余量，避免误报 TIMEOUT
                8000);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        String query = args.path("query").asText("").trim();
        int topK = args.has("topK") ? args.path("topK").asInt(DEFAULT_TOP_K) : DEFAULT_TOP_K;
        topK = Math.max(1, Math.min(MAX_TOP_K, topK));
        List<KnowledgeChunk> chunks = vectorSearchService.search(query, topK);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<ToolEvidence> evidence = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("chunkId", chunk.getId());
            row.put("title", chunk.getTitle());
            row.put("content", truncate(chunk.getContent(), MAX_SNIPPET));
            row.put("metadata", chunk.getMetadata());
            rows.add(row);
            evidence.add(new ToolEvidence("KNOWLEDGE", String.valueOf(chunk.getId()),
                    chunk.getTitle(), truncate(chunk.getContent(), MAX_SNIPPET), 0.0));
        }
        String summary = rows.isEmpty() ? "知识库未命中相关资料"
                : "知识库命中 " + rows.size() + " 条资料: " + JsonUtil.toJson(rows);
        return ToolResult.success(rows, summary, evidence, System.currentTimeMillis() - start);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
