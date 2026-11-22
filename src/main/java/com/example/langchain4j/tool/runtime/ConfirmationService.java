package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.ToolExecution;
import com.example.langchain4j.service.ToolExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 写操作确认令牌：短期授权，保存服务端计划与归属。
 *
 * <p>同一确认的执行互斥由编排层的严格会话锁负责（Watchdog + Redis 故障 fail-closed），
 * 本类负责令牌的创建、读取与删除，并维护"一个会话最多一个待确认动作"的约束：
 * 每个会话只有一个 {@code agent:confirm:{conversationId}} key，值本身就是当前待确认动作，
 * 创建新动作直接 trySet，已存在则拒绝，确认/取消后删除该 key。
 *
 * <p>令牌只是"用户同意过"的短期凭证，不是执行结果的事实源。创建令牌的同时会向
 * {@code ai_tool_execution} 写入 PENDING 台账（含计划快照）；用户确认后由工具执行继续
 * 流转状态。令牌被删、过期、Redis 重启都不会丢失这条业务记录。
 *
 * <p>故障策略：
 * <ul>
 *   <li>创建失败 → 抛 503，绝不下发无法执行的令牌；</li>
 *   <li>读取失败 → 抛 503，不能伪装成"令牌不存在"；</li>
 *   <li>令牌不存在/归属不符/过期 → 统一按"不存在"处理，不泄露他人令牌。</li>
 * </ul>
 */
@Slf4j
@Service
public class ConfirmationService {

    private static final String KEY_PREFIX = "agent:confirm:";

    private final RedissonClient redissonClient;
    private final AgentProperties properties;
    private final ToolExecutionService toolExecutionService;
    private final ObjectMapper objectMapper = JsonUtil.copyMapper();

    public ConfirmationService(RedissonClient redissonClient, AgentProperties properties,
                               ToolExecutionService toolExecutionService) {
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.toolExecutionService = toolExecutionService;
    }

    /**
     * 创建待确认动作：先写 Redis 单会话 key，再写 PENDING 台账。
     * <p>台账写失败会撤销 Redis key，保证不会下发"有令牌、无记录"的确认卡片。
     *
     * @return 含 confirmationId 与过期时间的待确认动作
     * @throws BizException Redis 不可用时抛出（fail-closed，不下发无法执行的令牌）
     */
    public PendingAction create(String userId,
                                String conversationId,
                                String model,
                                String tool,
                                List<Map<String, Object>> plan,
                                String summary,
                                String risk) {
        int ttlSeconds = Math.max(30, properties.getTool().getConfirmation().getTtlSeconds());
        String confirmationId = "cfm_" + UUID.randomUUID().toString().replace("-", "");
        long expiresAt = System.currentTimeMillis() + ttlSeconds * 1000L;
        PendingAction action = new PendingAction(confirmationId, userId, conversationId,
                model, tool, plan, summary, risk, expiresAt);
        RBucket<String> bucket = conversationBucket(conversationId);
        try {
            String json = objectMapper.writeValueAsString(action);
            // 单会话单 key：key 存在就表示已有待确认动作，直接拒绝第二张令牌。
            if (!bucket.trySet(json, ttlSeconds, TimeUnit.SECONDS)) {
                log.warn("[confirm] pending exists, conversation={}", conversationId);
                throw new BizException(ErrorCode.CONFIRMATION_PENDING);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[confirm] create failed, fail-closed: {}", e.getMessage());
            throw new BizException(ErrorCode.CACHE_UNAVAILABLE, "确认服务暂不可用，请稍后重试");
        }

        try {
            if (!toolExecutionService.recordPending(toExecution(action))) {
                deleteQuietly(bucket);
                throw new BizException(ErrorCode.INTERNAL_ERROR,
                        "确认记录写入失败，请重新发起操作");
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            deleteQuietly(bucket);
            log.error("[confirm] pending record failed, rollback redis key: {}", e.getMessage());
            throw new BizException(ErrorCode.DB_UNAVAILABLE, "确认记录保存失败，请稍后重试");
        }
        log.info("[confirm] created, idTail={}, tool={}, ttl={}s", idTail(confirmationId), tool,
                ttlSeconds);
        return action;
    }

    /** PendingAction → 待确认台账快照。 */
    private ToolExecution toExecution(PendingAction action) {
        ToolExecution execution = new ToolExecution();
        execution.setExecutionId(action.confirmationId());
        execution.setUserId(action.userId());
        execution.setConversationId(action.conversationId());
        execution.setTool(action.tool());
        execution.setPlanJson(JsonUtil.toJson(action.plan()));
        execution.setSummary(action.summary());
        execution.setRisk(action.risk());
        execution.setExpiresAt(OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(action.expiresAt()), ZoneOffset.UTC));
        return execution;
    }

    /** 撤销刚写入的会话 key；失败只告警，原异常继续上抛。 */
    private void deleteQuietly(RBucket<String> bucket) {
        try {
            bucket.delete();
        } catch (Exception e) {
            log.warn("[confirm] rollback redis key failed: {}", e.getMessage());
        }
    }

    /**
     * 会话是否存在待确认动作。
     *
     * <p>这是聊天入口的体验层前置检查：Redis 读失败时 fail-open，允许继续执行普通问答；
     * 真正防止第二张令牌的原子闸门在 {@link #create} 的 trySet，那里 Redis 故障会 fail-closed。
     */
    public boolean hasPending(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return false;
        }
        try {
            return conversationBucket(conversationId).get() != null;
        } catch (Exception e) {
            log.warn("[confirm] pending check failed, fail-open, conversation={}: {}",
                    conversationId, e.getMessage());
            return false;
        }
    }

