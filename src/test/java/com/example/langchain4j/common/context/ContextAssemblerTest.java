package com.example.langchain4j.common.context;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.config.AgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上下文装配器单测：只验证预算算法，不调用模型、不依赖 Spring。
 * 用例通过真实的 AgentProperties 构造装配器，和线上装配路径保持一致。
 * 内容用中文字符构造，TokenEstimator 对中文按 1 字符 1 token 估算，
 * 所以 token 数等于字符数，断言可读且稳定。
 */
class ContextAssemblerTest {

    /** 生成链路五块齐全：总量不超预算，各块都拿到内容。 */
    @Test
    void allocatesAllBlocksWithinBudget() {
        ContextAssembler assembler = assembler(ContextScene.GENERATE, 32000,
                Map.of(BlockType.RETRIEVAL, 3, BlockType.SUMMARY, 1, BlockType.MEMORY, 1),
                Map.of(BlockType.RETRIEVAL, 6000, BlockType.SUMMARY, 800, BlockType.MEMORY, 400));

        Allocation allocation = assembler.allocate(ContextScene.GENERATE, List.of(
                block(BlockType.SYSTEM, 1200),
                block(BlockType.QUERY, 50),
                block(BlockType.RETRIEVAL, 6000),
                block(BlockType.SUMMARY, 800),
                block(BlockType.MEMORY, 400),
                ContextBlock.of(BlockType.HISTORY, List.of(
                        repeated('甲', 700), repeated('乙', 700), repeated('丙', 600)))));

        assertThat(allocation.totalUsed()).isLessThanOrEqualTo(allocation.totalBudget());
        assertThat(allocation.has(BlockType.SYSTEM)).isTrue();
        assertThat(allocation.has(BlockType.RETRIEVAL)).isTrue();
        assertThat(allocation.segments(BlockType.HISTORY)).hasSize(3);
        assertThat(allocation.logLine()).contains("scene=GENERATE");
    }

