package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.frame.FrameDeliveryGate;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 超时放弃的“回收责任交接”契约。
 *
 * <p>这段逻辑守着一个很具体的事故：PixelCopy 超时后若直接回收目标位图，
 * 而回读稍后才完成，就会往已回收内存里写；反过来若什么都不做，位图就泄漏了。
 * 因此这里逐条验证“任何路径下责任恰好归一方”。</p>
 */
public class FrameDeliveryGateTest {

    @Test
    public void deliverThenAwaitExposesResultCode() throws Exception {
        FrameDeliveryGate gate = new FrameDeliveryGate();
        assertEquals(FrameDeliveryGate.NO_RESULT, gate.resultCode());

        assertTrue(gate.deliver(7));
        assertTrue(gate.await(100));
        assertTrue(gate.isDelivered());
        assertEquals(7, gate.resultCode());
    }

    @Test
    public void awaitTimesOutWhenNothingIsDelivered() throws Exception {
        FrameDeliveryGate gate = new FrameDeliveryGate();
        long startNs = System.nanoTime();
        assertFalse(gate.await(30));
        assertFalse(gate.isDelivered());
        assertTrue((System.nanoTime() - startNs) >= 20_000_000L);
    }

    @Test
    public void abandonWinsWhenProducerIsLateAndThenProducerMustCleanUp() {
        FrameDeliveryGate gate = new FrameDeliveryGate();
        assertTrue("等待方先放弃 → 抢到责任", gate.abandon());
        assertFalse("生产者迟到：deliver 必须失败，提示它自己回收资源", gate.deliver(1));
        assertFalse(gate.isDelivered());
    }

    @Test
    public void deliveredResultBelongsToWaiterAndAbandonFails() throws Exception {
        FrameDeliveryGate gate = new FrameDeliveryGate();
        assertTrue(gate.deliver(3));
        assertTrue(gate.await(100));
        assertFalse("已交付 → 放弃无效，资源归等待方", gate.abandon());
        assertEquals(3, gate.resultCode());
    }

    @Test
    public void repeatedDeliverOnlyFirstWins() {
        FrameDeliveryGate gate = new FrameDeliveryGate();
        assertTrue(gate.deliver(1));
        assertFalse(gate.deliver(2));
        assertEquals("首次交付的结果才有效", 1, gate.resultCode());
    }

    @Test
    public void awaitIsWokenUpByConcurrentDeliver() throws Exception {
        final FrameDeliveryGate gate = new FrameDeliveryGate();
        final CountDownLatch delivered = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gate.deliver(11);
            delivered.countDown();
        });
        producer.start();
        assertTrue("等待方应被唤醒而不是超时", gate.await(2000));
        assertTrue(delivered.await(2, TimeUnit.SECONDS));
        assertEquals(11, gate.resultCode());
        producer.join();
    }

    /** 竞争压力测试：交付与放弃只能有一个赢家，且赢家拿到的责任与返回码始终自洽。 */
    @Test
    public void racingDeliverAndAbandonHasExactlyOneWinner() throws Exception {
        for (int i = 0; i < 2000; i++) {
            final FrameDeliveryGate gate = new FrameDeliveryGate();
            final AtomicInteger winners = new AtomicInteger();
            final AtomicInteger deliverWins = new AtomicInteger();
            final CountDownLatch start = new CountDownLatch(1);
            final CountDownLatch done = new CountDownLatch(2);

            Thread producer = new Thread(() -> {
                await(start);
                if (gate.deliver(5)) {
                    winners.incrementAndGet();
                    deliverWins.incrementAndGet();
                }
                done.countDown();
            });
            Thread waiter = new Thread(() -> {
                await(start);
                if (gate.abandon()) {
                    winners.incrementAndGet();
                }
                done.countDown();
            });
            producer.start();
            waiter.start();
            start.countDown();
            assertTrue("第 " + i + " 轮未结束", done.await(5, TimeUnit.SECONDS));

            assertEquals("第 " + i + " 轮赢家数不为 1", 1, winners.get());
            assertEquals("deliver 是否成功应与 isDelivered 一致",
                    deliverWins.get() == 1, gate.isDelivered());
        }
    }

    @Test
    public void toStringReflectsState() {
        FrameDeliveryGate gate = new FrameDeliveryGate();
        assertTrue(gate.toString().contains("PENDING"));
        gate.deliver(9);
        assertTrue(gate.toString().contains("DELIVERED"));

        FrameDeliveryGate abandoned = new FrameDeliveryGate();
        abandoned.abandon();
        assertTrue(abandoned.toString().contains("ABANDONED"));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
