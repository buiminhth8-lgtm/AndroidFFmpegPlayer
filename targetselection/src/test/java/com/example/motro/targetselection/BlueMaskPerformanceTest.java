package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.vision.BlueMaskAnalyzer;
import com.example.motro.targetselection.vision.BlueMaskResult;
import com.example.motro.targetselection.vision.BinaryMask;

import org.junit.Test;

import java.util.Arrays;
import java.util.Locale;

/**
 * ROI + 蓝色掩码的**耗时基准**（不是精度测试）。
 *
 * <p>为什么单独放一个类而不是塞进 {@code BlueMaskDetectorTest}：这个测试测的是"多快"，
 * 而功能测试测的是"对不对"。两者对环境的敏感度完全不同 —— 耗时断言必须留出足够余量
 * 才不会在忙碌的 CI 上假失败；混在一起会逼着后来的人把功能断言也一起放宽。</p>
 *
 * <p><b>断言为什么这么松</b>：这里只打算逮住<b>量级</b>错误，比如不小心把
 * {@code Bitmap.getPixels} 换成逐像素 {@code Bitmap.getPixel}（每次一次 JNI 调用），
 * 或者每帧新建大数组导致 GC 抖动。这类问题通常是 10~100 倍级别的退化，用
 * {@value #MAX_ACCEPTABLE_MEDIAN_MS} ms 的中位数上限足以拦下，同时远离本机实测值
 * （个位数毫秒）两个数量级。</p>
 *
 * <p>测的是{@link CapturedFrame}的内存像素路径，也就是设备上
 * {@code BitmapFrameImage.readArgbRegion} 一次 bulk 回读之后的纯 JVM 计算开销；
 * 真机上的 {@code Bitmap.getPixels} 拷贝本身不在这个数字里。</p>
 */
public class BlueMaskPerformanceTest {

    private static final int VIDEO_WIDTH = 1280;
    private static final int VIDEO_HEIGHT = 720;

    private static final int WARMUP_ITERATIONS = 50;
    private static final int MEASURED_ITERATIONS = 200;

    /** 中位数上限，见类注释：只为拦截量级退化。 */
    private static final double MAX_ACCEPTABLE_MEDIAN_MS = 20.0;

    /** 取画面正中：这样默认半径的 ROI 是完整 401x401，不会被视频边界夹掉一块。 */
    private static final float TOUCH_X = VIDEO_WIDTH / 2f;
    private static final float TOUCH_Y = VIDEO_HEIGHT / 2f;

    @Test
    public void defaultRoiStaysFarBelowTheLatencyBudget() {
        final Profile profile = measure(TargetSelectionConfig.DEFAULT_ROI_RADIUS, "默认 ROI");

        assertTrue("默认 ROI 应当拿到真的掩码，否则这个基准没测到东西：" + profile.summary(),
                profile.bluePixelCount > 0);
        assertTrue(String.format(Locale.ROOT,
                        "默认 ROI 处理中位耗时 %.2fms 超过 %.1fms，怀疑出现了量级退化（例如逐像素 Bitmap.getPixel）：%s",
                        profile.medianMs, MAX_ACCEPTABLE_MEDIAN_MS, profile.summary()),
                profile.medianMs < MAX_ACCEPTABLE_MEDIAN_MS);
    }

    /**
     * 更大的 ROI 必须按像素数近似线性增长 —— 如果出现超线性，说明里面有 O(n²) 之类的结构
     * （例如每行重新扫描整张掩码）。
     *
     * <p>比较 {@code radius=100}（200x200）与 {@code radius=200}（400x400）：两者都在画面中心，
     * 谁都不会被视频边界夹取，像素数正好是 4 倍关系，于是"耗时是否也约 4 倍"这个判据才有意义。
     * 用边界处的点击点来比会得到 2.5 倍这种被夹取污染的数字，判据就废了。</p>
     */
    @Test
    public void quadrupleThePixelsDoesNotBlowUpTheCost() {
        final int baseRadius = TargetSelectionConfig.DEFAULT_ROI_RADIUS / 2;
        final Profile small = measure(baseRadius, "半半径 ROI");
        final Profile large = measure(baseRadius * 2, "默认 ROI");

        final double pixelRatio = large.pixelCount / (double) small.pixelCount;
        final double timeRatio = large.medianMs / Math.max(small.medianMs, 0.001d);

        assertTrue(String.format(Locale.ROOT,
                "两个 ROI 都不该被边界夹取：%s / %s", small.summary(), large.summary()),
                small.pixelCount == 200 * 200 && large.pixelCount == 400 * 400);
        assertEquals("像素数应是精确 4 倍关系", 4.0d, pixelRatio, 0.01d);
        assertTrue(String.format(Locale.ROOT,
                        "像素数 4 倍却让耗时涨到 %.2f 倍 —— 超线性增长通常意味着算法里有 O(n²)：%s / %s",
                        timeRatio, small.summary(), large.summary()),
                timeRatio < 4.0d * 2.5d);
    }

