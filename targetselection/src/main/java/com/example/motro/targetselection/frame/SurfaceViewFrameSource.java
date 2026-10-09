package com.example.motro.targetselection.frame;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/**
 * 面向 {@code SurfaceView} 的通用取帧实现：对 View 的 {@code Surface} 做 PixelCopy 回读。
 *
 * <p><b>为什么这条路径是“播放器无关”的</b>：它只认 {@code SurfaceView} 这个 Android 类型。
 * 任何把画面输出到 SurfaceView 的播放器（FFmpegPlayer 的 native 渲染、Media3 的
 * {@code SurfaceView} 输出、VLC、GStreamer 的 glimagesink/window）都能直接复用同一个实例，
 * 不需要为每家写适配代码：</p>
 * <pre>
 *   任意播放器 ──▶ SurfaceView ──▶ SurfaceViewFrameSource ──▶ CapturedFrame
 * </pre>
 *
 * <p><b>取帧流程</b>（对应类内注释的分步）：</p>
 * <pre>
 *   capture()  [工作线程]
 *     ├─ View attach / Surface 有效 / 尺寸合法 校验   → 不合法直接返回明确状态
 *     ├─ Bitmap.createBitmap(目标尺寸, ARGB_8888)
 *     ├─ post 到主线程 → PixelCopy.request(surface, bitmap, cb, mainHandler)
 *     └─ 阻塞等待（上限 = request.timeoutMs 或 DEFAULT_CAPTURE_TIMEOUT_MS）
 * </pre>
 *
 * <p><b>三处容易踩的坑，这里都处理了</b>：</p>
 * <ol>
 *   <li><b>Android 要求 ARGB_8888</b>，且位图必须可变；其它 Config 会直接失败。</li>
 *   <li><b>超时后不能立刻 recycle</b>：PixelCopy 可能仍在往目标位图里写，
 *       抢着回收会写坏已释放内存。这里用 {@link FrameDeliveryGate} 做“回收责任交接”，
 *       超时后由稍后到达的回调负责回收，任何路径都恰好回收一次。</li>
 *   <li><b>取消回读是不可能的</b>：PixelCopy 一旦发起就无法中止，所以“取消”只能体现在
 *       结果层（见 {@link AbstractFrameSource#captureAsync}），不能假装平台支持中断。</li>
 * </ol>
 *
 * <p><b>时间戳</b>：PixelCopy 只能拿到“当前合成缓冲”，无法对应解码帧时间轴，
 * 因此 {@link CapturedFrame#ptsUs} 与 {@link CapturedFrame#frameTimestampNs} 一律为
 * {@link CapturedFrame#NO_PTS_US} / {@link CapturedFrame#NO_TIMESTAMP_NS}，不做伪造。</p>
 *
 * <p><b>版本兼容</b>：本模块 minSdk 24，而 PixelCopy 正是 API 24 引入的，
 * 因此无需版本分支。后续若要支持更低版本，只需在此子类内做分支，不影响核心 API。</p>
 *
 * <p><b>生命周期</b>：本类对 View 持强引用（弱引用会在 GC 后静默变成取帧失败，更难排查）。
 * 持有方必须在 View 离开时调用 {@link #close()}，届时引用被清空、内部线程被关闭；
 * 之后所有取帧返回 {@code CANCELLED}。</p>
 */
public final class SurfaceViewFrameSource extends AbstractFrameSource {

    private final Object viewLock = new Object();
    private SurfaceView view;
    private Handler mainHandler;

    /**
     * @param view 目标 SurfaceView，非 null
     * @throws IllegalArgumentException view 为 null
     */
    public SurfaceViewFrameSource(SurfaceView view) {
        if (view == null) {
            throw new IllegalArgumentException("view must not be null");
        }
        this.view = view;
    }

    @Override
    protected boolean isCallingThreadMainThread() {
        Looper main = Looper.getMainLooper();
        return main != null && main == Looper.myLooper();
    }

    @Override
    protected void onClose() {
        synchronized (viewLock) {
            view = null;
        }
    }

