package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.vision.GlyphTemplateTargetIdRecognizer;
import com.example.motro.targetselection.vision.LabelExtractionResult;
import com.example.motro.targetselection.vision.LabelExtractionStatus;
import com.example.motro.targetselection.vision.LabelRegionExtractor;
import com.example.motro.targetselection.vision.TargetIdRecognitionResult;
import com.example.motro.targetselection.vision.TargetIdRecognitionStatus;
import com.example.motro.targetselection.vision.TargetIdRecognizer;

import org.junit.Test;

import java.util.Arrays;

/**
 * 编号识别层的单测。
 *
 * <p>这里刻意分成两组，因为它们的证明力完全不同：</p>
 * <ul>
 *   <li><b>编排测试</b>（fake recognizer）：验证「识别结果 → 命中语义」的映射。
 *       重点是<b>识别失败绝不能把已确认的 bbox 命中降级成 MISS</b> —— 这条语义
 *       用真实像素测不出来（真实像素只会给出"成功/失败"两种结果，
 *       不会给出"上层拿到什么状态"）。因此这里注入一个可控的
 *       {@link TargetIdRecognizer}，把每种失败都逼出来。</li>
 *   <li><b>真实实现测试</b>（{@link GlyphTemplateTargetIdRecognizer}）：用
 *       {@link TestImages#drawArialLikeText} 画出的、形状完全确定的数字，
 *       验证「像素 → 编号」这条链路真的能跑通。期望值是可复现的，因为字形的
 *       形状由测试代码自己决定。</li>
 * </ul>
 *
 * <p><b>为什么用 Arial 字形而不是七段数码管</b>：识别实现的模板已经换成真实字体
 * （Arial）点阵 —— 真实推流帧用的是常规字体，七段模板在真实帧上完全读不出编号。
 * 单测若继续画七段数字，测到的就是"七段认七段"，与真实能力脱节。测试侧的
 * {@link TestImages#drawArialLikeText} 与识别实现的模板点阵<b>各存一份</b>、
 * 同源但独立，因此"两份点阵对得上"本身也是一条隐含的校验。</p>
 */
public class TargetIdRecognizerTest {

    private static final int LABEL_WIDTH = 128;
    private static final int LABEL_HEIGHT = 24;

    // ================================================================== 编排：fake recognizer

    /** 可通过构造参数指定任意返回结果的假识别器 —— 用来穷举上层拿到的状态映射。 */
    private static final class FakeRecognizer implements TargetIdRecognizer {

        private final TargetIdRecognitionResult result;
        private int calls;

        FakeRecognizer(TargetIdRecognitionResult result) {
            this.result = result;
        }

        @Override
        public TargetIdRecognitionResult recognize(LabelExtractionResult label,
                                                   TargetSelectionConfig config) {
            calls++;
            return result;
        }

        @Override
        public String name() {
            return "fake";
        }
    }

    @Test
    public void successfulRecognitionCarriesTheId() {
        final FakeRecognizer fake = new FakeRecognizer(
                TargetIdRecognitionResult.success("1963", "id:1963", 0.9f, 1L, 2L, 0L, 3L, "fake"));
        final TargetIdRecognitionResult result =
                fake.recognize(labelWithGlyphs(4), TargetSelectionConfig.defaults());

        assertEquals(TargetIdRecognitionStatus.SUCCESS, result.status);
        assertTrue(result.hasId());
        assertEquals("1963", result.targetId);
        assertNotNull(result.targetIdValue);
        assertEquals(Long.valueOf(1963L), result.targetIdValue);
        assertFalse(result.isHitWithoutId());
    }

    @Test
    public void everyFailureStatusKeepsTheHit() {
        // 这是本 Slice 最重要的一条不变量：识别失败不是"没点中目标"
        final TargetIdRecognitionResult[] failures = {
                TargetIdRecognitionResult.noId("abc", 0.2f, 1L, 1L, 0L, 2L, "no digits", "fake"),
                TargetIdRecognitionResult.lowConfidence("id:1963", 0.3f, 1L, 1L, 0L, 2L, "low", "fake"),
                TargetIdRecognitionResult.invalidLabel("no pixels", 1L, "fake"),
                TargetIdRecognitionResult.recognitionFailed("boom", 1L, 1L, 2L, "error", "fake"),
        };
        for (TargetIdRecognitionResult failure : failures) {
            assertTrue(failure.status + " 必须保持命中语义（HIT_ID_UNKNOWN，而不是 MISS）",
                    failure.status.keepsHit());
            assertTrue(failure.status + " 必须被判为 isHitWithoutId",
                    failure.isHitWithoutId());
            assertFalse(failure.status + " 不该有 id", failure.hasId());
            // 非成功状态下一律不带 targetId —— 否则上层会写出 targetId != null 的模糊分支
            assertNull(failure.status + " 不该带 targetId", failure.targetId);
        }
    }