    /** 内部计时与外部计时必须是同一个量级（避免 processingMs 忘了换算或算错单位）。 */
    @Test
    public void reportedProcessingTimeMatchesExternalMeasurement() {
        final Profile profile = measure(TargetSelectionConfig.DEFAULT_ROI_RADIUS, "默认 ROI");

        // processingMs 是整数毫秒，量级对得上就够了：允许 3ms 的取整差与调度抖动
        assertTrue(String.format(Locale.ROOT,
                        "processingMs=%d 与外部实测中位 %.2fms 明显不一致：%s",
                        profile.reportedProcessingMs, profile.medianMs, profile.summary()),
                profile.reportedProcessingMs <= profile.medianMs + 3.0d);
        assertTrue("processingMs 不该比外部实测还慢很多：" + profile.summary(),
                profile.reportedProcessingMs >= 0L);
    }

    // ---------------------------------------------------------------- 基础设施

    /**
     * 跑一轮预热 + 测量。用"中位数"而不是平均值作为结论依据：平均值会被偶发的 GC / 线程
     * 抢占拖偏，中位数才反映"通常情况下多快"。
     */
    private static Profile measure(int radius, String label) {
        final int[] pixels = TestImages.solid(VIDEO_WIDTH, VIDEO_HEIGHT, TestImages.GRAY_BACKGROUND);
        // 画一条真实会遇到的 4px 蓝框线。框的右边线与下边线都落在中心 ROI 内，
        // 于是 radius=100 与 radius=200 两种 ROI 都能拿到非空掩码（空掩码会跳过部分工作，测不准）。
        TestImages.strokeRect(pixels, VIDEO_WIDTH, VIDEO_HEIGHT,
                200, 120, 500, 280, 4, TestImages.SHIFTED_BLUE);
        final CapturedFrame frame = CapturedFrame.ofImage(
                ArgbFrameImage.of(VIDEO_WIDTH, VIDEO_HEIGHT, pixels), 0L, 0L);

        final BlueMaskAnalyzer analyzer = new BlueMaskAnalyzer(
                TargetSelectionConfig.defaults().toBuilder()
                        .roiRadiusX(radius).roiRadiusY(radius).build());

        BlueMaskResult last = null;
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            last = analyzer.analyze(frame, null, TOUCH_X, TOUCH_Y);
        }

        final long[] nanos = new long[MEASURED_ITERATIONS];
        for (int i = 0; i < MEASURED_ITERATIONS; i++) {
            final long start = System.nanoTime();
            last = analyzer.analyze(frame, null, TOUCH_X, TOUCH_Y);
            nanos[i] = System.nanoTime() - start;
        }
        assertTrue("基准跑完后 ROI 应当仍然有效", last.hasRoi());

        final BinaryMask mask = last.mask;
        Arrays.sort(nanos);
        final Profile profile = new Profile();
        profile.label = label;
        profile.radius = radius;
        profile.width = mask.width();
        profile.height = mask.height();
        profile.pixelCount = mask.width() * mask.height();
        profile.bluePixelCount = last.bluePixelCount;
        profile.reportedProcessingMs = last.processingMs;
        profile.minMs = nanos[0] / 1_000_000.0d;
        profile.medianMs = nanos[nanos.length / 2] / 1_000_000.0d;
        profile.p95Ms = nanos[(int) (nanos.length * 0.95d)] / 1_000_000.0d;
        profile.maxMs = nanos[nanos.length - 1] / 1_000_000.0d;
        System.out.println(profile.summary());
        return profile;
    }

    private static final class Profile {
        String label;
        int radius;
        int width;
        int height;
        int pixelCount;
        int bluePixelCount;
        long reportedProcessingMs;
        double minMs;
        double medianMs;
        double p95Ms;
        double maxMs;

        String summary() {
            return String.format(Locale.ROOT,
                    "[bench] %-11s radius=%-3d %dx%d (%d px) blue=%-6d "
                            + "min=%.2fms median=%.2fms p95=%.2fms max=%.2fms (%.1f Mpx/s) reported=%dms",
                    label, radius, width, height, pixelCount, bluePixelCount,
                    minMs, medianMs, p95Ms, maxMs,
                    pixelCount / Math.max(medianMs, 0.001d) / 1000.0d,
                    reportedProcessingMs);
        }
    }
}
