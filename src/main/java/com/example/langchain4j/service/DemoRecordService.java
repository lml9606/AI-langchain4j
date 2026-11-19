package com.example.langchain4j.service;

import com.example.langchain4j.repository.entity.ToolDemoRecord;
import com.example.langchain4j.repository.entity.ToolExecution;
import com.example.langchain4j.repository.mapper.ToolDemoRecordMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * 工具演示业务服务：读工具的查询源 + 写工具的幂等业务写。
 *
 * <p>写路径是生产级模板：
 * <pre>
 * @Transactional
 *   ├─ 幂等闸门（台账 RUNNING，与业务同事务）
 *   ├─ 业务写（UPDATE ... WHERE user_id = ?）
 *   └─ 台账 SUCCEEDED + 结果
 * </pre>
 * 任何一步失败整体回滚，台账不会留下"成功但业务没改"的假象。
 */
@Slf4j
@Service
public class DemoRecordService {

    /** 写工具执行结果（工具层再映射为 ToolResult） */
    public record CompleteOutcome(boolean success, boolean replayed,
                                  String result, String errorCode, String errorMessage) {

        static CompleteOutcome ok(String result, boolean replayed) {
            return new CompleteOutcome(true, replayed, result, "", "");
        }

        static CompleteOutcome failure(String errorCode, String errorMessage) {
            return new CompleteOutcome(false, false, "", errorCode, errorMessage);
        }
    }

    private final ToolDemoRecordMapper recordMapper;
    private final ToolExecutionService executionService;

    public DemoRecordService(ToolDemoRecordMapper recordMapper, ToolExecutionService executionService) {
        this.recordMapper = recordMapper;
        this.executionService = executionService;
    }

    /** 读工具：按用户查询，status 为空表示全部 */
    public List<ToolDemoRecord> list(String userId, String status, int limit) {
        return recordMapper.selectByUserId(userId, status, limit);
    }

    /** 按标题定位记录：最多取 2 条用于判断是否重名，由调用方决定是否拒绝 */
    public List<ToolDemoRecord> findByTitle(String userId, String title) {
        return recordMapper.selectByTitle(userId, title, 2);
    }

    /** 演示数据播种：同一用户已有数据时不再重复写入 */
    @Transactional
    public int seed(String userId) {
        if (recordMapper.countByUserId(userId) > 0) {
            return 0;
        }
        List<String> titles = List.of(
                "演示任务 A：核对账单",
                "演示任务 B：回复客户工单",
                "演示任务 C：整理周报");
        int created = 0;
        for (String title : titles) {
            ToolDemoRecord record = new ToolDemoRecord();
            record.setUserId(userId);
            record.setTitle(title);
            record.setContent("这是一条用于演示读/写工具的模拟数据。");
            record.setStatus(ToolDemoRecord.STATUS_OPEN);
            created += recordMapper.insert(record);
        }
        log.info("[demo-record] seeded, user={}, count={}", userId, created);
        return created;
    }

    /**
     * 写工具：把记录标记为完成。
     *
     * <p>{@code executionId} 是幂等键（当前等于 confirmationId）。同一键重复调用：
     * 已成功 → 返回上次结果；执行中 → 提示稍后；参数不同 → 拒绝。
     */
    @Transactional(rollbackFor = Exception.class)
    public CompleteOutcome complete(String userId, String conversationId, String executionId,
                                    String tool, long recordId) {
        ToolExecution execution = new ToolExecution();
        execution.setExecutionId(executionId);
        execution.setUserId(userId);
        execution.setConversationId(conversationId);
        execution.setTool(tool);
        execution.setArgsHash(argsHash(recordId));

        ToolExecutionService.BeginResult begin = executionService.begin(execution);
        switch (begin.status()) {
            case REPLAY:
                return CompleteOutcome.ok(begin.execution().getResult(), true);
            case IN_PROGRESS:
                return CompleteOutcome.failure("IN_PROGRESS", "该操作正在执行中，请稍后查询结果");
            case CONFLICT:
                return CompleteOutcome.failure("IDEMPOTENCY_CONFLICT",
                        "同一确认标识对应了不同的操作参数，已拒绝执行");
            case ACQUIRED:
                break;
            default:
                return CompleteOutcome.failure("IDEMPOTENCY_ERROR", "幂等校验失败");
        }

        ToolDemoRecord record = recordMapper.selectById(recordId, userId);
        if (record == null) {
            executionService.fail(userId, executionId, "NOT_FOUND", "记录不存在或无权操作");
            return CompleteOutcome.failure("NOT_FOUND", "记录不存在或无权操作");
        }
        if (ToolDemoRecord.STATUS_DONE.equals(record.getStatus())) {
            String result = "记录 " + recordId + " 已经是完成状态，未重复修改";
            executionService.succeed(userId, executionId, result);
            return CompleteOutcome.ok(result, true);
        }
        if (recordMapper.markDone(recordId, userId) == 0) {
            // 并发下可能被另一条确认先完成，按"已完成"处理，保持业务幂等
            ToolDemoRecord latest = recordMapper.selectById(recordId, userId);
            if (latest == null || !ToolDemoRecord.STATUS_DONE.equals(latest.getStatus())) {
                executionService.fail(userId, executionId, "UPDATE_FAILED", "记录状态更新失败");
                return CompleteOutcome.failure("UPDATE_FAILED", "记录状态更新失败");
            }
        }
        String result = "已将记录 " + recordId + "（" + record.getTitle() + "）标记为完成";
        executionService.succeed(userId, executionId, result);
        log.info("[demo-record] completed, user={}, recordId={}, executionTail={}",
                userId, recordId, idTail(executionId));
        return CompleteOutcome.ok(result, false);
    }

    /** 参数指纹：同一幂等键换了 recordId 必须被识别出来 */
    private String argsHash(long recordId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    ("recordId=" + recordId).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return "recordId=" + recordId;
        }
    }

    private String idTail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}
