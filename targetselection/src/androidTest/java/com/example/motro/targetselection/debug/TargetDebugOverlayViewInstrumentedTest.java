package com.example.motro.targetselection.debug;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoPointMappingResult;
import com.example.motro.targetselection.geometry.VideoPointMappingStatus;
import com.example.motro.targetselection.geometry.VideoScaleType;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 叠加层的设备侧测试：JVM 跑不了的部分 —— 真实触摸分发与真实绘制。
 *
 * <p>三件事必须证明，否则这个调试工具本身就是可疑的：</p>
 * <ol>
 *   <li><b>不干扰播放</b>：叠在顶层却要放行手势，靠断言「下方兄弟仍收到 ACTION_DOWN」，
 *       而不是只看 {@code isClickable()} 之类的标志位。</li>
 *   <li><b>真的画了东西</b>：把 View 画进 Bitmap 再探像素位置。断言"标记画在 (700,500)"
 *       比断言"没抛异常"强得多 —— 后者一个空 {@code onDraw} 也能过。</li>
 *   <li><b>不画脏数据</b>：清空状态后必须一个像素都不留，否则叠加层会在画面上留残影。</li>
 * </ol>
 */
@RunWith(AndroidJUnit4.class)
public class TargetDebugOverlayViewInstrumentedTest {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final int BIG_WIDTH = 1600;
    private static final int BIG_HEIGHT = 1200;

    // ------------------------------------------------------------------ 不干扰播放

