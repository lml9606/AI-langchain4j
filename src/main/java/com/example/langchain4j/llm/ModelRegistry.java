package com.example.langchain4j.llm;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.config.AgentProperties;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多模型路由注册表：模型名 → ChatModel 实例。
 * <p>
 * 由各模型 Config 产出的「模型名 → ChatModel」Map Bean 扁平化汇总而成：
 * <ul>
 *   <li>千问：QwenConfig 包装 dashscope starter 自动装配的 qwenChatModel Bean（单模型 Map）；</li>
 *   <li>DeepSeek：DeepSeekConfig 按 agent.deepseek.model-names 循环装配的多模型 Map；</li>
 *   <li>key 为模型真实名称（qwen-max / deepseek-flash / deepseek-v4-pro），前端传 model 参数按此表路由。</li>
 * </ul>
 * 启动期 fail-fast：模型名重复导致路由覆盖、默认模型未注册时直接抛异常，
 * 避免“配置错了运行期才发现”的隐性故障；新增模型只需在对应供应商的配置列表加一个名字。
 */
@Component
public class ModelRegistry {

    private final Map<String, ChatModel> chatModels;
    private final String defaultModel;

    public ModelRegistry(List<Map<String, ChatModel>> providerModels, AgentProperties properties) {
        Map<String, ChatModel> models = new LinkedHashMap<>();
        // 扁平化合并各供应商的模型 Map（千问一个、DeepSeek 若干个），重名 fail-fast
        for (Map<String, ChatModel> provider : providerModels) {
            for (Map.Entry<String, ChatModel> entry : provider.entrySet()) {
                ChatModel previous = models.putIfAbsent(entry.getKey(), entry.getValue());
                if (previous != null) {
                    throw new IllegalStateException("[llm] 模型名重复导致路由覆盖: " + entry.getKey()
                            + "，请检查各模型配置的模型名");
                }
            }
        }
        this.chatModels = Collections.unmodifiableMap(models);
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
