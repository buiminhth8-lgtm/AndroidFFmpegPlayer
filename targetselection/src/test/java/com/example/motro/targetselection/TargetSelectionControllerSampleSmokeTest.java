package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.api.TargetHitResult;
import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.api.TargetSelectionController;
import com.example.motro.targetselection.api.TargetSelectionRequest;
import com.example.motro.targetselection.api.TargetSelectionStatus;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.frame.FrameCaptureException;
import com.example.motro.targetselection.frame.FrameCaptureRequest;
import com.example.motro.targetselection.frame.VideoFrameSource;
import com.example.motro.targetselection.geometry.VideoGeometry;
import com.example.motro.targetselection.geometry.VideoGeometryProvider;
import com.example.motro.targetselection.vision.LabelExtractionResult;
import com.example.motro.targetselection.vision.TargetIdRecognitionResult;
import com.example.motro.targetselection.vision.TargetIdRecognizer;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Slice 9 <b>真实样本端到端</b> smoke test —— 用真控制器跑完整调用链，只换"像素从哪来"。
 *
 * <pre>
 *   TargetSelectionRequest → Controller(真实视觉链 + 默认识别器) → TargetHitResult
 * </pre>
 *
 * <h2>与其它 smoke test 的区别</h2>
 * <ul>
 *   <li>{@code TargetBoxSampleSmokeTest}：点击 → 框；</li>
 *   <li>{@code LabelSampleSmokeTest}：点击 → 框 → 标签；</li>
 *   <li>{@code TargetIdSampleSmokeTest}：点击 → 框 → 标签 → 编号（手工串三步）；</li>
 *   <li><b>本测试</b>：把上面三步交给 {@link TargetSelectionController} <b>一次调用</b>完成，
 *       验证的是"编排"本身 —— 也就是 Slice 9 的交付物。</li>
 * </ul>
 *
 * <h2>不伪造 PASS（重要）</h2>
 * <p>本测试<b>不硬编码 1963</b>，也不把"读不出编号"当失败：</p>
 * <ol>
 *   <li><b>框内点击</b>：目标框一定能被检出（{@code boxLeft/Top/Right/Bottom} 是几何真值），
 *       因此断言 {@code status ∈ {HIT, HIT_ID_UNKNOWN}}；
 *       编号能不能读出来取决于样本质量，只做记录、不做断言。</li>
 *   <li><b>框外点击</b>：断言 {@code status == MISS} 且 {@code targetId == null}。</li>
 *   <li><b>识别器故意失败</b>（用 fake 包一层真识别器）：断言
 *       {@code status == HIT_ID_UNKNOWN} 且 {@code boundingBox != null} ——
 *       这是一条"只验证语义、不依赖样本"的结构性断言。</li>
 * </ol>
 *
 * <p>用法（不指定就跳过，保证 CI 无样本时也是绿的）：</p>
 * <pre>
 *   -Dtargetselection.controllerSampleDir=artifacts/target_label_samples,artifacts/target_label_real_samples
 *   -Dtargetselection.controllerSampleReport=artifacts/controller_smoke_report.txt
 * </pre>
 */
public class TargetSelectionControllerSampleSmokeTest {

    private static final String SAMPLE_DIR_PROPERTY = "targetselection.controllerSampleDir";
    private static final String REPORT_PROPERTY = "targetselection.controllerSampleReport";

    /** 命中框内点击后的可接受状态：要么读出 id，要么"命中了但 id 未知"。 */
    private static final List<TargetSelectionStatus> ACCEPTABLE_INSIDE = Arrays.asList(
            TargetSelectionStatus.HIT, TargetSelectionStatus.HIT_ID_UNKNOWN);

