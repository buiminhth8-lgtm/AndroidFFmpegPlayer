package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.api.TouchSelectionRequest;
import com.example.motro.targetselection.debug.TargetDebugState;
import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoPointMappingResult;
import com.example.motro.targetselection.geometry.VideoPointMappingStatus;
import com.example.motro.targetselection.geometry.VideoScaleType;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link TargetDebugState} —— 叠加层的数据契约。
 *
 * <p>这个类被刻意做成纯 JVM（不含任何 Android 类型），所以状态推导可以在这里穷举：
 * 回投影、几何代理、"没数据时不留脏值"这些都断言得动，不必依赖设备。</p>
 */
public class TargetDebugStateTest {

    private static final float EPS = 1e-3f;

    /** 1280x720 视频放进 640x480 的 View，FIT_CENTER → 内容区 [0,60,640,420]。 */
    private static VideoGeometry letterbox() {
        return VideoGeometry.createWithScale(1280, 720, 640, 480, VideoScaleType.FIT_CENTER);
    }

    // ------------------------------------------------------------------ 成功映射

    @Test
    public void successfulMappingIsProjectedBackOntoTheTouchPoint() {
        VideoGeometry geometry = letterbox();
        VideoPointMappingResult mapping = VideoCoordinateMapper.map(geometry, 200f, 270f);
        assertEquals(VideoPointMappingStatus.SUCCESS, mapping.status);

        TargetDebugState state = TargetDebugState.of(mapping, 12345L);

        // 反解得到的视频坐标
        assertEquals(400f, state.videoX(), EPS);
        assertEquals(420f, state.videoY(), EPS);
        assertEquals(0.3125f, state.normalizedX(), EPS);
        assertEquals(0.58333f, state.normalizedY(), EPS);

        // 正推回 View 必须还原触摸点 —— 这是「正反共用一套推导」的直接证据
        assertTrue(state.hasMappedViewPoint());
        assertEquals(200f, state.mappedViewX, EPS);
        assertEquals(270f, state.mappedViewY, EPS);
        assertTrue("往返误差应≈0，实际 " + state.roundTripErrorPx, state.roundTripErrorPx <= 0.02f);

        // 触摸点与时刻原样保留
        assertEquals(200f, state.touchViewX(), EPS);
        assertEquals(270f, state.touchViewY(), EPS);
        assertEquals(12345L, state.eventTimeNs);
        assertEquals(VideoPointMappingStatus.SUCCESS, state.status());
    }

    @Test
    public void roundTripStaysExactAcrossRotationAndMirror() {
        final int[] rotations = {0, 90, 180, 270};
        for (int rotation : rotations) {
            for (boolean mirrorX : new boolean[]{false, true}) {
                for (boolean mirrorY : new boolean[]{false, true}) {
                    VideoGeometry geometry = VideoGeometry.createWithScale(
                            1280, 720, 640, 480, VideoScaleType.FIT_CENTER, rotation, mirrorX, mirrorY);
                    // 内容区中心在 4 种旋转下都可见，且镜像不该改变它
                    float centerX = (geometry.contentLeft + geometry.contentRight) / 2f;
                    float centerY = (geometry.contentTop + geometry.contentBottom) / 2f;

                    VideoPointMappingResult mapping = VideoCoordinateMapper.map(geometry, centerX, centerY);
                    assertEquals(VideoPointMappingStatus.SUCCESS, mapping.status);

                    TargetDebugState state = TargetDebugState.of(mapping);
                    String label = "rotation=" + rotation + " mirror=" + mirrorX + "," + mirrorY;
                    assertTrue(label + "：中心点回投影误差应≈0，实际 " + state.roundTripErrorPx,
                            state.roundTripErrorPx <= 0.02f);
                    assertEquals(label + "：回投影应落在内容区中心", centerX, state.mappedViewX, 0.02f);
                    assertEquals(label + "：回投影应落在内容区中心", centerY, state.mappedViewY, 0.02f);
                }
            }
        }
    }

    // ------------------------------------------------------------------ 没有坐标的两种状态

    @Test
    public void outsideVideoKeepsTouchPointButCarriesNoCoordinates() {
        VideoGeometry geometry = letterbox();
        // y=20 落在上黑边（内容区从 y=60 开始）
        VideoPointMappingResult mapping = VideoCoordinateMapper.map(geometry, 200f, 20f);
        assertEquals(VideoPointMappingStatus.OUTSIDE_VIDEO, mapping.status);

        TargetDebugState state = TargetDebugState.of(mapping, TouchSelectionRequest.NO_EVENT_TIME_NS);

        assertEquals(VideoPointMappingStatus.OUTSIDE_VIDEO, state.status());
        // 触摸点要留着（叠加层仍然要画出用户点在哪）
        assertEquals(200f, state.touchViewX(), EPS);
        assertEquals(20f, state.touchViewY(), EPS);
        // 但绝不能带坐标 —— 否则叠加层会画出一个不存在的“命中点”
        assertTrue(Float.isNaN(state.videoX()));
        assertTrue(Float.isNaN(state.videoY()));
        assertTrue(Float.isNaN(state.mappedViewX));
        assertTrue(Float.isNaN(state.mappedViewY));
        assertTrue(Float.isNaN(state.roundTripErrorPx));
        assertFalse(state.hasMappedViewPoint());
        // 几何本身仍然可用（黑边正是几何告诉我们的）
        assertTrue(state.hasGeometry());
        assertEquals(60, state.contentTop());
    }

