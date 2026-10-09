package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.frame.AbstractFrameSource;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.frame.FrameCaptureCallback;
import com.example.motro.targetselection.frame.FrameCaptureException;
import com.example.motro.targetselection.frame.FrameCaptureHandle;
import com.example.motro.targetselection.frame.FrameCaptureRequest;
import com.example.motro.targetselection.frame.FrameCaptureResult;
import com.example.motro.targetselection.frame.FrameCaptureStatus;
import com.example.motro.targetselection.frame.FrameSourceType;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * 共享骨架的关键契约：阻塞入口的主线程保护、异步“恰好一次回调”、取消语义、
 * 关闭语义、以及 Slice 0 异常式契约的双向桥接。
 *
 * <p>这些行为全部与平台无关，所以能在这里逐条跑完；平台特有的部分
 * （PixelCopy / getBitmap）留给 {@code :targetselection} 的 androidTest 冒烟测试。</p>
 */
public class AbstractFrameSourceTest {

    /** 回调就地执行，便于把异步路径的断言写成确定性断言。 */
    private static final Executor DIRECT = Runnable::run;

    // ---------------------------------------------------------------- 阻塞入口

    @Test
    public void blockingCaptureReturnsSuccessWithSourceTypeAndNoFakedPts() {
        FakeViewFrameSource source = new FakeViewFrameSource();

        FrameCaptureResult result = source.capture(request(200L, 0, 0L));

        assertEquals(FrameCaptureStatus.SUCCESS, result.status);
        assertTrue(result.isSuccess());
        CapturedFrame frame = result.frame();
        assertNotNull(frame);
        assertEquals(320, frame.width);
        assertEquals(180, frame.height);
        assertEquals(FrameSourceType.SURFACE_VIEW, frame.sourceType);
        // 平台拿不到真实 PTS 时应留空 —— 绝不拿墙上时间冒充
        assertFalse(frame.hasPts());
        assertFalse(frame.hasFrameTimestamp());
        assertTrue(frame.captureCompletedNs >= frame.captureRequestNs);
        assertTrue(result.rawCode == FrameCaptureResult.NO_RAW_CODE);

        result.release();
        assertEquals(1, source.lastProducedImage.releaseCount);
    }

    @Test
    public void blockingCaptureOnMainThreadIsRejectedWithoutTouchingTheSource() {
        FakeViewFrameSource source = new FakeViewFrameSource();
        source.mainThreadSimulator = Thread.currentThread();

        FrameCaptureResult result = source.capture(request(200L, 0, 0L));

        assertEquals(FrameCaptureStatus.UNSUPPORTED, result.status);
        assertTrue(result.debugReason.contains("captureAsync"));
        assertEquals("主线程上不得真正发起取帧", 0, source.captureCount.get());
    }

    @Test
    public void requestTimeComesFromTheRequestWhenTheCallerStampedIt() {
        FakeViewFrameSource source = new FakeViewFrameSource();
        source.capture(request(200L, 0, 4321L)).release();
        assertEquals(4321L, source.lastRequestTimeNs);

        source.capture(request(200L, 0, 0L)).release();
        assertTrue("未标注时应由实现打点", source.lastRequestTimeNs > 0L);
    }

    @Test
    public void timeoutPolicyFallsBackToTheDefaultWhenRequestHasNoBudget() {
        FakeViewFrameSource source = new FakeViewFrameSource();

        source.capture(request(0L, 0, 0L)).release();
        assertEquals(AbstractFrameSource.DEFAULT_CAPTURE_TIMEOUT_MS, source.lastTimeoutMs);

        source.capture(request(250L, 0, 0L)).release();
        assertEquals(250L, source.lastTimeoutMs);
    }

    @Test
    public void unexpectedExceptionBecomesExplicitCaptureFailureNotAnEmptyFrame() {
        FakeViewFrameSource source = new FakeViewFrameSource();
        source.throwOnCapture = new IllegalStateException("platform exploded");

        FrameCaptureResult result = source.capture(request(200L, 0, 0L));

        assertEquals(FrameCaptureStatus.CAPTURE_FAILED, result.status);
        assertFalse("失败结果绝不能带图", result.hasFrame());
        assertTrue(result.debugReason, result.debugReason.contains("IllegalStateException"));
        assertTrue(result.debugReason, result.debugReason.contains("platform exploded"));
    }

    /** 子类违约返回 null 时，宁可报失败也不能“静默当成成功”。 */
    @Test
    public void nullResultFromHookIsReportedAsFailure() {
        AbstractFrameSource source = new AbstractFrameSource() {
            @Override
            protected FrameCaptureResult captureFromView(FrameCaptureRequest request, long requestTimeNs) {
                return null;
            }
        };

        FrameCaptureResult result = source.capture(request(200L, 0, 0L));

        assertEquals(FrameCaptureStatus.CAPTURE_FAILED, result.status);
        assertTrue(result.debugReason, result.debugReason.contains("null"));
        source.close();
    }

