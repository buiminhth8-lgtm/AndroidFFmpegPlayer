package com.example.motro.targetselection.frame;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.SurfaceView;
import android.view.TextureView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 真机冒烟测试：证明两个通用取帧实现在真实 Android 设备上确实能拿到画面。
 *
 * <p>纯 JVM 单测覆盖不了 PixelCopy 与 {@code TextureView.getBitmap()} 这两条平台路径，
 * 所以这里补齐。断言不止「尺寸对」，还包括「中心像素等于渲染的纯色」——
 * 否则一张尺寸正确的空位图也能骗过测试，那就等于没验证。</p>
 *
 * <p>失败时如实失败：不做“环境不允许就跳过”的软化处理，避免出现假绿。</p>
 */
@RunWith(AndroidJUnit4.class)
public class ViewFrameSourceInstrumentedTest {

    private static final long FIRST_FRAME_TIMEOUT_MS = 15_000L;
    private static final int MAX_CAPTURE_ATTEMPTS = 12;

    @Test
    public void surfaceViewFrameSourceCapturesRealGlContent() throws Exception {
        try (ActivityScenario<GlSurfaceViewSmokeTestActivity> scenario =
                     ActivityScenario.launch(GlSurfaceViewSmokeTestActivity.class)) {
            AtomicReference<GlSurfaceViewSmokeTestActivity> holder = new AtomicReference<>();
            scenario.onActivity(holder::set);
            GlSurfaceViewSmokeTestActivity activity = holder.get();
            assertNotNull(activity);
            assertTrue("GL 首帧未在超时内渲染出来", activity.awaitFirstFrame(FIRST_FRAME_TIMEOUT_MS));
            assertTrue("GL Surface 尺寸无效", activity.renderedWidth() > 0 && activity.renderedHeight() > 0);

            SurfaceViewFrameSource source = new SurfaceViewFrameSource(activity.surfaceView());
            try {
                FrameCaptureResult result = captureWithRetry(source);
                assertTrue("PixelCopy 始终未成功：" + result, result.isSuccess());

                CapturedFrame frame = result.frame();
                assertNotNull(frame);
                assertEquals(FrameSourceType.SURFACE_VIEW, frame.sourceType);
                assertEquals("宽度应等于 SurfaceView 的有效尺寸",
                        activity.renderedWidth(), frame.width);
                assertEquals("高度应等于 SurfaceView 的有效尺寸",
                        activity.renderedHeight(), frame.height);
                assertFalse("平台给不出真实 PTS，不得伪造", frame.hasPts());
                assertFalse("平台给不出真实帧时间戳，不得伪造", frame.hasFrameTimestamp());

                Bitmap bitmap = bitmapOf(frame);
                int center = bitmap.getPixel(frame.width / 2, frame.height / 2);
                assertTrue("中心像素不是 GL 清屏色(纯红)，实际=#" + Integer.toHexString(center),
                        Color.red(center) >= 240
                                && Color.green(center) <= 16
                                && Color.blue(center) <= 16);
                assertEquals("回读位图应是不透明 ARGB_8888", 255, Color.alpha(center));

                result.release();
                assertTrue("释放后位图必须真的被回收（ownsBitmap=true）", bitmap.isRecycled());
                assertFalse("释放后不应再持有图像", result.hasFrame());
            } finally {
                source.close();
            }
        }
    }