    @Test
    public void invalidGeometryCarriesNothingButStillHoldsTheTouchPoint() {
        VideoPointMappingResult mapping = VideoCoordinateMapper.map(VideoGeometry.EMPTY, 10f, 10f);
        assertEquals(VideoPointMappingStatus.INVALID_GEOMETRY, mapping.status);

        TargetDebugState state = TargetDebugState.of(mapping);

        assertEquals(VideoPointMappingStatus.INVALID_GEOMETRY, state.status());
        assertFalse("退化几何不应被报告为可用", state.hasGeometry());
        assertFalse(state.hasMappedViewPoint());
        assertEquals(10f, state.touchViewX(), EPS);
        // 几何缺失时所有几何代理值必须是中性值，不能是脏数据
        assertEquals(0, state.contentLeft());
        assertEquals(0, state.contentTop());
        assertEquals(0, state.contentRight());
        assertEquals(0, state.contentBottom());
        assertEquals(0, state.videoWidth());
        assertEquals(0, state.videoHeight());
        assertEquals(0, state.rotationDegrees());
        assertFalse(state.mirrorX());
        assertFalse(state.mirrorY());
        assertEquals(VideoScaleType.FIT_CENTER, state.scaleType());
    }

    @Test
    public void nullGeometryIsHandledTheSameWayAsDegenerateGeometry() {
        VideoPointMappingResult mapping = VideoCoordinateMapper.map(null, 5f, 5f);
        assertEquals(VideoPointMappingStatus.INVALID_GEOMETRY, mapping.status);

        TargetDebugState state = TargetDebugState.of(mapping);
        assertFalse(state.hasGeometry());
        assertFalse(state.hasMappedViewPoint());
        assertEquals(0, state.videoWidth());
        assertNotNull(state.debugString());   // 不应 NPE
    }

    // ------------------------------------------------------------------ 几何信息可读

    @Test
    public void geometryFieldsAreExposedForTheOverlay() {
        // 1920x1080 视频、90° 旋转 + 水平镜像、铺满 1080x1920 —— 几个字段都有明确取值
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1920, 1080, 1080, 1920, VideoScaleType.CENTER_CROP, 90, true, false);
        assertTrue(geometry.isUsable());

        TargetDebugState state = TargetDebugState.of(VideoCoordinateMapper.map(geometry, 540f, 960f));

        assertEquals(1920, state.videoWidth());
        assertEquals(1080, state.videoHeight());
        assertEquals(90, state.rotationDegrees());
        assertTrue(state.mirrorX());
        assertFalse(state.mirrorY());
        assertEquals(VideoScaleType.CENTER_CROP, state.scaleType());
        assertEquals(geometry.contentLeft, state.contentLeft());
        assertEquals(geometry.contentTop, state.contentTop());
        assertEquals(geometry.contentRight, state.contentRight());
        assertEquals(geometry.contentBottom, state.contentBottom());

