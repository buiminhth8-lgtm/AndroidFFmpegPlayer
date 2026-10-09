package com.example.motro.targetselection;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoPointMappingResult;
import com.example.motro.targetselection.geometry.VideoPointMappingStatus;
import com.example.motro.targetselection.geometry.VideoScaleType;

import org.junit.Test;

/**
 * 坐标映射数学测试。用例全部是能手算验证的整数比，且**断言具体坐标值**而不是只断言“不崩溃” ——
 * 坐标算错一点点（比如旋转与镜像顺序反了）依然能“不崩溃”，只有具体数值能抓住它。
 *
 * <p>约定：显示内容 = 镜像( 顺时针旋转( 解码帧 ) )。</p>
 */
public class VideoCoordinateMapperTest {

    private static final float EPS = 0.01f;

    private static VideoPointMappingResult map(VideoGeometry geometry, float x, float y) {
        return VideoCoordinateMapper.map(geometry, x, y);
    }

    // ---------------------------------------------------- 1) 等比缩放与关键点

    @Test
    public void proportionalMappingMatchesViewToVideoRatio() {
        // 1280x720 铺进 1920x1080：正好 1.5 倍，无黑边
        VideoGeometry geometry = VideoGeometry.createWithScale(1280, 720, 1920, 1080, VideoScaleType.FIT_CENTER);
        assertTrue(geometry.isUsable());
        assertEquals(0, geometry.contentLeft);
        assertEquals(0, geometry.contentTop);
        assertEquals(1920, geometry.contentRight);
        assertEquals(1080, geometry.contentBottom);

        VideoPointMappingResult center = map(geometry, 960f, 540f);
        assertEquals(VideoPointMappingStatus.SUCCESS, center.status);
        assertEquals(640f, center.videoX, EPS);
        assertEquals(360f, center.videoY, EPS);
        assertEquals(0.5f, center.normalizedX, EPS);
        assertEquals(0.5f, center.normalizedY, EPS);
        assertTrue(center.insideVideoContent);

        // 左上 / 右下角：应精确落在视频角点
        assertEquals(0f, map(geometry, 0f, 0f).videoX, EPS);
        assertEquals(0f, map(geometry, 0f, 0f).videoY, EPS);
        assertEquals(1280f, map(geometry, 1920f, 1080f).videoX, EPS);
        assertEquals(720f, map(geometry, 1920f, 1080f).videoY, EPS);

        // 归一化坐标应与视频宽高比一致
        VideoPointMappingResult quarter = map(geometry, 480f, 270f);
        assertEquals(320f, quarter.videoX, EPS);
        assertEquals(180f, quarter.videoY, EPS);
        assertEquals(0.25f, quarter.normalizedX, EPS);
        assertEquals(0.25f, quarter.normalizedY, EPS);
    }

    @Test
    public void fitCenterLetterboxBarsMapCorrectlyAndRejectOutsidePoints() {
        // 2:1 视频放进正方形 View：上下各 25px 黑边，内容区 = [0,25,100,75]
        VideoGeometry geometry = VideoGeometry.createWithScale(200, 100, 100, 100, VideoScaleType.FIT_CENTER);
        assertEquals(25, geometry.contentTop);
        assertEquals(75, geometry.contentBottom);

        // 内容区顶边的中点 → 视频顶边中点
        VideoPointMappingResult topEdge = map(geometry, 50f, 25f);
        assertEquals(VideoPointMappingStatus.SUCCESS, topEdge.status);
        assertEquals(100f, topEdge.videoX, EPS);
        assertEquals(0f, topEdge.videoY, EPS);

        // 内容区底边 → 视频底边
        assertEquals(100f, map(geometry, 50f, 75f).videoY, EPS);
        // 左 / 右边缘
        assertEquals(0f, map(geometry, 0f, 50f).videoX, EPS);
        assertEquals(200f, map(geometry, 100f, 50f).videoX, EPS);
        // 中心
        assertEquals(100f, map(geometry, 50f, 50f).videoX, EPS);
        assertEquals(50f, map(geometry, 50f, 50f).videoY, EPS);

        // 黑边上的点击必须明确报 OUTSIDE_VIDEO，且不带任何坐标
        VideoPointMappingResult onBar = map(geometry, 50f, 10f);
        assertEquals(VideoPointMappingStatus.OUTSIDE_VIDEO, onBar.status);
        assertFalse(onBar.insideVideoContent);
        assertFalse(onBar.hasVideoCoordinates());
        assertTrue(Float.isNaN(onBar.videoX));
        assertTrue(Float.isNaN(onBar.normalizedY));
        // View 之外的点击同样是 OUTSIDE_VIDEO
        assertEquals(VideoPointMappingStatus.OUTSIDE_VIDEO, map(geometry, 50f, -5f).status);
        assertEquals(VideoPointMappingStatus.OUTSIDE_VIDEO, map(geometry, 101f, 50f).status);
    }

