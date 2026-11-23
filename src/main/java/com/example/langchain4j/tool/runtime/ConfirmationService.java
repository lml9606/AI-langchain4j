package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.audit.AuditPayloadProvider;
import com.example.langchain4j.audit.AuditStep;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
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
    @AuditStep("confirm-create")
    public PendingAction create(String userId,
                                String conversationId,
                                String model,
                                String tool,
                                List<Map<String, Object>> plan,
                                String summary,
                                String risk) {
        // 兼容旧版单写确认：沿用 confirmationId 作为幂等键，不追加 stepNo。
        return create(userId, conversationId, model, "", plan, summary, risk,
                List.of(new PendingWriteStep(0, tool, argsFromPlan(plan, tool))));
    }

    /**
     * 批量确认：一个令牌覆盖本轮全部写步骤。
     *
     * <p>每个写步骤对应一条 PENDING 台账，幂等键为 {@code runId:stepNo}；
     * 用户确认后 TaskRunExecutor 按步骤顺序执行，不会重复写。
     *
     * @param runId 任务运行 ID；空字符串表示旧版单写链路
     * @param steps 待确认写步骤（stepNo 必须是计划中的真实步骤号）
     */
    @AuditStep("confirm-create")
    public PendingAction create(String userId,
                                String conversationId,
                                String model,
                                String runId,
                                List<Map<String, Object>> plan,
                                String summary,
                                String risk,
                                List<PendingWriteStep> steps) {
        int ttlSeconds = Math.max(30, properties.getTool().getConfirmation().getTtlSeconds());
        String confirmationId = "cfm_" + UUID.randomUUID().toString().replace("-", "");
        long expiresAt = System.currentTimeMillis() + ttlSeconds * 1000L;
        String tool = steps != null && steps.size() == 1
                ? steps.get(0).tool() : "MULTI_WRITE";
        PendingAction action = new PendingAction(confirmationId, userId, conversationId,
                model, runId, tool, steps, plan, summary, risk, expiresAt);
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
            toolExecutionService.recordPendingBatch(toExecutions(action));
        } catch (BizException e) {
            deleteQuietly(bucket);
            throw e;
        } catch (Exception e) {
            deleteQuietly(bucket);
            log.error("[confirm] pending record failed, rollback redis key: {}", e.getMessage());
            throw new BizException(ErrorCode.DB_UNAVAILABLE, "确认记录保存失败，请稍后重试");
        }
        log.info("[confirm] created, idTail={}, tool={}, writes={}, runTail={}, ttl={}s",
                idTail(confirmationId), tool, action.steps().size(), idTail(runId), ttlSeconds);
        return action;
    }

    /** PendingAction → 每个写步骤一条待确认台账。 */
    private List<ToolExecution> toExecutions(PendingAction action) {
        List<ToolExecution> executions = new ArrayList<>(action.steps().size());
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(action.expiresAt()), ZoneOffset.UTC);
        for (PendingWriteStep step : action.steps()) {
            ToolExecution execution = new ToolExecution();
            execution.setExecutionId(executionId(action, step));
            execution.setUserId(action.userId());
            execution.setConversationId(action.conversationId());
            execution.setTool(step.tool());
            String argsJson = JsonUtil.toJson(step.args() == null ? Map.of() : step.args());
            execution.setArgsJson(argsJson);
            execution.setArgsHash(sha256(argsJson));
            execution.setPlanJson(JsonUtil.toJson(action.plan()));
            execution.setSummary(action.summary());
            execution.setRisk(action.risk());
            execution.setExpiresAt(expiresAt);
            executions.add(execution);
        }
        return executions;
    }

    /**
     * 幂等键必须跨"重新确认"保持稳定：
     * 旧版单写用 confirmationId；多步骤用 runId:stepNo，重试换令牌也不会重复写。
     */
    private String executionId(PendingAction action, PendingWriteStep step) {
        if (action.runId() == null || action.runId().isBlank()) {
            return action.confirmationId();
        }
        return action.runId() + ":" + step.stepNo();
    }

    /** 从计划中提取写工具参数；找不到时返回空对象。 */
    private Map<String, Object> argsFromPlan(List<Map<String, Object>> plan, String tool) {
        if (plan == null) {
            return Map.of();
        }
        for (Map<String, Object> step : plan) {
            if (step == null || !tool.equals(String.valueOf(step.get("tool")))) {
                continue;
            }
            Object args = step.get("args");
            if (args instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
        }
        return Map.of();
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
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
     * 新消息取消旧确认前读取令牌：只读、不删除。
     * <p>Redis 读失败返回空，不阻断数据库取消；真正的删除在数据库事务提交后执行。
     */
    public Optional<PendingAction> findPendingForCancellation(String conversationId,
                                                              String userId) {
        if (conversationId == null || conversationId.isBlank()
                || userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = conversationBucket(conversationId).get();
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            PendingAction action = objectMapper.readValue(json, PendingAction.class);
            if (!userId.equals(action.userId())) {
                log.warn("[confirm] cancel skipped, user mismatch, conversation={}",
                        conversationId);
                return Optional.empty();
            }
            return Optional.of(action);
        } catch (Exception e) {
            log.warn("[confirm] cancel read failed, continue db cancel, conversation={}: {}",
                    conversationId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 数据库取消提交后精准删除旧令牌。
     * <p>先比对 confirmationId 与 userId，避免误删期间新创建的令牌；Redis 删除失败只告警，
     * 数据库已是 CANCELLED，确认链路会拒绝执行，令牌由 TTL 兜底过期。
     */
    public void deletePendingIfMatches(String conversationId, String userId,
                                       String confirmationId) {
        if (conversationId == null || conversationId.isBlank()
                || userId == null || userId.isBlank()
                || confirmationId == null || confirmationId.isBlank()) {
            return;
        }
        try {
            RBucket<String> bucket = conversationBucket(conversationId);
            String json = bucket.get();
            if (json == null || json.isBlank()) {
                return;
            }
            PendingAction current = objectMapper.readValue(json, PendingAction.class);
            if (!confirmationId.equals(current.confirmationId())) {
                log.warn("[confirm] skip token delete, confirmation changed, conversation={}",
                        conversationId);
                return;
            }
            if (!userId.equals(current.userId())) {
                log.warn("[confirm] skip token delete, user mismatch, conversation={}",
                        conversationId);
                return;
            }
            bucket.delete();
        } catch (Exception e) {
            log.warn("[confirm] token delete failed after db cancel, wait ttl, conversation={}: {}",
                    conversationId, e.getMessage());
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

    /** 待确认写步骤：stepNo 对应 ai_task_step.step_no。 */
    public record PendingWriteStep(int stepNo,
                                   String tool,
                                   Map<String, Object> args) {

        public PendingWriteStep {
            tool = tool == null ? "" : tool;
            args = args == null ? Map.of() : Map.copyOf(args);
        }
    }

    /**
     * 待确认写操作：plan 为完整执行计划，steps 为本轮全部写步骤。
     * 确认后原样交给执行图，按步骤顺序执行。
     */
    public record PendingAction(String confirmationId,
                                String userId,
                                String conversationId,
                                String model,
                                String runId,
                                String tool,
                                List<PendingWriteStep> steps,
                                List<Map<String, Object>> plan,
                                String summary,
                                String risk,
                                long expiresAt) implements AuditPayloadProvider {

        public PendingAction {
            userId = userId == null ? "" : userId;
            conversationId = conversationId == null ? "" : conversationId;
            model = model == null ? "" : model;
            runId = runId == null ? "" : runId;
            tool = tool == null ? "" : tool;
            steps = steps == null ? List.of() : List.copyOf(steps);
            plan = plan == null ? List.of() : List.copyOf(plan);
            summary = summary == null ? "" : summary;
            risk = risk == null ? "" : risk;
        }

        /** 审计只保留令牌展示字段，不回写完整计划，避免重复存储和泄露内部参数。 */
        @Override
        public Map<String, Object> auditPayload() {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("confirmationId", confirmationId);
            payload.put("tool", tool);
            payload.put("runId", runId);
            payload.put("writes", steps.size());
            payload.put("summary", summary);
            payload.put("risk", risk);
            payload.put("expiresAt", expiresAt);
            return payload;
        }
    }
}
