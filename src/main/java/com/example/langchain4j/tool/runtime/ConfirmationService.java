package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 写操作确认令牌：生成、一次性消费与归属校验。
 * <p>
 * 与普通缓存不同，这里必须 fail-closed：创建失败就不返回令牌，消费失败就拒绝执行，
 * 绝不能因为 Redis 故障而跳过人工确认。令牌用 getAndDelete 原子消费，保证同一确认只执行一次。
 */
@Slf4j
@Service
public class ConfirmationService {

    private static final String KEY_PREFIX = "agent:confirm:";

    private final RedissonClient redissonClient;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper = JsonUtil.copyMapper();

    /** 注入 Redis 客户端与 Agent 配置。 */
    public ConfirmationService(RedissonClient redissonClient, AgentProperties properties) {
        this.redissonClient = redissonClient;
        this.properties = properties;
    }

    /**
     * 创建待确认动作并写入 Redis。
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
        try {
            RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + confirmationId);
            bucket.set(objectMapper.writeValueAsString(action), ttlSeconds, TimeUnit.SECONDS);
            log.info("[confirm] created, idTail={}, tool={}, ttl={}s",
                    idTail(confirmationId), tool, ttlSeconds);
            return action;
        } catch (Exception e) {
            log.error("[confirm] create failed, fail-closed: {}", e.getMessage());
            throw new BizException(ErrorCode.CACHE_UNAVAILABLE, "确认服务暂不可用，请稍后重试");
        }
    }

    /**
     * 一次性消费确认令牌：先删除再校验，任何失败都不允许继续执行写操作。
     *
     * @param confirmationId 前端回传的确认 ID
     * @param userId         当前登录用户（令牌必须属于本人）
     * @return 令牌有效时返回待确认动作；不存在、过期、归属不符或 Redis 故障返回 empty
     */
    public Optional<PendingAction> consume(String confirmationId, String userId) {
        if (confirmationId == null || confirmationId.isBlank()) {
            return Optional.empty();
        }
        try {
            RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + confirmationId);
            String json = bucket.getAndDelete();
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            PendingAction action = objectMapper.readValue(json, PendingAction.class);
            if (!userId.equals(action.userId())) {
                log.warn("[confirm] user mismatch, idTail={}, conversation={}",
                        idTail(confirmationId), action.conversationId());
                return Optional.empty();
            }
            if (action.expiresAt() < System.currentTimeMillis()) {
                log.warn("[confirm] expired, idTail={}, conversation={}",
                        idTail(confirmationId), action.conversationId());
                return Optional.empty();
            }
            log.info("[confirm] consumed, idTail={}, conversation={}, tool={}",
                    idTail(confirmationId), action.conversationId(), action.tool());
            return Optional.of(action);
        } catch (Exception e) {
            log.warn("[confirm] consume failed, idTail={}, fail-closed: {}",
                    idTail(confirmationId), e.getMessage());
            return Optional.empty();
        }
    }

    /** 确认令牌脱敏：长 ID 仅保留末 8 位，短 ID 完全隐藏。 */
    private String idTail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }

    /** 待确认写操作：plan 为完整执行计划，确认后原样交给执行图 */
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