        // 旋转 + 镜像组合下，中心点仍然要能原样回来
        assertEquals(540f, state.mappedViewX, 0.02f);
        assertEquals(960f, state.mappedViewY, 0.02f);
    }

    @Test
    public void debugStringCarriesRotationMirrorAndRoundTripError() {
        VideoGeometry geometry = VideoGeometry.createWithScale(
                1280, 720, 640, 480, VideoScaleType.FIT_CENTER, 90, true, false);
        TargetDebugState state = TargetDebugState.of(
                VideoCoordinateMapper.map(geometry, 320f, 240f), 777L);

        String text = state.debugString();
        assertTrue(text, text.contains("rotation=90"));
        assertTrue(text, text.contains("mirror=true,false"));
        assertTrue(text, text.contains("status=SUCCESS"));
        assertTrue(text, text.contains("mappedView="));
        assertTrue(text, text.contains("roundTripErrPx="));
        assertTrue(text, text.contains("eventTimeNs=777"));
    }

    // ------------------------------------------------------------------ 预留字段

    @Test
    public void reservedFieldsAreEmptyByDefault() {
        TargetDebugState state = TargetDebugState.of(
                VideoCoordinateMapper.map(letterbox(), 200f, 270f));

        assertNull(state.searchRoi);
        assertTrue(state.detectedBoxes.isEmpty());
        assertNull(state.selectedBox);
        assertNull(state.labelRoi);
        assertNull(state.targetId);
        assertEquals(0f, state.confidence, 0f);
        assertEquals(0L, state.processingMs);
    }

    @Test
    public void builderCarriesReservedFieldsAndCopiesTheBoxList() {
        VideoPointMappingResult mapping = VideoCoordinateMapper.map(letterbox(), 200f, 270f);
        List<TargetBoundingBox> boxes = new ArrayList<>();
        boxes.add(TargetBoundingBox.of(10f, 20f, 30f, 40f));

        TargetDebugState state = TargetDebugState.builder(mapping, 42L)
                .searchRoi(TargetBoundingBox.of(0f, 0f, 100f, 100f))
                .detectedBoxes(boxes)
                .selectedBox(TargetBoundingBox.of(5f, 5f, 15f, 15f))
                .labelRoi(TargetBoundingBox.of(1f, 1f, 2f, 2f))
                .targetId("T-1")
                .confidence(0.75f)
                .processingMs(12L)
                .build();

        // 构造后修改来源列表不得影响已建状态（防御性拷贝）
        boxes.add(TargetBoundingBox.of(50f, 50f, 60f, 60f));
        assertEquals(1, state.detectedBoxes.size());

        assertNotNull(state.searchRoi);
        assertNotNull(state.selectedBox);
        assertNotNull(state.labelRoi);
        assertEquals("T-1", state.targetId);
        assertEquals(0.75f, state.confidence, 0f);
        assertEquals(12L, state.processingMs);
        assertSame("顺带确认核心字段没被 builder 丢掉", mapping, state.mapping);

        // 列表本身不可修改
        try {
            state.detectedBoxes.add(TargetBoundingBox.of(0f, 0f, 1f, 1f));
            fail("detectedBoxes 应为不可修改列表");
        } catch (UnsupportedOperationException expected) {
            // 预期
        }

        // null 列表视为空
        TargetDebugState nullBoxes = TargetDebugState.builder(mapping, 42L)
                .detectedBoxes(null)
                .build();
        assertTrue(nullBoxes.detectedBoxes.isEmpty());
    }

    @Test
    public void toBuilderKeepsCoreAndReservedFields() {
        VideoPointMappingResult mapping = VideoCoordinateMapper.map(letterbox(), 200f, 270f);
        TargetDebugState first = TargetDebugState.builder(mapping, 9L)
                .detectedBoxes(Arrays.asList(TargetBoundingBox.of(1f, 1f, 2f, 2f)))
                .targetId("T-7")
                .confidence(0.5f)
                .processingMs(3L)
                .build();

        TargetDebugState second = first.toBuilder().build();

        assertSame(mapping, second.mapping);
        assertEquals(9L, second.eventTimeNs);
        assertEquals("T-7", second.targetId);
        assertEquals(0.5f, second.confidence, 0f);
        assertEquals(3L, second.processingMs);
        assertEquals(1, second.detectedBoxes.size());
        // 推导字段也应一致（toBuilder 不该把回投影丢掉）
        assertEquals(first.mappedViewX, second.mappedViewX, 0f);
        assertEquals(first.mappedViewY, second.mappedViewY, 0f);
    }

    // ------------------------------------------------------------------ 参数校验

    @Test
    public void nullMappingIsRejected() {
        try {
            TargetDebugState.of(null);
            fail("mapping 为 null 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    @Test
    public void wallClockStyleNegativeEventTimeIsRejected() {
        VideoPointMappingResult mapping = VideoCoordinateMapper.map(letterbox(), 200f, 270f);
        try {
            TargetDebugState.of(mapping, -5L);
            fail("负数（且非哨兵）时刻应被拒绝，避免把墙上时间当成单调时间");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        // 哨兵值必须放行
        assertEquals(TouchSelectionRequest.NO_EVENT_TIME_NS,
                TargetDebugState.of(mapping, TouchSelectionRequest.NO_EVENT_TIME_NS).eventTimeNs);
    }

    @Test
    public void invalidReservedValuesAreRejected() {
        VideoPointMappingResult mapping = VideoCoordinateMapper.map(letterbox(), 200f, 270f);

        assertBuilderRejects("confidence 超出 [0,1]",
                () -> TargetDebugState.builder(mapping, 0L).confidence(1.5f));
        assertBuilderRejects("confidence 为 NaN",
                () -> TargetDebugState.builder(mapping, 0L).confidence(Float.NaN));
        assertBuilderRejects("processingMs 为负",
                () -> TargetDebugState.builder(mapping, 0L).processingMs(-1L));
        assertBuilderRejects("detectedBoxes 含 null",
                () -> TargetDebugState.builder(mapping, 0L)
                        .detectedBoxes(Arrays.asList((TargetBoundingBox) null)));
    }

    private static void assertBuilderRejects(String what, Runnable action) {
        try {
            action.run();
            fail(what + " 应抛 IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }
}
