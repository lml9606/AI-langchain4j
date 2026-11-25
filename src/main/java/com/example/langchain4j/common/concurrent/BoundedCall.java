package com.example.langchain4j.common.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/**
 * 有界调用模板：提交任务、等待超时、取消、异常归一。
 *
 * 工具执行、模型调用和图执行都需要同一套语义：任务提交到指定线程池并保留
 * TaskDecorator（traceId、用户身份透传）；等待超过预算后调用 cancel(true)
 * 尽量中断工作线程并统一返回 TIMEOUT；不向调用方抛裸异常，而是返回 Outcome，
 * 由调用方翻译成自己的结果类型。
 *
 * 本类不记录日志、不做审计、不做重试。这些属于调用方语义，放在这里会让工具、
 * 模型和图的审计口径混在一起。
 */
public final class BoundedCall {

    private BoundedCall() {
    }

    /**
     * 提交任务并等待结果，返回统一 Outcome。
     *
     * 提交被线程池拒绝时返回 REJECTED；执行异常返回 FAILED；等待超时返回 TIMEOUT 并请求取消。
     * cancel(true) 只是发出中断请求，不保证工作线程已经停止，调用方仍应通过台账或业务幂等
     * 处理可能已经发生的副作用。
     *
     * @param callable  实际工作
     * @param executor  执行线程池；必须支持 execute(Runnable)
     * @param timeoutMs 等待预算，非正数按 1ms 处理
     * @return 包含状态、值、异常、取消请求结果和耗时的 Outcome
     */
    public static <T> Outcome<T> execute(Callable<T> callable, Executor executor, long timeoutMs) {
        long start = System.currentTimeMillis();
        FutureTask<T> task = new FutureTask<>(callable);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            return new Outcome<>(Status.REJECTED, null, e, false, elapsed(start));
        } catch (RuntimeException e) {
            return new Outcome<>(Status.FAILED, null, e, false, elapsed(start));
        }
        return await(task, timeoutMs);
    }

    /**
     * 等待一个已经提交的 Future。
     *
     * 适用于“提交和等待分离”的调用方，例如图执行需要先 submit 再等待。
     * 超时后请求取消并返回 TIMEOUT，不会再等待或读取后续结果；业务补偿由调用方处理。
     *
     * @param future    已提交任务
     * @param timeoutMs 等待预算，非正数按 1ms 处理
     * @return 任务成功、超时、中断或失败后的统一结果；不会抛出 Future 的原始异常
     */
    public static <T> Outcome<T> await(Future<T> future, long timeoutMs) {
        long start = System.currentTimeMillis();
        try {
            T value = future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            return new Outcome<>(Status.SUCCESS, value, null, false, elapsed(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            boolean cancelled = future.cancel(true);
            return new Outcome<>(Status.INTERRUPTED, null, e, cancelled, elapsed(start));
        } catch (TimeoutException e) {
            boolean cancelled = future.cancel(true);
            return new Outcome<>(Status.TIMEOUT, null, e, cancelled, elapsed(start));
        } catch (ExecutionException e) {
            return new Outcome<>(Status.FAILED, null, causeOf(e), false, elapsed(start));
        } catch (RuntimeException e) {
            return new Outcome<>(Status.FAILED, null, e, false, elapsed(start));
        }
    }

    private static Throwable causeOf(ExecutionException e) {
        return e.getCause() == null ? e : e.getCause();
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    /**
     * 调用结果状态。REJECTED 表示线程池队列已满，任务从未开始执行。
     */
    public enum Status {
        SUCCESS, TIMEOUT, REJECTED, INTERRUPTED, FAILED
    }

    /**
     * 一次有界调用的结果。
     *
     * @param status    结果状态
     * @param value     成功时的返回值，允许为 null
     * @param error     失败原因，成功时为 null
     * @param cancelled 是否成功把任务标记为取消；不代表工作线程已经响应中断或停止
     * @param elapsedMs 从提交/开始等待到返回结果的墙钟耗时
     */
    public record Outcome<T>(Status status, T value, Throwable error, boolean cancelled,
                             long elapsedMs) {

        public boolean success() {
            return status == Status.SUCCESS;
        }
    }
}
