package com.example.motro.targetselection.frame;

import android.graphics.Bitmap;
import android.os.Looper;
import android.view.TextureView;

/**
 * 面向 {@code TextureView} 的通用取帧实现：直接取 View 的位图。
 *
 * <pre>
 *   任意播放器 ──▶ TextureView ──▶ TextureViewFrameSource ──▶ CapturedFrame
 *                    │
 *                    └─ isAvailable() → getBitmap(w,h) → ARGB_8888 位图
 * </pre>
 *
 * <p>与 {@link SurfaceViewFrameSource} 使用完全相同的 {@link VideoFrameSource} API，
 * 因此 app 层可以在不改业务代码的前提下切换承载 View（例如把 SurfaceView 换成
 * TextureView 以换取可动画/可裁剪能力）。</p>
 *
 * <p><b>平台语义（按 SDK 源码核实，不是推测）</b>：</p>
 * <ul>
 *   <li>{@code getBitmap(w,h)} 在「surface texture 不可用」或「w/h &lt;= 0」时返回 <b>null</b>，
 *       因此这两类情况分别映射为 {@code SOURCE_NOT_READY} 与 {@code INVALID_SIZE}，
 *       不留“成功但空图”的口子。</li>
 *   <li>拷贝过程出错时平台会返回一张<b>空白位图</b>而不是抛异常，
 *       所以“全透明”与“真的是黑画面”在平台层不可区分 —— 本类不做猜测，
 *       如实返回位图，把判断留给识别层。</li>
 *   <li>当 View 不在硬件加速窗口内、拿不到 hardware layer 时，
 *       {@code getBitmap} 会抛 {@code IllegalStateException}，本类将其如实映射为
 *       {@code CAPTURE_FAILED} 并保留消息。</li>
 *   <li>唯一的使用禁忌是“不要在 {@code onDraw} 里调用”。工作线程调用是允许的。</li>
 * </ul>
 *
 * <p><b>位图生命周期</b>：每次都新建位图，**从不复用**：已回收的位图绝不可能被再次写入；
 * 新建出来的位图所有权通过 {@link BitmapFrameImage#of(Bitmap, boolean)} 移交给
 * {@link CapturedFrame}（{@code ownsBitmap=true}），由上层统一释放。</p>
 *
 * <p><b>时间戳</b>：与 SurfaceView 版本同理，这里只能拿到“当前纹理内容”，
 * 无法对应解码帧时间轴，因此 PTS / 帧时间戳一律留空，不伪造。</p>
 *
 * <p><b>调用约定</b>：与 {@link SurfaceViewFrameSource} 保持一致 ——
 * 阻塞入口 {@code capture()} 禁止在主线程使用（主线程请用 {@code captureAsync()}），
 * 这样两个实现在上层看来行为一致，替换承载 View 时不需要改调用方式。</p>
 *
 * <p><b>生命周期</b>：强引用 View；持有方在 View 离开时必须调用 {@link #close()}。</p>
 */
public final class TextureViewFrameSource extends AbstractFrameSource {

    private final Object viewLock = new Object();
    private TextureView view;

    /**
     * @param view 目标 TextureView，非 null
     * @throws IllegalArgumentException view 为 null
     */
    public TextureViewFrameSource(TextureView view) {
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
        final TextureView target;
        synchronized (viewLock) {
            target = view;
        }
        if (target == null) {
            return FrameCaptureResult.sourceNotReady("TextureViewFrameSource is closed");
        }
        if (!target.isAvailable()) {
            return FrameCaptureResult.sourceNotReady(
                    "TextureView surface texture is not available yet");
        }

        final int viewWidth = target.getWidth();
        final int viewHeight = target.getHeight();
        final FrameSize size = FrameSize.forCapture(viewWidth, viewHeight, request.maxLongSidePx);
        if (size == null) {
            return FrameCaptureResult.invalidSize("TextureView size is " + viewWidth + "x" + viewHeight);
        }

        final Bitmap bitmap;
        try {
            bitmap = target.getBitmap(size.width, size.height);
        } catch (IllegalStateException e) {
            return FrameCaptureResult.captureFailed(
                    "TextureView.getBitmap() failed (hardware layer unavailable): " + describe(e));
        } catch (Throwable t) {
            return FrameCaptureResult.captureFailed("TextureView.getBitmap() failed: " + describe(t));
        }
        if (bitmap == null) {
            // 平台在“不可用”或“尺寸非法”时返回 null；前面已校验，这里是竞态兜底
            return FrameCaptureResult.sourceNotReady(
                    "TextureView.getBitmap() returned null (surface texture became unavailable)");
        }
        if (bitmap.isRecycled()) {
            // 正常路径不可能发生；真发生说明有人越权回收，明确报错而不是继续用
            return FrameCaptureResult.captureFailed("TextureView.getBitmap() returned a recycled bitmap");
        }
        final int bitmapWidth = bitmap.getWidth();
        final int bitmapHeight = bitmap.getHeight();
        if (bitmapWidth <= 0 || bitmapHeight <= 0) {
            bitmap.recycle();
            return FrameCaptureResult.invalidSize(
                    "TextureView bitmap size is " + bitmapWidth + "x" + bitmapHeight);
        }

        final long completedNs = System.nanoTime();
        return FrameCaptureResult.success(CapturedFrame.of(
                BitmapFrameImage.of(bitmap, true),
                bitmapWidth, bitmapHeight,
                requestTimeNs, completedNs,
                CapturedFrame.NO_PTS_US, CapturedFrame.NO_TIMESTAMP_NS,
                FrameSourceType.TEXTURE_VIEW));
    }

    private static String describe(Throwable t) {
        if (t == null) {
            return "unknown";
        }
        String message = t.getMessage();
        return message == null || message.isEmpty() ? t.getClass().getSimpleName() : message;
    }
}
