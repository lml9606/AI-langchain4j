package com.example.langchain4j.service;

import com.example.langchain4j.tool.runtime.ConfirmationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 新消息取消旧确认：数据库先提交，Redis 令牌后删除。 */
@ExtendWith(MockitoExtension.class)
class ConfirmationCancellationServiceTest {

    @Mock
    private ConfirmationService confirmationService;
    @Mock
    private ToolExecutionService toolExecutionService;
    @Mock
    private TaskRunService taskRunService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private ConfirmationCancellationService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any()))
                .thenReturn(new SimpleTransactionStatus());
        service = new ConfirmationCancellationService(confirmationService, toolExecutionService,
                taskRunService, transactionManager);
    }

    @Test
    @DisplayName("数据库取消提交后才删除 Redis 令牌")
    void deletesTokenAfterDatabaseCommit() {
        when(confirmationService.findPendingForCancellation("c1", "demo"))
                .thenReturn(Optional.of(action()));

        service.cancelByNewMessage("c1", "demo");

        InOrder order = inOrder(toolExecutionService, taskRunService, confirmationService);
        order.verify(toolExecutionService).cancelPendingByConversation("demo", "c1");
        order.verify(taskRunService).cancelByConversation("demo", "c1");
        order.verify(confirmationService).deletePendingIfMatches("c1", "demo", "cfm_1");
        verify(transactionManager).commit(any());
    }

    @Test
    @DisplayName("数据库取消失败 -> 回滚且保留 Redis 令牌，不阻断新对话")
    void keepsTokenWhenDatabaseCancelFails() {
        when(confirmationService.findPendingForCancellation("c1", "demo"))
                .thenReturn(Optional.of(action()));
        doThrow(new RuntimeException("db down"))
                .when(taskRunService).cancelByConversation("demo", "c1");

        assertThatCode(() -> service.cancelByNewMessage("c1", "demo"))
                .doesNotThrowAnyException();

        verify(transactionManager).rollback(any());
        verify(confirmationService, never()).deletePendingIfMatches(any(), any(), any());
    }

    private ConfirmationService.PendingAction action() {
        return new ConfirmationService.PendingAction(
                "cfm_1", "demo", "c1", "model-x", "",
                "write_tool",
                List.of(new ConfirmationService.PendingWriteStep(
                        0, "write_tool", Map.of("id", 1))),
                List.of(Map.of("tool", "write_tool", "args", Map.of("id", 1))),
                "即将执行：修改业务数据。", "WRITE",
                System.currentTimeMillis() + 60_000);
    }
}
