package com.example.motro.targetselection.frame;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一次异步取帧的句柄：<b>保证结果只交付一次</b>，并允许调用方放弃这次请求。
 *
 * <p>为什么需要它：异步取帧存在“取消”与“完成”同时发生的竞态。没有单一的仲裁点，
 * 就可能出现回调被触发两次、或者迟到帧无人回收。本类把仲裁收敛到一个 CAS：
 * {@link #cancel()} 与 {@link #finish()} 抢同一个状态位，赢家才负责后续动作。</p>
 *
 * <p>状态迁移（单向，不可逆）：</p>
 * <pre>
 *   PENDING ──finish()──▶ COMPLETED   （正常交付，回调会收到真实结果）
 *   PENDING ──cancel()──▶ CANCELLED   （回调会收到 CANCELLED，恰好一次）
 * </pre>
 *
 * <p>线程约定：所有方法线程安全，可从任意线程调用。{@link #create} 传入的取消动作
 * 在**调用 {@link #cancel()} 的那个线程**上执行，因此它必须是快而短的投递动作，
 * 不要在里面做阻塞 I/O。</p>
 *
 * <p>{@link #create} 与 {@link #finish()} 面向 {@link VideoFrameSource} 实现方；
 * 业务调用方只使用 {@link #cancel()} / {@link #isCancelled()} / {@link #isDone()}。</p>
 */
public final class FrameCaptureHandle {

    private static final int PENDING = 0;
    private static final int COMPLETED = 1;
    private static final int CANCELLED = 2;

    private final AtomicInteger state = new AtomicInteger(PENDING);
    private final Runnable onCancel;

    private FrameCaptureHandle(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    /**
     * 创建一个待完成的句柄。
     *
     * @param onCancel 首次取消成功时执行的动作（用于投递 CANCELLED 结果、释放迟到帧），可为 null
     */
    public static FrameCaptureHandle create(Runnable onCancel) {
        return new FrameCaptureHandle(onCancel);
    }

    /**
     * 放弃本次请求。
     *
     * <p>已经取消或已经完成后返回 false（幂等），此时真实结果仍会按原样交付 ——
     * 也就是说“取消”只在结果尚未产出时生效，不会把已完成的成功结果吞掉。</p>
     *
     * @return true = 本次调用完成了状态迁移（调用方由此知其取消生效）
     */
    public boolean cancel() {
        if (!state.compareAndSet(PENDING, CANCELLED)) {
            return false;
        }
        if (onCancel != null) {
            onCancel.run();
        }
        return true;
    }

    /**
     * 抢占结果的交付权，供实现方在取帧结束时调用。
     *
     * @return true = 抢到了，调用方负责把结果交给回调；false = 已被取消或已交付，
     *         调用方必须自行回收结果里的资源（参见 {@link FrameCaptureResult#release()}）
     */
    public boolean finish() {
        return state.compareAndSet(PENDING, COMPLETED);
    }

    /** 是否已被取消（此时不会再交付真实结果）。 */
    public boolean isCancelled() {
        return state.get() == CANCELLED;
    }

    /** 是否已有结局（完成或取消）。 */
    public boolean isDone() {
        return state.get() != PENDING;
    }

    @Override
    public String toString() {
        int current = state.get();
        String name = current == PENDING ? "PENDING" : (current == COMPLETED ? "COMPLETED" : "CANCELLED");
        return "FrameCaptureHandle{" + name + "}";
    }
}
