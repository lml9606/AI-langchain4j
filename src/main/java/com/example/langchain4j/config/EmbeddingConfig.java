package com.example.langchain4j.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * BGE-M3 本地向量模型装配（Ollama 部署，替换百炼 text-embedding-v3）。
 * <p>
 * 说明：
 * <ul>
 *   <li>手动装配并标注 {@link Primary}：dashscope starter 已自动装配 qwenEmbeddingModel，
 *       两个 EmbeddingModel Bean 会歧义注入，@Primary 让 EmbeddingService 优先使用本 Bean；</li>
 *   <li>BGE-M3 输出 1024 维，与 ai_knowledge_chunk.embedding vector(1024) 一致，无需改表；</li>
 *   <li>切换模型后向量空间变化：已入库的旧向量必须删除并重新导入，否则检索结果错乱。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class EmbeddingConfig {

    @Bean
    @Primary
    public EmbeddingModel bgeM3EmbeddingModel(AgentProperties properties) {
        AgentProperties.Embedding cfg = properties.getEmbedding();
        log.info("[embedding] BGE-M3 local model registered: baseUrl={}, modelName={}",
                cfg.getBaseUrl(), cfg.getModelName());
        return OllamaEmbeddingModel.builder()
                .baseUrl(cfg.getBaseUrl())
                .modelName(cfg.getModelName())
                .build();
    }
}
