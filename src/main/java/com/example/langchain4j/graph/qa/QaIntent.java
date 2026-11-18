package com.example.langchain4j.graph.qa;

import java.util.Set;

/**
 * 知识问答意图常量与白名单。
 * <p>
 * 从旧 IntentNode 中抽出：意图是跨节点共享的领域枚举，不应该依附在某个已删除的节点上。
 */
public final class QaIntent {

    public static final String CODE_GENERATE = "code_generate";
    public static final String KNOWLEDGE_QA = "knowledge_qa";
    public static final String TASK = "task";
    public static final String CHITCHAT = "chitchat";
    public static final String REJECT = "reject";
    /** 分类失败/未知值兜底：走简单对话，不检索、不调用工具，风险最低。 */
    public static final String DEFAULT = CHITCHAT;

    private static final Set<String> VALID = Set.of(
            CODE_GENERATE, KNOWLEDGE_QA, TASK, CHITCHAT, REJECT);

    private QaIntent() {
    }

    /** 模型输出白名单校验：未知值统一降级为简单对话，避免误检索或误调工具。 */
    public static String normalize(String intent) {
        String value = intent == null ? "" : intent.trim();
        return VALID.contains(value) ? value : DEFAULT;
    }

    public static boolean isKnowledge(String intent) {
        return KNOWLEDGE_QA.equals(intent);
    }
}
