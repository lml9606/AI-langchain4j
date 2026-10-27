package com.example.langchain4j.llm;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.config.AgentProperties;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多模型路由注册表：模型名 → ChatModel 实例。
 * <p>
 * 注册规则：
 * <ul>
 *   <li>千问：dashscope starter 自动装配的 qwenChatModel Bean；</li>
 *   <li>DeepSeek：DeepSeekConfig 手动装配的 deepseekChatModel Bean；</li>
 *   <li>key 为模型真实名称（qwen-max / deepseek-flash），前端传 model 参数按此表路由。</li>
 * </ul>
 * 启动期 fail-fast：默认模型未注册、模型名重复导致路由覆盖时直接抛异常，
 * 避免“配置错了运行期才发现”的隐性故障。
 */
@Component
public class ModelRegistry {

    private final Map<String, ChatModel> chatModels;
    private final String defaultModel;

    public ModelRegistry(@Qualifier("qwenChatModel") ChatModel qwenChatModel,
                         @Qualifier("deepseekChatModel") ChatModel deepseekChatModel,
                         AgentProperties properties) {
        Map<String, ChatModel> models = new LinkedHashMap<>();
        models.put(properties.getLlm().getQwenModelName(), qwenChatModel);
        models.put(properties.getDeepseek().getModelName(), deepseekChatModel);
        if (models.size() != 2) {
            throw new IllegalStateException("[llm] 模型名重复导致路由覆盖: " + models.keySet()
                    + "，请检查 agent.llm.qwen-model-name 与 agent.deepseek.model-name");
        }
        this.chatModels = Map.copyOf(models);
        this.defaultModel = properties.getLlm().getDefaultModel();
        if (!chatModels.containsKey(defaultModel)) {
            throw new IllegalStateException("[llm] agent.llm.default-model '" + defaultModel
                    + "' 未注册，可用模型: " + chatModels.keySet());
        }
    }

    /**
     * 解析本轮实际使用的模型名：
     * 前端传入优先（未知模型抛 400，快速失败不浪费后续资源）；为空用默认模型。
     */
    public String resolve(String requestedModel) {
        if (requestedModel == null || requestedModel.isBlank()) {
            return defaultModel;
        }
        if (!chatModels.containsKey(requestedModel)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "不支持的模型: " + requestedModel + "，可选: " + chatModels.keySet());
        }
        return requestedModel;
    }

    /** 按模型名取 ChatModel（防御未注册模型，图内兜底抛 400 而非 NPE） */
    public ChatModel chatModel(String modelName) {
        ChatModel model = chatModels.get(modelName);
        if (model == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "未注册的模型: " + modelName);
        }
        return model;
    }
}
