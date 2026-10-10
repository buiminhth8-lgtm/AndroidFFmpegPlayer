package com.example.motro.targetselection;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.vision.DetectedTargetBox;
import com.example.motro.targetselection.vision.TargetBoxAnalyzer;
import com.example.motro.targetselection.vision.TargetBoxDetectionResult;
import com.example.motro.targetselection.vision.TargetBoxDetectionStatus;

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
 * 真实采样帧上的「矩形恢复 + 触摸命中」离线 smoke test —— <b>默认跳过</b>，
 * 只有显式指定样本目录时才运行：
 * <pre>
 *   ./gradlew :targetselection:testDebugUnitTest --tests '*TargetBoxSampleSmokeTest*' \
 *       -Dtargetselection.boxSampleDir=artifacts/target_box_samples
 * </pre>
 *
 * <p>样本由 {@code tools/roi_blue_mask/build_samples.sh} 现场生成（真实解码帧 + HEVC 往返），
 * 不进版本库。每个样本记录 {@code expect} 与目标框的视频坐标，因此这里不只验
 * "命不命中"，还能验<b>框恢复得准不准</b> —— 后者更重要：框位置错了，
 * 命中判定就是"精确地判错"。</p>
 *
 * <p>{@code expect} 三种取值：</p>
 * <ul>
 *   <li>{@code HIT_BOX}：必须命中，且选中框与样本记录的框吻合（被 ROI 截断的样本只验可信的那几条边）。</li>
 *   <li>{@code MISS}：ROI 里能找到候选，但点击点不在框内 —— 必须不命中。</li>
 *   <li>{@code NO_BOX}：ROI 里压根没有框 —— 必须报 NO_BOX 且不命中。</li>
 * </ul>
 */
public class TargetBoxSampleSmokeTest {

    private static final String SAMPLE_DIR_PROPERTY = "targetselection.boxSampleDir";
    private static final String REPORT_PROPERTY = "targetselection.boxSampleReport";

    private static final String EXPECT_HIT = "HIT_BOX";
    private static final String EXPECT_MISS = "MISS";
    private static final String EXPECT_NO_BOX = "NO_BOX";

    /**
     * 真实 HEVC 往返会带来 4:2:0 色度子采样导致的边缘偏移（细线在色度面被抹开 1~2px），
     * 所以这里比合成图用例（1.5px）放得宽一些。
     */
    private static final float EDGE_TOLERANCE_PX = 6f;

