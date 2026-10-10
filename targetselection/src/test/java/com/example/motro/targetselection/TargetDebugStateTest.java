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
import com.example.motro.targetselection.vision.DetectedTargetBox;
import com.example.motro.targetselection.vision.RoiRect;
import com.example.motro.targetselection.vision.TargetBoxDetectionResult;
import com.example.motro.targetselection.vision.TargetBoxDetectionStatus;
import com.example.motro.targetselection.vision.TargetHitTester;
import com.example.motro.targetselection.vision.TargetHitTestResult;

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

    // ------------------------------------------------------------------ 矩形恢复阶段（Slice 5）

    @Test
    public void boxFieldsAreEmptyByDefault() {
        TargetDebugState state = TargetDebugState.of(
                VideoCoordinateMapper.map(letterbox(), 200f, 270f));

        assertNull(state.searchRoi);
        assertTrue(state.detectedBoxes.isEmpty());
        assertNull(state.boxStatus);
        assertEquals(0, state.candidateCount);
        assertNull(state.selectedBox);
        assertNull(state.labelRoi);
        assertNull(state.targetId);
        assertEquals(0f, state.confidence, 0f);
        assertEquals(0L, state.processingMs);
        assertEquals(0L, state.detectMs);
        assertEquals(0L, state.hitTestMs);

        // 「没数据」必须能被查询方法识别，否则叠加层无法决定"画不画这一段"
        assertFalse(state.hasVisionResult());
        assertFalse(state.hasBoxDetection());
        assertFalse(state.hasDetectedBoxes());
        assertFalse(state.isHit());
        assertEquals("未做检测时 debugString 不得出现 boxes 段",
                -1, state.debugString().indexOf("boxes="));
    }

    @Test
    public void builderCarriesBoxFieldsAndCopiesTheBoxList() {
        VideoPointMappingResult mapping = VideoCoordinateMapper.map(letterbox(), 200f, 270f);
        List<DetectedTargetBox> boxes = new ArrayList<>();
        boxes.add(box(10f, 20f, 30f, 40f, 0.8f, 0.9f, 4));

        TargetDebugState state = TargetDebugState.builder(mapping, 42L)
                .searchRoi(TargetBoundingBox.of(0f, 0f, 100f, 100f))
                .detectedBoxes(boxes)
                .boxStatus(TargetBoxDetectionStatus.SUCCESS)
                .candidateCount(3)
                .selectedBox(TargetBoundingBox.of(5f, 5f, 15f, 15f))
                .labelRoi(TargetBoundingBox.of(1f, 1f, 2f, 2f))
                .targetId("T-1")
                .confidence(0.75f)
                .processingMs(12L)
                .timings(7L, 2L)
                .build();

        // 构造后修改来源列表不得影响已建状态（防御性拷贝）
        boxes.add(box(50f, 50f, 60f, 60f, 0.5f, 0.5f, 2));
        assertEquals(1, state.detectedBoxes.size());

        assertNotNull(state.searchRoi);
        assertNotNull(state.selectedBox);
        assertNotNull(state.labelRoi);
        assertEquals(TargetBoxDetectionStatus.SUCCESS, state.boxStatus);
        assertEquals(3, state.candidateCount);
        assertEquals("T-1", state.targetId);
        assertEquals(0.75f, state.confidence, 0f);
        assertEquals(12L, state.processingMs);
        assertEquals(7L, state.detectMs);
        assertEquals(2L, state.hitTestMs);
        assertSame("顺带确认核心字段没被 builder 丢掉", mapping, state.mapping);
        assertTrue(state.hasBoxDetection());
        assertTrue(state.hasDetectedBoxes());
        assertTrue(state.isHit());

        // 列表本身不可修改
        try {
            state.detectedBoxes.add(box(0f, 0f, 1f, 1f, 0.5f, 0.5f, 2));
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

    /**
     * {@link TargetDebugState#withBoxDetection} 是叠加层的标准接法：它必须
     * 一次性填好候选、状态、计数、选中框、置信度与分步耗时，并把
     * {@code processingMs} 升级为总耗时（而不是停留在掩码那一步）。
     */
    @Test
    public void withBoxDetectionFillsTheWholeStageAndUpgradesTheTotalTime() {
        VideoPointMappingResult mapping = VideoCoordinateMapper.map(letterbox(), 200f, 270f);
        DetectedTargetBox best = box(100f, 100f, 300f, 250f, 1f, 1f, 4);
        DetectedTargetBox second = box(400f, 100f, 500f, 200f, 0.6f, 0.8f, 3);

        TargetHitTestResult hit = new TargetHitTester(12)
                .hitTest(180f, 170f, Arrays.asList(best, second));
        TargetBoxDetectionResult detection = TargetBoxDetectionResult
                .detected(Arrays.asList(best, second), 6L)
                .withHitTest(hit, 9L);

        // 先只挂掩码（processingMs 此时是掩码耗时），再挂检测结果
        TargetDebugState state = TargetDebugState.builder(mapping, 0L)
                .processingMs(2L)
                .build()
                .withBoxDetection(detection);

        assertEquals(TargetBoxDetectionStatus.SUCCESS, state.boxStatus());
        assertEquals(2, state.candidateCount());
        assertEquals(2, state.detectedBoxes.size());
        assertEquals("选中框必须取 rectInVideo", best.rectInVideo, state.selectedBox);
        assertEquals(1f, state.confidence, 0f);
        assertEquals(6L, state.detectMs);
        assertEquals("命中耗时取真实测量值", hit.processingMs, state.hitTestMs);
        assertEquals("processingMs 必须升级为 totalMs", 9L, state.processingMs);
        assertTrue(state.isHit());

        // 叠加层文本要能读出这些量
        final String text = state.debugString();
        assertTrue(text, text.contains("boxes=SUCCESS"));
        assertTrue(text, text.contains("candidates=2"));
        assertTrue(text, text.contains("detectMs=6"));
        assertTrue(text, text.contains("hitTestMs=" + hit.processingMs));
        assertTrue(text, text.contains("totalMs=9"));
    }

    /** 未命中（MISS）时不得留下选中框与置信度 —— 否则叠加层会画出一个并不存在的"命中框"。 */
    @Test
    public void withBoxDetectionOnMissKeepsSelectedBoxNull() {
        VideoPointMappingResult mapping = VideoCoordinateMapper.map(letterbox(), 200f, 270f);
        DetectedTargetBox only = box(100f, 100f, 300f, 250f, 0.9f, 0.9f, 4);

        TargetBoxDetectionResult detection = TargetBoxDetectionResult
                .detected(Arrays.asList(only), 4L);

        TargetDebugState state = TargetDebugState.builder(mapping, 0L)
                .processingMs(11L)
                .build()
                .withBoxDetection(detection);

        assertEquals(TargetBoxDetectionStatus.SUCCESS, state.boxStatus());
        assertEquals(1, state.detectedBoxes.size());
        assertNull("没有命中就不该有选中框", state.selectedBox);
        assertEquals(0f, state.confidence, 0f);
        assertFalse(state.isHit());
        assertTrue(state.debugString(), state.debugString().contains("selected=null"));
    }

    /** 失败路径也要如实反映（{@code NO_BOX} / {@code INVALID_MASK}），否则叠加层只能空着让人猜。 */
    @Test
    public void withBoxDetectionCarriesFailureStatus() {
        VideoPointMappingResult mapping = VideoCoordinateMapper.map(letterbox(), 200f, 270f);

        TargetDebugState noBox = TargetDebugState.of(mapping)
                .withBoxDetection(TargetBoxDetectionResult
                        .failure(TargetBoxDetectionStatus.NO_BOX, "no edges"));
        assertEquals(TargetBoxDetectionStatus.NO_BOX, noBox.boxStatus());
        assertTrue(noBox.hasBoxDetection());
        assertFalse(noBox.hasDetectedBoxes());
        assertEquals(0, noBox.candidateCount());

        TargetDebugState badMask = TargetDebugState.of(mapping)
                .withBoxDetection(TargetBoxDetectionResult
                        .failure(TargetBoxDetectionStatus.INVALID_MASK, "no roi"));
        assertEquals(TargetBoxDetectionStatus.INVALID_MASK, badMask.boxStatus());
    }

    @Test
    public void toBuilderKeepsCoreAndBoxFields() {
        VideoPointMappingResult mapping = VideoCoordinateMapper.map(letterbox(), 200f, 270f);
        TargetDebugState first = TargetDebugState.builder(mapping, 9L)
                .detectedBoxes(Arrays.asList(box(1f, 1f, 2f, 2f, 0.9f, 1f, 4)))
                .boxStatus(TargetBoxDetectionStatus.SUCCESS)
                .candidateCount(5)
                .targetId("T-7")
                .confidence(0.5f)
                .processingMs(3L)
                .timings(2L, 1L)
                .build();

        TargetDebugState second = first.toBuilder().build();

        assertSame(mapping, second.mapping);
        assertEquals(9L, second.eventTimeNs);
        assertEquals("T-7", second.targetId);
        assertEquals(0.5f, second.confidence, 0f);
        assertEquals(3L, second.processingMs);
        assertEquals(1, second.detectedBoxes.size());
        assertEquals(TargetBoxDetectionStatus.SUCCESS, second.boxStatus);
        assertEquals(5, second.candidateCount);
        assertEquals(2L, second.detectMs);
        assertEquals(1L, second.hitTestMs);
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
                        .detectedBoxes(Arrays.asList((DetectedTargetBox) null)));
        assertBuilderRejects("candidateCount 为负",
                () -> TargetDebugState.builder(mapping, 0L).candidateCount(-1));
        assertBuilderRejects("detectMs 为负",
                () -> TargetDebugState.builder(mapping, 0L).timings(-1L, 0L));
        assertBuilderRejects("hitTestMs 为负",
                () -> TargetDebugState.builder(mapping, 0L).timings(0L, -1L));
        assertBuilderRejects("boxDetection 为 null",
                () -> TargetDebugState.builder(mapping, 0L).boxDetection(null));
    }

    /** 造一个候选框替身：ROI 局部坐标随手给，测试只关心视频坐标与评分。 */
    private static DetectedTargetBox box(float left, float top, float right, float bottom,
                                         float confidence, float completeness, int presentEdges) {
        return DetectedTargetBox.of(RoiRect.of(0, 0, 10, 10),
                TargetBoundingBox.of(left, top, right, bottom),
                confidence, completeness, presentEdges, false);
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
