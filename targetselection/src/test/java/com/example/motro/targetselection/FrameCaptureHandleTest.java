package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.frame.FrameCaptureHandle;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 异步句柄的“恰好一次”契约。取消与完成是并发发生的两个事件，
 * 没有单一仲裁点就会出现“回调两次”或“迟到帧无人回收”，所以这里既有顺序断言也有竞争压力测试。
 */
public class FrameCaptureHandleTest {

    @Test
    public void cancelWinsOnceAndFiresCancelAction() {
        AtomicInteger cancelActions = new AtomicInteger();
        FrameCaptureHandle handle = FrameCaptureHandle.create(cancelActions::incrementAndGet);

        assertFalse(handle.isDone());
        assertFalse(handle.isCancelled());

        assertTrue("首次取消应成功", handle.cancel());
        assertTrue(handle.isCancelled());
        assertTrue(handle.isDone());
        assertEquals(1, cancelActions.get());

        assertFalse("重复取消应失败（幂等）", handle.cancel());
        assertEquals("取消动作不得重复执行", 1, cancelActions.get());
    }

    @Test
    public void finishWinsOnceAndBlocksLaterCancel() {
        AtomicInteger cancelActions = new AtomicInteger();
        FrameCaptureHandle handle = FrameCaptureHandle.create(cancelActions::incrementAndGet);

        assertTrue(handle.finish());
        assertTrue(handle.isDone());
        assertFalse(handle.isCancelled());

        assertFalse("已完成就不能再取消", handle.cancel());
        assertEquals(0, cancelActions.get());
        assertFalse("重复 finish 应失败", handle.finish());
    }

    @Test
    public void cancelFirstBlocksFinishSoResultIsDropped() {
        FrameCaptureHandle handle = FrameCaptureHandle.create(null);
        assertTrue(handle.cancel());
        assertFalse("已取消 → finish 必须失败，结果应由实现方回收", handle.finish());
        assertTrue(handle.isCancelled());
    }

    @Test
    public void nullCancelActionIsAllowed() {
        FrameCaptureHandle handle = FrameCaptureHandle.create(null);
        assertTrue(handle.cancel());
        assertTrue(handle.isCancelled());
    }

    /** 竞争压力测试：无论怎么抖动，取消动作最多执行一次、且只有一个赢家。 */
    @Test
    public void racingCancelAndFinishHasExactlyOneWinner() throws Exception {
        final int iterations = 2000;
        for (int i = 0; i < iterations; i++) {
            final AtomicInteger cancelActions = new AtomicInteger();
            final AtomicInteger winners = new AtomicInteger();
            final FrameCaptureHandle handle = FrameCaptureHandle.create(cancelActions::incrementAndGet);
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(2);

            Thread canceller = new Thread(() -> {
                await(start);
                if (handle.cancel()) {
                    winners.incrementAndGet();
                }
                done.countDown();
            });
            Thread finisher = new Thread(() -> {
                await(start);
                if (handle.finish()) {
                    winners.incrementAndGet();
                }
                done.countDown();
            });
            canceller.start();
            finisher.start();
            start.countDown();
            assertTrue("第 " + i + " 轮未在超时内结束", done.await(5, TimeUnit.SECONDS));

            assertEquals("第 " + i + " 轮赢家数不为 1", 1, winners.get());
            assertTrue("取消动作执行次数异常：" + cancelActions.get(), cancelActions.get() <= 1);
            assertEquals("取消动作次数应与取消赢家一致",
                    handle.isCancelled() ? 1 : 0, cancelActions.get());
            assertTrue(handle.isDone());
        }
    }

    @Test
    public void toStringReflectsState() {
        FrameCaptureHandle handle = FrameCaptureHandle.create(null);
        assertTrue(handle.toString().contains("PENDING"));
        handle.finish();
        assertTrue(handle.toString().contains("COMPLETED"));

        FrameCaptureHandle cancelled = FrameCaptureHandle.create(null);
        cancelled.cancel();
        assertTrue(cancelled.toString().contains("CANCELLED"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
