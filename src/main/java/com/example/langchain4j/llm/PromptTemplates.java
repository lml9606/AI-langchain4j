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

    /**
     * 校验失败后的重试提示（要求模型重新生成）。
     * 护栏：明确“不得编造”——重试压力下模型易凑输出，防幻觉在重试环中被放大。
     */
    public static final String VALIDATE_RETRY_PROMPT = """
            你上一次的输出不符合要求（可能为空、过于简短或格式错误）。
            请重新生成：内容完整、结构清晰、使用 Markdown 格式；
            必须基于参考资料与对话事实回答，不得编造不存在的内容。
            用户需求：%s
            """;

    /** 多轮对话改写：话题归属判断 + 指代消解一次完成，输出严格 JSON */
    public static final String REWRITE_SYSTEM = """
            你是对话改写器：根据对话历史与已有话题列表，完成两项任务：
            1. 话题归属：判断用户最新输入属于哪个已有话题（topic 填该话题名，newTopic=false）；
               若是全新话题，newTopic=true，topic 填简短的新话题名（如“任务A”）；
               无历史或无法判断时 newTopic=false，topic 填空字符串。
            2. 指代消解：把用户最新输入改写成不依赖历史也能理解的完整句子
               （把“它/这个/那个/上面/第二个”等替换为具体所指），不得改变用户原意；
               若最新输入本身已完整、无需历史即可理解，原样输出。
            只输出严格 JSON，不要任何解释、引号或前后缀：
            {"rewritten": "改写后的句子", "topic": "话题名", "newTopic": false}
            """;
    
    /**
     * 段摘要生成：重写式 —— 已有摘要 + 新增对话合并重写为完整摘要（非增量拼接），
     * 天然去重、总长可控，避免拼接式滚雪球。供滑出窗口的历史注入 system prompt。
     */
    public static final String SUMMARY_SYSTEM = """
            你是对话摘要器：根据已有摘要和新增对话，输出一份合并重写后的完整摘要。
            要求：
            1. 合并：保留已有摘要中的全部要点，把新增对话的要点并入，重复内容只保留一次；
            2. 重写：输出的是完整摘要（不是增量），用条目列出，总长不超过3000字；
            3. 保留任务目标、关键决策、重要实体与结论，丢弃寒暄与过程噪音；
            4. 不要编造对话中不存在的内容。
            """;
    
    /**
     * 段摘要注入块：拼入生成节点的 system prompt，帮助模型回忆窗口外的历史要点。
     * 护栏：摘要为压缩产物可能不完整/有误，声明与对话记录冲突时以原文为准，
     * 降低错误摘要（system prompt 权威性高于对话消息）压过正确原文的概率。
     */
    public static final String SUMMARY_BLOCK = """
                
            以下是本会话当前任务的历史要点摘要（用于回忆对话窗口中未包含的早期内容；
            摘要为压缩产物，可能不完整，若与上方对话记录冲突，以对话记录为准）：
            %s
            """;
}