    @Test
    public void fitCenterPillarboxBarsMapCorrectly() {
        // 1:1 视频放进 2:1 的 View：左右各 50px 黑边（pillarbox），内容区 = [50,0,150,100]
        VideoGeometry geometry = VideoGeometry.createWithScale(100, 100, 200, 100, VideoScaleType.FIT_CENTER);
        assertEquals(50, geometry.contentLeft);
        assertEquals(150, geometry.contentRight);

        assertEquals(0f, map(geometry, 50f, 50f).videoX, EPS);
        assertEquals(50f, map(geometry, 50f, 50f).videoY, EPS);
        assertEquals(100f, map(geometry, 150f, 50f).videoX, EPS);
        assertEquals(50f, map(geometry, 100f, 50f).videoX, EPS);

        // 左右黑边
        assertEquals(VideoPointMappingStatus.OUTSIDE_VIDEO, map(geometry, 10f, 50f).status);
        assertEquals(VideoPointMappingStatus.OUTSIDE_VIDEO, map(geometry, 190f, 50f).status);
    }

    // ---------------------------------------------------- 2) CENTER_CROP

    @Test
    public void centerCropRestoresTheCroppedWindow() {
        // 2:1 视频铺满方形 View：缩放比取 max → 画面被横向裁掉一半（左右各 25%）
        VideoGeometry geometry = VideoGeometry.createWithScale(200, 100, 100, 100, VideoScaleType.CENTER_CROP);
        assertEquals(VideoScaleType.CENTER_CROP, geometry.scaleType);
        // 铺满语义下可见区域 == 整个 View，因此**没有黑边**
        assertEquals(0, geometry.contentLeft);
        assertEquals(0, geometry.contentTop);
        assertEquals(100, geometry.contentRight);
        assertEquals(100, geometry.contentBottom);

        // 视图左边缘对应视频的 25% 处（被裁掉的那部分不会出现在屏幕上）
        VideoPointMappingResult leftEdge = map(geometry, 0f, 0f);
        assertEquals(VideoPointMappingStatus.SUCCESS, leftEdge.status);
        assertEquals(50f, leftEdge.videoX, EPS);
        assertEquals(0f, leftEdge.videoY, EPS);

        // 中心仍精确对应视频中心
        assertEquals(100f, map(geometry, 50f, 50f).videoX, EPS);
        assertEquals(50f, map(geometry, 50f, 50f).videoY, EPS);

        // 右下角对应视频的 75% / 100%
        assertEquals(150f, map(geometry, 100f, 100f).videoX, EPS);
        assertEquals(100f, map(geometry, 100f, 100f).videoY, EPS);

        // 裁切方向的上下边缘没有被裁，映射应覆盖完整高度
        assertEquals(0f, map(geometry, 50f, 0f).videoY, EPS);
        assertEquals(100f, map(geometry, 50f, 100f).videoY, EPS);

        // 只有点在 View 之外才算 OUTSIDE_VIDEO（CENTER_CROP 没有黑边）
        assertEquals(VideoPointMappingStatus.OUTSIDE_VIDEO, map(geometry, -1f, 50f).status);
        assertEquals(VideoPointMappingStatus.OUTSIDE_VIDEO, map(geometry, 50f, 101f).status);
    }

