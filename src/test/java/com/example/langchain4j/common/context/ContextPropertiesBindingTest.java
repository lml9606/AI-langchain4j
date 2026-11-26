package com.example.langchain4j.common.context;

import com.example.langchain4j.config.AgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 预算配置绑定单测：验证 application.properties 里的枚举键能正确绑定。
 *
 * 这类配置用 Map<枚举, 值> 承载，写错大小写或层级时不会在编译期报错，
 * 只会在启动时静默变成空 Map，所以必须有一条测试盯着。
 */
class ContextPropertiesBindingTest {

    /** 场景名和块名都按枚举绑定，权重、保底、总开关都要落到对象上。 */
    @Test
    void bindsSceneProfilesFromProperties() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.context.default-total-tokens", "9000")
                .withProperty("agent.context.question-last", "false")
                .withProperty("agent.context.scenes.GENERATE.total-tokens", "32000")
                .withProperty("agent.context.scenes.GENERATE.minimums.RETRIEVAL", "6000")
                .withProperty("agent.context.scenes.GENERATE.weights.RETRIEVAL", "3")
                .withProperty("agent.context.scenes.REWRITE.total-tokens", "4000");

        AgentProperties.Context context = Binder.get(environment)
                .bind("agent.context", Bindable.of(AgentProperties.Context.class))
                .orElseThrow(IllegalStateException::new);

        assertThat(context.isQuestionLast()).isFalse();
        assertThat(context.getDefaultTotalTokens()).isEqualTo(9000);
        assertThat(context.getScenes()).containsKeys(ContextScene.GENERATE, ContextScene.REWRITE);

        AgentProperties.Context.Scene generate = context.getScenes().get(ContextScene.GENERATE);
        assertThat(generate.getTotalTokens()).isEqualTo(32000);
        assertThat(generate.getMinimums()).containsEntry(BlockType.RETRIEVAL, 6000);
        assertThat(generate.getWeights()).containsEntry(BlockType.RETRIEVAL, 3);
    }
}
