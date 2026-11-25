package com.example.langchain4j.tool.demo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 工具演示业务服务：读工具的查询源 + 写工具的幂等业务写。
 *
 * 写路径只负责业务写入；幂等台账由 {@code WriteExecutionTemplate} 统一包裹：
 * 业务服务不再依赖 confirmationId，也不直接操作执行台账。
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

    public DemoRecordService(ToolDemoRecordMapper recordMapper) {
        this.recordMapper = recordMapper;
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
     * 幂等重放在 {@code WriteExecutionTemplate} 完成；本方法只保证业务状态幂等：
     * 记录已经是 DONE 时不再重复修改。
     */
    @Transactional(rollbackFor = Exception.class)
    public CompleteOutcome complete(String userId, long recordId) {
        ToolDemoRecord record = recordMapper.selectById(recordId, userId);
        if (record == null) {
            return CompleteOutcome.failure("NOT_FOUND", "记录不存在或无权操作");
        }
        if (ToolDemoRecord.STATUS_DONE.equals(record.getStatus())) {
            String result = "记录 " + recordId + " 已经是完成状态，未重复修改";
            return CompleteOutcome.ok(result, true);
        }
        if (recordMapper.markDone(recordId, userId) == 0) {
            // 并发下可能被另一条确认先完成，按"已完成"处理，保持业务幂等
            ToolDemoRecord latest = recordMapper.selectById(recordId, userId);
            if (latest == null || !ToolDemoRecord.STATUS_DONE.equals(latest.getStatus())) {
                return CompleteOutcome.failure("UPDATE_FAILED", "记录状态更新失败");
            }
        }
        String result = "已将记录 " + recordId + "（" + record.getTitle() + "）标记为完成";
        log.info("[demo-record] completed, user={}, recordId={}", userId, recordId);
        return CompleteOutcome.ok(result, false);
    }
}