    @Test
    public void centerCropOnVerticalVideoCropsVertically() {
        // 1:1 视频铺进 2:1 的 View：这次是**上下**被裁
        VideoGeometry geometry = VideoGeometry.createWithScale(100, 100, 200, 100, VideoScaleType.CENTER_CROP);
        assertEquals(0, geometry.contentTop);
        assertEquals(100, geometry.contentBottom);

        // 视图上边缘 → 视频 25% 处
        assertEquals(25f, map(geometry, 100f, 0f).videoY, EPS);
        assertEquals(75f, map(geometry, 100f, 100f).videoY, EPS);
        assertEquals(50f, map(geometry, 100f, 50f).videoY, EPS);
        // 横向铺满，完整可见
        assertEquals(0f, map(geometry, 0f, 50f).videoX, EPS);
        assertEquals(100f, map(geometry, 200f, 50f).videoX, EPS);
    }

    // ---------------------------------------------------- 3) 旋转

    @Test
    public void rotation90MapsDisplayCornersToExpectedVideoCorners() {
        // 100x50 的横向视频顺时针转 90° 后显示在 50x100 的 View 上
        VideoGeometry geometry = VideoGeometry.createWithScale(100, 50, 50, 100,
                VideoScaleType.FIT_CENTER, 90, false, false);

        // 显示左上角 ↔ 解码帧左下角
        VideoPointMappingResult topLeft = map(geometry, 0f, 0f);
        assertEquals(0f, topLeft.videoX, EPS);
        assertEquals(50f, topLeft.videoY, EPS);
        // 显示右上角 ↔ 解码帧左上角
        assertEquals(0f, map(geometry, 50f, 0f).videoX, EPS);
        assertEquals(0f, map(geometry, 50f, 0f).videoY, EPS);
        // 显示左下角 ↔ 解码帧右下角
        assertEquals(100f, map(geometry, 0f, 100f).videoX, EPS);
        assertEquals(50f, map(geometry, 0f, 100f).videoY, EPS);
        // 中心不变
        assertEquals(50f, map(geometry, 25f, 50f).videoX, EPS);
        assertEquals(25f, map(geometry, 25f, 50f).videoY, EPS);
    }

    @Test
    public void rotation180FlipsBothAxes() {
        VideoGeometry geometry = VideoGeometry.createWithScale(100, 50, 100, 50,
                VideoScaleType.FIT_CENTER, 180, false, false);

        assertEquals(100f, map(geometry, 0f, 0f).videoX, EPS);
        assertEquals(50f, map(geometry, 0f, 0f).videoY, EPS);
        assertEquals(0f, map(geometry, 100f, 50f).videoX, EPS);
        assertEquals(0f, map(geometry, 100f, 50f).videoY, EPS);
        assertEquals(50f, map(geometry, 50f, 25f).videoX, EPS);
        assertEquals(25f, map(geometry, 50f, 25f).videoY, EPS);
    }

    @Test
    public void rotation270MapsDisplayCornersToExpectedVideoCorners() {
        VideoGeometry geometry = VideoGeometry.createWithScale(100, 50, 50, 100,
                VideoScaleType.FIT_CENTER, 270, false, false);

        // 显示左上角 ↔ 解码帧右上角
        assertEquals(100f, map(geometry, 0f, 0f).videoX, EPS);
        assertEquals(0f, map(geometry, 0f, 0f).videoY, EPS);
        // 显示右下角 ↔ 解码帧左下角
        assertEquals(0f, map(geometry, 50f, 100f).videoX, EPS);
        assertEquals(50f, map(geometry, 50f, 100f).videoY, EPS);
        assertEquals(50f, map(geometry, 25f, 50f).videoX, EPS);
        assertEquals(25f, map(geometry, 25f, 50f).videoY, EPS);
    }

    // ---------------------------------------------------- 4) 镜像

