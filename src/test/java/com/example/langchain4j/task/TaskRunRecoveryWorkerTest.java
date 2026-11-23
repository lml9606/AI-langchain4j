package com.example.langchain4j.task;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.task.nodes.TaskAnswerNode;
import com.example.langchain4j.repository.entity.Message;
import com.example.langchain4j.repository.entity.TaskRun;
import com.example.langchain4j.service.ConversationService;
import com.example.langchain4j.service.TaskRunService;
import com.example.langchain4j.tool.runtime.TaskRunExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 恢复 worker：业务终态后必须幂等补偿会话助手消息。 */
@ExtendWith(MockitoExtension.class)
class TaskRunRecoveryWorkerTest {

    @Mock
    private TaskRunService runService;
    @Mock
    private TaskRunExecutor runExecutor;
    @Mock
    private TaskAnswerNode taskAnswerNode;
    @Mock
    private ConversationService conversationService;

    private TaskRunRecoveryWorker worker;

    @BeforeEach
    void setUp() {
        worker = new TaskRunRecoveryWorker(runService, runExecutor, new AgentProperties(),
                taskAnswerNode, conversationService);
    }

    @Test
    @DisplayName("终态未回复 -> 用任务结果补发助手消息并标记 reply_saved")
    void compensationSavesReplyOnce() {
        TaskRun run = run();
        when(runService.findLeaseExpired(anyInt())).thenReturn(List.of());
        when(runService.findUnreplied(anyInt())).thenReturn(List.of(run));
        when(runExecutor.describe("u1", "r1")).thenReturn(result(run));
        when(conversationService.latestMessage("c1")).thenReturn(
                message(Message.ROLE_USER, "确认执行：完成记录"));

        worker.recover();

        verify(conversationService).saveMessage(eq("c1"), eq(Message.ROLE_ASSISTANT),
                eq("业务已完成"), anyString(), isNull(), isNull(), eq("task-reply:r1"));
        verify(runService).saveResult("u1", "r1", "业务已完成");
        verify(runService).markReplySaved("u1", "r1");
    }

    @Test
    @DisplayName("会话最后一条已是助手消息 -> 不再重复补发，只标记完成")
    void skipDuplicatedReply() {
        TaskRun run = run();
        when(runService.findLeaseExpired(anyInt())).thenReturn(List.of());
        when(runService.findUnreplied(anyInt())).thenReturn(List.of(run));
        when(runExecutor.describe("u1", "r1")).thenReturn(result(run));
        when(conversationService.latestMessage("c1")).thenReturn(
                message(Message.ROLE_ASSISTANT, "业务已完成"));

        worker.recover();

        verify(conversationService, never()).saveMessage(anyString(), anyString(),
                anyString(), anyString(), any(), any(), anyString());
        verify(runService).markReplySaved("u1", "r1");
    }

    private TaskRun run() {
        TaskRun run = new TaskRun();
        run.setId("r1");
        run.setUserId("u1");
        run.setConversationId("c1");
        run.setWorkflowId("wf");
        run.setStatus(TaskRun.Status.SUCCEEDED);
        run.setResult("业务已完成");
        run.setReplySaved(false);
        return run;
    }

    private TaskRunExecutor.TaskRunResult result(TaskRun run) {
        return new TaskRunExecutor.TaskRunResult(run, List.of(), List.of(), List.of(), false);
    }

    private Message message(String role, String content) {
        Message message = new Message();
        message.setRole(role);
        message.setContent(content);
        return message;
    }
}