    @Test
    public void realSampleFramesProduceTheExpectedHitVerdictsAndBoxes() throws IOException {
        final String dirPath = System.getProperty(SAMPLE_DIR_PROPERTY, "").trim();
        Assume.assumeTrue("未指定 -D" + SAMPLE_DIR_PROPERTY + "，跳过真实样本 smoke test",
                !dirPath.isEmpty());
        final File dir = resolveAgainstProjectRoot(dirPath);
        Assume.assumeTrue("样本目录不存在：" + dir.getAbsolutePath(), dir.isDirectory());

        final List<File> metas = new ArrayList<>();
        final File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.isFile() || !child.getName().endsWith(".txt")) {
                    continue;
                }
                // 只认"有同名 .rgba 的 .txt"才算样本描述。
                // 不能只按扩展名收：报告文件（report.txt）默认就写在这个目录里，
                // 只按 .txt 收会在"跑过一次带报告之后"把报告当成样本读进来，
                // 报出"缺少字段 width"这种与真实缺陷无关的错。
                final String base = child.getName().substring(0, child.getName().length() - 4);
                if (new File(dir, base + ".rgba").isFile()) {
                    metas.add(child);
                }
            }
        }
        Collections.sort(metas, (a, b) -> a.getName().compareTo(b.getName()));
        Assume.assumeTrue("样本目录里没有 .txt 描述文件：" + dir.getAbsolutePath(), !metas.isEmpty());

        final TargetSelectionConfig config = TargetSelectionConfig.defaults();
        final TargetBoxAnalyzer analyzer = new TargetBoxAnalyzer(config);
        final StringBuilder report = new StringBuilder(4096);
        report.append("真实采样帧矩形恢复 / 命中验证（配置：ROI 半径 ")
                .append(config.roiRadiusX).append('x').append(config.roiRadiusY)
                .append("px，最小框 ").append(config.minBoxWidth).append('x').append(config.minBoxHeight)
                .append("px，边覆盖率≥").append(config.minEdgeCoverage)
                .append("，最小置信度 ").append(config.minBoxConfidence)
                .append("，触摸容差 ").append(config.touchTolerancePx).append("px）\n");
        report.append(String.format(Locale.ROOT,
                "%-24s %-8s %5s %-14s %-38s %-7s %6s %6s %6s %s%n",
                "sample", "expect", "cand", "status", "selectedBox", "result",
                "detMs", "hitMs", "totMs", "note"));

        final List<String> failures = new ArrayList<>();
        final List<long[]> timings = new ArrayList<>();
        int hits = 0;

        for (File metaFile : metas) {
            final Map<String, String> meta = readMeta(metaFile);
            final String name = metaFile.getName().substring(0, metaFile.getName().length() - 4);
            final int width = Integer.parseInt(required(meta, "width"));
            final int height = Integer.parseInt(required(meta, "height"));
            final float touchX = Float.parseFloat(required(meta, "touchX"));
            final float touchY = Float.parseFloat(required(meta, "touchY"));
            final String expect = required(meta, "expect").toUpperCase(Locale.ROOT);
            final boolean clipped = "yes".equalsIgnoreCase(meta.getOrDefault("clipped", "no"));
            final String note = meta.getOrDefault("note", "");

            final int[] argb = readRgba(new File(dir, name + ".rgba"), width, height);
            final int[] argbSnapshot = Arrays.copyOf(argb, argb.length);
            final CapturedFrame frame =
                    CapturedFrame.ofImage(ArgbFrameImage.of(width, height, argb), 0L, 0L);

            final TargetBoxDetectionResult result = analyzer.analyze(frame, null, touchX, touchY);
            timings.add(new long[]{result.detectMs, result.hitTestMs, result.totalMs});
            if (result.isHit()) {
                hits++;
            }

            final List<String> problems = new ArrayList<>();
            if (EXPECT_HIT.equals(expect)) {
                if (!result.isHit()) {
                    problems.add("期望命中但未命中（status=" + result.status
                            + ", candidates=" + result.candidateCount + "）");
                }
                if (result.candidateCount < 1) {
                    problems.add("期望至少 1 个候选，实际 0");
                }
                if (result.selectedBox == null) {
                    problems.add("命中却没有选中框");
                } else {
                    checkSelectedBox(problems, name, result.selectedBox, meta, clipped,
                            touchX, config, width);
                }
            } else if (EXPECT_MISS.equals(expect)) {
                if (result.isHit()) {
                    problems.add("期望不命中但命中了：" + result.selectedBox);
                }
                if (result.candidateCount < 1) {
                    problems.add("本样本应能找回候选框（这样才能区分出'有框但没点中'），实际 0");
                }
                if (result.status != TargetBoxDetectionStatus.SUCCESS) {
                    problems.add("期望 status=SUCCESS（有候选），实际 " + result.status);
                }
            } else if (EXPECT_NO_BOX.equals(expect)) {
                if (result.status != TargetBoxDetectionStatus.NO_BOX) {
                    problems.add("期望 status=NO_BOX，实际 " + result.status);
                }
                if (result.isHit()) {
                    problems.add("NO_BOX 样本不该命中");
                }
                if (result.candidateCount != 0) {
                    problems.add("NO_BOX 样本不该有候选，实际 " + result.candidateCount);
                }
            } else {
                throw new IllegalStateException("未知的 expect 值：" + expect + "（" + metaFile + "）");
            }

            report.append(String.format(Locale.ROOT,
                    "%-24s %-8s %5d %-14s %-38s %-7s %6d %6d %6d %s%n",
                    name, expect, result.candidateCount, result.status,
                    result.selectedBox == null ? "-" : describe(result.selectedBox),
                    problems.isEmpty() ? "PASS" : "FAIL",
                    result.detectMs, result.hitTestMs, result.totalMs, note));

            // 多候选时把每个候选都列出来 —— "多出来的候选是什么"是判断误检的关键信息，
            // 只看选中框会漏掉"文字也凑出了候选框"这类精度问题。
            if (result.candidateCount > 1) {
                for (DetectedTargetBox candidate : result.candidates) {
                    report.append("        candidate ").append(describe(candidate)).append('\n');
                }
            }

            for (String problem : problems) {
                failures.add(name + "：" + problem + " | " + result.debugString());
            }
            // 分析过程不得改动输入像素（真实样本上再确认一次）
            assertArrayEquals("sample " + name + " 修改了输入像素", argbSnapshot, argb);
        }

        report.append('\n').append(timingSummary(timings, metas.size(), hits));
        report.append('\n');
        writeReport(report.toString());
        System.out.println(report);

        assertTrue("真实样本矩形恢复/命中验证失败：\n" + report, failures.isEmpty());
    }

    /**
     * 比对选中框与样本记录的期望框。
     *
     * <p>被 ROI 截断的样本只验<b>可信的边</b>：左/上/下三条，右边改成断言
     * "确实被夹到 ROI 界上" —— 若强行要求它等于真实框右边，就等于要求检测器
     * 凭空猜出 ROI 之外的内容。</p>
     */
    private static void checkSelectedBox(List<String> problems, String name,
                                        DetectedTargetBox box, Map<String, String> meta,
                                        boolean clipped, float touchX,
                                        TargetSelectionConfig config, int videoWidth) {
        final float expectedLeft = Float.parseFloat(required(meta, "boxLeft"));
        final float expectedTop = Float.parseFloat(required(meta, "boxTop"));
        final float expectedRight = Float.parseFloat(required(meta, "boxRight"));
        final float expectedBottom = Float.parseFloat(required(meta, "boxBottom"));

        assertNear(problems, name, "left", box.rectInVideo.left, expectedLeft);
        assertNear(problems, name, "top", box.rectInVideo.top, expectedTop);
        assertNear(problems, name, "bottom", box.rectInVideo.bottom, expectedBottom);
        if (clipped) {
            final float roiRight = Math.min(touchX + config.roiRadiusX, videoWidth);
            assertNear(problems, name, "right(被 ROI 夹取)", box.rectInVideo.right, roiRight);
            if (!box.clippedByRoi) {
                problems.add("几何被 ROI 夹取了却没标记 clippedByRoi");
            }
        } else {
            assertNear(problems, name, "right", box.rectInVideo.right, expectedRight);
            if (box.clippedByRoi) {
                problems.add("框完全在 ROI 内却被标记成 clippedByRoi");
            }
        }
    }

    private static void assertNear(List<String> problems, String name, String edge,
                                   float actual, float expected) {
        if (Math.abs(actual - expected) > EDGE_TOLERANCE_PX) {
            problems.add(edge + " 偏差过大：实际 " + actual + "，期望 " + expected
                    + "（容差 " + EDGE_TOLERANCE_PX + "）");
        }
    }

    private static String describe(DetectedTargetBox box) {
        return String.format(Locale.ROOT, "[%.0f,%.0f,%.0f,%.0f] c=%.2f e=%d%s",
                box.rectInVideo.left, box.rectInVideo.top,
                box.rectInVideo.right, box.rectInVideo.bottom,
                box.confidence, box.presentEdges, box.clippedByRoi ? " clip" : "");
    }

    /** 耗时统计 —— 用来回答"点击一次要多久"，也是发现性能退化的基线。 */
    private static String timingSummary(List<long[]> timings, int samples, int hits) {
        long detectMax = 0L;
        long hitMax = 0L;
        long totalMax = 0L;
        long totalSum = 0L;
        for (long[] t : timings) {
            detectMax = Math.max(detectMax, t[0]);
            hitMax = Math.max(hitMax, t[1]);
            totalMax = Math.max(totalMax, t[2]);
            totalSum += t[2];
        }
        final long average = timings.isEmpty() ? 0L : totalSum / timings.size();
        return String.format(Locale.ROOT,
                "样本 %d 个（命中 %d），耗时 max：detect=%dms hitTest=%dms total=%dms；"
                        + "total 均值=%dms%n"
                        + "说明：这些数字包含 HEVC 解码帧的回读与整条链路，"
                        + "不是纯算法耗时；单帧单次，不含解码/渲染开销。",
                samples, hits, detectMax, hitMax, totalMax, average);
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
