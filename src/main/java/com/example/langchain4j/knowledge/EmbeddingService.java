package com.example.langchain4j.knowledge;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 嵌入服务：统一封装向量嵌入调用，隔离 graph 层与模型 API。
 */
@Slf4j
@Service
public class EmbeddingService {

    private final EmbeddingModel embeddingModel;

    public EmbeddingService(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
    }

    /**
     * 文本嵌入。
     *
     * @param text 输入文本（已做防御性校验）
     * @return 向量；模型不可用时抛 {@link BizException}，由调用方降级
     */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "嵌入文本不能为空");
        }
        try {
            Response<Embedding> response = embeddingModel.embed(text);
            if (response == null || response.content() == null) {
                throw new IllegalStateException("嵌入模型返回空结果");
            }
            return response.content().vector();
        } catch (Exception e) {
            log.error("[embedding] embed failed, text length={}", text.length(), e);
            throw new BizException(ErrorCode.AGENT_LLM_UNAVAILABLE, "向量嵌入服务不可用", e);
        }
    }
}
