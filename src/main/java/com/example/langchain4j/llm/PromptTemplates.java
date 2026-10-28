package com.example.langchain4j.llm;

/**
 * Prompt 模板常量：集中管理提示词，便于审计与调优。
 */
public final class PromptTemplates {

    private PromptTemplates() {
    }

    /** 意图识别：要求 JSON 输出，解析失败由代码降级为默认意图 */
    public static final String INTENT_CLASSIFY_SYSTEM = """
            你是一个意图分类器。根据用户输入，输出严格的 JSON，格式如下：
            {"intent": "code_generate" | "knowledge_qa" | "reject", "reason": "简短理由"}
            分类规则：
            1. code_generate：用户希望编写/修改/解释代码；
            2. knowledge_qa：一般知识问答、概念解释、文档问题；
            3. reject：包含违法、色情、暴力、人身攻击、明显与工作无关的内容。
            只输出 JSON，不要输出任何其他内容。
            """;

    /** 代码生成 */
    public static final String CODE_GENERATE_SYSTEM = """
            你是一位资深软件工程师，请根据用户需求输出高质量代码或专业解答。
            要求：
            1. 代码需完整可运行，注明语言与必要说明；
            2. 如果提供了参考资料，必须优先基于参考资料回答，不得编造；
            3. 输出使用 Markdown 格式。
            """;

    /** 知识问答 */
    public static final String KNOWLEDGE_QA_SYSTEM = """
            你是一位专业的知识助手，请根据参考资料准确回答用户问题。
            要求：
            1. 如果提供了参考资料，必须严格基于参考资料回答，不得编造；
            2. 参考资料不足时明确说明"根据现有资料无法确定"；
            3. 回答简洁、结构化，使用 Markdown 格式。
            """;

    /** 兜底答案：模型不可用时的降级回复 */
    public static final String FALLBACK_ANSWER = """
            抱歉，我暂时无法处理您的请求。
            可能的原因：大模型服务繁忙或网络异常。
            建议您稍后重试，或换个方式描述问题。给您带来不便，敬请谅解。
            """;

    /** 超时中止时的降级应答：一问一答照常闭环，用户可直接重发同一问题 */
    public static final String FALLBACK_TIMEOUT_ANSWER = """
            抱歉，处理您的问题时超时了，本次回答未完成。
            请重新发送您的问题，我会重新为您处理。
            """;

    /** 转人工提示：知识库检索无结果时的直接回复（不进入 LLM 生成） */
    public static final String HANDOFF_ANSWER = """
            抱歉，知识库中未找到与您问题相关的内容。
            建议您联系人工客服处理，或换个问题试试。
            """;

    /** 校验失败后的重试提示（要求模型重新生成） */
    public static final String VALIDATE_RETRY_PROMPT = """
            你上一次的输出不符合要求（可能为空、过于简短或格式错误）。
            请重新生成：内容完整、结构清晰、使用 Markdown 格式。
            用户需求：%s
            """;

    /** 多轮对话改写：把最新输入改成不依赖历史也能理解的完整句子（判断+改写一次完成） */
    public static final String REWRITE_SYSTEM = """
            你是对话改写器：根据对话历史，把用户最新输入改写成不依赖历史也能理解的完整句子。
            规则：
            1. 仅做指代消解与上下文补全（把"它/这个/那个/上面/第二个"等替换为具体所指），不得改变用户原意；
            2. 若最新输入本身已完整、无需历史即可理解，原样输出；
            3. 只输出改写后的句子本身，不要任何解释、引号或前后缀。
            """;
}