    /**
     * 只读取并校验令牌，不抢占、不删除。
     * <p>供编排层在进入会话锁前做归属校验；Redis 故障抛 503，而不是伪装成令牌不存在。
     */
    public Optional<PendingAction> preview(String conversationId, String confirmationId,
                                           String userId) {
        return Optional.ofNullable(load(conversationId, confirmationId, userId));
    }

    /**
     * 完成确认：删除会话当前令牌（成功执行或用户取消后调用）。
     * <p>只有会话 key 仍指向该令牌时才删除，避免误删后续新操作。
     */
    public void finish(String conversationId, String confirmationId) {
        if (conversationId == null || conversationId.isBlank()
                || confirmationId == null || confirmationId.isBlank()) {
            return;
        }
        try {
            RBucket<String> bucket = conversationBucket(conversationId);
            String json = bucket.get();
            if (json == null || json.isBlank()) {
                return;
            }
            PendingAction action = objectMapper.readValue(json, PendingAction.class);
            if (confirmationId.equals(action.confirmationId())) {
                bucket.delete();
            }
        } catch (Exception e) {
            // 令牌 TTL 到期会自然清理；删不掉不影响业务结果
            log.warn("[confirm] finish failed, idTail={}: {}", idTail(confirmationId),
                    e.getMessage());
        }
    }

    /**
     * 读取并校验归属/过期；Redis 故障抛 503，其余无效情况返回 null
     */
    private PendingAction load(String conversationId, String confirmationId, String userId) {
        if (conversationId == null || conversationId.isBlank()
                || confirmationId == null || confirmationId.isBlank()) {
            return null;
        }
        String json;
        try {
            json = conversationBucket(conversationId).get();
        } catch (Exception e) {
            log.error("[confirm] load failed, idTail={}, fail-closed: {}", idTail(confirmationId),
                    e.getMessage());
            throw new BizException(ErrorCode.CACHE_UNAVAILABLE, "确认服务暂不可用，请稍后重试");
        }
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            PendingAction action = objectMapper.readValue(json, PendingAction.class);
            if (!confirmationId.equals(action.confirmationId())) {
                log.warn("[confirm] confirmation mismatch, conversation={}, idTail={}",
                        conversationId, idTail(confirmationId));
                return null;
            }
            if (!userId.equals(action.userId())) {
                log.warn("[confirm] user mismatch, idTail={}, conversation={}",
                        idTail(confirmationId), action.conversationId());
                return null;
            }
            // Redis TTL 是过期事实源；expiresAt 只用于响应展示。
            return action;
        } catch (Exception e) {
            log.warn("[confirm] decode failed, idTail={}: {}", idTail(confirmationId),
                    e.getMessage());
            return null;
        }
    }

    /**
     * 令牌桶：集中 key 拼接与 Redisson 访问，业务方法不直接操作 bucket。
     */
    private RBucket<String> conversationBucket(String conversationId) {
        return redissonClient.getBucket(conversationKey(conversationId));
    }

    /**
     * 令牌 key：只在这里维护前缀。
     */
    private String conversationKey(String conversationId) {
        return KEY_PREFIX + conversationId;
    }

    /**
     * 确认令牌脱敏：长 ID 仅保留末 8 位，短 ID 完全隐藏。
     */
    private String idTail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }

    /**
     * 待确认写操作：plan 为完整执行计划，确认后原样交给执行图
     */
    public record PendingAction(String confirmationId,
                                String userId,
                                String conversationId,
                                String model,
                                String tool,
                                List<Map<String, Object>> plan,
                                String summary,
                                String risk,
                                long expiresAt) {

        public PendingAction {
            userId = userId == null ? "" : userId;
            conversationId = conversationId == null ? "" : conversationId;
            model = model == null ? "" : model;
            tool = tool == null ? "" : tool;
            plan = plan == null ? List.of() : List.copyOf(plan);
            summary = summary == null ? "" : summary;
            risk = risk == null ? "" : risk;
        }
    }
}