    @Test
    public void mirrorXFlipsDisplayHorizontallyOnly() {
        VideoGeometry geometry = VideoGeometry.createWithScale(100, 50, 100, 50,
                VideoScaleType.FIT_CENTER, 0, true, false);

        assertEquals(100f, map(geometry, 0f, 0f).videoX, EPS);
        assertEquals(0f, map(geometry, 0f, 0f).videoY, EPS);
        assertEquals(75f, map(geometry, 25f, 25f).videoX, EPS);
        assertEquals(25f, map(geometry, 25f, 25f).videoY, EPS);
        assertEquals(0f, map(geometry, 100f, 50f).videoX, EPS);
        assertEquals(50f, map(geometry, 100f, 50f).videoY, EPS);
    }

    @Test
    public void mirrorYFlipsDisplayVerticallyOnly() {
        VideoGeometry geometry = VideoGeometry.createWithScale(100, 50, 100, 50,
                VideoScaleType.FIT_CENTER, 0, false, true);

        assertEquals(0f, map(geometry, 0f, 0f).videoX, EPS);
        assertEquals(50f, map(geometry, 0f, 0f).videoY, EPS);
        assertEquals(25f, map(geometry, 25f, 25f).videoX, EPS);
        assertEquals(25f, map(geometry, 25f, 25f).videoY, EPS);
        assertEquals(100f, map(geometry, 100f, 50f).videoX, EPS);
        assertEquals(0f, map(geometry, 100f, 50f).videoY, EPS);
    }

    // ---------------------------------------------------- 5) 旋转 + 镜像组合

    @Test
    public void rotationAndMirrorComposeInTheDocumentedOrder() {
        // 顺序为「先旋转后镜像」：显示 = 镜像(旋转(解码帧))
        VideoGeometry geometry = VideoGeometry.createWithScale(100, 50, 50, 100,
                VideoScaleType.FIT_CENTER, 90, true, false);

        // 四个角逐一手算：
        // 显示(0,0)  → u=0,v=0 → 镜像X 后 u=1 → 90°: (v·w, (1-u)·h) = (0, 0)
        assertEquals(0f, map(geometry, 0f, 0f).videoX, EPS);
        assertEquals(0f, map(geometry, 0f, 0f).videoY, EPS);
        // 显示(50,0) → u=1,v=0 → 镜像X 后 u=0 → (0, 50)
        assertEquals(0f, map(geometry, 50f, 0f).videoX, EPS);
        assertEquals(50f, map(geometry, 50f, 0f).videoY, EPS);
        // 显示(0,100) → u=0,v=1 → 镜像X 后 u=1 → (100, 0)
        assertEquals(100f, map(geometry, 0f, 100f).videoX, EPS);
        assertEquals(0f, map(geometry, 0f, 100f).videoY, EPS);
        // 显示(50,100) → u=1,v=1 → 镜像X 后 u=0 → (100, 50)
        assertEquals(100f, map(geometry, 50f, 100f).videoX, EPS);
        assertEquals(50f, map(geometry, 50f, 100f).videoY, EPS);
        // 中心在旋转/镜像下都不动
        assertEquals(50f, map(geometry, 25f, 50f).videoX, EPS);
        assertEquals(25f, map(geometry, 25f, 50f).videoY, EPS);
    }

    @Test
    public void mirrorXPlusMirrorYEqualsRotation180() {
        // 不变式：显示坐标系上「先水平镜像再垂直镜像」== 旋转 180°，两者映射结果必须完全一致
        VideoGeometry bothMirrors = VideoGeometry.createWithScale(160, 90, 160, 90,
                VideoScaleType.FIT_CENTER, 0, true, true);
        VideoGeometry rotated = VideoGeometry.createWithScale(160, 90, 160, 90,
                VideoScaleType.FIT_CENTER, 180, false, false);

        for (float x = 0f; x <= 160f; x += 32f) {
            for (float y = 0f; y <= 90f; y += 30f) {
                assertEquals(map(bothMirrors, x, y).videoX, map(rotated, x, y).videoX, EPS);
                assertEquals(map(bothMirrors, x, y).videoY, map(rotated, x, y).videoY, EPS);
            }
        }
    }

