package com.example.langchain4j.common.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 有界调用模板：提交任务、等待超时、取消、异常归一。
 *
 * <p>工具执行、模型调用和图执行都需要同一套语义：
 * <ul>
 *   <li>任务提交到指定线程池，保留线程池的 TaskDecorator（traceId / 用户身份透传）；</li>
 *   <li>等待超过预算后 {@code cancel(true)}，尽量中断工作线程；</li>
 *   <li>取消失败说明任务刚好完成，再尝试取回真实结果，避免误报超时；</li>
 *   <li>不向调用方抛裸异常，统一返回 {@link Outcome}，由调用方翻译成自己的结果类型。</li>
 * </ul>
 *
 * <p>本类不记录日志、不做审计、不做重试：这些属于调用方语义，放在这里会让工具、
 * 模型和图的审计口径混在一起。
 */
public final class BoundedCall {

    private BoundedCall() {
    }

    /** 调用结果状态。REJECTED 表示线程池队列已满，任务从未开始执行。 */
    public enum Status {
        SUCCESS,
        TIMEOUT,
        REJECTED,
        INTERRUPTED,
        FAILED
    }

    /**
     * 一次有界调用的结果。
     *
     * @param status    结果状态
     * @param value     成功时的返回值，允许为 null
     * @param error     失败原因，成功时为 null
     * @param cancelled 是否成功取消了任务
     * @param timedOut  是否曾经达到超时边界；为 true 且 success 时表示“超时后取回了晚到结果”
     * @param elapsedMs 从提交/开始等待到返回结果的墙钟耗时
     */
    public record Outcome<T>(Status status,
                             T value,
                             Throwable error,
                             boolean cancelled,
                             boolean timedOut,
                             long elapsedMs) {

        public boolean success() {
            return status == Status.SUCCESS;
        }
    }

    /**
     * 提交任务并等待结果。
     *
     * @param callable  实际工作
     * @param executor  执行线程池；必须支持 execute(Runnable)
     * @param timeoutMs 等待预算，非正数按 1ms 处理
     */
    public static <T> Outcome<T> execute(Callable<T> callable, Executor executor, long timeoutMs) {
        long start = System.currentTimeMillis();
        FutureTask<T> task = new FutureTask<>(callable);
        try {
            executor.execute(task);
        } catch (RejectedExecutionException e) {
            return new Outcome<>(Status.REJECTED, null, e, false, false, elapsed(start));
        } catch (RuntimeException e) {
            return new Outcome<>(Status.FAILED, null, e, false, false, elapsed(start));
        }
        return await(task, timeoutMs, start);
    }

    /**
     * 等待一个已经提交的 Future。
     *
     * <p>适用于“提交和等待分离”的调用方，例如图执行需要先 submit 再等待；
     * 语义与 {@link #execute(Callable, Executor, long)} 完全一致。
     */
    public static <T> Outcome<T> await(Future<T> future, long timeoutMs) {
        return await(future, timeoutMs, System.currentTimeMillis());
    }

    private static <T> Outcome<T> await(Future<T> future, long timeoutMs, long start) {
        try {
            T value = future.get(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            return new Outcome<>(Status.SUCCESS, value, null, false, false, elapsed(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            boolean cancelled = future.cancel(true);
            return new Outcome<>(Status.INTERRUPTED, null, e, cancelled, false, elapsed(start));
        } catch (TimeoutException e) {
            boolean cancelled = future.cancel(true);
            // 任务可能恰好在超时边界完成：cancel=false 时尝试取回真实结果，避免误报超时。
            if (!cancelled) {
                try {
                    T value = future.get(0, TimeUnit.MILLISECONDS);
                    return new Outcome<>(Status.SUCCESS, value, null, false, true, elapsed(start));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return new Outcome<>(Status.INTERRUPTED, null, interrupted, false, true, elapsed(start));
                } catch (ExecutionException failed) {
                    return new Outcome<>(Status.FAILED, null, causeOf(failed), false, true, elapsed(start));
                } catch (Exception ignored) {
                    // 取消失败但结果仍未就绪，或已被其他线程取消，统一按超时处理。
                }
            }
            return new Outcome<>(Status.TIMEOUT, null, e, cancelled, true, elapsed(start));
        } catch (ExecutionException e) {
            return new Outcome<>(Status.FAILED, null, causeOf(e), false, false, elapsed(start));
        } catch (RuntimeException e) {
            return new Outcome<>(Status.FAILED, null, e, false, false, elapsed(start));
        }
    }

    private static Throwable causeOf(ExecutionException e) {
        return e.getCause() == null ? e : e.getCause();
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
