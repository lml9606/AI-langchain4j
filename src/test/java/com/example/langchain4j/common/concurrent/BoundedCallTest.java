package com.example.langchain4j.common.concurrent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;

/** 有界调用模板单测：成功、超时、线程池拒绝和异常解包。 */
class BoundedCallTest {

    @Test
    @DisplayName("正常返回 -> SUCCESS，保留返回值")
    void returnValue() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            BoundedCall.Outcome<Integer> outcome =
                    BoundedCall.execute(() -> 42, executor, 1_000);

            assertThat(outcome.status()).isEqualTo(BoundedCall.Status.SUCCESS);
            assertThat(outcome.value()).isEqualTo(42);
            assertThat(outcome.timedOut()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("超过预算 -> TIMEOUT，并尝试取消任务")
    void timeoutCancelsTask() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            BoundedCall.Outcome<String> outcome = BoundedCall.execute(() -> {
                Thread.sleep(500);
                return "late";
            }, executor, 30);

            assertThat(outcome.status()).isEqualTo(BoundedCall.Status.TIMEOUT);
            assertThat(outcome.cancelled()).isTrue();
            assertThat(outcome.timedOut()).isTrue();
            assertThat(outcome.elapsedMs()).isLessThan(500);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("线程池拒绝 -> REJECTED，任务从未开始")
    void rejectedTask() {
        BoundedCall.Outcome<String> outcome = BoundedCall.execute(() -> "never",
                task -> {
                    throw new RejectedExecutionException("queue full");
                }, 1_000);

        assertThat(outcome.status()).isEqualTo(BoundedCall.Status.REJECTED);
        assertThat(outcome.error()).isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @DisplayName("任务异常 -> FAILED，取原始 cause")
    void failedTaskKeepsCause() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            BoundedCall.Outcome<String> outcome = BoundedCall.execute(() -> {
                throw new IllegalStateException("boom");
            }, executor, 1_000);

            assertThat(outcome.status()).isEqualTo(BoundedCall.Status.FAILED);
            assertThat(outcome.error()).isInstanceOf(IllegalStateException.class);
            assertThat(outcome.error()).hasMessage("boom");
        } finally {
            executor.shutdownNow();
        }
    }
}
