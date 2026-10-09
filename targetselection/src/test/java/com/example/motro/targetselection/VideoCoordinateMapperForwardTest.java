package com.example.motro.targetselection;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoPointMappingResult;
import com.example.motro.targetselection.geometry.VideoScaleType;

import org.junit.Test;

/**
 * {@link VideoCoordinateMapper#mapVideoToView} —— 正向映射（视频坐标 → View 坐标）。
 *
 * <p>测两层：</p>
 * <ol>
 *   <li><b>关键点的显式期望值</b>：视频四角、中心在 0/90/180/270、mirrorX/Y、
 *       FIT_CENTER / CENTER_CROP 下应落到哪个像素。这类断言才抓得住「旋转方向写反了」
 *       「镜像只做了一半」这种只错一点的 bug。</li>
 *   <li><b>与反解的往返恒等</b>：{@code 正推(反解(p)) == p}。这是两层公式共用同一套推导的
 *       硬证据 —— 一旦有人只改了一边，这里立刻红。</li>
 * </ol>
 *
 * <p>基准几何（见 {@link #letterbox()}）：1280x720 视频放进 640x480 的 View，
 * FIT_CENTER → 缩放 0.5，内容区 [0,60,640,420]（上下各 60px 黑边）。</p>
 */
public class VideoCoordinateMapperForwardTest {

    private static final float EPS = 1e-3f;

    /** 往返恒等的容差（像素）：足够小，任何旋转/镜像顺序写错都会远超它。 */
    private static final float ROUND_TRIP_EPS = 0.02f;

    /** 1280x720 视频 / 640x480 View / FIT_CENTER：内容区 [0,60,640,420]。 */
    private static VideoGeometry letterbox() {
        return VideoGeometry.createWithScale(1280, 720, 640, 480, VideoScaleType.FIT_CENTER);
    }

    // ------------------------------------------------------------------ 关键点

    @Test
    public void contentRectIsTheLetterboxedWindow() {
        VideoGeometry geometry = letterbox();
        assertTrue(geometry.isUsable());
        assertTrue(geometry.contentLeft == 0 && geometry.contentTop == 60
                && geometry.contentRight == 640 && geometry.contentBottom == 420);
    }

    @Test
    public void videoCornersLandOnContentRectCornersWithoutRotation() {
        VideoGeometry geometry = letterbox();

        // 解码帧左上角 → 内容区左上角
        assertPoint(0f, 60f, VideoCoordinateMapper.mapVideoToView(geometry, 0f, 0f));
        // 解码帧右下角 → 内容区右下角
        assertPoint(640f, 420f, VideoCoordinateMapper.mapVideoToView(geometry, 1280f, 720f));
        // 中心 → 中心
        assertPoint(320f, 240f, VideoCoordinateMapper.mapVideoToView(geometry, 640f, 360f));
    }

    @Test
    public void rotation90TurnsSourceTopLeftIntoDisplayTopRight() {
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1280, 720, 640, 480, VideoScaleType.FIT_CENTER, 90, false, false);
        // 90° 后显示画面变成 720x1280，塞进 640x480 → 缩放 0.375，内容区 [185,0,455,480]
        assertTrue(geometry.contentLeft == 185 && geometry.contentTop == 0
                && geometry.contentRight == 455 && geometry.contentBottom == 480);