    @Test
    public void lowConfidenceKeepsRawTextSoTheFailureIsExplainable() {
        final TargetIdRecognitionResult result = TargetIdRecognitionResult.lowConfidence(
                "id:1963", 0.2f, 1L, 1L, 0L, 2L, "below threshold", "fake");
        assertEquals(TargetIdRecognitionStatus.LOW_CONFIDENCE, result.status);
        assertEquals("id:1963", result.rawText);
        assertNull(result.targetId);
    }

    @Test
    public void recognitionFailureCarriesTheReason() {
        final TargetIdRecognitionResult result = TargetIdRecognitionResult.recognitionFailed(
                "raw", 1L, 2L, 3L, "glyph matcher threw", "fake");
        assertEquals(TargetIdRecognitionStatus.RECOGNITION_FAILED, result.status);
        assertEquals("glyph matcher threw", result.debugReason);
        assertTrue(result.status.isFailure());
    }

    @Test
    public void successFactoryRejectsNonNumericIds() {
        assertThrows(IllegalArgumentException.class, () -> TargetIdRecognitionResult.success(
                "id:1963", "id:1963", 0.9f, 0L, 0L, 0L, 0L, "fake"));
        assertThrows(IllegalArgumentException.class, () -> TargetIdRecognitionResult.success(
                "1963a", "1963a", 0.9f, 0L, 0L, 0L, 0L, "fake"));
        assertThrows(IllegalArgumentException.class, () -> TargetIdRecognitionResult.success(
                "", "", 0.9f, 0L, 0L, 0L, 0L, "fake"));
    }

    @Test
    public void confidenceOutsideRangeIsClampedAndNaNBecomesNull() {
        // 置信度是 Float —— 允许 null（"真的不知道"），但数值必须落在 [0,1]
        final TargetIdRecognitionResult high = TargetIdRecognitionResult.noId(
                "x", 1.7f, 0L, 0L, 0L, 0L, "r", "fake");
        assertEquals(Float.valueOf(1f), high.confidence);

        final TargetIdRecognitionResult nan = TargetIdRecognitionResult.noId(
                "x", Float.NaN, 0L, 0L, 0L, 0L, "r", "fake");
        assertNull("NaN 置信度必须被规整成 null（「真的不知道」，而不是 0）", nan.confidence);

        final TargetIdRecognitionResult absent = TargetIdRecognitionResult.noId(
                "x", null, 0L, 0L, 0L, 0L, "r", "fake");
        assertNull(absent.confidence);
    }

    @Test
    public void invalidLabelAndRecognitionFailureAreDistinct() {
        assertTrue(TargetIdRecognitionStatus.INVALID_LABEL.isFailure());
        assertTrue(TargetIdRecognitionStatus.RECOGNITION_FAILED.isFailure());
        assertFalse(TargetIdRecognitionStatus.NO_ID.isFailure());
        assertFalse(TargetIdRecognitionStatus.LOW_CONFIDENCE.isFailure());
        assertFalse(TargetIdRecognitionStatus.SUCCESS.isFailure());
    }

    @Test
    public void timingsAreClampedAndSummedInDebugString() {
        final TargetIdRecognitionResult result = TargetIdRecognitionResult.success(
                "1963", "id:1963", 0.9f, 2L, 3L, 1L, 7L, "fake");
        assertEquals(2L, result.preprocessMs);
        assertEquals(3L, result.recognizeMs);
        assertEquals(1L, result.parseMs);
        assertEquals(7L, result.processingMs());
        assertTrue(result.debugString().contains("totalMs=7"));
        assertTrue(result.debugString().contains("id=1963"));
    }

