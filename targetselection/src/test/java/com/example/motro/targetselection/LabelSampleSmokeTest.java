package com.example.motro.targetselection;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.vision.DetectedTargetBox;
import com.example.motro.targetselection.vision.LabelCandidate;
import com.example.motro.targetselection.vision.LabelExtractionResult;
import com.example.motro.targetselection.vision.LabelExtractionStatus;
import com.example.motro.targetselection.vision.LabelRegionExtractor;
import com.example.motro.targetselection.vision.TargetBoxAnalyzer;
import com.example.motro.targetselection.vision.TargetBoxDetectionResult;

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
 * 真实采样帧上的「选中框 → 它的 {@code id:xxxx} 标签区域」离线 smoke test ——
 * <b>默认跳过</b>，只有显式指定样本目录时才运行：
 * <pre>
 *   ./gradlew :targetselection:testDebugUnitTest --tests '*LabelSampleSmokeTest*' \
 *       -Dtargetselection.labelSampleDir=artifacts/target_label_samples,artifacts/target_label_real_samples
 * </pre>
 *
 * <p>样本由 {@code tools/roi_blue_mask/build_samples.sh} 现场生成（不进版本库），分两族：</p>
 * <ul>
 *   <li><b>合成样本</b>（{@code artifacts/target_label_samples}）：在去饱和的真实底帧上合成
 *       蓝框 + 蓝编号，覆盖需求里逐个点名的几何场景（靠顶部/靠左右边缘/小目标/断框/
 *       无编号/多目标/编号溢出）。编号可以用「一串空心小方块」拼出来，于是期望外接盒是
 *       <b>算术精确</b>的，断言就落在具体像素上。</li>
 *   <li><b>真实帧样本</b>（{@code artifacts/target_label_real_samples}）：直接解码自真实
 *       采集视频，<b>不含任何合成叠加层</b>。它们承担本 Slice 最关键的几条结论 ——
 *       同一个编号内部的字形空隙约 10px、两个相邻目标的编号之间也是约 10px、
 *       框顶边的抗锯齿残段被压缩切成几段 —— 这些合成样本复现不出来。</li>
 * </ul>
 *
 * <p>每个样本走的是<b>端到端</b>的两步：先 {@link TargetBoxAnalyzer} 用点击点恢复出选中框，
 * 再把该框交给 {@link LabelRegionExtractor} 取标签区域。只验「标签对不对」而不验
 * 「框对不对」会把上游的错误悄悄吞掉。</p>
 *
 * <p>{@code expect} 两种取值：</p>
 * <ul>
 *   <li>{@code LABEL_FOUND}：必须 {@code SUCCESS}，且 ROI 必须<b>包住</b>样本记录的编号盒；
 *       若样本还写了 {@code labelMinWidth/labelMaxWidth/labelMaxHeight}，一并校验 ——
 *       上界用来钉住"缩小到了编号那一小块"，下界用来钉住"没有把编号截断"。</li>
 *   <li>{@code NO_LABEL}：必须 {@code NO_LABEL}（<b>不是</b> MISS、也不是 INVALID_*）——
 *       目标已经选中了，只是没读到编号，上层据此报 {@code HIT_ID_UNKNOWN}。</li>
 * </ul>
 */
public class LabelSampleSmokeTest {

    private static final String SAMPLE_DIR_PROPERTY = "targetselection.labelSampleDir";
    private static final String REPORT_PROPERTY = "targetselection.labelSampleReport";

    private static final String EXPECT_LABEL_FOUND = "LABEL_FOUND";
    private static final String EXPECT_NO_LABEL = "NO_LABEL";

    /**
     * ROI 与样本记录的编号盒之间的容差。真实帧的编号边界是在生成样本时用同一套蓝色判据
     * 量出来的，两边对边界像素的处理只差"外边界 / 闭区间"的一次加减，所以 4px 足够，
     * 又紧到能把"少接住一个字形"（十几像素）判出来。
     */
    private static final float LABEL_TOLERANCE_PX = 4f;

    /**
     * 选中框与样本记录的真值之间的容差。这里放宽到 8px，是因为框要经过
     * Slice 5 的矩形恢复（含 ROI 夹取与压缩造成的 1~3px 边缘偏移），
     * 本测试要保的不是框的精度（那是 Slice 5 的 smoke test 的职责），
     * 而是"标签是相对**这个**框找的"。
     */
    private static final float BOX_TOLERANCE_PX = 8f;

    /**
     * 标签检测阶段的耗时上限（毫秒）。整数毫秒读数 + 真实帧上偶发的 GC，
     * 所以给一个宽容但仍有意义的上限：超过它就说明这一阶段退化成了"每次点击都要好几毫秒"。
     */
    private static final long DETECT_TIME_BUDGET_MS = 8L;