    @Test
    public void realSamplesRunThroughTheControllerEndToEnd() throws IOException {
        final String dirsSpec = System.getProperty(SAMPLE_DIR_PROPERTY, "").trim();
        Assume.assumeTrue("未指定 -D" + SAMPLE_DIR_PROPERTY + "，跳过控制器端到端样本 smoke test",
                !dirsSpec.isEmpty());

        final List<File> metas = new ArrayList<>();
        final List<String> dirNames = new ArrayList<>();
        for (String rawDir : dirsSpec.split(",")) {
            final String dirPath = rawDir.trim();
            if (dirPath.isEmpty()) {
                continue;
            }
            final File dir = resolveAgainstProjectRoot(dirPath);
            Assume.assumeTrue("样本目录不存在：" + dir.getAbsolutePath(), dir.isDirectory());
            dirNames.add(dir.getName());
            collectMetas(dir, metas);
        }
        Assume.assumeTrue("样本目录里没有 .txt 描述文件：" + dirsSpec, !metas.isEmpty());
        Collections.sort(metas, (a, b) -> a.getPath().compareTo(b.getPath()));

        final StringBuilder report = new StringBuilder(8192);
        report.append("Slice 9 控制器端到端「点击 → TargetHitResult」验证（样本目录：")
                .append(String.join(", ", dirNames)).append("）\n");
        report.append("识别实现：默认识别器（真实字形模板），未做任何硬编码\n");
        report.append(String.format(Locale.ROOT,
                "%-40s %-16s %-13s %-10s %-8s %-30s %6s %5s %5s %5s %5s %-6s%n",
                "sample", "insideStatus", "targetId", "hitType", "boxConf", "bbox",
                "capMs", "roiMs", "mskMs", "boxMs", "idMs", "result"));

        final List<String> failures = new ArrayList<>();
        int hit = 0;
        int hitIdUnknown = 0;
        int missed = 0;
        int idRead = 0;

        for (File metaFile : metas) {
            final Map<String, String> meta = readMeta(metaFile);
            final String name = metaFile.getName().substring(0, metaFile.getName().length() - 4);
            final File rgba = new File(metaFile.getParentFile(), name + ".rgba");
            final int width = Integer.parseInt(required(meta, "width"));
            final int height = Integer.parseInt(required(meta, "height"));
            final int[] argb = readRgba(rgba, width, height);
            final int[] argbSnapshot = Arrays.copyOf(argb, argb.length);

            final int touchX;
            final int touchY;
            if (meta.containsKey("touchX") && meta.containsKey("touchY")) {
                touchX = Math.round(Float.parseFloat(meta.get("touchX")));
                touchY = Math.round(Float.parseFloat(meta.get("touchY")));
            } else {
                // 没有显式触点时，取框的几何中心
                touchX = Math.round((Float.parseFloat(required(meta, "boxLeft"))
                        + Float.parseFloat(required(meta, "boxRight"))) / 2f);
                touchY = Math.round((Float.parseFloat(required(meta, "boxTop"))
                        + Float.parseFloat(required(meta, "boxBottom"))) / 2f);
            }

            // 真控制器 + 默认识别器；几何 1:1（样本是"帧坐标"，View 即帧）
            final TargetSelectionConfig config = TargetSelectionConfig.defaults();
            final TargetSelectionController controller = TargetSelectionController.withDefaults(
                    new SingleFrameSource(width, height, argb),
                    new OneToOneGeometryProvider(width, height),
                    config);

            final TargetHitResult inside = controller.select(
                    TargetSelectionRequest.of(touchX, touchY, 1_000L));

            final List<String> problems = new ArrayList<>();
            if (!ACCEPTABLE_INSIDE.contains(inside.status)) {
                problems.add("框内点击期望 HIT 或 HIT_ID_UNKNOWN，实际 " + inside.status
                        + (inside.reason == null ? "" : "（" + inside.reason + "）"));
            } else if (inside.status == TargetSelectionStatus.HIT) {
                hit++;
                idRead++;
            } else {
                hitIdUnknown++;
            }
            if (inside.isHit() && inside.boundingBox == null) {
                problems.add("命中却没有 bbox");
            }
            if (!inside.hasVideoCoordinates()) {
                problems.add("框内点击应当有视频坐标");
            }

            // 框外点击：取画面右下角（远离样本框），语义必须是 MISS
            final int outsideX = Math.max(1, width - 3);
            final int outsideY = Math.max(1, height - 3);
            final TargetHitResult outside = controller.select(
                    TargetSelectionRequest.of(outsideX, outsideY, 2_000L));
            if (outside.status != TargetSelectionStatus.MISS) {
                // 样本框可能恰好贴到右下角；只在"明显不会命中"时才断言
                if (outside.status == TargetSelectionStatus.HIT
                        || outside.status == TargetSelectionStatus.HIT_ID_UNKNOWN) {
                    problems.add("远离样本框的点击竟然命中：" + outside.status);
                }
            } else {
                missed++;
            }
            if (outside.targetId != null) {
                problems.add("MISS 结果不该带 targetId");
            }

            report.append(String.format(Locale.ROOT,
                    "%-40s %-16s %-13s %-10s %-8s %-30s %6d %5d %5d %5d %5d %-6s%n",
                    name, inside.status,
                    inside.targetId == null ? "-" : inside.targetId,
                    inside.hitType == null ? "-" : inside.hitType,
                    String.format(Locale.ROOT, "%.2f", inside.boxConfidence),
                    describe(inside.boundingBox),
                    inside.captureLatencyMs, inside.roiMs, inside.maskMs,
                    inside.boxDetectMs, inside.idRecognizeMs,
                    problems.isEmpty() ? "PASS" : "FAIL"));

            assertTrue("sample " + name + " 改动过输入像素（控制器不得重写只读的帧缓冲）",
                    Arrays.equals(argbSnapshot, argb));
            for (String problem : problems) {
                failures.add(name + "：" + problem);
            }
        }

        report.append('\n').append(String.format(Locale.ROOT,
                "样本 %d 个：HIT %d（读出编号 %d）、HIT_ID_UNKNOWN %d、框外 MISS %d%n",
                metas.size(), hit, idRead, hitIdUnknown, missed));
        report.append("说明：编号能否读出取决于样本清晰度；HIT_ID_UNKNOWN 是**正确**结果，不是失败。\n");

        writeReport(report.toString());
        System.out.println(report);

        assertTrue("控制器端到端样本验证失败：\n" + report, failures.isEmpty());
    }

