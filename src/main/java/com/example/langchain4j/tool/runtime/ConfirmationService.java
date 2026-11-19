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
 * 写操作确认令牌：短期授权 + 处理租约。
 *
 * <p>职责拆成两块，避免"一个令牌既当授权又当执行状态"：
 * <ul>
 *   <li><b>令牌</b>（{@code agent:confirm:{id}}）：保存服务端计划与归属，TTL 到期自动失效；</li>
 *   <li><b>处理租约</b>（{@code agent:confirm:{id}:claim}）：SET NX 抢占，保证同一确认同一时刻只有一个执行者。</li>
 * </ul>
 *
 * <p>令牌只是"用户同意过"的短期凭证，不是执行结果的事实源。执行是否成功由数据库台账
 * {@code ai_tool_execution} 回答——令牌被删、过期、Redis 重启都不会丢失这个事实。
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
    private static final String CLAIM_SUFFIX = ":claim";

    private final RedissonClient redissonClient;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper = JsonUtil.copyMapper();

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
     * 只读取并校验令牌，不抢占、不删除。
     * <p>供编排层在进入会话锁前做归属校验；Redis 故障抛 503，而不是伪装成令牌不存在。
     */
    public Optional<PendingAction> preview(String confirmationId, String userId) {
        return Optional.ofNullable(load(confirmationId, userId));
    }

    /**
     * 抢占处理租约：同一确认同一时刻只允许一个执行者。
     * <p>租约到期自动释放，进程崩溃不会永久锁死；真正的重复执行防护由写工具幂等台账兜底。
     *
     * @throws BizException 令牌不存在（404）、已有执行者（409）、Redis 故障（503）
     */
    public PendingAction claim(String confirmationId, String userId) {
        PendingAction action = load(confirmationId, userId);
        if (action == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "确认请求不存在、已过期或已使用，请重新发起操作");
        }
        int claimSeconds = Math.max(1, properties.getTool().getConfirmation().getClaimSeconds());
        try {
            RBucket<String> claimBucket = redissonClient.getBucket(KEY_PREFIX + confirmationId + CLAIM_SUFFIX);
            boolean acquired = claimBucket.trySet("1", claimSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("[confirm] claim conflict, idTail={}, conversation={}",
                        idTail(confirmationId), action.conversationId());
                throw new BizException(ErrorCode.CONFIRMATION_IN_PROGRESS);
            }
            log.info("[confirm] claimed, idTail={}, conversation={}, tool={}",
                    idTail(confirmationId), action.conversationId(), action.tool());
            return action;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[confirm] claim failed, idTail={}, fail-closed: {}", idTail(confirmationId), e.getMessage());
            throw new BizException(ErrorCode.CACHE_UNAVAILABLE, "确认服务暂不可用，请稍后重试");
        }
    }

    /**
     * 释放处理租约但保留令牌：执行失败时调用，用户可以用同一个 confirmationId 重试。
     * <p>重试是否安全由写工具的幂等台账保证，不依赖令牌本身。
     */
    public void release(String confirmationId) {
        if (confirmationId == null || confirmationId.isBlank()) {
            return;
        }
        try {
            redissonClient.getBucket(KEY_PREFIX + confirmationId + CLAIM_SUFFIX).delete();
        } catch (Exception e) {
            // 租约会自然过期，释放失败不阻断主流程
            log.warn("[confirm] release failed, idTail={}: {}", idTail(confirmationId), e.getMessage());
        }
    }

    /** 完成确认：删除令牌与处理租约（成功执行或用户取消后调用） */
    public void finish(String confirmationId) {
        if (confirmationId == null || confirmationId.isBlank()) {
            return;
        }
        try {
            redissonClient.getBucket(KEY_PREFIX + confirmationId + CLAIM_SUFFIX).delete();
            redissonClient.getBucket(KEY_PREFIX + confirmationId).delete();
        } catch (Exception e) {
            // 令牌 TTL 到期会自然清理；删不掉不影响业务结果
            log.warn("[confirm] finish failed, idTail={}: {}", idTail(confirmationId), e.getMessage());
        }
    }

    /** 读取并校验归属/过期；Redis 故障抛 503，其余无效情况返回 null */
    private PendingAction load(String confirmationId, String userId) {
        if (confirmationId == null || confirmationId.isBlank()) {
            return null;
        }
        String json;
        try {
            RBucket<String> bucket = redissonClient.getBucket(KEY_PREFIX + confirmationId);
            json = bucket.get();
        } catch (Exception e) {
            log.error("[confirm] load failed, idTail={}, fail-closed: {}", idTail(confirmationId), e.getMessage());
            throw new BizException(ErrorCode.CACHE_UNAVAILABLE, "确认服务暂不可用，请稍后重试");
        }
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            PendingAction action = objectMapper.readValue(json, PendingAction.class);
            if (!userId.equals(action.userId())) {
                log.warn("[confirm] user mismatch, idTail={}, conversation={}",
                        idTail(confirmationId), action.conversationId());
                return null;
            }
            if (action.expiresAt() < System.currentTimeMillis()) {
                log.warn("[confirm] expired, idTail={}, conversation={}",
                        idTail(confirmationId), action.conversationId());
                return null;
            }
            return action;
        } catch (Exception e) {
            log.warn("[confirm] decode failed, idTail={}: {}", idTail(confirmationId), e.getMessage());
            return null;
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
