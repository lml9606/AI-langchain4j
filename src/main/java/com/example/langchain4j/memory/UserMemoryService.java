package com.example.langchain4j.memory;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.knowledge.embedding.EmbeddingService;
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
 * 用户长期记忆服务：召回注入、异步抽取、候选晋升、同义合并、查看与删除。
 *
 * 设计边界：
 * - 记忆是跨会话的稳定信息，不是对话历史；每轮对话只注入少量高价值条目；
 * - 抽取异步执行且失败不影响主链路，避免增加用户等待；
 * - 模型只负责“提候选”，系统负责 key 字典、状态、有效期、合并和去重；
 * - 同一用户 + 类型 + key 唯一，重复抽取覆盖更新，防止记忆无限膨胀；
 * - 用户可查看/删除/手动修正，避免错误记忆长期放大。
 */
@Slf4j
@Service
public class UserMemoryService {

    /** 允许的记忆类型；TEMPORARY 只短期保留，不参与常规召回。 */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            UserMemory.TYPE_PREFERENCE, UserMemory.TYPE_FACT,
            UserMemory.TYPE_TASK_CONSTRAINT, UserMemory.TYPE_TEMPORARY);
    private static final int MAX_KEY_LENGTH = 64;
    private static final int MAX_CONTENT_LENGTH = 1000;
    private static final int MAX_EXTRACT_INPUT_LENGTH = 1500;
    private static final int MAX_QUOTE_LENGTH = 200;
    private static final int MAX_TASK_KEY_LENGTH = 120;
    private static final double MIN_EXTRACT_IMPORTANCE = 0.3;
    /** 关键词完全不像时不再调用 embedding，避免每条新事实都产生远程调用和错误日志 */
    private static final double MIN_MERGE_LEXICAL = 0.15;
    private static final int MAX_LIST_LIMIT = 200;

    private final UserMemoryMapper memoryMapper;
    private final UserMemoryStore memoryStore;
    private final MemoryExtractTaskMapper extractTaskMapper;
    private final AgentProperties properties;
    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final EmbeddingService embeddingService;
    private final ThreadPoolTaskExecutor memoryExecutor;
    private final AgentMetrics metrics;

    /**
     * 注入 Mapper、LLM、提示词、向量服务与记忆专用线程池；服务本身无本地状态。
     */
    public UserMemoryService(UserMemoryMapper memoryMapper,
                             UserMemoryStore memoryStore,
                             MemoryExtractTaskMapper extractTaskMapper,
                             AgentProperties properties,
                             LlmGateway llmGateway,
                             PromptTemplateService promptTemplateService,
                             EmbeddingService embeddingService,
                             @Qualifier("memoryExecutor") ThreadPoolTaskExecutor memoryExecutor,
                             AgentMetrics metrics) {
        this.memoryMapper = memoryMapper;
        this.memoryStore = memoryStore;
        this.extractTaskMapper = extractTaskMapper;
        this.properties = properties;
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.embeddingService = embeddingService;
        this.memoryExecutor = memoryExecutor;
        this.metrics = metrics;
    }

    /**
     * 召回并格式化本轮可注入的长期记忆。
     *
     * 先按重要度取候选，再用查询关键词做轻量重排（命中关键字 +0.15），
     * 最后按配置字符数截断。只取 ACTIVE 且非 TEMPORARY 的记忆。
     * 查询失败只降级为空记忆，不影响主对话。
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
            metrics.recordMemoryRecall(ranked.size());
            return format(ranked, Math.max(100, config.getMaxPromptChars()));
        } catch (Exception e) {
            log.warn("[memory] recall failed, degrade to empty: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 手动写入或覆盖一条长期记忆（用户纠错入口）。
     *
     * 手动写入视为用户已确认，直接 ACTIVE；类型必须属于白名单，key 统一转小写下划线格式。
     * 写入失败抛出业务异常，由接口层返回给用户。
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
        memory.setConfidence(1.0);
        memory.setStatus(UserMemory.STATUS_ACTIVE);
        memory.setSourceConversationId(sourceConversationId);
        memory.setExtractorVersion("manual");
        memory.setValidFrom(OffsetDateTime.now());
        return memoryStore.saveExtracted(memory);
    }

    /**
     * 手动修改一条记忆的内容和重要度。
     *
     * 与抽取写入的区别：允许降低重要度，修改后状态提升为 ACTIVE。
     *
     * @return 修改后的记录；不存在或不属于当前用户返回 null
     */
    public UserMemory update(String userId, Long id, String content, Double importance) {
        String normalizedContent = content == null ? "" : content.trim();
        if (normalizedContent.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "记忆内容不能为空");
        }
        if (normalizedContent.length() > MAX_CONTENT_LENGTH) {
            normalizedContent = normalizedContent.substring(0, MAX_CONTENT_LENGTH);
        }
        double normalizedImportance = clamp(importance == null ? 0.8 : importance);
        return memoryStore.updateContent(userId, id, normalizedContent,
                normalizedImportance, "MANUAL");
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
     * 先用 messageId 抢占抽取任务，重复投递直接跳过；只提交任务，不等待 LLM。
     * 队列满或抽取失败都只记录告警，不影响对话结果。
     *
     * @param userId         当前用户
     * @param conversationId 来源会话（审计/追溯）
     * @param messageId      本轮用户消息 ID；为空时用内容哈希兜底
     * @param query          本轮用户输入
     * @param answer         本轮助手回答
     */
    public void extractAsync(String userId, String conversationId, Long messageId,
                             String query, String answer) {
        AgentProperties.Memory config = properties.getMemory();
        if (!config.isEnabled() || userId == null || userId.isBlank()
                || query == null || query.isBlank()
                || answer == null || answer.isBlank()) {
            return;
        }
        String taskKey = taskKey(messageId, conversationId, query, answer);
        try {
            if (extractTaskMapper.insertIfAbsent(taskKey, userId, conversationId) == 0) {
                log.debug("[memory] extraction skipped, duplicate message, task={}", taskKey);
                metrics.recordMemoryExtract("skipped");
                return;
            }
        } catch (Exception e) {
            // 幂等表不可用时宁可跳过，也不重复沉淀；主对话不受影响
            log.warn("[memory] extraction gate failed, skip this turn: {}", e.getMessage());
            metrics.recordMemoryExtract("failed");
            return;
        }
        try {
            memoryExecutor.execute(() ->
                    extract(userId, conversationId, messageId, taskKey, query, answer));
        } catch (Exception e) {
            markTaskQuietly(taskKey, "FAILED");
            metrics.recordMemoryExtract("failed");
            log.warn("[memory] extraction rejected, drop this turn: {}", e.getMessage());
        }
    }

    /**
     * 执行抽取：调用 LLM 输出 JSON，逐条校验、合并、晋升后落库。
     *
     * 任何异常都在这里消化，绝不影响调用方（后台线程）；任务状态只用于观测。
     */
    private void extract(String userId, String conversationId, Long messageId,
                         String taskKey, String query, String answer) {
        try {
            String userPrompt = "用户：\n" + truncate(query)
                    + "\n\n助手：\n" + truncate(answer);
            String systemPrompt = promptTemplateService.resolveFormatted(
                    "MEMORY_EXTRACT_SYSTEM", PromptTemplates.MEMORY_EXTRACT_SYSTEM,
                    keyList(properties.getMemory().getPreferenceKeys()),
                    keyList(properties.getMemory().getConstraintKeys()));
            JsonNode node = llmGateway.generateJson(LlmRequest.of(resolveModel(),
                            systemPrompt, userPrompt)
                    .maxOutputTokens(Math.max(128, properties.getMemory().getExtractMaxOutputTokens()))
                    .scene("memory-extract"));
            if (node == null || !node.path("memories").isArray()) {
                markTaskQuietly(taskKey, "DONE");
                metrics.recordMemoryExtract("empty");
                return;
            }
            int saved = 0;
            for (JsonNode item : node.path("memories")) {
                UserMemory candidate = buildCandidate(userId, conversationId, messageId, item);
                if (candidate == null) {
                    continue;
                }
                // 偏好/约束必须落在受控 key 字典里；事实/临时信息允许自拟 key，但先尝试同义合并
                if (isOpenType(candidate.getMemoryType())) {
                    String mergedKey = mergeKey(userId, candidate.getMemoryType(),
                            candidate.getMemoryKey(), candidate.getContent());
                    if (mergedKey != null) {
                        candidate.setMemoryKey(mergedKey);
                        metrics.recordMemoryMerge();
                    }
                } else if (!keyAllowed(candidate.getMemoryType(), candidate.getMemoryKey())) {
                    log.info("[memory] drop unlisted key, type={}, key={}",
                            candidate.getMemoryType(), candidate.getMemoryKey());
                    metrics.recordMemoryExtract("skipped");
                    continue;
                }
                memoryStore.saveExtracted(candidate);
                saved++;
            }
            markTaskQuietly(taskKey, "DONE");
            if (saved > 0) {
                metrics.recordMemoryExtract("saved");
                metrics.recordMemoryExtractSaved(saved);
                log.info("[memory] extracted, user={}, conversation={}, count={}, task={}",
                        mask(userId), conversationId, saved, taskKey);
            } else {
                metrics.recordMemoryExtract("empty");
            }
        } catch (Exception e) {
            metrics.recordMemoryExtract("failed");
            markTaskQuietly(taskKey, "FAILED");
            log.warn("[memory] extraction failed, ignored: {}", e.getMessage());
        }
    }

    /**
     * 更新抽取任务状态；失败只记 debug。
     *
     * 任务表只用于观测和幂等排查，状态更新失败不能反过来影响抽取结果或主对话。
     */
    private void markTaskQuietly(String taskKey, String status) {
        try {
            extractTaskMapper.markStatus(taskKey, status);
        } catch (Exception e) {
            log.debug("[memory] task status update failed, task={}, status={}: {}",
                    taskKey, status, e.getMessage());
        }
    }

    /**
     * 把模型输出的一条 JSON 转成候选记忆；校验不通过返回 null。
     *
     * 校验边界：类型白名单、内容非空、重要度门槛、confidence 收敛、quote 截断。
     * key 只做归一，不在这里做字典校验，调用方按类型决定放行还是丢弃。
     */
    private UserMemory buildCandidate(String userId, String conversationId, Long messageId,
                                      JsonNode item) {
        String type = normalizeType(item.path("type").asText(""));
        String content = item.path("content").asText("").trim();
        double importance = item.path("importance").asDouble(0.5);
        if (type == null || content.isBlank() || importance < MIN_EXTRACT_IMPORTANCE) {
            metrics.recordMemoryExtract("skipped");
            return null;
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            content = content.substring(0, MAX_CONTENT_LENGTH);
        }
        String key = normalizeKey(item.path("key").asText(""));
        if (key.isBlank()) {
            key = "mem_" + Integer.toHexString(content.hashCode());
        }
        boolean explicit = item.path("explicit").asBoolean(false);
        UserMemory memory = new UserMemory();
        memory.setUserId(userId);
        memory.setMemoryType(type);
        memory.setMemoryKey(key);
        memory.setContent(content);
        memory.setImportance(clamp(importance));
        memory.setConfidence(clamp(item.path("confidence").asDouble(0.6)));
        // 显式长期指令直接生效；其余先候选，第二次出现由存储层晋升
        memory.setStatus(explicit || UserMemory.TYPE_TEMPORARY.equals(type)
                ? UserMemory.STATUS_ACTIVE : UserMemory.STATUS_CANDIDATE);
        memory.setValidFrom(OffsetDateTime.now());
        memory.setSourceConversationId(conversationId);
        memory.setSourceMessageId(messageId == null ? "" : String.valueOf(messageId));
        memory.setSourceQuote(truncate(item.path("quote").asText(""), MAX_QUOTE_LENGTH));
        memory.setExtractorVersion(properties.getMemory().getExtractorVersion());
        return memory;
    }

    /**
     * 尝试把新事实合并到已有记忆的 key 上。
     *
     * 先用关键词相似度粗筛；关键词不足时再用最近一条记忆做 embedding 兜底，
     * 专门处理“同一属性换值”的情况（如下周去北京 → 改去上海）。
     * embedding 不可用时：关键词达标则按关键词合并，否则不合并。
     * 返回 null 表示没有足够相似的旧记忆，应作为新 key 插入。
     */
    private String mergeKey(String userId, String type, String key, String content) {
        AgentProperties.Memory config = properties.getMemory();
        int limit = Math.max(1, config.getMergeCandidateLimit());
        List<UserMemory> candidates;
        try {
            candidates = memoryMapper.selectRecentByType(userId, type, limit);
        } catch (Exception e) {
            log.debug("[memory] merge candidates failed, keep new key: {}", e.getMessage());
            return null;
        }
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        UserMemory best = null;
        double bestScore = 0;
        for (UserMemory candidate : candidates) {
            if (candidate.getMemoryKey() == null || candidate.getContent() == null) {
                continue;
            }
            double score = lexicalSimilarity(content, candidate.getContent());
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best == null || bestScore < MIN_MERGE_LEXICAL) {
            return null;
        }
        if (bestScore < config.getMergeSimilarity()) {
            // 关键词不足以判断时，用最相似的一条同类型记忆做向量兜底；
            // 同属性换值的句子往往只有少量字不同，关键词 Jaccard 会偏低
            Double similarity = embeddingSimilarity(content, best.getContent());
            if (similarity != null && similarity >= config.getMergeEmbeddingSimilarity()) {
                log.debug("[memory] merged by embedding fallback, type={}, fromKey={}, toKey={}",
                        type, key, best.getMemoryKey());
                return best.getMemoryKey();
            }
            return null;
        }
        Double similarity = embeddingSimilarity(content, best.getContent());
        if (similarity == null) {
            // 向量服务不可用时用关键词结果兜底，保证抽取链不断
            return best.getMemoryKey();
        }
        return similarity >= config.getMergeEmbeddingSimilarity() ? best.getMemoryKey() : null;
    }

    /**
     * 计算两段文本的向量相似度。
     *
     * @return 0~1 的余弦相似度；embedding 不可用或维度异常时返回 null，
     *         由调用方决定是降级到关键词还是放弃合并
     */
    private Double embeddingSimilarity(String left, String right) {
        if (left == null || right == null || left.isBlank() || right.isBlank()) {
            return null;
        }
        try {
            float[] leftVector = embeddingService.embed(left);
            float[] rightVector = embeddingService.embed(right);
            return cosine(leftVector, rightVector);
        } catch (Exception e) {
            log.debug("[memory] embedding verify failed, degrade: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 关键词相似度：按字符二元组算 Jaccard。
     *
     * 中文没有空格，二元组比整句包含更稳；英文/数字混合也会被切成连续字符片段。
     */
    private double lexicalSimilarity(String left, String right) {
        Set<String> a = bigrams(left);
        Set<String> b = bigrams(right);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int intersection = 0;
        for (String token : a) {
            if (b.contains(token)) {
                intersection++;
            }
        }
        return (double) intersection / (a.size() + b.size() - intersection);
    }

    /** 文本转字符二元组集合；去除标点/空白并转小写，避免格式差异影响相似度。 */
    private Set<String> bigrams(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{S}\\s]+", "");
        Set<String> grams = new LinkedHashSet<>();
        if (normalized.length() == 1) {
            grams.add(normalized);
            return grams;
        }
        for (int i = 0; i + 2 <= normalized.length(); i++) {
            grams.add(normalized.substring(i, i + 2));
        }
        return grams;
    }

    /** 余弦相似度；任一向量为空或维度不一致时返回 0。 */
    private double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
            return 0;
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    /** 事实/临时类型允许自拟 key，因此需要走同义合并。 */
    private boolean isOpenType(String type) {
        return UserMemory.TYPE_FACT.equals(type) || UserMemory.TYPE_TEMPORARY.equals(type);
    }

    /** 受控 key 校验：偏好和任务约束只能使用配置字典里的 key。 */
    private boolean keyAllowed(String type, String key) {
        AgentProperties.Memory config = properties.getMemory();
        if (UserMemory.TYPE_PREFERENCE.equals(type)) {
            return config.getPreferenceKeys() != null && config.getPreferenceKeys().contains(key);
        }
        if (UserMemory.TYPE_TASK_CONSTRAINT.equals(type)) {
            return config.getConstraintKeys() != null && config.getConstraintKeys().contains(key);
        }
        return true;
    }

    /** 把 key 字典转成提示词里的可读列表；空配置明确告诉模型“禁止新增”，避免静默漂移。 */
    private String keyList(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return "未配置（本轮不要新增该类记忆）";
        }
        return String.join("、", keys);
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
        if (UserMemory.TYPE_TEMPORARY.equals(memoryType)) {
            return "临时";
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

    /** 重要度收敛到 0~1；未提供或 NaN 时用默认值。 */
    private double clamp(double importance) {
        if (Double.isNaN(importance)) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, importance));
    }

    /** 抽取模型：配置为空回全局默认。 */
    private String resolveModel() {
        String model = properties.getMemory().getModel();
        return model == null || model.isBlank() ? properties.getLlm().getDefaultModel() : model;
    }

    /** 抽取输入截断，控制 token 成本。 */
    private String truncate(String text) {
        return truncate(text, MAX_EXTRACT_INPUT_LENGTH);
    }

    /** 按指定长度截断；null 返回空串。 */
    private String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength
                ? text : text.substring(0, maxLength) + "...[截断]";
    }

    /**
     * 抽取幂等键：优先用消息 ID；没有消息 ID 时用会话 + 问题 + 回答的哈希兜底。
     * 结果长度受数据库列限制，避免超长写入失败。
     */
    private String taskKey(Long messageId, String conversationId, String query, String answer) {
        String key = messageId != null && messageId > 0
                ? "msg:" + messageId
                : "hash:" + Integer.toHexString(
                        (conversationId + "|" + query + "|" + answer).hashCode());
        return key.length() <= MAX_TASK_KEY_LENGTH ? key : key.substring(0, MAX_TASK_KEY_LENGTH);
    }

    /** 日志脱敏：用户 ID 只保留末 4 位。 */
    private String mask(String userId) {
        if (userId == null || userId.length() <= 4) {
            return "***";
        }
        return "***" + userId.substring(userId.length() - 4);
    }
}