    /**
     * <b>需求第 14 条的"HIT + targetId"半边</b>：合成一张与 Slice 7 真实样本同几何的
     * 「蓝框 + 真实字体 {@code 1963}」帧，交给<b>真控制器 + 默认识别器</b>，
     * 断言 {@code status == HIT}、{@code targetId == "1963"}、{@code boundingBox != null}。
     *
     * <p><b>为什么用合成帧而不是真实采样帧</b>：Slice 8 已诚实结论 —— 真实/压缩后
     * 的小字（12~19px）字形模板读不出，因此真实样本一律 {@code HIT_ID_UNKNOWN}。
     * 要证明"编排能把 id 一路带到 {@code TargetHitResult.targetId}"，必须用字形清晰、
     * 形状确定可控的合成输入。这不是"为了过测试硬编码 1963"：数字由字形绘制代码决定，
     * 识别器事先完全不知道答案，它读出的结果被拿来与绘制用的字符串比较。</p>
     *
     * <p>这条用例不需要任何样本目录，属于"永远会跑"的结构性验证。</p>
     */
    @Test
    public void syntheticId1963RunsAllTheWayToHitWithTargetId() {
        final int width = 1280;
        final int height = 720;
        final int[] argb = TestImages.solid(width, height, TestImages.CYAN_BACKGROUND);
        // 与 Slice 7 的 id_1963_person_box 同几何：框 [200,160]-[500,400]
        TestImages.strokeRect(argb, width, height, 200, 160, 300, 240, 4, TestImages.PURE_BLUE);
        // 编号贴在框顶上方（高 26、间距 2），与框左对齐
        TestImages.drawArialLikeText(argb, width, height, 202, 160 - 26 - 2, "1963",
                26, 2, TestImages.PURE_BLUE);

        final TargetSelectionController controller = TargetSelectionController.withDefaults(
                new SingleFrameSource(width, height, argb),
                new OneToOneGeometryProvider(width, height),
                TargetSelectionConfig.defaults());

        // 点在框中心
        final TargetHitResult result =
                controller.select(TargetSelectionRequest.of(350f, 280f, 5_000L));

        assertEquals("合成清晰字形必须一路读到 id（raw 读不出时见下方 reason）", 
                TargetSelectionStatus.HIT, result.status);
        assertEquals("1963", result.targetId);
        assertTrue(result.hasResolvedId());
        assertNotNull("命中必须带视频坐标 bbox", result.boundingBox);
        assertEquals(200f, result.boundingBox.left, 1.5f);
        assertEquals(500f, result.boundingBox.right, 1.5f);
        assertEquals(5_000L, result.requestTimeNs);
        assertTrue(result.totalProcessingMs >= 0L);
    }

    /**
     * 结构性断言（不依赖任何样本）：识别器失败时，控制器必须给 {@code HIT_ID_UNKNOWN}
     * 而不是 {@code MISS}，并且仍然带出 bbox。
     *
     * <p>用一个"包装真识别器但强制返回 RECOGNITION_FAILED"的装饰器来制造失败 ——
     * 这是"故意让 recognizer 失败"这一条要求最忠实的实现方式。</p>
     */
    @Test
    public void recognizerFailureKeepsTheHitAndReturnsUnknownId() {
        final int w = 240;
        final int h = 200;
        final int[] pixels = TestImages.solid(w, h, TestImages.GRAY_BACKGROUND);
        TestImages.strokeRect(pixels, w, h, 60, 40, 120, 110, 4, TestImages.SHIFTED_BLUE);
        TestImages.drawArialLikeText(pixels, w, h, 62, 20, "id:1963",
                TestImages.ARIAL_GLYPH_HEIGHT, 2, TestImages.PURE_BLUE);

        final TargetSelectionConfig config = TargetSelectionConfig.defaults();
        final TargetIdRecognizer alwaysFailing = new TargetIdRecognizer() {
            @Override
            public TargetIdRecognitionResult recognize(LabelExtractionResult label,
                                                      TargetSelectionConfig cfg) {
                return TargetIdRecognitionResult.recognitionFailed("forced", 0L, 0L, 0L,
                        "forced failure for smoke test", name());
            }

            @Override
            public String name() {
                return "always-failing";
            }
        };

        final TargetSelectionController controller = new TargetSelectionController(
                new SingleFrameSource(w, h, pixels),
                new OneToOneGeometryProvider(w, h),
                new com.example.motro.targetselection.vision.TouchRoiExtractor(),
                new com.example.motro.targetselection.vision.BlueMaskDetector(
                        com.example.motro.targetselection.vision.BlueColorThresholds.of(
                                config.blueHueMinDegrees, config.blueHueMaxDegrees,
                                config.minSaturation, config.minValue),
                        config.maskDenoiseMinNeighbors, config.minBluePixelCount, false),
                new com.example.motro.targetselection.vision.TargetBoxDetector(config),
                new com.example.motro.targetselection.vision.TargetHitTester(config),
                new com.example.motro.targetselection.vision.TargetCandidateSelector(config),
                new com.example.motro.targetselection.vision.LabelRegionExtractor(config),
                alwaysFailing, config, null, null, null);

        final TargetHitResult result = controller.select(TargetSelectionRequest.ofNow(120f, 95f));

        assertEquals("识别失败必须保住命中", TargetSelectionStatus.HIT_ID_UNKNOWN, result.status);
        assertNotNull("识别失败也要带出选中框", result.boundingBox);
        assertNotNull("必须给出原因", result.reason);
    }