        assertPoint(455f, 0f, VideoCoordinateMapper.mapVideoToView(geometry, 0f, 0f));
        assertPoint(0f + 185f, 480f, VideoCoordinateMapper.mapVideoToView(geometry, 1280f, 720f));
        assertPoint(320f, 240f, VideoCoordinateMapper.mapVideoToView(geometry, 640f, 360f));
    }

    @Test
    public void rotation180MapsSourceTopLeftToDisplayBottomRight() {
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1280, 720, 640, 480, VideoScaleType.FIT_CENTER, 180, false, false);
        assertPoint(640f, 420f, VideoCoordinateMapper.mapVideoToView(geometry, 0f, 0f));
        assertPoint(0f, 60f, VideoCoordinateMapper.mapVideoToView(geometry, 1280f, 720f));
        assertPoint(320f, 240f, VideoCoordinateMapper.mapVideoToView(geometry, 640f, 360f));
    }

    @Test
    public void rotation270TurnsSourceTopLeftIntoDisplayBottomLeft() {
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1280, 720, 640, 480, VideoScaleType.FIT_CENTER, 270, false, false);
        assertTrue(geometry.contentLeft == 185 && geometry.contentRight == 455);

        assertPoint(185f, 480f, VideoCoordinateMapper.mapVideoToView(geometry, 0f, 0f));
        assertPoint(455f, 0f, VideoCoordinateMapper.mapVideoToView(geometry, 1280f, 720f));
        assertPoint(320f, 240f, VideoCoordinateMapper.mapVideoToView(geometry, 640f, 360f));
    }

    @Test
    public void mirrorXFlipsHorizontallyAndMirrorYVertically() {
        VideoGeometry mirrorX = VideoGeometry.createWithScale(
                1280, 720, 640, 480, VideoScaleType.FIT_CENTER, 0, true, false);
        assertPoint(640f, 60f, VideoCoordinateMapper.mapVideoToView(mirrorX, 0f, 0f));
        assertPoint(0f, 420f, VideoCoordinateMapper.mapVideoToView(mirrorX, 1280f, 720f));

        VideoGeometry mirrorY = VideoGeometry.createWithScale(
                1280, 720, 640, 480, VideoScaleType.FIT_CENTER, 0, false, true);
        assertPoint(0f, 420f, VideoCoordinateMapper.mapVideoToView(mirrorY, 0f, 0f));
        assertPoint(640f, 60f, VideoCoordinateMapper.mapVideoToView(mirrorY, 1280f, 720f));

        // 中心不受镜像影响 —— 这条能抓出「镜像中心算错」的实现
        assertPoint(320f, 240f, VideoCoordinateMapper.mapVideoToView(mirrorX, 640f, 360f));
        assertPoint(320f, 240f, VideoCoordinateMapper.mapVideoToView(mirrorY, 640f, 360f));
    }

    @Test
    public void mirrorXPlusMirrorYEqualsRotation180() {
        VideoGeometry both = VideoGeometry.createWithScale(
                1280, 720, 640, 480, VideoScaleType.FIT_CENTER, 0, true, true);
        VideoGeometry rot180 = VideoGeometry.createWithScale(
                1280, 720, 640, 480, VideoScaleType.FIT_CENTER, 180, false, false);

        assertPoint(640f, 420f, VideoCoordinateMapper.mapVideoToView(both, 0f, 0f));
        assertPoint(640f, 420f, VideoCoordinateMapper.mapVideoToView(rot180, 0f, 0f));
        assertPoint(0f, 60f, VideoCoordinateMapper.mapVideoToView(both, 1280f, 720f));
        assertPoint(0f, 60f, VideoCoordinateMapper.mapVideoToView(rot180, 1280f, 720f));
    }

    @Test
    public void centerCropRestoresTheCroppedWindow() {
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1280, 720, 640, 480, VideoScaleType.CENTER_CROP);
        // 铺满：内容区等于整个 View
        assertTrue(geometry.contentLeft == 0 && geometry.contentTop == 0
                && geometry.contentRight == 640 && geometry.contentBottom == 480);

        // 16:9 铺进 4:3 只裁横向：可见的视频 x ∈ [160, 1120]（两侧各裁掉 12.5%），
        // 纵向完整可见 y ∈ [0, 720]。于是「可见窗口的四角」应正好落在 View 的四角上。
        assertPoint(0f, 0f, VideoCoordinateMapper.mapVideoToView(geometry, 160f, 0f));
        assertPoint(640f, 480f, VideoCoordinateMapper.mapVideoToView(geometry, 1120f, 720f));
        // 中心不变
        assertPoint(320f, 240f, VideoCoordinateMapper.mapVideoToView(geometry, 640f, 360f));
        // 可见窗口左缘中点 → View 左缘中点；纵向不裁，故 y 取半
        assertPoint(0f, 240f, VideoCoordinateMapper.mapVideoToView(geometry, 160f, 360f));
    }

    @Test
    public void croppedAwayVideoPointsAreClampedToViewEdge() {
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1280, 720, 640, 480, VideoScaleType.CENTER_CROP);
        // 这两个点在 CENTER_CROP 下不可见（被裁掉），没有真实屏幕位置 → 夹到 View 范围内
        assertPoint(0f, 0f, VideoCoordinateMapper.mapVideoToView(geometry, 0f, 0f));
        assertPoint(640f, 480f, VideoCoordinateMapper.mapVideoToView(geometry, 1280f, 720f));
    }

    @Test
    public void outOfRangeVideoPointsAreClampedIntoVideoSpace() {
        VideoGeometry geometry = letterbox();
        assertPoint(640f, 420f, VideoCoordinateMapper.mapVideoToView(geometry, 99_999f, 99_999f));
        assertPoint(0f, 60f, VideoCoordinateMapper.mapVideoToView(geometry, -99_999f, -99_999f));
    }

    // ------------------------------------------------------------------ 往返恒等

    @Test
    public void forwardMappingIsTheExactInverseOfReverseMapping() {
        final int[] rotations = {0, 90, 180, 270};
        // 这些点同时落在旋转 0/180 的内容区 [0,60,640,420] 与旋转 90/270 的内容区
        // [185,0,455,480] 之内 —— 否则反解会返回 OUTSIDE_VIDEO，测不到往返。
        final float[] touches = {320f, 240f, 200f, 240f, 250f, 100f, 440f, 400f};

        for (int rotation : rotations) {
            for (boolean mirrorX : new boolean[]{false, true}) {
                for (boolean mirrorY : new boolean[]{false, true}) {
                    VideoGeometry geometry = VideoGeometry.createWithScale(
                            1280, 720, 640, 480, VideoScaleType.FIT_CENTER, rotation, mirrorX, mirrorY);
                    for (int i = 0; i < touches.length; i += 2) {
                        assertRoundTrip(geometry, touches[i], touches[i + 1],
                                "rotation=" + rotation + " mirror=" + mirrorX + "," + mirrorY);
                    }
                }
            }
        }
    }

    @Test
    public void roundTripAlsoHoldsForCenterCrop() {
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1280, 720, 640, 480, VideoScaleType.CENTER_CROP);
        assertRoundTrip(geometry, 320f, 240f, "CENTER_CROP center");
        assertRoundTrip(geometry, 40f, 40f, "CENTER_CROP near corner");
        assertRoundTrip(geometry, 600f, 440f, "CENTER_CROP far corner");
    }

    @Test
    public void roundTripHoldsForPortraitVideoWithRotationAndMirror() {
        // 竖屏视频 + 旋转 + 镜像：最容易把顺序写错的一组
        VideoGeometry geometry = VideoGeometry.createWithScale(
                720, 1280, 1080, 720, VideoScaleType.FIT_CENTER, 90, true, false);
        assertTrue(geometry.isUsable());
        assertRoundTrip(geometry, geometry.contentLeft + 1f, geometry.contentTop + 1f, "portrait rot90 mirrorX");
        assertRoundTrip(geometry,
                (geometry.contentLeft + geometry.contentRight) / 2f,
                (geometry.contentTop + geometry.contentBottom) / 2f,
                "portrait rot90 mirrorX center");
    }

    // ------------------------------------------------------------------ 非法输入

    @Test
    public void unusableGeometryYieldsNull() {
        assertNull(VideoCoordinateMapper.mapVideoToView(null, 10f, 10f));
        assertNull(VideoCoordinateMapper.mapVideoToView(VideoGeometry.EMPTY, 10f, 10f));
    }

    @Test
    public void nonFiniteVideoPointYieldsNull() {
        VideoGeometry geometry = letterbox();
        assertNull("NaN 视频坐标不得被当成合法输入（会算出 NaN 画到屏幕上）",
                VideoCoordinateMapper.mapVideoToView(geometry, Float.NaN, 10f));
        assertNull(VideoCoordinateMapper.mapVideoToView(geometry, 10f, Float.NaN));
        assertNull(VideoCoordinateMapper.mapVideoToView(geometry, Float.POSITIVE_INFINITY, 10f));
    }

    // ------------------------------------------------------------------ helpers

    /** 反解再正推，落点必须回到原点（允许浮点误差，但要求非常小）。 */
    private static void assertRoundTrip(VideoGeometry geometry, float viewX, float viewY, String label) {
        VideoPointMappingResult mapping = VideoCoordinateMapper.map(geometry, viewX, viewY);
        assertTrue(label + "：原点应能映射成功，实际 " + mapping.status, mapping.status.isMapped());

        float[] back = VideoCoordinateMapper.mapVideoToView(geometry, mapping.videoX, mapping.videoY);
        assertNotNull(label + "：正推结果不应为 null", back);
        // 途经多次除法与乘法，留 0.02px 的容差（仍足以抓住任何顺序/轴向写错）
        assertTrue(label + " 往返不恒等：expected (" + viewX + "," + viewY + ") but was ("
                        + back[0] + "," + back[1] + ")",
                Math.abs(back[0] - viewX) <= ROUND_TRIP_EPS && Math.abs(back[1] - viewY) <= ROUND_TRIP_EPS);
    }

    private static void assertPoint(float expectedX, float expectedY, float[] actual) {
        assertPoint(expectedX, expectedY, actual, "");
    }

    private static void assertPoint(float expectedX, float expectedY, float[] actual, String label) {
        assertNotNull("映射结果不应为 null（" + label + "）", actual);
        assertTrue(label + " expected (" + expectedX + "," + expectedY + ") but was ("
                        + actual[0] + "," + actual[1] + ")",
                Math.abs(actual[0] - expectedX) <= EPS && Math.abs(actual[1] - expectedY) <= EPS);
    }
}