    @Test
    public void realSampleFramesProduceTheExpectedLabelRegions() throws IOException {
        final String dirsSpec = System.getProperty(SAMPLE_DIR_PROPERTY, "").trim();
        Assume.assumeTrue("未指定 -D" + SAMPLE_DIR_PROPERTY + "，跳过标签样本 smoke test",
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

        final TargetSelectionConfig config = TargetSelectionConfig.defaults();
        final TargetBoxAnalyzer analyzer = new TargetBoxAnalyzer(config);
        final LabelRegionExtractor extractor = new LabelRegionExtractor(config);

        final StringBuilder report = new StringBuilder(8192);
        report.append("真实采样帧「选中框 → 标签区域」验证（样本目录：")
                .append(String.join(", ", dirNames)).append("）\n");
        report.append("配置：ROI 半径 ").append(config.roiRadiusX).append('x').append(config.roiRadiusY)
                .append("px，搜索带 上").append(config.labelSearchHeightPx)
                .append("/左右").append(config.labelHorizontalPaddingPx)
                .append("/下压").append(config.labelVerticalPaddingPx)
                .append("px，离框顶上限 ").append(config.labelMaxDistanceFromBoxPx)
                .append("px，编号最小 ").append(config.minLabelCandidateWidth)
                .append('x').append(config.minLabelCandidateHeight)
                .append("px，候选上限 ").append(config.maxLabelCandidateCount)
                .append("，蓝判据 hue[").append(config.blueHueMinDegrees).append(',')
                .append(config.blueHueMaxDegrees).append("] S≥").append(config.minSaturation)
                .append(" V≥").append(config.minValue).append('\n');
        report.append(String.format(Locale.ROOT,
                "%-40s %-11s %4s %-8s %-20s %-20s %4s %4s %6s %-6s %5s %5s %5s %s%n",
                "sample", "expect", "cand", "status", "selectedBox", "labelRoi",
                "w", "h", "gap", "result", "detMs", "cropMs", "totMs", "note"));

        final List<String> failures = new ArrayList<>();
        final List<long[]> timings = new ArrayList<>();
        int found = 0;

        for (File metaFile : metas) {
            final Map<String, String> meta = readMeta(metaFile);
            final String name = metaFile.getName().substring(0, metaFile.getName().length() - 4);
            final File rgba = new File(metaFile.getParentFile(), name + ".rgba");
            final int width = Integer.parseInt(required(meta, "width"));
            final int height = Integer.parseInt(required(meta, "height"));
            final float touchX = Float.parseFloat(required(meta, "touchX"));
            final float touchY = Float.parseFloat(required(meta, "touchY"));
            final String expect = required(meta, "expect").toUpperCase(Locale.ROOT);
            final String note = meta.getOrDefault("note", "");

            final int[] argb = readRgba(rgba, width, height);
            final int[] argbSnapshot = Arrays.copyOf(argb, argb.length);
            final CapturedFrame frame =
                    CapturedFrame.ofImage(ArgbFrameImage.of(width, height, argb), 0L, 0L);

            final List<String> problems = new ArrayList<>();

            // ---- 第一步：点击 → 选中框（端到端，几何与分析帧一致 → 传 null）
            final TargetBoxDetectionResult detection = analyzer.analyze(frame, null, touchX, touchY);
            if (!detection.isHit() || detection.selectedBox == null) {
                problems.add("点击没有命中目标框（status=" + detection.status
                        + ", candidates=" + detection.candidateCount + "）");
            }

            DetectedTargetBox selected = detection.selectedBox;
            if (selected != null && meta.containsKey("boxLeft")) {
                checkSelectedBox(problems, name, selected, meta);
            }

            // ---- 第二步：选中框 → 标签区域
            LabelExtractionResult labelResult = null;
            if (selected != null) {
                labelResult = extractor.extract(frame, null, selected.rectInVideo);
                timings.add(new long[]{labelResult.labelDetectMs, labelResult.labelCropMs,
                        labelResult.totalMs});
            }

            if (labelResult == null) {
                report.append(String.format(Locale.ROOT,
                        "%-40s %-11s %4s %-8s %-20s %-20s %4s %4s %6s %-6s %5s %5s %5s %s%n",
                        name, expect, detection.candidateCount, detection.status, "-", "-",
                        0, 0, "-", "FAIL", 0, 0, 0, note));
                for (String problem : problems) {
                    failures.add(name + "：" + problem + " | " + detection.debugString());
                }
                assertArrayEquals("sample " + name + " 修改了输入像素", argbSnapshot, argb);
                continue;
            }

            final LabelExtractionStatus status = labelResult.status;
            final TargetBoundingBox roi = labelResult.getLabelRectInVideo();

            if (EXPECT_LABEL_FOUND.equals(expect)) {
                if (!status.hasLabel()) {
                    problems.add("期望找到标签区域，实际 status=" + status
                            + "（" + labelResult.debugReason + "）");
                }
                if (roi == null) {
                    problems.add("期望找到标签区域，但没有选中标签");
                } else {
                    found++;
                    checkLabelCoverage(problems, name, labelResult, meta);
                }
                if (labelResult.labelArgb == null) {
                    problems.add("SUCCESS 却没有标签像素");
                } else if (labelResult.labelArgb.length
                        != labelResult.labelWidth * labelResult.labelHeight) {
                    problems.add("标签像素长度与宽高不一致");
                }
            } else if (EXPECT_NO_LABEL.equals(expect)) {
                if (status != LabelExtractionStatus.NO_LABEL) {
                    problems.add("期望 NO_LABEL，实际 " + status
                            + (roi == null ? "" : "（ROI=" + describe(roi) + "）"));
                }
                if (labelResult.hasLabel()) {
                    problems.add("NO_LABEL 样本不该有标签");
                }
                if (roi != null) {
                    problems.add("NO_LABEL 却给出了标签矩形：" + describe(roi));
                }
                if (labelResult.labelArgb != null) {
                    problems.add("NO_LABEL 不该裁剪出标签像素");
                }
                // NO_LABEL 的语义必须仍然是"点中了目标、只是没有编号"
                if (!status.isHitWithoutLabel()) {
                    problems.add("NO_LABEL 必须被视作 HIT_ID_UNKNOWN，而不是 MISS");
                }
            } else {
                throw new IllegalStateException("未知的 expect 值：" + expect + "（" + metaFile + "）");
            }

            if (labelResult.labelDetectMs > DETECT_TIME_BUDGET_MS) {
                problems.add("标签检测耗时 " + labelResult.labelDetectMs + "ms 超过预算 "
                        + DETECT_TIME_BUDGET_MS + "ms");
            }

            report.append(String.format(Locale.ROOT,
                    "%-40s %-11s %4d %-8s %-20s %-20s %4d %4d %6s %-6s %5d %5d %5d %s%n",
                    name, expect, labelResult.candidateCount(), status,
                    selected == null ? "-" : describe(selected.rectInVideo),
                    roi == null ? "-" : describe(roi),
                    roi == null ? 0 : Math.round(roi.width()),
                    roi == null ? 0 : Math.round(roi.height()),
                    labelResult.selectedLabel == null ? "-"
                            : String.format(Locale.ROOT, "%.1f", labelResult.selectedLabel.signedGapToBox),
                    problems.isEmpty() ? "PASS" : "FAIL",
                    labelResult.labelDetectMs, labelResult.labelCropMs, labelResult.totalMs, note));

            for (LabelCandidate candidate : labelResult.candidates) {
                report.append("        candidate ").append(candidate.debugString()).append('\n');
            }
            if (labelResult.debugReason != null) {
                report.append("        reason=").append(labelResult.debugReason).append('\n');
            }
            if (status == LabelExtractionStatus.NO_LABEL) {
                report.append("        searchRegion=").append(describe(labelResult.labelSearchRegion))
                        .append('\n');
            }

            for (String problem : problems) {
                failures.add(name + "：" + problem + " | " + labelResult.debugString());
            }
            // 提取过程不得改动输入像素（真实样本上再确认一次）
            assertArrayEquals("sample " + name + " 修改了输入像素", argbSnapshot, argb);
        }

        report.append('\n').append(timingSummary(timings, metas.size(), found));
        report.append('\n');
        writeReport(report.toString());
        System.out.println(report);

        assertTrue("真实样本标签区域验证失败：\n" + report, failures.isEmpty());
    }

    /** 收集目录下"有同名 .rgba"的 .txt —— 报告文件默认就写在同一目录，不能只看扩展名。 */
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

    /**
     * 选中框必须与样本记录的真值吻合。
     *
     * <p>被 ROI 截断的样本这里不做特殊处理：本测试的样本点击点都挑在框中心附近，
     * 目标是量级几十到三百像素、ROI 是 400x400，不存在截断。</p>
     */
    private static void checkSelectedBox(List<String> problems, String name,
                                         DetectedTargetBox box, Map<String, String> meta) {
        assertNear(problems, name, "选中框 left", box.rectInVideo.left,
                Float.parseFloat(required(meta, "boxLeft")), BOX_TOLERANCE_PX);
        assertNear(problems, name, "选中框 top", box.rectInVideo.top,
                Float.parseFloat(required(meta, "boxTop")), BOX_TOLERANCE_PX);
        assertNear(problems, name, "选中框 right", box.rectInVideo.right,
                Float.parseFloat(required(meta, "boxRight")), BOX_TOLERANCE_PX);
        assertNear(problems, name, "选中框 bottom", box.rectInVideo.bottom,
                Float.parseFloat(required(meta, "boxBottom")), BOX_TOLERANCE_PX);
    }

    /**
     * ROI 必须<b>包住</b>样本记录的编号盒（四边都不得缩进去），
     * 并校验可选的尺寸上下界。
     */
    private static void checkLabelCoverage(List<String> problems, String name,
                                           LabelExtractionResult result, Map<String, String> meta) {
        final TargetBoundingBox roi = result.getLabelRectInVideo();
        if (meta.containsKey("labelLeft")) {
            final float left = Float.parseFloat(required(meta, "labelLeft"));
            final float top = Float.parseFloat(required(meta, "labelTop"));
            final float right = Float.parseFloat(required(meta, "labelRight"));
            final float bottom = Float.parseFloat(required(meta, "labelBottom"));
            assertAtMost(problems, name, "ROI.left 越过编号左端", roi.left, left, LABEL_TOLERANCE_PX);
            assertAtMost(problems, name, "ROI.top 越过编号上端", roi.top, top, LABEL_TOLERANCE_PX);
            assertAtLeast(problems, name, "ROI.right 没盖住编号右端", roi.right, right,
                    LABEL_TOLERANCE_PX);
            assertAtLeast(problems, name, "ROI.bottom 没盖住编号下端", roi.bottom, bottom,
                    LABEL_TOLERANCE_PX);
        }
        checkSizeBound(problems, name, "labelMinWidth", roi.width(), true, meta);
        checkSizeBound(problems, name, "labelMaxWidth", roi.width(), false, meta);
        checkSizeBound(problems, name, "labelMaxHeight", roi.height(), false, meta);
    }

    private static void checkSizeBound(List<String> problems, String name, String key,
                                       float actual, boolean lowerBound, Map<String, String> meta) {
        final String raw = meta.getOrDefault(key, "").trim();
        if (raw.isEmpty() || "-".equals(raw)) {
            return;
        }
        final float limit = Float.parseFloat(raw);
        if (lowerBound && actual < limit - LABEL_TOLERANCE_PX) {
            problems.add("ROI 宽 " + actual + " 小于 " + key + "=" + limit
                    + "（编号被截断了一个或多个字形）");
        }
        if (!lowerBound && actual > limit + LABEL_TOLERANCE_PX) {
            problems.add("ROI 尺寸 " + actual + " 超过 " + key + "=" + limit
                    + "（ROI 撑到了编号之外）");
        }
    }

    private static void assertNear(List<String> problems, String name, String what,
                                   float actual, float expected, float tolerance) {
        if (Math.abs(actual - expected) > tolerance) {
            problems.add(what + " 偏差过大：实际 " + actual + "，期望 " + expected
                    + "（容差 " + tolerance + "）");
        }
    }

    private static void assertAtMost(List<String> problems, String name, String what,
                                     float actual, float limit, float tolerance) {
        if (actual > limit + tolerance) {
            problems.add(what + "：实际 " + actual + "，上限 " + limit + "（容差 " + tolerance + "）");
        }
    }

    private static void assertAtLeast(List<String> problems, String name, String what,
                                      float actual, float limit, float tolerance) {
        if (actual < limit - tolerance) {
            problems.add(what + "：实际 " + actual + "，下限 " + limit + "（容差 " + tolerance + "）");
        }
    }

    private static String describe(TargetBoundingBox box) {
        return String.format(Locale.ROOT, "[%.0f,%.0f,%.0f,%.0f]",
                box.left, box.top, box.right, box.bottom);
    }

    /** 耗时统计 —— 回答"点击一次取标签要多久"，也是发现性能退化的基线。 */
    private static String timingSummary(List<long[]> timings, int samples, int found) {
        long detectMax = 0L;
        long cropMax = 0L;
        long totalMax = 0L;
        long totalSum = 0L;
        for (long[] t : timings) {
            detectMax = Math.max(detectMax, t[0]);
            cropMax = Math.max(cropMax, t[1]);
            totalMax = Math.max(totalMax, t[2]);
            totalSum += t[2];
        }
        final long average = timings.isEmpty() ? 0L : totalSum / timings.size();
        return String.format(Locale.ROOT,
                "样本 %d 个（提取到标签 %d），耗时 max：labelDetect=%dms labelCrop=%dms total=%dms；"
                        + "total 均值=%dms%n"
                        + "说明：这些数字包含真实解码帧的像素回读与整条链路，"
                        + "不是纯算法耗时；单帧单次，不含解码/渲染开销。",
                samples, found, detectMax, cropMax, totalMax, average);
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