    @Test
    public void rotationAndMirrorComposeWithCenterCrop() {
        // 90° 旋转 + 铺满：4:3 视频转 90° 后是 3:4，铺进 1:1 的 View 需要裁掉显示方向的纵向
        VideoGeometry geometry = VideoGeometry.createWithScale(400, 300, 100, 100,
                VideoScaleType.CENTER_CROP, 90, false, false);
        assertTrue(geometry.isUsable());
        assertEquals(0, geometry.contentLeft);
        assertEquals(100, geometry.contentRight);
        assertEquals(0, geometry.contentTop);
        assertEquals(100, geometry.contentBottom);

        // 显示帧 300x400 铺进 100x100：scale = max(1/3, 1/4) = 1/3，可见 v ∈ [0.125, 0.875]
        VideoPointMappingResult center = map(geometry, 50f, 50f);
        assertEquals(VideoPointMappingStatus.SUCCESS, center.status);
        assertEquals(200f, center.videoX, EPS);
        assertEquals(150f, center.videoY, EPS);

        // 旋转 90° 后：显示横向(viewX) 对应解码帧纵向(videoY)，完整可见
        assertEquals(300f, map(geometry, 0f, 50f).videoY, EPS);
        assertEquals(0f, map(geometry, 100f, 50f).videoY, EPS);
        // 显示纵向(viewY) 对应解码帧横向(videoX)，被裁到 [50, 350]
        assertEquals(50f, map(geometry, 50f, 0f).videoX, EPS);
        assertEquals(350f, map(geometry, 50f, 100f).videoX, EPS);
    }

    // ---------------------------------------------------- 6) 非法几何与边界

    @Test
    public void invalidOrDegenerateGeometryIsReportedAsInvalidGeometry() {
        assertSame(VideoPointMappingStatus.INVALID_GEOMETRY, map(null, 1f, 1f).status);
        assertSame(VideoPointMappingStatus.INVALID_GEOMETRY, map(VideoGeometry.EMPTY, 1f, 1f).status);
        assertSame(VideoPointMappingStatus.INVALID_GEOMETRY,
                map(VideoGeometry.create(0, 50, 100, 50), 1f, 1f).status);
        assertSame(VideoPointMappingStatus.INVALID_GEOMETRY,
                map(VideoGeometry.create(100, 50, 0, 50), 1f, 1f).status);
        // 内容区越界 → 不可用（宁可报 INVALID_GEOMETRY 也不猜坐标）
        assertSame(VideoPointMappingStatus.INVALID_GEOMETRY,
                map(VideoGeometry.create(100, 50, 100, 50, 0, 0, 120, 50, 0, false, false), 1f, 1f).status);

        // 与 OUTSIDE_VIDEO 是两回事：前者是环境问题，后者是用户点了黑边
        assertFalse(map(null, 1f, 1f).insideVideoContent);
    }

    @Test
    public void mappedCoordinatesNeverLeaveVideoBounds() {
        VideoGeometry[] geometries = {
                VideoGeometry.createWithScale(1280, 720, 640, 480, VideoScaleType.FIT_CENTER),
                VideoGeometry.createWithScale(1280, 720, 640, 480, VideoScaleType.CENTER_CROP),
                VideoGeometry.createWithScale(100, 50, 50, 100, VideoScaleType.FIT_CENTER, 90, true, false),
                VideoGeometry.createWithScale(100, 50, 50, 100, VideoScaleType.FIT_CENTER, 270, false, true),
                VideoGeometry.createWithScale(400, 300, 100, 100, VideoScaleType.CENTER_CROP, 180, true, true),
        };
        for (VideoGeometry geometry : geometries) {
            for (float x = -10f; x <= geometry.viewWidth + 10f; x += 7f) {
                for (float y = -10f; y <= geometry.viewHeight + 10f; y += 7f) {
                    VideoPointMappingResult result = map(geometry, x, y);
                    if (result.status != VideoPointMappingStatus.SUCCESS) {
                        continue;
                    }
                    assertTrue("videoX 越界：" + result.debugString(),
                            result.videoX >= 0f && result.videoX <= geometry.videoWidth);
                    assertTrue("videoY 越界：" + result.debugString(),
                            result.videoY >= 0f && result.videoY <= geometry.videoHeight);
                    assertTrue("normalizedX 越界：" + result.debugString(),
                            result.normalizedX >= 0f && result.normalizedX <= 1f);
                    assertTrue("normalizedY 越界：" + result.debugString(),
                            result.normalizedY >= 0f && result.normalizedY <= 1f);
                }
            }
        }
    }

