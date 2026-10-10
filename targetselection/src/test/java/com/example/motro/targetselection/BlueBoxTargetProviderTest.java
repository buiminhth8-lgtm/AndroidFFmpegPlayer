package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.api.TargetHitResult;
import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.api.TargetSelectionController;
import com.example.motro.targetselection.api.TargetSelectionStatus;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.frame.FrameCaptureException;
import com.example.motro.targetselection.frame.FrameCaptureRequest;
import com.example.motro.targetselection.frame.VideoFrameSource;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoGeometryProvider;
import com.example.motro.targetselection.geometry.VideoScaleType;
import com.example.motro.targetselection.vision.BlueBoxTargetProvider;
import com.example.motro.targetselection.vision.TargetCandidate;

import org.junit.Test;

/**
 * {@link BlueBoxTargetProvider} —— 把像素法检测接到既有 {@code TargetProvider} 契约上的适配器。
 *
 * <p>它本身逻辑很少，但<b>位置关键</b>：它是"Slice 5 的算法"与"Slice 0 定好的控制器管线"
 * 之间唯一的接缝。所以这里既测适配器自身的映射（命中→无 id 候选，未命中→null），
 * 也测<b>端到端</b>：真控制器 + 真适配器，看它到底能不能产出 {@code HIT_ID_UNKNOWN}
 * 与 {@code MISS} —— 这正是本 Slice 对外的交付承诺。</p>
 */
public class BlueBoxTargetProviderTest {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 600;
    private static final float EPS = 1.5f;

    // ---------------------------------------------------------------- 适配器自身

    @Test
    public void hitInsideTheBoxReturnsABoxWithoutId() {
        BlueBoxTargetProvider provider = provider(null);

        TargetCandidate candidate = provider.hitTest(frameFor(boxCanvas()), 270f, 160f);

        assertNotNull("点在框内必须给出候选", candidate);
        assertFalse("本 Slice 不做 id 识别，只给框；控制器据此产出 HIT_ID_UNKNOWN",
                candidate.hasId());
        assertEquals(120f, candidate.boundingBox.left, EPS);
        assertEquals(40f, candidate.boundingBox.top, EPS);
        assertEquals(420f, candidate.boundingBox.right, EPS);
        assertEquals(280f, candidate.boundingBox.bottom, EPS);
        assertTrue("四边齐全的置信度应当是满分", candidate.confidence > 0.95f);
    }

    /** 未命中必须返回 null —— 控制器把 null 翻译成 MISS，别在这里造一个"空候选"。 */
    @Test
    public void missReturnsNull() {
        BlueBoxTargetProvider provider = provider(null);

        assertNull(provider.hitTest(frameFor(boxCanvas()), 700f, 500f));
    }

    /** ROI 里压根没有框时也是 null（控制器不必区分是"没框"还是"没点中"）。 */
    @Test
    public void noBoxInRoiReturnsNull() {
        BlueBoxTargetProvider provider = provider(null);

        assertNull(provider.hitTest(frameFor(canvas()), 400f, 300f));
    }