    @Test
    public void textureViewFrameSourceCapturesRealCanvasContent() throws Exception {
        try (ActivityScenario<TextureViewSmokeTestActivity> scenario =
                     ActivityScenario.launch(TextureViewSmokeTestActivity.class)) {
            AtomicReference<TextureViewSmokeTestActivity> holder = new AtomicReference<>();
            scenario.onActivity(holder::set);
            TextureViewSmokeTestActivity activity = holder.get();
            assertNotNull(activity);
            assertTrue("TextureView 纹理未在超时内就绪",
                    activity.awaitAvailable(FIRST_FRAME_TIMEOUT_MS));

            assertTrue("画布未就绪，无法构造测试内容", drawUntilSuccess(activity));

            TextureViewFrameSource source = new TextureViewFrameSource(activity.textureView());
            try {
                FrameCaptureResult result = captureWithRetry(source);
                assertTrue("TextureView 取帧始终未成功：" + result, result.isSuccess());

                CapturedFrame frame = result.frame();
                assertNotNull(frame);
                assertEquals(FrameSourceType.TEXTURE_VIEW, frame.sourceType);
                assertEquals("宽度应等于 TextureView 的尺寸",
                        activity.textureView().getWidth(), frame.width);
                assertEquals("高度应等于 TextureView 的尺寸",
                        activity.textureView().getHeight(), frame.height);
                assertFalse("平台给不出真实 PTS，不得伪造", frame.hasPts());

                Bitmap bitmap = bitmapOf(frame);
                int center = bitmap.getPixel(frame.width / 2, frame.height / 2);
                assertTrue("中心像素不是画布填充色(纯绿)，实际=#" + Integer.toHexString(center),
                        Color.green(center) >= 240
                                && Color.red(center) <= 16
                                && Color.blue(center) <= 16);

                result.release();
            } finally {
                source.close();
            }
        }
    }

    /**
     * 设备侧的“明确报错”验证：未 attach 的 View 不得静默返回空图，
     * 关闭后的来源也不得继续尝试取帧 —— 两条路径都必须给出可判定的状态。
     */
    @Test
    public void detachedAndClosedSourcesReportExplicitStatuses() {
        Context context = ApplicationProvider.getApplicationContext();

        TextureViewFrameSource textureSource =
                new TextureViewFrameSource(new TextureView(context));
        try {
            FrameCaptureResult result = textureSource.capture(FrameCaptureRequest.latestFrame(500L));
            assertEquals(FrameCaptureStatus.SOURCE_NOT_READY, result.status);
            assertFalse("失败结果绝不能带图", result.hasFrame());
            assertNotNull(result.debugReason);
        } finally {
            textureSource.close();
        }
        assertEquals("关闭后必须明确报 CANCELLED",
                FrameCaptureStatus.CANCELLED,
                textureSource.capture(FrameCaptureRequest.latestFrame(500L)).status);

        SurfaceViewFrameSource surfaceSource =
                new SurfaceViewFrameSource(new SurfaceView(context));
        try {
            FrameCaptureResult result = surfaceSource.capture(FrameCaptureRequest.latestFrame(500L));
            assertEquals(FrameCaptureStatus.SOURCE_NOT_READY, result.status);
            assertFalse("失败结果绝不能带图", result.hasFrame());
            assertNotNull(result.debugReason);
        } finally {
            surfaceSource.close();
        }
        assertEquals("关闭后必须明确报 CANCELLED",
                FrameCaptureStatus.CANCELLED,
                surfaceSource.capture(FrameCaptureRequest.latestFrame(500L)).status);
    }

    /** 表面刚创建时可能还没有可回读的缓冲，重试到出现成功结果或次数用尽。 */
    private static FrameCaptureResult captureWithRetry(VideoFrameSource source) throws Exception {        FrameCaptureResult result = null;
        for (int attempt = 0; attempt < MAX_CAPTURE_ATTEMPTS; attempt++) {
            if (result != null) {
                result.release();
            }
            result = source.capture(FrameCaptureRequest.latestFrame(2000L));
            if (result.isSuccess()) {
                return result;
            }
            Thread.sleep(250L);
        }
        return result;
    }

    private static boolean drawUntilSuccess(TextureViewSmokeTestActivity activity) throws Exception {
        for (int attempt = 0; attempt < MAX_CAPTURE_ATTEMPTS; attempt++) {
            if (activity.drawFillColor()) {
                return true;
            }
            Thread.sleep(250L);
        }
        return false;
    }

    private static Bitmap bitmapOf(CapturedFrame frame) {
        FrameImage image = frame.image();
        assertTrue("成功结果必须携带图像", image instanceof BitmapFrameImage);
        Bitmap bitmap = ((BitmapFrameImage) image).bitmap();
        assertNotNull(bitmap);
        assertFalse("回读位图不应处于已回收状态", bitmap.isRecycled());
        return bitmap;
    }
}
