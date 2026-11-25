package com.example.langchain4j.memory;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.model.LlmRequest;
import com.example.langchain4j.prompt.PromptTemplates;
import com.example.langchain4j.prompt.PromptTemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 用户长期记忆服务：召回注入、异步抽取、查看与删除。
 *
 * 设计边界：
 * - 记忆是跨会话的稳定信息，不是对话历史；每轮对话只注入少量高价值条目；
 * - 抽取异步执行且失败不影响主链路，避免增加用户等待；
 * - 同一用户 + 类型 + key 唯一，重复抽取覆盖更新，防止记忆无限膨胀；
 * - 用户可查看/删除/手动修正，避免错误记忆长期放大。
 */
@Slf4j
@Service
public class UserMemoryService {

    private static final Set<String> ALLOWED_TYPES = Set.of(
            UserMemory.TYPE_PREFERENCE, UserMemory.TYPE_FACT, UserMemory.TYPE_TASK_CONSTRAINT);
    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_CONTENT_LENGTH = 1000;
    private static final int MAX_EXTRACT_INPUT_LENGTH = 1500;
    private static final double MIN_EXTRACT_IMPORTANCE = 0.3;
    private static final int MAX_LIST_LIMIT = 200;

    private final UserMemoryMapper memoryMapper;
    private final AgentProperties properties;
    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final ThreadPoolTaskExecutor memoryExecutor;

    /** 注入 Mapper、LLM、提示词与记忆专用线程池；服务本身无本地状态。 */
    public UserMemoryService(UserMemoryMapper memoryMapper, AgentProperties properties,
                             LlmGateway llmGateway, PromptTemplateService promptTemplateService,
                             @Qualifier("memoryExecutor") ThreadPoolTaskExecutor memoryExecutor) {
        this.memoryMapper = memoryMapper;
        this.properties = properties;
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.memoryExecutor = memoryExecutor;
    }

