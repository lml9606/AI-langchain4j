package com.example.langchain4j.service;

import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.ResumeOptimizeRequest;
import com.example.langchain4j.dto.ResumeOptimizeResponse;
import com.example.langchain4j.dto.ResumeTargetJobDto;
import com.example.langchain4j.graph.resume.ResumeOptimizeState;
import com.example.langchain4j.guard.RateLimiter;
import com.example.langchain4j.guard.SessionLock;
import com.example.langchain4j.llm.ModelRegistry;
import com.example.langchain4j.repository.entity.GraphExecution;
import com.example.langchain4j.repository.entity.ResumeRevision;
import com.example.langchain4j.repository.entity.UserResume;
import com.example.langchain4j.repository.mapper.ResumeRevisionMapper;
import com.example.langchain4j.repository.mapper.UserResumeMapper;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 服务层测试：草稿落库 → 图执行 → 成功 markDone / 失败 markError。
 * 图本身用 mock 替代，聚焦服务层编排与状态流转。
 */
@ExtendWith(MockitoExtension.class)
class ResumeOptimizeServiceTest {

    @Mock
    private CompiledGraph<ResumeOptimizeState> graph;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private SessionLock sessionLock;
    @Mock
    private ModelRegistry modelRegistry;
    @Mock
    private AgentMetrics metrics;
    @Mock
    private AuditService auditService;
    @Mock
    private ResumeRevisionMapper resumeRevisionMapper;
    @Mock
    private UserResumeMapper userResumeMapper;
    @Mock
    private ConversationService conversationService;

    private ThreadPoolTaskExecutor executor;
    private ResumeOptimizeService service;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10);
        executor.initialize();
        service = new ResumeOptimizeService(graph, rateLimiter, sessionLock, modelRegistry,
                properties, metrics, auditService, resumeRevisionMapper, userResumeMapper,
                conversationService, executor);
        UserContext.set("alice", "USER");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        executor.shutdown();
    }

    @Test
    @DisplayName("成功：插 RUNNING 草稿 → 图执行 → markDone → 返回改稿")
    void optimizeSuccess() {
        when(modelRegistry.resolve(null)).thenReturn("test-model");
        when(sessionLock.executeWithLock(anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        when(graph.invoke(anyMap(), any(RunnableConfig.class)))
                .thenReturn(Optional.of(successState()));

        ResumeOptimizeResponse response = service.optimize(request());

        assertThat(response.revisionId()).startsWith("rv_");
        assertThat(response.revisedMarkdown()).contains("负责订单系统");
        assertThat(response.changes()).hasSize(1);
        verify(resumeRevisionMapper).insert(org.mockito.ArgumentMatchers.argThat(
                revision -> ResumeRevision.STATUS_RUNNING.equals(revision.getStatus())
                        && "alice".equals(revision.getUserId())));
        verify(resumeRevisionMapper).markDone(anyString(), anyString(), anyString(),
                anyString(), eq("test-model"), eq(120));
        verify(auditService).audit(anyString(), eq("resume_workflow"),
                eq(GraphExecution.STATUS_SUCCESS), anyString(), anyString(), isNull(), anyLong());
    }

    @Test
    @DisplayName("图返回兜底状态 → markError + 抛业务异常，不返回半份简历")
    void optimizeFallbackMarksError() {
        when(modelRegistry.resolve(null)).thenReturn("test-model");
        when(sessionLock.executeWithLock(anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());
        when(graph.invoke(anyMap(), any(RunnableConfig.class)))
                .thenReturn(Optional.of(fallbackState()));

        assertThatThrownBy(() -> service.optimize(request()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("改稿不合格");

        verify(resumeRevisionMapper).markError(anyString(), anyString());
    }

    @Test
    @DisplayName("已有 RUNNING 记录 → optimize 抛「进行中」，不落新草稿、不跑图")
    void optimizeBlockedWhenRunning() {
        when(modelRegistry.resolve(null)).thenReturn("test-model");
        when(resumeRevisionMapper.selectRunningByUser("alice")).thenReturn(runningRevision());
        when(sessionLock.executeWithLock(anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());

        assertThatThrownBy(() -> service.optimize(request()))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("简历优化进行中");

        verify(resumeRevisionMapper, never()).insert(any());
        verify(graph, never()).invoke(anyMap(), any(RunnableConfig.class));
    }

    @Test
    @DisplayName("对话触发：无简历档案 → 返回上传引导文案，不落草稿")
    void triggerAsyncWithoutProfile() {
        when(userResumeMapper.selectByUserId("alice")).thenReturn(null);

        String message = service.triggerAsync("alice", "conv-1", "test-model");

        assertThat(message).contains("上传简历");
        verify(resumeRevisionMapper, never()).insert(any());
    }

    @Test
    @DisplayName("对话触发：有档案且无进行中任务 → 落草稿 + 提交后台执行")
    void triggerAsyncWithProfile() {
        when(userResumeMapper.selectByUserId("alice")).thenReturn(profile());
        when(resumeRevisionMapper.selectRunningByUser("alice")).thenReturn(null);
        when(sessionLock.executeWithLock(anyString(), any(Supplier.class)))
                .thenAnswer(invocation -> ((Supplier<?>) invocation.getArgument(1)).get());

        String message = service.triggerAsync("alice", "conv-1", "test-model");

        assertThat(message).contains("已开始优化");
        verify(resumeRevisionMapper).insert(org.mockito.ArgumentMatchers.argThat(
                revision -> ResumeRevision.STATUS_RUNNING.equals(revision.getStatus())
                        && "conv-1".equals(revision.getConversationId())
                        && "alice".equals(revision.getUserId())));
    }

    private ResumeOptimizeRequest request() {
        ResumeOptimizeRequest request = new ResumeOptimizeRequest();
        request.setResumeText("## 工作经历\nJava 开发");
        request.setResumeTitle("Java 后端-张三");
        request.setMode("TARGETED");
        ResumeTargetJobDto target = new ResumeTargetJobDto();
        target.setJobName("Java 后端");
        target.setJd("负责订单系统");
        request.setTargets(List.of(target));
        return request;
    }

    private ResumeRevision runningRevision() {
        ResumeRevision revision = new ResumeRevision();
        revision.setRevisionId("rv_running");
        revision.setUserId("alice");
        revision.setStatus(ResumeRevision.STATUS_RUNNING);
        return revision;
    }

    private ResumeOptimizeState successState() {
        return new ResumeOptimizeState(Map.of(
                ResumeOptimizeState.REVISED_MD, "## 工作经历\nJava 开发，负责订单系统",
                ResumeOptimizeState.CHANGES, List.of(Map.of(
                        "section", "工作经历",
                        "type", "rewrote",
                        "summary", "精简",
                        "reason", "对齐 JD")),
                ResumeOptimizeState.DIFF, List.of(Map.of(
                        "tag", "replace", "left", "Java 开发", "right", "Java 开发，负责订单系统")),
                ResumeOptimizeState.TOKEN_USAGE, 120,
                ResumeOptimizeState.DONE, true));
    }

    private ResumeOptimizeState fallbackState() {
        return new ResumeOptimizeState(Map.of(
                ResumeOptimizeState.FALLBACK, true,
                ResumeOptimizeState.ERROR, "改稿不合格",
                ResumeOptimizeState.DONE, true));
    }

    private UserResume profile() {
        UserResume resume = new UserResume();
        resume.setUserId("alice");
        resume.setTitle("Java 后端-张三");
        resume.setContent("## 工作经历\nJava 开发");
        return resume;
    }
}
