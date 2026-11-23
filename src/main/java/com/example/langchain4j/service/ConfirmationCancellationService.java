package com.example.langchain4j.service;

import com.example.langchain4j.tool.runtime.ConfirmationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;

/**
 * 新消息到达时取消旧确认：数据库优先，Redis 令牌最后清理。
 *
 * 取消顺序固定：先读取旧令牌但不删除，拿到 confirmationId 用于最后精准删除；再在同一
 * 数据库事务内取消 ai_tool_execution、ai_task_run、ai_task_step；数据库事务提交成功后，
 * 才删除 Redis 旧令牌。
 *
 * 这样可以避免“Redis 已删、数据库没删”的不可恢复窗口：数据库取消失败时保留令牌，
 * 用户仍可在原上下文确认或取消；Redis 删除失败时数据库已是 CANCELLED，确认链路会按台账
 * 拒绝执行，令牌最迟由 TTL 清理。
 */
@Slf4j
@Service
public class ConfirmationCancellationService {

    private final ConfirmationService confirmationService;
    private final ToolExecutionService toolExecutionService;
    private final TaskRunService taskRunService;
    private final TransactionTemplate transactionTemplate;

    public ConfirmationCancellationService(ConfirmationService confirmationService,
                                           ToolExecutionService toolExecutionService,
                                           TaskRunService taskRunService,
                                           PlatformTransactionManager transactionManager) {
        this.confirmationService = confirmationService;
        this.toolExecutionService = toolExecutionService;
        this.taskRunService = taskRunService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * 新用户消息视为放弃旧确认。
     *
     * 数据库取消失败不阻断新对话，但保留旧令牌，避免制造“令牌已删、台账仍 PENDING”的
     * 孤儿状态。数据库事务提交后才尝试删除 Redis 令牌，删除失败交给 TTL 和台账状态兜底。
     */
    public void cancelByNewMessage(String conversationId, String userId) {
        if (conversationId == null || conversationId.isBlank()
                || userId == null || userId.isBlank()) {
            return;
        }
        Optional<ConfirmationService.PendingAction> pending =
                confirmationService.findPendingForCancellation(conversationId, userId);
        try {
            transactionTemplate.executeWithoutResult(status -> {
                toolExecutionService.cancelPendingByConversation(userId, conversationId);
                taskRunService.cancelByConversation(userId, conversationId);
            });
        } catch (RuntimeException e) {
            log.warn("[confirm-cancel] database cancel failed, keep redis token, conversation={}: {}",
                    conversationId, e.getMessage());
            return;
        }
        pending.ifPresent(action -> {
            confirmationService.deletePendingIfMatches(conversationId, userId,
                    action.confirmationId());
            log.info("[confirm-cancel] old confirmation cancelled after db commit, "
                            + "conversation={}, idTail={}",
                    conversationId, idTail(action.confirmationId()));
        });
    }

    private String idTail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}
