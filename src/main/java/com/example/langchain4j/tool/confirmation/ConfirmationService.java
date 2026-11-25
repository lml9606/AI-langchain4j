package com.example.langchain4j.tool.confirmation;

import com.example.langchain4j.common.constant.RedisKeys;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.tool.execution.ToolExecution;
import com.example.langchain4j.tool.execution.ToolExecutionService;
import com.example.langchain4j.tool.run.TaskRun;
import com.example.langchain4j.tool.run.TaskRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 写操作确认令牌：短期授权，保存服务端工具、参数与归属。
 *
 * 同一确认的执行互斥由编排层的严格会话锁负责（Watchdog + Redis 故障 fail-closed），
 * 本类负责令牌的创建、读取与删除，并维护“一个会话最多一个待确认动作”的约束：
 * 每个会话只有一个 {@code agent:confirm:{conversationId}} key，创建新动作直接 trySet，
 * 已存在则拒绝，确认/取消后删除该 key。
 *
 * 令牌只是“用户同意过”的短期凭证，不是执行结果的事实源。创建令牌的同时会在同一事务内
 * 写入 {@code ai_task_run} 和每步 {@code ai_tool_execution} 的 PENDING 台账；
 * 用户确认后由写工具继续流转状态。令牌被删、过期、Redis 重启都不会丢失执行事实。
 */
@Slf4j
@Service
public class ConfirmationService {


    private final RedissonClient redissonClient;
    private final AgentProperties properties;
    private final ToolExecutionService toolExecutionService;
    private final TaskRunService taskRunService;
    private final ObjectMapper objectMapper = JsonUtil.copyMapper();

    public ConfirmationService(RedissonClient redissonClient, AgentProperties properties,
                               ToolExecutionService toolExecutionService,
                               TaskRunService taskRunService) {
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.toolExecutionService = toolExecutionService;
        this.taskRunService = taskRunService;
    }

    /**
     * 创建单写确认动作：先写 Redis 会话 key，再写 PENDING 台账。
     *
     * 台账写失败会撤销 Redis key，保证不会下发“有令牌、无记录”的确认卡片。
     * 同一 confirmationId 同时作为写工具幂等键，重复确认不会重复修改业务数据。
     */
    public PendingAction create(String userId,
                                String conversationId,
                                String model,
                                String tool,
                                Map<String, Object> args,
                                String summary,
                                String risk) {
        return create(userId, conversationId, model,
                List.of(new PendingAction.Step(1, tool, args, summary)), risk);
    }