    @Test
    public void contentEdgesAreInclusiveAndAnythingOutsideIsRejected() {
        VideoGeometry geometry = VideoGeometry.createWithScale(200, 100, 100, 100, VideoScaleType.FIT_CENTER);

        // 边界本身算命中
        assertSame(VideoPointMappingStatus.SUCCESS, map(geometry, 0f, 25f).status);
        assertSame(VideoPointMappingStatus.SUCCESS, map(geometry, 100f, 75f).status);
        // 边界点映射出的坐标必须精确等于视频边界，不能因取整漂到界外
        assertEquals(0f, map(geometry, 0f, 25f).videoX, EPS);
        assertEquals(0f, map(geometry, 0f, 25f).videoY, EPS);
        assertEquals(200f, map(geometry, 100f, 75f).videoX, EPS);
        assertEquals(100f, map(geometry, 100f, 75f).videoY, EPS);

        // 内侧容差仍算命中；外侧一律拒绝（黑边就是黑边，不做无意义的宽容）
        assertSame(VideoPointMappingStatus.SUCCESS, map(geometry, 50f, 25f + 1e-4f).status);
        assertSame(VideoPointMappingStatus.OUTSIDE_VIDEO, map(geometry, 50f, 25f - 1e-4f).status);
        assertSame(VideoPointMappingStatus.OUTSIDE_VIDEO, map(geometry, 50f, 75f + 0.5f).status);
        assertSame(VideoPointMappingStatus.OUTSIDE_VIDEO, map(geometry, -1e-4f, 50f).status);
    }

    @Test
    public void explicitContentRectGeometryStillMapsLinearly() {
        // Slice 0 的 11 参工厂（调用方自带 contentRect）语义必须保持不变：内容区内线性映射
        VideoGeometry geometry = VideoGeometry.create(200, 100, 100, 100, 0, 25, 100, 75, 0, false, false);
        assertEquals(VideoScaleType.FIT_CENTER, geometry.scaleType);

        VideoPointMappingResult result = map(geometry, 50f, 50f);
        assertEquals(VideoPointMappingStatus.SUCCESS, result.status);
        assertEquals(100f, result.videoX, EPS);
        assertEquals(50f, result.videoY, EPS);
        assertSame(VideoPointMappingStatus.OUTSIDE_VIDEO, map(geometry, 50f, 10f).status);
    }

    // ---------------------------------------------------- 7) 内容区推导

    @Test
    public void deriveContentRectHandlesFitCenterAndCenterCrop() {
        assertArrayEquals(new int[]{0, 25, 100, 75},
                VideoCoordinateMapper.deriveContentRect(100, 100, 200, 100, VideoScaleType.FIT_CENTER, 0));
        assertArrayEquals(new int[]{50, 0, 150, 100},
                VideoCoordinateMapper.deriveContentRect(200, 100, 100, 100, VideoScaleType.FIT_CENTER, 0));
        assertArrayEquals(new int[]{0, 0, 100, 100},
                VideoCoordinateMapper.deriveContentRect(100, 100, 200, 100, VideoScaleType.CENTER_CROP, 0));
        // 无缩放需求时两者一致
        assertArrayEquals(new int[]{0, 0, 1920, 1080},
                VideoCoordinateMapper.deriveContentRect(1920, 1080, 1280, 720, VideoScaleType.FIT_CENTER, 0));
    }