    @Override
    protected FrameCaptureResult captureFromView(FrameCaptureRequest request, long requestTimeNs) {
        final SurfaceView target;
        synchronized (viewLock) {
            target = view;
        }
        if (target == null) {
            return FrameCaptureResult.sourceNotReady("SurfaceViewFrameSource is closed");
        }
        if (!target.isAttachedToWindow()) {
            return FrameCaptureResult.sourceNotReady("SurfaceView is not attached to a window");
        }
        final SurfaceHolder holder = target.getHolder();
        final Surface surface = holder == null ? null : holder.getSurface();
        if (surface == null || !surface.isValid()) {
            return FrameCaptureResult.sourceNotReady("SurfaceView surface is not valid");
        }

        // 1) 尺寸：以 View 当前有效尺寸为准；View 还没被布局时为 0 → 明确报 INVALID_SIZE
        final FrameSize size = FrameSize.forCapture(
                target.getWidth(), target.getHeight(), request.maxLongSidePx);
        if (size == null) {
            return FrameCaptureResult.invalidSize("SurfaceView size is "
                    + target.getWidth() + "x" + target.getHeight());
        }

        final Bitmap bitmap;
        try {
            // PixelCopy 只接受可变 ARGB_8888 目标位图
            bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888);
        } catch (Throwable t) {
            return FrameCaptureResult.captureFailed("Bitmap.createBitmap failed: " + describe(t));
        }

        final long timeoutMs = captureTimeoutMs(request);
        final FrameDeliveryGate gate = new FrameDeliveryGate();
        final Handler handler;
        try {
            handler = mainHandler();
        } catch (Throwable t) {
            bitmap.recycle();
            return FrameCaptureResult.captureFailed("main looper unavailable: " + describe(t));
        }

        // 2) 发起异步回读：必须在主线程（SurfaceFlinger 的合成缓冲只在 UI 线程安全取得）
        try {
            if (!handler.post(() -> requestPixelCopy(holder, bitmap, gate, handler))) {
                bitmap.recycle();
                return FrameCaptureResult.captureFailed("failed to post PixelCopy to the main thread");
            }
        } catch (Throwable t) {
            bitmap.recycle();
            return FrameCaptureResult.captureFailed("PixelCopy request failed: " + describe(t));
        }

        // 3) 阻塞等待（工作线程上），超时后按“回收责任交接”处理，绝不双删也绝不泄漏
        boolean delivered;
        try {
            delivered = gate.await(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            delivered = false;
        }
        if (!delivered && gate.abandon()) {
            // 抢在回调之前放弃：稍后到达的回调会看到 deliver() 失败并自行回收该位图
            return FrameCaptureResult.timeout(
                    "PixelCopy did not complete within " + timeoutMs + " ms",
                    PixelCopyStatusMapper.ERROR_TIMEOUT);
        }
        // 走到这里：要么 await 返回 true，要么 abandon 失败（回调刚完成）—— 结果都归我们
        final long completedNs = System.nanoTime();
        final int rawResult = gate.resultCode();
        final FrameCaptureStatus status = PixelCopyStatusMapper.map(rawResult);
        if (status != FrameCaptureStatus.SUCCESS) {
            bitmap.recycle();
            return FrameCaptureResult.failure(status,
                    "PixelCopy failed: " + PixelCopyStatusMapper.describe(rawResult)
                            + " (" + rawResult + ")", rawResult);
        }
        return FrameCaptureResult.success(CapturedFrame.of(
                BitmapFrameImage.of(bitmap, true),
                size.width, size.height,
                requestTimeNs, completedNs,
                CapturedFrame.NO_PTS_US, CapturedFrame.NO_TIMESTAMP_NS,
                FrameSourceType.SURFACE_VIEW), rawResult);
    }

    /**
     * 主线程侧：发起 PixelCopy。发起前再验一次 Surface，
     * 避免“校验通过 → 窗口被销毁 → 真正回读”之间的竞态。
     */
    private void requestPixelCopy(SurfaceHolder holder, Bitmap bitmap,
                                  FrameDeliveryGate gate, Handler handler) {
        final Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) {
            if (gate.deliver(PixelCopyStatusMapper.ERROR_SOURCE_INVALID)) {
                return;
            }
            // 等待方已放弃 → 位图归本次回调负责
            bitmap.recycle();
            return;
        }
        try {
            PixelCopy.request(surface, bitmap, result -> {
                if (!gate.deliver(result)) {
                    // 等待方已超时放弃：目标位图不再有主人，由本回调回收
                    bitmap.recycle();
                }
            }, handler);
        } catch (Throwable t) {
            if (!gate.deliver(PixelCopyStatusMapper.ERROR_SOURCE_INVALID)) {
                bitmap.recycle();
            }
        }
    }

    private Handler mainHandler() {
        synchronized (viewLock) {
            if (mainHandler == null) {
                mainHandler = new Handler(Looper.getMainLooper());
            }
            return mainHandler;
        }
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String message = t.getMessage();
        return message == null || message.isEmpty() ? t.getClass().getSimpleName() : message;
    }
}