    /**
     * 创建多步确认动作：一个会话仍然只有一张令牌，令牌里保存完整步骤计划。
     *
     * 数据库在同一事务内写 run + 每步 PENDING 台账；任一步失败都撤销 Redis 令牌，
     * 保证下发的确认卡片一定能找到对应的执行记录。
     *
     * @param steps 已按执行顺序排列的步骤（stepNo 由本方法重排为 1..N）
     * @param risk  风险等级（当前固定 WRITE）
     */
    public PendingAction create(String userId,
                                String conversationId,
                                String model,
                                List<PendingAction.Step> steps,
                                String risk) {
        int ttlSeconds = Math.max(30, properties.getTool().getConfirmation().getTtlSeconds());
        String confirmationId = "cfm_" + UUID.randomUUID().toString().replace("-", "");
        long expiresAt = System.currentTimeMillis() + ttlSeconds * 1000L;
        PendingAction action = new PendingAction(confirmationId, userId, conversationId,
                model, null, null, joinSummary(steps), risk, expiresAt, steps);
        if (action.steps().isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "写操作计划不能为空");
        }

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
            taskRunService.createPending(toRun(action), toExecutions(action));
        } catch (BizException e) {
            deleteQuietly(bucket);
            throw e;
        } catch (Exception e) {
            deleteQuietly(bucket);
            log.error("[confirm] pending record failed, rollback redis key: {}", e.getMessage());
            throw new BizException(ErrorCode.DB_UNAVAILABLE, "确认记录保存失败，请稍后重试");
        }
        log.info("[confirm] created, idTail={}, steps={}, firstTool={}, ttl={}s",
                idTail(confirmationId), action.steps().size(), action.tool(), ttlSeconds);
        return action;
    }

    /** 待确认动作转运行台账：run_id = confirmationId，一个确认一个批次。 */
    private TaskRun toRun(PendingAction action) {
        TaskRun run = new TaskRun();
        run.setRunId(action.confirmationId());
        run.setUserId(action.userId());
        run.setConversationId(action.conversationId());
        run.setStatus(TaskRun.Status.PENDING);
        run.setCurrentStep(0);
        run.setTotalSteps(action.steps().size());
        run.setPlanJson(JsonUtil.toJson(action.steps()));
        return run;
    }

    /**
     * 待确认动作转每步 PENDING 台账：executionId = confirmationId#stepNo。
     * 每步独立幂等键，重试时已完成步骤直接重放，不会重复修改业务数据。
     */
    private List<ToolExecution> toExecutions(PendingAction action) {
        OffsetDateTime expiresAt = OffsetDateTime.ofInstant(
                Instant.ofEpochMilli(action.expiresAt()), ZoneOffset.UTC);
        List<ToolExecution> executions = new ArrayList<>(action.steps().size());
        for (PendingAction.Step step : action.steps()) {
            ToolExecution execution = new ToolExecution();
            execution.setExecutionId(TaskRunService.stepExecutionId(
                    action.confirmationId(), step.stepNo()));
            execution.setRunId(action.confirmationId());
            execution.setStepNo(step.stepNo());
            execution.setUserId(action.userId());
            execution.setConversationId(action.conversationId());
            execution.setTool(step.tool());
            execution.setArgsJson(JsonUtil.toJson(step.args()));
            execution.setSummary(step.summary());
            execution.setRisk(action.risk());
            execution.setExpiresAt(expiresAt);
            executions.add(execution);
        }
        return executions;
    }

    /** 确认卡片摘要：列出每步动作，前端无需再拼装。 */
    private String joinSummary(List<PendingAction.Step> steps) {
        if (steps == null || steps.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder("共 ").append(steps.size()).append(" 步：");
        int index = 1;
        for (PendingAction.Step step : steps) {
            if (step == null) {
                continue;
            }
            builder.append('\n').append(index++).append(". ")
                    .append(step.summary().isBlank()
                            ? step.tool() + " 参数 " + JsonUtil.toJson(step.args())
                            : step.summary());
        }
        return builder.toString();
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
     *
     * Redis 读失败返回空，不阻断数据库取消；真正的删除在数据库事务提交后执行。
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
     *
     * 先比对 confirmationId 与 userId，避免误删期间新创建的令牌；Redis 删除失败只告警，
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
            if (!confirmationId.equals(current.confirmationId())
                    || !userId.equals(current.userId())) {
                log.warn("[confirm] skip token delete, confirmation/user changed, conversation={}",
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
     *
     * 供编排层在严格会话锁内做归属校验；Redis 故障抛 503，而不是伪装成令牌不存在。
     */
    public Optional<PendingAction> preview(String conversationId, String confirmationId,
                                           String userId) {
        return Optional.ofNullable(load(conversationId, confirmationId, userId));
    }

    /**
     * 完成确认：删除会话当前令牌（成功执行或用户取消后调用）。
     *
     * 只有会话 key 仍指向该令牌时才删除，避免误删后续新操作。
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

    /** 读取并校验归属；Redis 故障抛 503，其余无效情况返回 null。 */
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

    /** 令牌桶：集中 key 拼接与 Redisson 访问，业务方法不直接操作 bucket。 */
    private RBucket<String> conversationBucket(String conversationId) {
        return redissonClient.getBucket(conversationKey(conversationId));
    }

    /** 令牌 key：只在这里维护前缀。 */
    private String conversationKey(String conversationId) {
        return RedisKeys.CONFIRMATION_PREFIX + conversationId;
    }

    /** 确认令牌脱敏：长 ID 仅保留末 8 位，短 ID 完全隐藏。 */
    private String idTail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }

    /**
     * 待确认写动作：一个会话一张令牌，令牌内保存完整步骤计划。
     *
     * 兼容旧格式：历史 Redis 令牌没有 steps 字段，反序列化后由 tool + args 自动补成单步；
     * tool/args 始终镜像第一步，供旧版确认卡片和日志继续使用。
     */
    public record PendingAction(String confirmationId,
                                String userId,
                                String conversationId,
                                String model,
                                String tool,
                                Map<String, Object> args,
                                String summary,
                                String risk,
                                long expiresAt,
                                List<Step> steps) {

        public PendingAction {
            userId = userId == null ? "" : userId;
            conversationId = conversationId == null ? "" : conversationId;
            model = model == null ? "" : model;
            tool = tool == null ? "" : tool;
            args = immutable(args);
            summary = summary == null ? "" : summary;
            risk = risk == null ? "" : risk;
            // 步骤序号由服务端统一重排，不接受模型或历史数据给出的序号
            List<Step> normalized = new ArrayList<>();
            if (steps != null) {
                int index = 1;
                for (Step step : steps) {
                    if (step == null || step.tool() == null || step.tool().isBlank()) {
                        continue;
                    }
                    normalized.add(new Step(index++, step.tool(), step.args(), step.summary()));
                }
            }
            if (normalized.isEmpty() && !tool.isBlank()) {
                normalized.add(new Step(1, tool, args, summary));
            }
            steps = List.copyOf(normalized);
            if (!steps.isEmpty()) {
                tool = steps.get(0).tool();
                args = steps.get(0).args();
            }
        }

        /** 单个步骤：工具固定、参数固定、摘要用于确认卡片展示。 */
        public record Step(int stepNo, String tool, Map<String, Object> args, String summary) {

            public Step {
                tool = tool == null ? "" : tool;
                args = immutable(args);
                summary = summary == null ? "" : summary;
            }
        }

        /** Map 统一做不可变拷贝，避免令牌对象被调用方事后修改。 */
        private static Map<String, Object> immutable(Map<String, Object> value) {
            return value == null ? Map.of()
                    : Collections.unmodifiableMap(new LinkedHashMap<>(value));
        }
    }
}
