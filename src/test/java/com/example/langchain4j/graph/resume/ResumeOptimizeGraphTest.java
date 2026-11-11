package com.example.langchain4j.graph.resume;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.resume.nodes.ResumeDiffNode;
import com.example.langchain4j.graph.resume.nodes.ResumeEntryNode;
import com.example.langchain4j.graph.resume.nodes.ResumeFallbackNode;
import com.example.langchain4j.graph.resume.nodes.ResumeFinalizeNode;
import com.example.langchain4j.graph.resume.nodes.ResumeGenerateNode;
import com.example.langchain4j.graph.resume.nodes.ResumeTargetProfileNode;
import com.example.langchain4j.graph.resume.nodes.ResumeValidateNode;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 图拓扑测试：用 stub 节点验证三条路径——成功、校验失败后重试成功、重试超限兜底。
 * 不调真实 LLM，也不依赖 Spring 容器。
 */
@ExtendWith(MockitoExtension.class)
class ResumeOptimizeGraphTest {

    @Mock
    private ResumeEntryNode entryNode;
    @Mock
    private ResumeTargetProfileNode targetNode;
    @Mock
    private ResumeGenerateNode generateNode;
    @Mock
    private ResumeValidateNode validateNode;
    @Mock
    private ResumeDiffNode diffNode;
    @Mock
    private ResumeFinalizeNode finalizeNode;
    @Mock
    private ResumeFallbackNode fallbackNode;

    @Test
    @DisplayName("成功路径：entry → target → generate → validate → diff → finalize")
    void happyPath() {
        stubCommonNodes();
        stubDiff();
        stubFinalize();
        when(validateNode.apply(any())).thenReturn(Map.of(
                ResumeOptimizeState.VALIDATION, ResumeOptimizeState.Validation.of(true, "", ""),
                ResumeOptimizeState.ERROR, ""));

        ResumeOptimizeState state = invoke(buildGraph(2));

        assertThat(state.done()).isTrue();
        verify(generateNode, times(1)).apply(any());
        verify(diffNode, times(1)).apply(any());
        verify(fallbackNode, never()).apply(any());
    }

    @Test
    @DisplayName("校验失败一次后重试成功：generate 调用两次")
    void retryThenSuccess() {
        stubCommonNodes();
        stubDiff();
        stubFinalize();
        when(validateNode.apply(any())).thenReturn(
                Map.of(ResumeOptimizeState.VALIDATION,
                                ResumeOptimizeState.Validation.of(false, "FACT", "手机号丢失"),
                        ResumeOptimizeState.RETRY_COUNT, 1),
                Map.of(ResumeOptimizeState.VALIDATION,
                                ResumeOptimizeState.Validation.of(true, "", ""),
                        ResumeOptimizeState.ERROR, ""));

        ResumeOptimizeState state = invoke(buildGraph(2));

        assertThat(state.done()).isTrue();
        verify(generateNode, times(2)).apply(any());
        verify(diffNode, times(1)).apply(any());
        verify(fallbackNode, never()).apply(any());
    }

    @Test
    @DisplayName("校验连续失败到上限：走兜底，不产出 diff")
    void fallbackWhenRetryExhausted() {
        stubCommonNodes();
        stubFinalize();
        stubFallback();
        when(validateNode.apply(any())).thenReturn(Map.of(
                ResumeOptimizeState.VALIDATION,
                ResumeOptimizeState.Validation.of(false, "STRUCTURE", "章节丢失"),
                ResumeOptimizeState.RETRY_COUNT, 1));

        ResumeOptimizeState state = invoke(buildGraph(1));

        assertThat(state.fallback()).isTrue();
        verify(fallbackNode, times(1)).apply(any());
        verify(diffNode, never()).apply(any());
    }

    private void stubCommonNodes() {
        when(entryNode.apply(any())).thenReturn(Map.of(
                ResumeOptimizeState.RESUME_MD, "## 工作经历\nJava 开发",
                ResumeOptimizeState.RETRY_COUNT, 0,
                ResumeOptimizeState.ERROR, ""));
        when(targetNode.apply(any())).thenReturn(Map.of(
                ResumeOptimizeState.TARGET_BLOCK, "### 目标岗位 1",
                ResumeOptimizeState.ERROR, ""));
        when(generateNode.apply(any())).thenReturn(Map.of(
                ResumeOptimizeState.REVISED_MD, "## 工作经历\nJava 开发，负责订单系统",
                ResumeOptimizeState.CHANGES, List.of(),
                ResumeOptimizeState.ERROR, ""));
    }

    private void stubDiff() {
        when(diffNode.apply(any())).thenReturn(Map.of(ResumeOptimizeState.DIFF, List.of()));
    }

    private void stubFinalize() {
        when(finalizeNode.apply(any())).thenReturn(Map.of(ResumeOptimizeState.DONE, true));
    }

    private void stubFallback() {
        when(fallbackNode.apply(any())).thenReturn(Map.of(
                ResumeOptimizeState.FALLBACK, true,
                ResumeOptimizeState.ERROR, "failed",
                ResumeOptimizeState.DONE, true));
    }

    private CompiledGraph<ResumeOptimizeState> buildGraph(int maxRetries) {
        AgentProperties properties = new AgentProperties();
        properties.getResume().setMaxRetries(maxRetries);
        ResumeOptimizeRouter router = new ResumeOptimizeRouter(properties);
        ResumeOptimizeGraphBuilder builder = new ResumeOptimizeGraphBuilder(
                new ResumeOptimizeStateSerializer(), router, entryNode, targetNode,
                generateNode, validateNode, diffNode, finalizeNode, fallbackNode, properties);
        return builder.build(new MemorySaver());
    }

    private ResumeOptimizeState invoke(CompiledGraph<ResumeOptimizeState> graph) {
        Map<String, Object> inputs = Map.of(
                ResumeOptimizeState.RESUME_RAW, "## 工作经历\nJava 开发",
                ResumeOptimizeState.MODE, "TARGETED",
                ResumeOptimizeState.TARGET_JOBS, List.of(),
                ResumeOptimizeState.SELECTED_SECTIONS, List.of(),
                ResumeOptimizeState.OPTIMIZATION_PROMPT, "",
                ResumeOptimizeState.MODEL, "test-model");
        RunnableConfig config = RunnableConfig.builder()
                .threadId("test-" + UUID.randomUUID()).build();
        return graph.invoke(inputs, config).orElseThrow();
    }
}