    @Test
    public void constructorRejectsNullArguments() {
        VideoGeometryProvider geometry = new FixedGeometryProvider(null);
        try {
            new BlueBoxTargetProvider(null, TargetSelectionConfig.defaults());
            throw new AssertionError("geometryProvider 为 null 应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
        try {
            new BlueBoxTargetProvider(geometry, null);
            throw new AssertionError("config 为 null 应被拒绝");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }

    /** 本类不持有需要释放的资源，close() 必须是安全的空操作（否则控制器 finally 里会炸）。 */
    @Test
    public void closeIsANoOp() {
        BlueBoxTargetProvider provider = provider(null);
        provider.close();
        provider.close();
    }

    @Test
    public void configIsTheOneItWasBuilt() {
        TargetSelectionConfig config = TargetSelectionConfig.defaults();
        assertSame(config, new BlueBoxTargetProvider(new FixedGeometryProvider(null), config).config());
    }

    // ---------------------------------------------------------------- 端到端：控制器产出 HIT_ID_UNKNOWN / MISS

    /**
     * 控制器链路上真实命中：{@code HIT_ID_UNKNOWN}（有框、没有 id）。
     *
     * <p>这条用例把本 Slice 的交付承诺钉死：算法不是只在单测里能跑，
     * 而是<b>接上既有控制器管线后</b>能给出一个上层可用的结果。</p>
     */
    @Test
    public void controllerReportsHitIdUnknownForABlueBox() {
        TargetSelectionController controller = controller();

        TargetHitResult result = controller.selectTargetAtViewPoint(270f, 160f);

        assertEquals(TargetSelectionStatus.HIT_ID_UNKNOWN, result.status);
        assertTrue(result.isHit());
        assertFalse("没有 id，只能算未解析出 id", result.hasResolvedId());
        assertNull("本 Slice 不得凭空给一个 id", result.targetId);
        assertNotNull(result.boundingBox);
        assertEquals(120f, result.boundingBox.left, EPS);
        assertEquals(280f, result.boundingBox.bottom, EPS);
        assertEquals(270f, result.touchVideoX, 0.01f);
    }

    @Test
    public void controllerReportsMissWhenTappingOutside() {
        TargetSelectionController controller = controller();

        TargetHitResult result = controller.selectTargetAtViewPoint(700f, 500f);

        assertEquals(TargetSelectionStatus.MISS, result.status);
        assertFalse(result.isHit());
        assertNull(result.boundingBox);
    }

    /**
     * 只认 id 的调用方（{@code reportUnknownTargetId=false}）拿到的是 MISS，
     * 而不是一个"有框没名字"的半成品 —— 降级必须发生在控制器里，不能漏到上层。
     */
    @Test
    public void controllerDowngradesToMissWhenTheCallerOnlyAcceptsIds() {
        TargetSelectionConfig config = TargetSelectionConfig.defaults().toBuilder()
                .reportUnknownTargetId(false)
                .build();
        TargetSelectionController controller = new TargetSelectionController(
                new StaticFrameSource(boxCanvas()),
                new FixedGeometryProvider(oneToOneGeometry()),
                providerFor(config),
                config);

        TargetHitResult result = controller.selectTargetAtViewPoint(270f, 160f);

        assertEquals(TargetSelectionStatus.MISS, result.status);
    }

    // ---------------------------------------------------------------- 辅助

    /** 造一个 provider；几何为 null 表示帧与视频 1:1（直接把解码帧交给分析通道）。 */
    private static BlueBoxTargetProvider provider(VideoGeometry geometry) {
        return new BlueBoxTargetProvider(new FixedGeometryProvider(geometry),
                TargetSelectionConfig.defaults());
    }

    private static BlueBoxTargetProvider providerFor(TargetSelectionConfig config) {
        return new BlueBoxTargetProvider(new FixedGeometryProvider(null), config);
    }

    /** 真控制器 + 真适配器 + 1:1 几何（视频 800x600、View 800x600，View 坐标即视频坐标）。 */
    private static TargetSelectionController controller() {
        final TargetSelectionConfig config = TargetSelectionConfig.defaults();
        return new TargetSelectionController(
                new StaticFrameSource(boxCanvas()),
                new FixedGeometryProvider(oneToOneGeometry()),
                new BlueBoxTargetProvider(new FixedGeometryProvider(oneToOneGeometry()), config),
                config);
    }

    /** 1:1 几何：不缩放、不旋转、不镜像，于是 View 坐标与视频坐标逐点相等（便于读断言）。 */
    private static VideoGeometry oneToOneGeometry() {
        return VideoGeometry.createWithScale(WIDTH, HEIGHT, WIDTH, HEIGHT, VideoScaleType.FIT_CENTER);
    }

    private static int[] canvas() {
        return TestImages.solid(WIDTH, HEIGHT, TestImages.GRAY_BACKGROUND);
    }

    /** 与真实样本同量级的框（300x240），点击点取在"能同时框住对边"的中心区。 */
    private static int[] boxCanvas() {
        int[] pixels = canvas();
        TestImages.strokeRect(pixels, WIDTH, HEIGHT, 120, 40, 300, 240, 4, TestImages.SHIFTED_BLUE);
        return pixels;
    }

    private static CapturedFrame frameFor(int[] pixels) {
        return CapturedFrame.ofImage(ArgbFrameImage.of(WIDTH, HEIGHT, pixels), 0L, 0L);
    }

    /**
     * 每次取帧都返回<b>新的</b> {@link CapturedFrame}。
     *
     * <p>不能复用同一个实例：控制器在 finally 里会 {@code release()} 它，
     * 第二次调用就会拿到一个已释放的帧 —— 那测出来的是"桩写得不对"，不是产品行为。</p>
     */
    private static final class StaticFrameSource implements VideoFrameSource {
        private final int[] pixels;

        StaticFrameSource(int[] pixels) {
            this.pixels = pixels;
        }

        @Override
        public CapturedFrame captureFrame(FrameCaptureRequest request) throws FrameCaptureException {
            return frameFor(pixels);
        }
    }

    private static final class FixedGeometryProvider implements VideoGeometryProvider {
        private final VideoGeometry geometry;

        FixedGeometryProvider(VideoGeometry geometry) {
            this.geometry = geometry;
        }

        @Override
        public VideoGeometry currentGeometry() {
            return geometry;
        }
    }
}
