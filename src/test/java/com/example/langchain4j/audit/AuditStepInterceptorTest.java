package com.example.langchain4j.audit;

import com.example.langchain4j.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

/** 注解审计单测：公开方法成功/异常都能被统一记录。 */
@ExtendWith(MockitoExtension.class)
class AuditStepInterceptorTest {

    @Mock
    private AuditRecorder recorder;

    private AuditStepInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new AuditStepInterceptor(recorder, new AgentProperties());
    }

    @Test
    @DisplayName("成功调用：自动解析 conversationId 并记录入参/返回值")
    void recordsSuccessfulInvocation() {
        Demo proxy = proxy(new DemoService());

        String result = proxy.run("c1", "hello");

        assertThat(result).isEqualTo("ok");
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(recorder).record(captor.capture());
        AuditEntry entry = captor.getValue();
        assertThat(entry.nodeName()).isEqualTo("demo");
        assertThat(entry.conversationId()).isEqualTo("c1");
        assertThat(entry.status()).isEqualTo("SUCCESS");
        assertThat(entry.input()).containsEntry("query", "hello");
        assertThat(entry.output()).containsEntry("result", "ok");
    }

    @Test
    @DisplayName("异常调用：记录 FAILED 后原样上抛")
    void recordsFailureAndRethrows() {
        Demo proxy = proxy(new FailingDemoService());

        assertThatThrownBy(() -> proxy.run("c1", "hello"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(recorder).record(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("FAILED");
        assertThat(captor.getValue().errorMsg()).isEqualTo("boom");
    }

    private Demo proxy(Demo target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.addAdvisor(new DefaultPointcutAdvisor(
                new AnnotationMatchingPointcut(null, AuditStep.class, true), interceptor));
        return (Demo) factory.getProxy();
    }

    private interface Demo {
        String run(String conversationId, String query);
    }

    private static class DemoService implements Demo {
        @Override
        @AuditStep("demo")
        public String run(String conversationId, String query) {
            return "ok";
        }
    }

    private static class FailingDemoService implements Demo {
        @Override
        @AuditStep("demo")
        public String run(String conversationId, String query) {
            throw new IllegalStateException("boom");
        }
    }
}