    @Test
    public void everyScriptedOutcomeIsSurfacedVerbatim() {
        FakeViewFrameSource source = new FakeViewFrameSource();

        source.outcome = FakeViewFrameSource.Outcome.NO_FRAME;
        assertEquals(FrameCaptureStatus.NO_FRAME, source.capture(request(200L, 0, 0L)).status);

        source.outcome = FakeViewFrameSource.Outcome.SOURCE_NOT_READY;
        assertEquals(FrameCaptureStatus.SOURCE_NOT_READY, source.capture(request(200L, 0, 0L)).status);

        source.outcome = FakeViewFrameSource.Outcome.INVALID_SIZE;
        assertEquals(FrameCaptureStatus.INVALID_SIZE, source.capture(request(200L, 0, 0L)).status);

        source.outcome = FakeViewFrameSource.Outcome.FAILURE;
        FrameCaptureResult failed = source.capture(request(200L, 0, 0L));
        assertEquals(FrameCaptureStatus.CAPTURE_FAILED, failed.status);
        assertEquals("平台原始码应保留在 rawCode 里", 42, failed.rawCode);
    }

    @Test
    public void nullRequestIsRejected() throws Exception {
        FakeViewFrameSource source = new FakeViewFrameSource();
        try {
            source.capture(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            source.captureFrame(null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    // ---------------------------------------------------------------- Slice 0 桥接

    @Test
    public void captureFrameBridgeMapsSuccessToFrameAndNoFrameToNull() throws Exception {
        FakeViewFrameSource source = new FakeViewFrameSource();

        source.outcome = FakeViewFrameSource.Outcome.SUCCESS;
        CapturedFrame frame = source.captureFrame(request(200L, 0, 0L));
        assertNotNull(frame);
        assertTrue(frame.hasImage());
        assertEquals(320, frame.width);
        frame.release();

        source.outcome = FakeViewFrameSource.Outcome.NO_FRAME;
        assertEquals("NO_FRAME 是正常时序，桥接成 null 而不是异常",
                null, source.captureFrame(request(200L, 0, 0L)));
    }

    @Test
    public void captureFrameBridgeThrowsForEveryFailureStatusAndKeepsStatusInMessage() {
        FakeViewFrameSource source = new FakeViewFrameSource();

        source.outcome = FakeViewFrameSource.Outcome.SOURCE_NOT_READY;
        assertCaptureException(source, FrameCaptureStatus.SOURCE_NOT_READY.name());

        source.outcome = FakeViewFrameSource.Outcome.INVALID_SIZE;
        assertCaptureException(source, FrameCaptureStatus.INVALID_SIZE.name());

        source.outcome = FakeViewFrameSource.Outcome.FAILURE;
        assertCaptureException(source, FrameCaptureStatus.CAPTURE_FAILED.name());
    }

    @Test
    public void closedSourceBridgesToCaptureExceptionNotSilentNull() {
        FakeViewFrameSource source = new FakeViewFrameSource();
        source.close();
        assertCaptureException(source, FrameCaptureStatus.CANCELLED.name());
    }

    // ---------------------------------------------------------------- 异步入口

    @Test
    public void asyncCallbackFiresExactlyOnceAndCarriesTheFrame() throws Exception {
        FakeViewFrameSource source = new FakeViewFrameSource();
        RecordingCallback callback = new RecordingCallback();

        FrameCaptureHandle handle = source.captureAsync(request(2000L, 0, 0L), callback, DIRECT);

        assertTrue("回调应在超时内到达", callback.latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, callback.count.get());
        assertTrue(callback.last.isSuccess());
        assertTrue(handle.isDone());
        assertFalse(handle.isCancelled());

        callback.last.release();
        source.close();
    }

    @Test
    public void asyncDoesNotBlockTheCallerWhileTheCaptureIsInFlight() throws Exception {
        FakeViewFrameSource source = new FakeViewFrameSource();
        CountDownLatch hold = new CountDownLatch(1);
        source.holdCapture = hold;
        RecordingCallback callback = new RecordingCallback();

        long startNs = System.nanoTime();
        FrameCaptureHandle handle = source.captureAsync(request(4000L, 0, 0L), callback, DIRECT);
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

        assertTrue("captureAsync 必须立刻返回，实测 " + elapsedMs + "ms", elapsedMs < 500L);
        assertFalse("取帧还没结束，句柄不应完成", handle.isDone());

        hold.countDown();
        assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, callback.count.get());
        callback.last.release();
        source.close();
    }

    @Test
    public void callingThreadIsUnaffectedByTheMainThreadGuardOnTheAsyncPath() throws Exception {
        FakeViewFrameSource source = new FakeViewFrameSource();
        // 把“当前测试线程”模拟成主线程：阻塞入口必须被拦下，异步入口必须照常工作
        source.mainThreadSimulator = Thread.currentThread();
        RecordingCallback callback = new RecordingCallback();

        source.captureAsync(request(4000L, 0, 0L), callback, DIRECT);

        assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, callback.count.get());
        assertTrue("异步路径不应被主线程保护拦下：" + callback.last,
                callback.last.isSuccess());
        assertNotEquals("但阻塞入口仍应被拦下",
                FrameCaptureStatus.SUCCESS, source.capture(request(2000L, 0, 0L)).status);

        callback.last.release();
        source.close();
    }

    @Test
    public void workerThreadIsReusedAcrossRequestsInsteadOfGrowing() throws Exception {
        FakeViewFrameSource source = new FakeViewFrameSource();
        RecordingCallback first = new RecordingCallback();
        source.captureAsync(request(2000L, 0, 0L), first, DIRECT);
        assertTrue(first.latch.await(5, TimeUnit.SECONDS));
        first.last.release();
        String firstThread = source.lastThreadName;

        RecordingCallback second = new RecordingCallback();
        source.captureAsync(request(2000L, 0, 0L), second, DIRECT);
        assertTrue(second.latch.await(5, TimeUnit.SECONDS));
        second.last.release();

        assertNotNull(firstThread);
        assertEquals("多次请求应复用同一个工作线程", firstThread, source.lastThreadName);
        source.close();
    }

    // ---------------------------------------------------------------- 取消

    @Test
    public void cancelBeforeCompletionDeliversCancelledOnceAndReleasesTheLateFrame() throws Exception {
        FakeViewFrameSource source = new FakeViewFrameSource();
        CountDownLatch hold = new CountDownLatch(1);
        source.holdCapture = hold;
        RecordingCallback callback = new RecordingCallback();

        FrameCaptureHandle handle = source.captureAsync(request(4000L, 0, 0L), callback, DIRECT);
        assertTrue("取消应抢在完成之前", handle.cancel());

        assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
        assertEquals("取消后仍必须回调，且只回调一次", 1, callback.count.get());
        assertEquals(FrameCaptureStatus.CANCELLED, callback.last.status);
        assertTrue(handle.isCancelled());

        // 放行被卡住的取帧：迟到帧必须被实现方回收，不能泄漏
        hold.countDown();
        assertTrue("迟到帧未被回收（位图泄漏）",
                awaitUntil(() -> source.lastProducedImage != null
                        && source.lastProducedImage.releaseCount == 1, 5000L));
        assertEquals("迟到结果不得再触发第二次回调", 1, callback.count.get());
        source.close();
    }

    @Test
    public void cancelAfterCompletionReturnsFalseAndTheRealResultSurvives() throws Exception {
        FakeViewFrameSource source = new FakeViewFrameSource();
        RecordingCallback callback = new RecordingCallback();

        FrameCaptureHandle handle = source.captureAsync(request(2000L, 0, 0L), callback, DIRECT);
        assertTrue(callback.latch.await(5, TimeUnit.SECONDS));

        assertFalse("已经完成就不能再取消", handle.cancel());
        assertEquals(1, callback.count.get());
        assertTrue("已产出的成功结果不能被取消吞掉", callback.last.isSuccess());
        callback.last.release();
        source.close();
    }

    /** 竞争压力测试：取消与完成同时发生，回调仍然只触发一次，且帧不被泄漏。 */
    @Test
    public void racingCancelAndCompletionStillDeliversExactlyOnce() throws Exception {
        for (int i = 0; i < 200; i++) {
            final FakeViewFrameSource source = new FakeViewFrameSource();
            final RecordingCallback callback = new RecordingCallback();

            final FrameCaptureHandle handle =
                    source.captureAsync(request(3000L, 0, 0L), callback, DIRECT);
            Thread canceller = new Thread(handle::cancel);
            canceller.start();

            assertTrue("第 " + i + " 轮回调未到达", callback.latch.await(5, TimeUnit.SECONDS));
            canceller.join(5000);

            assertEquals("第 " + i + " 轮回调次数不为 1", 1, callback.count.get());
            assertEquals("第 " + i + " 轮状态异常", handle.isCancelled(),
                    callback.last.status == FrameCaptureStatus.CANCELLED);
            if (callback.last.isSuccess()) {
                callback.last.release();
            } else {
                // 取消路径：看是否真的产出了帧、产出了就必须被回收
                assertTrue("第 " + i + " 轮迟到帧未被回收",
                        awaitUntil(() -> source.lastProducedImage == null
                                || source.lastProducedImage.releaseCount == 1, 2000L));
            }
            source.close();
        }
    }

    // ---------------------------------------------------------------- 关闭

    @Test
    public void closeIsIdempotentAndRunsTheHookOnce() {
        FakeViewFrameSource source = new FakeViewFrameSource();
        assertFalse(source.isClosed());
        source.close();
        source.close();
        source.close();
        assertTrue(source.isClosed());
        assertEquals(1, source.closeCount.get());
    }

    @Test
    public void closedSourceReturnsCancelledAndNeverTouchesThePlatform() {
        FakeViewFrameSource source = new FakeViewFrameSource();
        source.close();

        FrameCaptureResult result = source.capture(request(200L, 0, 0L));

        assertEquals(FrameCaptureStatus.CANCELLED, result.status);
        assertEquals(0, source.captureCount.get());
    }

    @Test
    public void asyncOnClosedSourceStillFiresTheCallbackExactlyOnce() throws Exception {
        FakeViewFrameSource source = new FakeViewFrameSource();
        source.close();
        RecordingCallback callback = new RecordingCallback();

        FrameCaptureHandle handle = source.captureAsync(request(200L, 0, 0L), callback, DIRECT);

        assertTrue("关闭后也不能让调用方永远等下去", callback.latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, callback.count.get());
        assertEquals(FrameCaptureStatus.CANCELLED, callback.last.status);
        assertTrue(handle.isDone());
    }

    /** 回调 Executor 拒收时，结果无人消费 —— 必须就地释放，否则位图泄漏。 */
    @Test
    public void rejectingCallbackExecutorReleasesTheFrameInsteadOfLeaking() throws Exception {
        FakeViewFrameSource source = new FakeViewFrameSource();
        RecordingCallback callback = new RecordingCallback();
        Executor rejecting = command -> {
            throw new RejectedExecutionException("callback executor is shut down");
        };

        source.captureAsync(request(2000L, 0, 0L), callback, rejecting);

        assertTrue("帧未被回收（位图泄漏）",
                awaitUntil(() -> source.lastProducedImage != null
                        && source.lastProducedImage.releaseCount == 1, 5000L));
        assertEquals(0, callback.count.get());
        source.close();
    }

    @Test
    public void asyncArgumentsAreValidatedEagerly() {
        FakeViewFrameSource source = new FakeViewFrameSource();
        RecordingCallback callback = new RecordingCallback();

        assertIllegalArgument(() -> source.captureAsync(null, callback, DIRECT));
        assertIllegalArgument(() -> source.captureAsync(request(200L, 0, 0L), null, DIRECT));
        assertIllegalArgument(() -> source.captureAsync(request(200L, 0, 0L), callback, null));
    }

    @Test
    public void callbackRunsOnTheProvidedExecutorNotOnTheCallerThread() throws Exception {
        FakeViewFrameSource source = new FakeViewFrameSource();
        ExecutorService callbackExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "test-callback"));
        try {
            RecordingCallback callback = new RecordingCallback();
            source.captureAsync(request(2000L, 0, 0L), callback, callbackExecutor);

            assertTrue(callback.latch.await(5, TimeUnit.SECONDS));
            assertEquals("test-callback", callback.threadName);
            assertNotEquals("回调不得在调用线程上跑",
                    Thread.currentThread().getName(), callback.threadName);
            callback.last.release();
        } finally {
            callbackExecutor.shutdownNow();
            source.close();
        }
    }