    // ================================================================== 真实实现：字形匹配

    private static LabelExtractionResult labelWithGlyphs(int digitCount) {
        final int width = 1280;
        final int height = 720;
        return extractLabel(width, height, frame -> {
            TestImages.strokeRect(frame, width, height, 200, 160, 300, 240, 4, TestImages.PURE_BLUE);
            TestImages.drawArialLikeText(frame, width, height, 202, 160 - 26 - 2,
                    repeat('8', digitCount), 26, 2, TestImages.PURE_BLUE);
        }, TargetBoundingBox.of(200, 160, 500, 400));
    }

    private static String repeat(char c, int count) {
        final StringBuilder b = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            b.append(c);
        }
        return b.toString();
    }

    @Test
    public void recognizerRejectsALabelWithoutPixels() {
        final TargetSelectionConfig config = TargetSelectionConfig.defaults();
        final TargetIdRecognizer recognizer = new GlyphTemplateTargetIdRecognizer(config);
        // 一张只有蓝框、框顶上方没有任何编号的图 → 提取器报 NO_LABEL →
        // 识别器必须如实报 INVALID_LABEL（而不是成功，也不是崩溃）
        final LabelExtractionResult noLabel = extractLabel(1280, 720, frame -> {
            TestImages.strokeRect(frame, 1280, 720, 200, 160, 300, 240, 4, TestImages.PURE_BLUE);
        }, TargetBoundingBox.of(200, 160, 500, 400));
        assertEquals(LabelExtractionStatus.NO_LABEL, noLabel.status);

        final TargetIdRecognitionResult result = recognizer.recognize(noLabel, config);
        assertEquals(TargetIdRecognitionStatus.INVALID_LABEL, result.status);
        assertTrue(result.isHitWithoutId());
        assertNull(result.targetId);
    }

    @Test
    public void recognizerReturnsNoIdForABlankLabel() {
        final TargetSelectionConfig config = TargetSelectionConfig.defaults();
        final TargetIdRecognizer recognizer = new GlyphTemplateTargetIdRecognizer(config);
        // 框上方的"编号"用灰色画（完全不蓝）→ 提取器找不到 → INVALID_LABEL。
        // 这条与上一条的区别是"框顶附近确实有东西，只是颜色不对"。
        final LabelExtractionResult blank = extractLabel(1280, 720, frame -> {
            TestImages.strokeRect(frame, 1280, 720, 200, 160, 300, 240, 4, TestImages.PURE_BLUE);
            TestImages.fillRect(frame, 1280, 720, 202, 142, 70, 16, TestImages.GRAY_BACKGROUND);
        }, TargetBoundingBox.of(200, 160, 500, 400));

        final TargetIdRecognitionResult result = recognizer.recognize(blank, config);
        assertEquals(TargetIdRecognitionStatus.INVALID_LABEL, result.status);
        assertTrue(result.isHitWithoutId());
        assertNull(result.targetId);
    }

    @Test
    public void recognizerReadsRealFontDigits() {
        final TargetSelectionConfig config = TargetSelectionConfig.defaults();
        final TargetIdRecognizer recognizer = new GlyphTemplateTargetIdRecognizer(config);

        // 注意：这里不包含单个 "1"。孤立的 1 只有 1~2 列宽，Slice 7 的标签提取器
        // 会因为"文本量太小"直接判定 NO_LABEL（那是提取阶段的取舍，不是识别器的错）。
        // 1 在多位编号里的表现由 "1963" / "1850" / "1975" 覆盖。
        final String[] expected = {"1963", "1850", "1975", "42", "999999", "7"};
        for (String id : expected) {
            final TargetIdRecognitionResult result =
                    recognizer.recognize(labelOfRealFont(id), config);
            assertEquals("真实字体数字 " + id + " 必须被识别出来（实际 status=" + result.status
                            + ", raw=" + result.rawText + ", reason=" + result.debugReason + "）",
                    id, result.targetId);
            assertEquals(TargetIdRecognitionStatus.SUCCESS, result.status);
            assertTrue("置信度必须落在 [0,1]", result.confidence >= 0f && result.confidence <= 1f);
        }
    }

    @Test
    public void recognizerIsDeterministic() {
        final TargetSelectionConfig config = TargetSelectionConfig.defaults();
        final TargetIdRecognizer recognizer = new GlyphTemplateTargetIdRecognizer(config);
        final LabelExtractionResult label = labelOfRealFont("1963");

        final TargetIdRecognitionResult first = recognizer.recognize(label, config);
        for (int i = 0; i < 10; i++) {
            final TargetIdRecognitionResult again = recognizer.recognize(label, config);
            assertEquals(first.status, again.status);
            assertEquals(first.targetId, again.targetId);
            assertEquals(first.rawText, again.rawText);
        }
    }

    @Test
    public void recognizerDoesNotMutateTheLabelPixels() {
        final TargetSelectionConfig config = TargetSelectionConfig.defaults();
        final TargetIdRecognizer recognizer = new GlyphTemplateTargetIdRecognizer(config);
        final LabelExtractionResult label = labelOfRealFont("1963");
        final int[] snapshot = Arrays.copyOf(label.labelArgb, label.labelArgb.length);

        recognizer.recognize(label, config);
        org.junit.Assert.assertArrayEquals("识别不得修改标签像素", snapshot, label.labelArgb);
    }

    @Test
    public void recognizerRejectsTooLongIdsViaDigitBound() {
        // 把上限收到 2 位：真实读出的 1963 有 4 位，必须被拒绝。
        // 这里用配置驱动而不是画一个七位编号 —— 七位编号会被 Slice 7 的文本行合并
        // 逻辑切成两条候选（那是提取阶段的取舍），测到的是提取器而不是识别器。
        final TargetSelectionConfig config = TargetSelectionConfig.defaults()
                .toBuilder().idMaxDigits(2).build();
        final TargetIdRecognizer recognizer = new GlyphTemplateTargetIdRecognizer(config);

        final TargetIdRecognitionResult result =
                recognizer.recognize(labelOfRealFont("1963"), config);
        assertNull("超过 idMaxDigits 的编号不得被接受", result.targetId);
        assertFalse(result.hasId());
        // 但命中语义必须保持：这是"读不出来"，不是"没点中"
        assertTrue(result.isHitWithoutId());
        assertNotNull("失败时必须留下原始文本以便排查", result.rawText);
        assertEquals("1963", result.rawText);
    }

    @Test
    public void minIdConfidenceGateProducesLowConfidence() {
        final TargetSelectionConfig config = TargetSelectionConfig.defaults();
        // 把门槛抬到 1.0：任何真实匹配都过不去 → LOW_CONFIDENCE（而不是 SUCCESS）
        final TargetSelectionConfig strict = config.toBuilder().minIdConfidence(1f).build();
        final TargetIdRecognizer recognizer = new GlyphTemplateTargetIdRecognizer(strict);

        final TargetIdRecognitionResult result =
                recognizer.recognize(labelOfRealFont("1963"), strict);
        assertEquals(TargetIdRecognitionStatus.LOW_CONFIDENCE, result.status);
        assertNull("置信度不足时不得带出编号", result.targetId);
        assertTrue(result.isHitWithoutId());
        // 但原始文本必须留着 —— 否则"识别不确定"这条结论无从核查
        assertNotNull("LOW_CONFIDENCE 必须留下原始文本", result.rawText);
        assertTrue(result.confidence != null && result.confidence < 1f);
    }

    @Test
    public void upscaleFactorOneStillWorks() {
        final TargetSelectionConfig config =
                TargetSelectionConfig.defaults().toBuilder().labelUpscaleFactor(1).build();
        final TargetIdRecognizer recognizer = new GlyphTemplateTargetIdRecognizer(config);
        final TargetIdRecognitionResult result =
                recognizer.recognize(labelOfRealFont("1963"), config);
        assertEquals("1963", result.targetId);
    }

    @Test
    public void recognizerNameIsStable() {
        assertEquals(GlyphTemplateTargetIdRecognizer.NAME,
                new GlyphTemplateTargetIdRecognizer(TargetSelectionConfig.defaults()).name());
    }

    @Test
    public void recognizerRejectsNullArguments() {
        final TargetIdRecognizer recognizer =
                new GlyphTemplateTargetIdRecognizer(TargetSelectionConfig.defaults());
        assertThrows(IllegalArgumentException.class,
                () -> recognizer.recognize(null, TargetSelectionConfig.defaults()));
        assertThrows(IllegalArgumentException.class,
                () -> recognizer.recognize(labelOfRealFont("1"), null));
        assertThrows(IllegalArgumentException.class,
                () -> new GlyphTemplateTargetIdRecognizer(null));
    }

    @Test
    public void closeIsIdempotentAndSafe() {
        final TargetIdRecognizer recognizer =
                new GlyphTemplateTargetIdRecognizer(TargetSelectionConfig.defaults());
        recognizer.close();
        recognizer.close();   // 幂等
    }

    // ================================================================== 与 Slice 7 的真实链路

    /**
     * 端到端：合成一张「蓝框 + 真实字体编号」的图 → 走
     * {@link LabelRegionExtractor} 取出标签 → 交给识别器读号。
     * 这条用例证明两层能真正串起来（而不是各自单测都过、拼起来取错区域）。
     */
    @Test
    public void endToEndLabelExtractionThenRecognition() {
        final TargetSelectionConfig config = TargetSelectionConfig.defaults();
        final int width = 1280;
        final int height = 720;
        final int[] argb = TestImages.solid(width, height, TestImages.CYAN_BACKGROUND);

        // 框：与 Slice 7 的 id_1963_person_box 同几何
        TestImages.strokeRect(argb, width, height, 200, 160, 300, 240, 4, TestImages.PURE_BLUE);
        // 编号：贴在框顶上方（高 26），与框左对齐
        TestImages.drawArialLikeText(argb, width, height, 202, 160 - 26 - 2, "1963",
                26, 2, TestImages.PURE_BLUE);

        final com.example.motro.targetselection.frame.CapturedFrame frame =
                com.example.motro.targetselection.frame.CapturedFrame.ofImage(
                        ArgbFrameImage.of(width, height, argb), 0L, 0L);

        final LabelRegionExtractor extractor = new LabelRegionExtractor(config);
        final LabelExtractionResult label = extractor.extract(frame, null,
                TargetBoundingBox.of(200, 160, 500, 400));
        assertEquals("标签区域必须被找到", LabelExtractionStatus.SUCCESS, label.status);

        final TargetIdRecognizer recognizer = new GlyphTemplateTargetIdRecognizer(config);
        final TargetIdRecognitionResult result = recognizer.recognize(label, config);
        assertEquals("端到端（标签提取 + 识别）必须读出 1963（raw=" + result.rawText
                        + ", reason=" + result.debugReason + "）",
                "1963", result.targetId);
        assertEquals(TargetIdRecognitionStatus.SUCCESS, result.status);
    }

    // ================================================================== helpers

    /**
     * 用真实字体字形画一张「蓝框 + 编号」的整帧，走真实的两层链路取标签
     * （这样识别器拿到的是"提取器真正会给出的那种"标签，而不是手搓的对象）。
     */
    private static LabelExtractionResult labelOfRealFont(String id) {
        final int width = 1280;
        final int height = 720;
        return extractLabel(width, height, frame -> {
            TestImages.strokeRect(frame, width, height, 200, 160, 300, 240, 4, TestImages.PURE_BLUE);
            // 编号贴框顶上方：高 26、间距 2
            TestImages.drawArialLikeText(frame, width, height, 202, 160 - 26 - 2, id,
                    26, 2, TestImages.PURE_BLUE);
        }, TargetBoundingBox.of(200, 160, 500, 400));
    }

    /** 画面绘制回调：往整帧 ARGB 上画东西。 */
    private interface FramePainter {
        void paint(int[] argb);
    }

    /** 构造整帧 → {@link LabelRegionExtractor} 取标签区域。 */
    private static LabelExtractionResult extractLabel(int width, int height, FramePainter painter,
                                                      TargetBoundingBox box) {
        final int[] argb = TestImages.solid(width, height, TestImages.CYAN_BACKGROUND);
        painter.paint(argb);
        final com.example.motro.targetselection.frame.CapturedFrame frame =
                com.example.motro.targetselection.frame.CapturedFrame.ofImage(
                        ArgbFrameImage.of(width, height, argb), 0L, 0L);
        return new LabelRegionExtractor(TargetSelectionConfig.defaults())
                .extract(frame, null, box);
    }
}