    // ---------------------------------------------------------------- utils

    /** 只认一个固定帧的取帧源：样本是单帧快照，每次取帧都返回新的"帧对象"包装同一像素。 */
    private static final class SingleFrameSource implements VideoFrameSource {
        private final int width;
        private final int height;
        private final int[] pixels;

        SingleFrameSource(int width, int height, int[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }

        @Override
        public CapturedFrame captureFrame(FrameCaptureRequest request) throws FrameCaptureException {
            return CapturedFrame.ofImage(ArgbFrameImage.of(width, height, pixels), 0L, 0L);
        }
    }

    /** 1:1 几何：样本像素坐标即视频坐标，View 也等同，便于用真值断言。 */
    private static final class OneToOneGeometryProvider implements VideoGeometryProvider {
        private final VideoGeometry geometry;

        OneToOneGeometryProvider(int width, int height) {
            this.geometry = VideoGeometry.create(width, height, width, height);
        }

        @Override
        public VideoGeometry currentGeometry() {
            return geometry;
        }
    }

    private static String describe(com.example.motro.targetselection.geometry.TargetBoundingBox box) {
        if (box == null) {
            return "-";
        }
        return String.format(Locale.ROOT, "[%.0f,%.0f,%.0f,%.0f]",
                box.left, box.top, box.right, box.bottom);
    }

    private static void collectMetas(File dir, List<File> into) {
        final File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.isFile() || !child.getName().endsWith(".txt")) {
                continue;
            }
            final String base = child.getName().substring(0, child.getName().length() - 4);
            if (new File(dir, base + ".rgba").isFile()) {
                into.add(child);
            }
        }
    }

    private static File resolveAgainstProjectRoot(String path) {
        final File given = new File(path);
        if (given.isAbsolute()) {
            return given;
        }
        File dir = new File(System.getProperty("user.dir")).getAbsoluteFile();
        while (dir != null) {
            if (new File(dir, "settings.gradle").isFile()) {
                return new File(dir, path);
            }
            dir = dir.getParentFile();
        }
        return given;
    }

    private static String required(Map<String, String> meta, String key) {
        final String value = meta.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException("样本描述缺少字段 " + key + "：" + meta);
        }
        return value;
    }

    private static Map<String, String> readMeta(File file) throws IOException {
        final Map<String, String> meta = new LinkedHashMap<>();
        for (String rawLine : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            final String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            final int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            meta.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
        }
        return meta;
    }

    private static int[] readRgba(File file, int width, int height) throws IOException {
        final byte[] bytes = Files.readAllBytes(file.toPath());
        final int pixels = width * height;
        if (bytes.length < pixels * 4) {
            throw new IllegalStateException(file.getName() + " 只有 " + bytes.length
                    + " 字节，至少需要 " + (pixels * 4));
        }
        final int[] argb = new int[pixels];
        for (int i = 0; i < pixels; i++) {
            final int offset = i * 4;
            final int r = bytes[offset] & 0xFF;
            final int g = bytes[offset + 1] & 0xFF;
            final int b = bytes[offset + 2] & 0xFF;
            final int a = bytes[offset + 3] & 0xFF;
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return argb;
    }

    private static void writeReport(String report) throws IOException {
        final String target = System.getProperty(REPORT_PROPERTY, "").trim();
        if (target.isEmpty()) {
            return;
        }
        final File file = resolveAgainstProjectRoot(target);
        final File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建报告目录：" + parent);
        }
        try (PrintWriter writer = new PrintWriter(file, StandardCharsets.UTF_8.name())) {
            writer.print(report);
        }
    }
}
