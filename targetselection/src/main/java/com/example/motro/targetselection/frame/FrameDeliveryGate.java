package com.example.motro.targetselection.frame;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 同步等待 + 超时放弃的交接闸门，用于“异步回读 + 阻塞等待”的取帧实现。
 *
 * <p>要解决的问题：PixelCopy 这类异步回读会往我们提供的目标位图里写数据。
 * 如果等待方超时后直接 {@code recycle()} 那张位图，而回读稍后才完成，
 * 就会往已回收的位图上写 → 崩溃；反过来，超时后什么都不做，位图就泄漏了。
 * 本类把“谁负责回收”做成一个原子交接：</p>
 *
 * <pre>
 *   等待方：await(timeout)
 *      ├─ true  → 已完成，resultCode() 有效，资源归等待方
 *      └─ false → 调 abandon()
 *                  ├─ true  → 抢在生产者之前放弃：生产者稍后的 deliver() 会失败，
 *                  │          由**生产者**负责回收，等待方不碰资源
 *                  └─ false → 生产者刚好完成：resultCode() 有效，资源归等待方
 * </pre>
 *
 * <p>不变量：{@code deliver()} 与 {@code abandon()} 只有一个能成功；
 * 且成功方一定拿到“回收责任”。因此任何一条路径下资源都恰好被回收一次。</p>
 *
 * <p>线程约定：生产者（异步回调线程）调 {@link #deliver(int)}，等待方（工作线程）
 * 调 {@link #await(long)} / {@link #abandon()}，两侧可并发。</p>
 */
public final class FrameDeliveryGate {

    private static final int PENDING = 0;
    private static final int DELIVERED = 1;
    private static final int ABANDONED = 2;

    /** {@link #resultCode()} 在无结果时的取值。 */
    public static final int NO_RESULT = Integer.MIN_VALUE;

    private final AtomicInteger state = new AtomicInteger(PENDING);
    private final CountDownLatch latch = new CountDownLatch(1);
    private volatile int resultCode = NO_RESULT;

    /**
     * 生产者交付结果（异步回调线程调用）。
     *
     * @param resultCode 平台原始返回码
     * @return true = 首次交付成功，结果已发布并唤醒等待方；
     *         false = 等待方已放弃（或已交付过），调用方必须自行回收它所产出的资源
     */
    public boolean deliver(int resultCode) {
        if (!state.compareAndSet(PENDING, DELIVERED)) {
            return false;
        }
        // 先写值再 countDown：CountDownLatch 提供 happens-before，await 返回后必可见
        this.resultCode = resultCode;
        latch.countDown();
        return true;
    }

    /**
     * 等待方阻塞等待结果。
     *
     * @param timeoutMs 最长等待毫秒数
     * @return true = 已交付，可读取 {@link #resultCode()}；false = 超时或被中断
     * @throws InterruptedException 等待期间线程被中断（调用方应恢复中断标志并按“未完成”处理）
     */
    public boolean await(long timeoutMs) throws InterruptedException {
        return latch.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
    }

    /**
     * 等待方在超时后放弃（或中断后放弃）。
     *
     * @return true = 抢在生产者之前放弃，生产者随后会自行回收资源；
     *         false = 生产者已完成交付，结果与资源均归等待方
     */
    public boolean abandon() {
        return state.compareAndSet(PENDING, ABANDONED);
    }

    /** 是否已交付（{@link #await(long)} 返回 true 后必为 true）。 */
    public boolean isDelivered() {
        return state.get() == DELIVERED;
    }

    /** 生产者交付的原始返回码；未交付时返回 {@link #NO_RESULT}。 */
    public int resultCode() {
        return resultCode;
    }

    @Override
    public String toString() {
        int current = state.get();
        String name = current == PENDING ? "PENDING" : (current == DELIVERED ? "DELIVERED" : "ABANDONED");
        return "FrameDeliveryGate{" + name + ", resultCode=" + resultCode + "}";
    }
}
