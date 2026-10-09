package com.example.motro.targetselection;

import com.example.motro.targetselection.frame.AbstractFrameSource;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.frame.FrameCaptureRequest;
import com.example.motro.targetselection.frame.FrameCaptureResult;
import com.example.motro.targetselection.frame.FrameImage;
import com.example.motro.targetselection.frame.FrameSourceType;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 测试用帧源：把“平台取像”换成脚本化行为，用来单独验证 {@link AbstractFrameSource} 的
 * 调度、拦截与竞态处理 —— 这正是把共享骨架做成纯 JVM 类的收益。
 *
 * <p>它能模拟真实实现里最要紧的几件事：返回各种状态、抛异常、假装自己在主线程、
 * 以及“取帧很慢”（用 {@link #holdCapture} 卡住），从而构造出取消与完成的竞争。</p>
 */
final class FakeViewFrameSource extends AbstractFrameSource {

    /** 脚本化结局。 */
    enum Outcome {
        SUCCESS, NO_FRAME, SOURCE_NOT_READY, INVALID_SIZE, FAILURE
    }

    volatile Outcome outcome = Outcome.SUCCESS;

    /** 非 null 时在 {@code captureFromView} 里抛出，用于验证基类兜底。 */
    volatile RuntimeException throwOnCapture;

    /**
     * 被模拟成“主线程”的那条线程。必须绑定具体线程而不是一个布尔值 ——
     * 否则异步路径上的工作线程也会被误判成主线程，测出来的结论是假的。
     */
    volatile Thread mainThreadSimulator;

    /** 非 null 时在返回结果前等待，用于构造“取消 vs 完成”的竞争。 */
    volatile CountDownLatch holdCapture;

    volatile int imageWidth = 320;
    volatile int imageHeight = 180;

    final AtomicInteger captureCount = new AtomicInteger();
    final AtomicInteger closeCount = new AtomicInteger();
    /** 最近一次取帧实际用到的计时起点。 */
    volatile long lastRequestTimeNs = Long.MIN_VALUE;
    /** 最近一次取帧实际采用的等待上限。 */
    volatile long lastTimeoutMs = Long.MIN_VALUE;
    /** 最近一次取帧所在的线程名，用于验证工作线程被复用而不是每次新建。 */
    volatile String lastThreadName;
    /** 最近一次产出的假图像，用于断言“取消后帧确实被释放、没泄漏”。 */
    volatile FakeFrameImage lastProducedImage;

    private final AtomicInteger inFlight = new AtomicInteger();

    @Override
    protected boolean isCallingThreadMainThread() {
        Thread simulated = mainThreadSimulator;
        return simulated != null && simulated == Thread.currentThread();
    }

    @Override
    protected void onClose() {
        closeCount.incrementAndGet();
    }

    @Override
    protected FrameCaptureResult captureFromView(FrameCaptureRequest request, long requestTimeNs) {
        captureCount.incrementAndGet();
        lastRequestTimeNs = requestTimeNs;
        lastTimeoutMs = captureTimeoutMs(request);
        lastThreadName = Thread.currentThread().getName();
        inFlight.incrementAndGet();
        try {
            CountDownLatch hold = holdCapture;
            if (hold != null) {
                // 有界等待：即使测试断言写错也不会把整条测试链挂死
                hold.await(5, TimeUnit.SECONDS);
            }
            if (throwOnCapture != null) {
                throw throwOnCapture;
            }
            switch (outcome) {
                case NO_FRAME:
                    return FrameCaptureResult.noFrame("fake: no frame");
                case SOURCE_NOT_READY:
                    return FrameCaptureResult.sourceNotReady("fake: source not ready");
                case INVALID_SIZE:
                    return FrameCaptureResult.invalidSize("fake: invalid size");
                case FAILURE:
                    return FrameCaptureResult.captureFailed("fake: capture failed", 42);
                case SUCCESS:
                default:
                    FakeFrameImage image = new FakeFrameImage(imageWidth, imageHeight);
                    lastProducedImage = image;
                    return FrameCaptureResult.success(CapturedFrame.of(
                            image, imageWidth, imageHeight,
                            requestTimeNs, requestTimeNs + 2_000_000L,
                            CapturedFrame.NO_PTS_US, CapturedFrame.NO_TIMESTAMP_NS,
                            FrameSourceType.SURFACE_VIEW));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return FrameCaptureResult.cancelled("fake: interrupted while holding");
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /** 便捷构造：带请求发起时刻的请求。 */
    static FrameCaptureRequest request(long timeoutMs, int maxLongSidePx, long requestTimeNs) {
        return new FrameCaptureRequest(
                FrameCaptureRequest.NO_TARGET_PTS_US, timeoutMs, maxLongSidePx,
                FrameCaptureRequest.NO_FRAME_AGE_LIMIT_NS, requestTimeNs);
    }
}