    // ---------------------------------------------------------------- utils

    private static FrameCaptureRequest request(long timeoutMs, int maxLongSidePx, long requestTimeNs) {
        return FakeViewFrameSource.request(timeoutMs, maxLongSidePx, requestTimeNs);
    }

    private static void assertCaptureException(FakeViewFrameSource source, String expectedStatusName) {
        try {
            source.captureFrame(request(200L, 0, 0L));
            fail("expected FrameCaptureException with " + expectedStatusName);
        } catch (FrameCaptureException expected) {
            assertTrue("异常消息应带上状态便于定位：" + expected.getMessage(),
                    expected.getMessage().contains(expectedStatusName));
        }
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期：参数错误在调用点立刻失败
        }
    }

    private static boolean awaitUntil(BooleanSupplier condition, long timeoutMs) throws Exception {
        long deadlineNs = System.nanoTime() + timeoutMs * 1_000_000L;
        while (System.nanoTime() < deadlineNs) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5L);
        }
        return condition.getAsBoolean();
    }

    /** 记录回调次数与最后一次结果，并暴露所在线程名。 */
    static final class RecordingCallback implements FrameCaptureCallback {
        final AtomicInteger count = new AtomicInteger();
        final CountDownLatch latch = new CountDownLatch(1);
        volatile FrameCaptureResult last;
        volatile String threadName;

        @Override
        public void onCaptureCompleted(FrameCaptureResult result) {
            count.incrementAndGet();
            last = result;
            threadName = Thread.currentThread().getName();
            latch.countDown();
        }
    }
}