    /** 强制块自身超出预算时直接失败，而不是静默截断 system。 */
    @Test
    void rejectsWhenMandatoryBlocksExceedBudget() {
        ContextAssembler assembler = assembler(ContextScene.GENERATE, 1000, Map.of(), Map.of());

        assertThatThrownBy(() -> assembler.allocate(ContextScene.GENERATE, List.of(
                block(BlockType.SYSTEM, 2000))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("输入内容过长");
    }

    /** 保底之和超预算时按固定顺序降级：记忆先被清空，摘要和检索保留。 */
    @Test
    void degradesMemoryBeforeOtherBlocks() {
        ContextAssembler assembler = assembler(ContextScene.GENERATE, 5000, Map.of(),
                Map.of(BlockType.RETRIEVAL, 3000,
                        BlockType.SUMMARY, 3000,
                        BlockType.MEMORY, 2000));

        Allocation allocation = assembler.allocate(ContextScene.GENERATE, List.of(
                block(BlockType.SYSTEM, 100),
                block(BlockType.RETRIEVAL, 3000),
                block(BlockType.SUMMARY, 3000),
                block(BlockType.MEMORY, 2000)));

        assertThat(allocation.has(BlockType.MEMORY)).isFalse();
        assertThat(allocation.has(BlockType.RETRIEVAL)).isTrue();
        assertThat(allocation.has(BlockType.SUMMARY)).isTrue();
        assertThat(allocation.totalUsed()).isLessThanOrEqualTo(5000);
    }

    /** 权重高的块在预算富余时拿到更多内容。 */
    @Test
    void higherWeightGetsMoreBudget() {
        ContextAssembler assembler = assembler(ContextScene.GENERATE, 1000,
                Map.of(BlockType.RETRIEVAL, 3, BlockType.SUMMARY, 1), Map.of());

        Allocation allocation = assembler.allocate(ContextScene.GENERATE, List.of(
                block(BlockType.SYSTEM, 100),
                block(BlockType.RETRIEVAL, 1000),
                block(BlockType.SUMMARY, 1000)));

        assertThat(allocation.usedTokens().get(BlockType.RETRIEVAL))
                .isGreaterThan(allocation.usedTokens().get(BlockType.SUMMARY));
    }

    /** 内容少于保底的块只占实际大小，腾出的预算回流给其他块。 */
    @Test
    void smallBlockOnlyConsumesWhatItHas() {
        ContextAssembler assembler = assembler(ContextScene.GENERATE, 1000, Map.of(),
                Map.of(BlockType.SUMMARY, 800, BlockType.RETRIEVAL, 100));

        Allocation allocation = assembler.allocate(ContextScene.GENERATE, List.of(
                block(BlockType.SYSTEM, 100),
                block(BlockType.SUMMARY, 10),
                block(BlockType.RETRIEVAL, 1000)));

        assertThat(allocation.usedTokens().get(BlockType.SUMMARY)).isEqualTo(10);
        assertThat(allocation.usedTokens().get(BlockType.RETRIEVAL)).isGreaterThan(800);
    }

    /** 历史超预算时保留最新的对话，丢弃最旧的整条消息。 */
    @Test
    void historyKeepsNewestMessages() {
        ContextAssembler assembler = assembler(ContextScene.GENERATE, 1000, Map.of(), Map.of());

        Allocation allocation = assembler.allocate(ContextScene.GENERATE, List.of(
                block(BlockType.SYSTEM, 100),
                ContextBlock.of(BlockType.HISTORY, List.of(
                        repeated('甲', 400), repeated('乙', 400), repeated('丙', 400)))));

        List<String> kept = allocation.segments(BlockType.HISTORY);
        assertThat(kept).hasSize(2);
        assertThat(kept.get(kept.size() - 1)).startsWith("丙");
        assertThat(kept.get(0)).startsWith("乙");
    }

    /** 没有检索、摘要、记忆的场景（如查询改写）不应报错，预算落在已有块上。 */
    @Test
    void missingBlocksDoNotBreakAllocation() {
        ContextAssembler assembler = assembler(ContextScene.REWRITE, 4000, Map.of(), Map.of());

        Allocation allocation = assembler.allocate(ContextScene.REWRITE, List.of(
                block(BlockType.SYSTEM, 800),
                block(BlockType.QUERY, 50),
                ContextBlock.of(BlockType.HISTORY, List.of(
                        repeated('甲', 500), repeated('乙', 500)))));

        assertThat(allocation.has(BlockType.RETRIEVAL)).isFalse();
        assertThat(allocation.has(BlockType.HISTORY)).isTrue();
        assertThat(allocation.totalUsed()).isLessThan(4000);
    }

    /** 截断优先落在段落边界，不把一句话劈成两半。 */
    @Test
    void slicerPrefersParagraphBoundary() {
        String text = "第一段内容。\n\n第二段内容。\n\n第三段内容。";

        String sliced = TextSlicer.sliceToTokens(text, 10);

        assertThat(sliced).isEqualTo("第一段内容。");
    }

    /** 落点在未闭合代码块内时退回代码块之前，不输出半段代码。 */
    @Test
    void slicerAvoidsBreakingCodeFence() {
        String text = "前置说明。\n\n```java\n" + "int a = 1;\n".repeat(50) + "```\n\n后置说明。";

        String sliced = TextSlicer.sliceToTokens(text, 100);

        assertThat(sliced).doesNotContain("```");
        assertThat(sliced).contains("前置说明");
    }

    /** 构造装配器：只给指定场景配置档位，其余场景走默认档。 */
    private static ContextAssembler assembler(ContextScene scene, int total,
                                              Map<BlockType, Integer> weights,
                                              Map<BlockType, Integer> minimums) {
        AgentProperties properties = new AgentProperties();
        AgentProperties.Context.Scene config = new AgentProperties.Context.Scene();
        config.setTotalTokens(total);
        config.getWeights().putAll(weights);
        config.getMinimums().putAll(minimums);
        properties.getContext().getScenes().put(scene, config);
        return new ContextAssembler(properties);
    }

    /** 构造指定 token 数的块：中文按 1 字符 1 token 估算。 */
    private static ContextBlock block(BlockType type, int tokens) {
        return ContextBlock.single(type, repeated('字', tokens));
    }

    /** 生成重复字符文本，用作可控 token 数的测试内容。 */
    private static String repeated(char c, int count) {
        return String.valueOf(c).repeat(count);
    }
}