    /**
     * 召回并格式化本轮可注入的长期记忆。
     *
     * 先按重要度取候选，再用查询关键词做轻量重排（命中关键字 +0.15），
     * 最后按配置字符数截断。查询失败只降级为空记忆，不影响主对话。
     *
     * @param userId 当前用户 ID
     * @param query  本轮用户输入（用于轻量相关性重排）
     * @return 可直接拼进提示词的记忆文本；无记忆/未启用/查询失败返回空串
     */
    public String recallForPrompt(String userId, String query) {
        AgentProperties.Memory config = properties.getMemory();
        if (!config.isEnabled() || userId == null || userId.isBlank()) {
            return "";
        }
        try {
            int limit = Math.max(1, config.getRecallLimit());
            List<UserMemory> candidates = memoryMapper.selectTop(userId,
                    Math.max(limit * 3, limit), config.getMinImportance());
            if (candidates == null || candidates.isEmpty()) {
                return "";
            }
            List<String> keywords = keywords(query);
            List<UserMemory> ranked = candidates.stream()
                    .sorted(Comparator.comparingDouble((UserMemory memory) ->
                                    score(memory, keywords)).reversed())
                    .limit(limit)
                    .toList();
            markUsedQuietly(userId, ranked);
            return format(ranked, Math.max(100, config.getMaxPromptChars()));
        } catch (Exception e) {
            log.warn("[memory] recall failed, degrade to empty: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 写入或覆盖一条长期记忆。
     *
     * 类型必须属于白名单，key 统一转小写下划线格式；内容和重要度做边界收敛。
     * 写入失败抛出业务异常，由调用方决定是否降级（对话主链路抽取时只记录告警）。
     *
     * @return 写入后的完整记录（含自增 id）
     */
    public UserMemory remember(String userId, String type, String key, String content,
                               double importance, String sourceConversationId) {
        String normalizedType = normalizeType(type);
        if (normalizedType == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的记忆类型");
        }
        String normalizedContent = content == null ? "" : content.trim();
        if (normalizedContent.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "记忆内容不能为空");
        }
        if (normalizedContent.length() > MAX_CONTENT_LENGTH) {
            normalizedContent = normalizedContent.substring(0, MAX_CONTENT_LENGTH);
        }
        // 模型可能输出中文 key：归一后为空时用内容哈希生成稳定 key，保证记忆仍可沉淀
        String normalizedKey = normalizeKey(key);
        if (normalizedKey.isBlank()) {
            normalizedKey = "mem_" + Integer.toHexString(normalizedContent.hashCode());
        }

        UserMemory memory = new UserMemory();
        memory.setUserId(userId);
        memory.setMemoryType(normalizedType);
        memory.setMemoryKey(normalizedKey);
        memory.setContent(normalizedContent);
        memory.setImportance(clamp(importance));
        memory.setSourceConversationId(sourceConversationId);
        memory.setExpiresAt(expiresAt());
        memoryMapper.upsert(memory);
        return memoryMapper.selectByKey(userId, normalizedType, normalizedKey);
    }

    /** 用户查看自己的记忆，按更新时间倒序；limit 收敛到 1~200。 */
    public List<UserMemory> list(String userId, int limit) {
        int bounded = Math.min(Math.max(1, limit), MAX_LIST_LIMIT);
        return memoryMapper.selectByUser(userId, bounded);
    }

    /**
     * 删除自己的记忆；返回 false 表示记录不存在或不属于当前用户。
     * 不区分“不存在”和“无权”，避免泄露其他用户记录是否存在。
     */
    public boolean delete(String userId, Long id) {
        if (id == null) {
            return false;
        }
        return memoryMapper.deleteById(userId, id) > 0;
    }

    /**
     * 异步抽取本轮值得长期记住的信息。
     *
     * 只提交任务，不等待 LLM；队列满或抽取失败都只记录告警，不影响对话结果。
     *
     * @param userId         当前用户
     * @param conversationId 来源会话（审计/追溯）
     * @param query          本轮用户输入
     * @param answer         本轮助手回答
     */
    public void extractAsync(String userId, String conversationId, String query, String answer) {
        AgentProperties.Memory config = properties.getMemory();
        if (!config.isEnabled() || userId == null || userId.isBlank()
                || query == null || query.isBlank()
                || answer == null || answer.isBlank()) {
            return;
        }
        try {
            memoryExecutor.execute(() -> extract(userId, conversationId, query, answer));
        } catch (Exception e) {
            log.warn("[memory] extraction rejected, drop this turn: {}", e.getMessage());
        }
    }

    /**
     * 执行抽取：调用 LLM 输出 JSON，逐条校验后 upsert。
     *
     * 任何异常都在这里消化，绝不影响调用方（后台线程）。
     */
    private void extract(String userId, String conversationId, String query, String answer) {
        try {
            String userPrompt = "用户：\n" + truncate(query)
                    + "\n\n助手：\n" + truncate(answer);
            JsonNode node = llmGateway.generateJson(LlmRequest.of(resolveModel(),
                            promptTemplateService.resolve("MEMORY_EXTRACT_SYSTEM",
                                    PromptTemplates.MEMORY_EXTRACT_SYSTEM), userPrompt)
                    .maxOutputTokens(Math.max(128, properties.getMemory().getExtractMaxOutputTokens()))
                    .scene("memory-extract"));
            if (node == null || !node.path("memories").isArray()) {
                return;
            }
            int saved = 0;
            for (JsonNode item : node.path("memories")) {
                String type = normalizeType(item.path("type").asText(""));
                String key = normalizeKey(item.path("key").asText(""));
                String content = item.path("content").asText("").trim();
                double importance = item.path("importance").asDouble(0.5);
                if (type == null || key.isBlank() || content.isBlank()
                        || importance < MIN_EXTRACT_IMPORTANCE) {
                    continue;
                }
                remember(userId, type, key, content, importance, conversationId);
                saved++;
            }
            if (saved > 0) {
                log.info("[memory] extracted, user={}, conversation={}, count={}",
                        mask(userId), conversationId, saved);
            }
        } catch (Exception e) {
            log.warn("[memory] extraction failed, ignored: {}", e.getMessage());
        }
    }

    /**
     * 记忆格式化：按类型给出中文标签，条目间换行；总长按配置截断。
     */
    private String format(List<UserMemory> memories, int maxChars) {
        StringBuilder builder = new StringBuilder();
        for (UserMemory memory : memories) {
            if (builder.length() >= maxChars) {
                break;
            }
            builder.append("- [").append(label(memory.getMemoryType())).append("] ")
                    .append(memory.getContent()).append('\n');
        }
        String text = builder.toString().trim();
        return text.length() <= maxChars ? text : text.substring(0, maxChars);
    }

    /** 召回命中记录：失败只告警，不影响对话。 */
    private void markUsedQuietly(String userId, List<UserMemory> memories) {
        List<Long> ids = memories.stream().map(UserMemory::getId)
                .filter(id -> id != null).toList();
        if (ids.isEmpty()) {
            return;
        }
        try {
            memoryMapper.markUsed(userId, ids);
        } catch (Exception e) {
            log.debug("[memory] mark used failed: {}", e.getMessage());
        }
    }

    /** 相关性打分：重要度为主，关键词命中加少量权重。 */
    private double score(UserMemory memory, List<String> keywords) {
        double importance = memory.getImportance() == null ? 0.5 : memory.getImportance();
        String content = memory.getContent() == null ? "" : memory.getContent().toLowerCase(Locale.ROOT);
        for (String keyword : keywords) {
            if (content.contains(keyword)) {
                return importance + 0.15;
            }
        }
        return importance;
    }

    /** 类型中文标签：用于拼装提示词和前端展示。 */
    private String label(String memoryType) {
        if (UserMemory.TYPE_PREFERENCE.equals(memoryType)) {
            return "偏好";
        }
        if (UserMemory.TYPE_TASK_CONSTRAINT.equals(memoryType)) {
            return "约束";
        }
        return "事实";
    }

    /**
     * 提取轻量关键词：按非字母数字切分，过滤过短项；长中文串补 2 字滑窗，提升中文召回命中率。
     */
    private List<String> keywords(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : query.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (token.length() >= 2 && token.length() <= 16) {
                tokens.add(token);
            }
        }
        for (String token : new ArrayList<>(tokens)) {
            if (tokens.size() >= 16) {
                break;
            }
            if (token.length() > 4) {
                for (int i = 0; i + 2 <= token.length() && tokens.size() < 16; i += 2) {
                    tokens.add(token.substring(i, i + 2));
                }
            }
        }
        return new ArrayList<>(tokens);
    }

    /** 类型归一：大小写无关，白名单外返回 null。 */
    private String normalizeType(String type) {
        if (type == null) {
            return null;
        }
        String normalized = type.trim().toUpperCase(Locale.ROOT);
        return ALLOWED_TYPES.contains(normalized) ? normalized : null;
    }

    /** key 归一：小写、非字母数字转下划线、去首尾下划线、限制长度。 */
    private String normalizeKey(String key) {
        if (key == null) {
            return "";
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9_]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        return normalized.length() <= MAX_KEY_LENGTH
                ? normalized : normalized.substring(0, MAX_KEY_LENGTH);
    }

    /** 重要度收敛到 0~1；未提供时用默认值。 */
    private double clamp(double importance) {
        if (Double.isNaN(importance)) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, importance));
    }

    /** 有效期：ttlDays<=0 表示永久；否则从现在起算。 */
    private OffsetDateTime expiresAt() {
        int ttlDays = properties.getMemory().getTtlDays();
        return ttlDays <= 0 ? null : OffsetDateTime.now().plusDays(ttlDays);
    }

    /** 抽取模型：配置为空回全局默认。 */
    private String resolveModel() {
        String model = properties.getMemory().getModel();
        return model == null || model.isBlank() ? properties.getLlm().getDefaultModel() : model;
    }

    /** 抽取输入截断，控制 token 成本。 */
    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_EXTRACT_INPUT_LENGTH
                ? text : text.substring(0, MAX_EXTRACT_INPUT_LENGTH) + "...[截断]";
    }

    /** 日志脱敏：用户 ID 只保留末 4 位。 */
    private String mask(String userId) {
        if (userId == null || userId.length() <= 4) {
            return "***";
        }
        return "***" + userId.substring(userId.length() - 4);
    }
}