    @Test
    public void overlayAtopTheVideoStillLetsTheGestureLayerReceiveTouch() {
        Context context = ApplicationProvider.getApplicationContext();
        FrameLayout parent = new FrameLayout(context);

        View gestureTarget = new View(context);
        parent.addView(gestureTarget, matchParent());

        TargetDebugOverlayView overlay = new TargetDebugOverlayView(context);
        parent.addView(overlay, matchParent());
        layout(parent, WIDTH, HEIGHT);

        assertSame("叠加层应当是最后一个子 View（即在最上层）",
                overlay, parent.getChildAt(parent.getChildCount() - 1));
        assertFalse("叠加层默认不得可点击", overlay.isClickable());
        assertFalse("叠加层默认不得可聚焦", overlay.isFocusable());

        final AtomicBoolean delivered = new AtomicBoolean(false);
        gestureTarget.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                delivered.set(true);
            }
            return true;   // 播放器/手势层正常消费
        });

        dispatchDown(parent, 100f, 100f);

        assertTrue("叠加层在最上层，但绝不能吞掉应交给播放器的 ACTION_DOWN", delivered.get());
    }

    @Test
    public void onTouchEventReportsNotConsumed() {
        TargetDebugOverlayView overlay = new TargetDebugOverlayView(
                ApplicationProvider.getApplicationContext());
        layout(overlay, WIDTH, HEIGHT);

        assertFalse("onTouchEvent 必须恒为 false（不消费）",
                overlay.onTouchEvent(downAt(120f, 80f)));
    }

    // ------------------------------------------------------------------ 状态增删

    @Test
    public void debugStateCanBeSetReplacedAndCleared() {
        TargetDebugOverlayView overlay = new TargetDebugOverlayView(
                ApplicationProvider.getApplicationContext());

        assertFalse(overlay.hasDebugState());
        assertNull(overlay.getDebugState());

        TargetDebugState first = stateFor(fullViewGeometry(WIDTH, HEIGHT), 400f, 300f);
        overlay.setDebugState(first);
        assertTrue(overlay.hasDebugState());
        assertSame(first, overlay.getDebugState());

        TargetDebugState second = stateFor(fullViewGeometry(WIDTH, HEIGHT), 100f, 100f);
        overlay.setDebugState(second);
        assertSame("再次设置应替换为新状态", second, overlay.getDebugState());

        // null 等同于清空，调用方不必自己写分支
        overlay.setDebugState(null);
        assertNull(overlay.getDebugState());
        assertFalse(overlay.hasDebugState());

        overlay.setDebugState(second);
        overlay.clearDebugState();
        assertNull(overlay.getDebugState());
    }

    // ------------------------------------------------------------------ 真实绘制

    @Test
    public void nothingIsDrawnBeforeStateIsSetAndAfterItIsCleared() {
        TargetDebugOverlayView overlay = new TargetDebugOverlayView(
                ApplicationProvider.getApplicationContext());

        assertEquals("未设置状态时叠加层必须是全透明的",
                0, countPaintedPixels(render(overlay, WIDTH, HEIGHT)));

        overlay.setDebugState(stateFor(fullViewGeometry(WIDTH, HEIGHT), 700f, 500f));
        assertTrue("设置状态后应当画出标记", countPaintedPixels(render(overlay, WIDTH, HEIGHT)) > 0);

        overlay.clearDebugState();
        assertEquals("清空状态后不得留任何残影",
                0, countPaintedPixels(render(overlay, WIDTH, HEIGHT)));
    }

    @Test
    public void overlayDisabledDrawsNothingEvenWithState() {
        TargetDebugOverlayView overlay = new TargetDebugOverlayView(
                ApplicationProvider.getApplicationContext());
        overlay.setDebugState(stateFor(fullViewGeometry(WIDTH, HEIGHT), 700f, 500f));

        overlay.setOverlayEnabled(false);
        assertEquals("调试开关关闭后不应绘制任何像素",
                0, countPaintedPixels(render(overlay, WIDTH, HEIGHT)));

        overlay.setOverlayEnabled(true);
        Bitmap restored = render(overlay, WIDTH, HEIGHT);
        assertTrue("重新开启后应恢复绘制", countPaintedPixels(restored) > 0);
    }

    @Test
    public void touchMarkerIsPaintedAtTheTouchPoint() {
        TargetDebugOverlayView overlay = new TargetDebugOverlayView(
                ApplicationProvider.getApplicationContext());
        overlay.setDebugState(stateFor(fullViewGeometry(WIDTH, HEIGHT), 700f, 500f));

        Bitmap bitmap = render(overlay, WIDTH, HEIGHT);
        assertTrue("触摸点标记必须画在 (700,500)", paintedAt(bitmap, 700, 500));
    }

    @Test
    public void outsideVideoTouchIsStillPaintedEvenWithoutMappedPoint() {
        // 1600x900 塞进 800x600 → 上下黑边，内容区 [0,75,800,450]
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1600, 900, WIDTH, HEIGHT, VideoScaleType.FIT_CENTER);
        assertEquals(75, geometry.contentTop);

        TargetDebugState state = stateFor(geometry, 700f, 20f);
        assertEquals("上黑边应被判为 OUTSIDE_VIDEO", VideoPointMappingStatus.OUTSIDE_VIDEO, state.status());
        assertFalse("没有视频坐标时不应有回投影落点", state.hasMappedViewPoint());

        TargetDebugOverlayView overlay = new TargetDebugOverlayView(
                ApplicationProvider.getApplicationContext());
        overlay.setDebugState(state);

        // 用户点了黑边 —— 仍然要看见"点在这儿"，只是不该出现任何映射点
        assertTrue("黑边上的触摸点也要画出来（否则用户以为没响应）",
                paintedAt(render(overlay, WIDTH, HEIGHT), 700, 20));
    }

    @Test
    public void invalidGeometryStillDrawsTheTouchMarkerWithoutCrashing() {
        TargetDebugOverlayView overlay = new TargetDebugOverlayView(
                ApplicationProvider.getApplicationContext());

        VideoPointMappingResult invalid = VideoCoordinateMapper.map(VideoGeometry.EMPTY, 300f, 200f);
        assertEquals(VideoPointMappingStatus.INVALID_GEOMETRY, invalid.status);
        overlay.setDebugState(TargetDebugState.of(invalid));

        Bitmap bitmap = render(overlay, WIDTH, HEIGHT);
        assertTrue("几何坏掉时触摸点仍应可见，便于分辨“没点到”与“几何没准备好”",
                paintedAt(bitmap, 300, 200));
    }

    @Test
    public void redrawsAtTheNewSizeAfterResize() {
        Context context = ApplicationProvider.getApplicationContext();
        TargetDebugOverlayView overlay = new TargetDebugOverlayView(context);

        // 第一次：800x600，同一视频比例铺满
        overlay.setDebugState(stateFor(fullViewGeometry(WIDTH, HEIGHT), 700f, 500f));
        Bitmap first = render(overlay, WIDTH, HEIGHT);
        assertTrue("首次绘制：标记应在 (700,500)", paintedAt(first, 700, 500));

        // 视口变大 → app 层会按新尺寸重建几何与状态，叠加层必须跟着用新尺寸绘制
        overlay.setDebugState(stateFor(fullViewGeometry(BIG_WIDTH, BIG_HEIGHT), 1400f, 800f));
        Bitmap second = render(overlay, BIG_WIDTH, BIG_HEIGHT);

        assertTrue("resize 后：标记应画在新位置 (1400,800)", paintedAt(second, 1400, 800));
        assertFalse("旧尺寸的落点不应再被画出（说明确实按新几何重绘了）",
                paintedAt(second, 700, 600));
    }

    // ------------------------------------------------------------------ helpers

    /** 与 View 同宽高比的视频铺满整个 View：内容区等于 View，无黑边。 */
    private static VideoGeometry fullViewGeometry(int width, int height) {
        return VideoGeometry.createWithScale(
                width * 2, height * 2, width, height, VideoScaleType.FIT_CENTER);
    }

    private static TargetDebugState stateFor(VideoGeometry geometry, float viewX, float viewY) {
        return TargetDebugState.of(VideoCoordinateMapper.map(geometry, viewX, viewY),
                SystemClock.uptimeMillis() * 1_000_000L);
    }

    private static ViewGroup.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private static void layout(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    /** 量好尺寸后把 View 画进一张离屏位图，用于逐像素核对。 */
    private static Bitmap render(TargetDebugOverlayView overlay, int width, int height) {
        layout(overlay, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        overlay.draw(new Canvas(bitmap));
        return bitmap;
    }

    private static void dispatchDown(View target, float x, float y) {
        MotionEvent event = downAt(x, y);
        try {
            target.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static MotionEvent downAt(float x, float y) {
        long now = SystemClock.uptimeMillis();
        return MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0);
    }

    private static boolean paintedAt(Bitmap bitmap, int x, int y) {
        return Color.alpha(bitmap.getPixel(x, y)) > 0;
    }

    private static int countPaintedPixels(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] pixels = new int[width * height];
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        int painted = 0;
        for (int pixel : pixels) {
            if (Color.alpha(pixel) > 0) {
                painted++;
            }
        }
        return painted;
    }
}