    @Test
    public void deriveContentRectSwapsAxesWhenRotatedBy90Or270() {
        // 100x50 转 90° 后是 50x100，铺进方形 View 会左右留黑边
        assertArrayEquals(new int[]{25, 0, 75, 100},
                VideoCoordinateMapper.deriveContentRect(100, 100, 100, 50, VideoScaleType.FIT_CENTER, 90));
        assertArrayEquals(new int[]{25, 0, 75, 100},
                VideoCoordinateMapper.deriveContentRect(100, 100, 100, 50, VideoScaleType.FIT_CENTER, 270));
        // 180 不交换
        assertArrayEquals(new int[]{0, 25, 100, 75},
                VideoCoordinateMapper.deriveContentRect(100, 100, 100, 50, VideoScaleType.FIT_CENTER, 180));

        assertTrue(VideoCoordinateMapper.isAxisSwapped(90));
        assertTrue(VideoCoordinateMapper.isAxisSwapped(270));
        assertFalse(VideoCoordinateMapper.isAxisSwapped(0));
        assertFalse(VideoCoordinateMapper.isAxisSwapped(180));
    }

    @Test
    public void deriveContentRectReturnsNullForInvalidSizes() {
        assertNull(VideoCoordinateMapper.deriveContentRect(0, 100, 200, 100, VideoScaleType.FIT_CENTER, 0));
        assertNull(VideoCoordinateMapper.deriveContentRect(100, 100, 0, 100, VideoScaleType.FIT_CENTER, 0));
        assertNull(VideoCoordinateMapper.deriveContentRect(-1, 100, 200, 100, VideoScaleType.FIT_CENTER, 0));
    }

    @Test
    public void derivedGeometryStaysUsableAndNeverExceedsTheView() {
        // 各种“除不尽”的尺寸组合，取整后都不能把内容区挤出 View（否则 isUsable 会假阴性）
        int[][] cases = {{101, 37, 333, 199}, {1, 1, 7, 3}, {1920, 1080, 1919, 1079}, {37, 101, 199, 333}};
        for (int[] c : cases) {
            for (VideoScaleType scale : VideoScaleType.values()) {
                for (int rotation : new int[]{0, 90, 180, 270}) {
                    VideoGeometry geometry = VideoGeometry.createWithScale(c[0], c[1], c[2], c[3], scale, rotation, false, false);
                    assertTrue("衍生几何必须可用：" + geometry, geometry.isUsable());
                    assertTrue("内容区不得越界：" + geometry, geometry.contentLeft >= 0 && geometry.contentTop >= 0);
                    assertTrue("内容区不得越界：" + geometry,
                            geometry.contentRight <= geometry.viewWidth && geometry.contentBottom <= geometry.viewHeight);
                }
            }
        }
    }

    // ---------------------------------------------------- 8) 与 VideoGeometry 的委托一致

    @Test
    public void geometryConvenienceEntryMatchesTheMapperExactly() {
        VideoGeometry geometry = VideoGeometry.createWithScale(200, 100, 100, 100, VideoScaleType.CENTER_CROP);
        VideoPointMappingResult result = map(geometry, 30f, 70f);
        float[] delegated = geometry.mapViewPointToVideo(30f, 70f);

        assertSame(VideoPointMappingStatus.SUCCESS, result.status);
        assertEquals(result.videoX, delegated[0], EPS);
        assertEquals(result.videoY, delegated[1], EPS);
        // 黑边点：mapper 给 OUTSIDE_VIDEO，便捷入口给 null（两者语义一致）
        assertNull(geometry.mapViewPointToVideo(-5f, -5f));
        assertSame(VideoPointMappingStatus.OUTSIDE_VIDEO, map(geometry, -5f, -5f).status);
        // 退化几何：便捷入口同样返回 null
        assertNull(VideoGeometry.EMPTY.mapViewPointToVideo(1f, 1f));
    }
}
