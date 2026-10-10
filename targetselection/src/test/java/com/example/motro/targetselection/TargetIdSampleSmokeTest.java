package com.example.motro.targetselection;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.vision.DetectedTargetBox;
import com.example.motro.targetselection.vision.GlyphTemplateTargetIdRecognizer;
import com.example.motro.targetselection.vision.LabelExtractionResult;
import com.example.motro.targetselection.vision.LabelRegionExtractor;
import com.example.motro.targetselection.vision.TargetBoxAnalyzer;
import com.example.motro.targetselection.vision.TargetBoxDetectionResult;
import com.example.motro.targetselection.vision.TargetIdRecognitionResult;
import com.example.motro.targetselection.vision.TargetIdRecognitionStatus;
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
 * 真实采样帧上的「点击 → 选中框 → 标签区域 → 编号」离线 smoke test ——
 * <b>默认跳过</b>，只有显式指定样本目录时才运行：
 * <pre>
 *   ./gradlew :targetselection:testDebugUnitTest --tests '*TargetIdSampleSmokeTest*' \
 *       -Dtargetselection.idSampleDir=artifacts/target_label_samples,artifacts/target_label_real_samples
 * </pre>
 *
 * <p>与 {@link LabelSampleSmokeTest} 共用同一批样本（同一份 {@code .txt} 元数据 + {@code .rgba}），
 * 区别是它只验到"标签区域"，本测试再往下走第三步 —— 把标签交给
 * {@link GlyphTemplateTargetIdRecognizer} 读编号。三条链路串起来才是本 Slice 的交付物：
 * <b>点击 → 框 → 标签 ROI → {@code targetId}</b>。</p>
 *
 * <p>{@code idExpect} 的取值（缺省为 {@code ID_FOUND}；<b>不由 {@code expect} 推断</b>）：</p>
 * <ul>
 *   <li>{@code ID_FOUND}：标签必须存在，且识别必须 {@code SUCCESS}；若样本写明
 *       {@code targetId} 则必须完全相等（不做"猜对了就行"的模糊断言 —— 认错号比认不出更危险）。</li>
 *   <li>{@code NO_ID}：识别必须是 {@code NO_ID} / {@code LOW_CONFIDENCE} / {@code INVALID_LABEL}
 *       之一，且<b>必须仍然保住命中</b>（{@code isHitWithoutId()}），上层据此报
 *       {@code HIT_ID_UNKNOWN}。</li>
 * </ul>
 *
 * <p><b>不伪造 PASS（本测试的核心纪律）</b>：</p>
 * <ol>
 *   <li>判定用<b>独立的 {@code idExpect}</b> 字段，<b>不再</b>从 Slice 7 的
 *       {@code expect}（{@code LABEL_FOUND}/{@code NO_LABEL}）去推断 ——
 *       "标签找得到"和"编号读得出"是两件事。早先的版本把 {@code LABEL_FOUND}
 *       默认当成 {@code ID_FOUND}，于是"标签找到了但编号读错"的样本也显示 PASS，
 *       那是虚假的通过。</li>
 *   <li>标 {@code ID_FOUND} 但没写 {@code targetId} 时，只要求"读出了一个编号"
 *       并在报告里标注 {@code (no-truth)}，提示"这条没有真值可比"。</li>
 *   <li>读不出编号时不会把用例改成通过 —— 要么样本标 {@code NO_ID}，
 *       要么用例失败并把 {@code rawText} / {@code debugReason} 打进报告。</li>
 * </ol>
 */
public class TargetIdSampleSmokeTest {

    private static final String SAMPLE_DIR_PROPERTY = "targetselection.idSampleDir";
    private static final String REPORT_PROPERTY = "targetselection.idSampleReport";

    private static final String EXPECT_ID_FOUND = "ID_FOUND";
    private static final String EXPECT_NO_ID = "NO_ID";

    /**
     * 只认 {@code idExpect} 字段，取值必须是 {@code ID_FOUND} 或 {@code NO_ID}。
     *
     * <p>缺省 {@code ID_FOUND}。<b>刻意不从 Slice 7 的 {@code expect} 推断</b> ——
     * 见类注释"不伪造 PASS"。</p>
     */
    private static String normalizeIdExpect(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return EXPECT_ID_FOUND;
        }
        final String value = raw.trim().toUpperCase(Locale.ROOT);
        if (EXPECT_ID_FOUND.equals(value) || EXPECT_NO_ID.equals(value)) {
            return value;
        }
        throw new IllegalStateException("未知的 idExpect 值：" + raw
                + "（只接受 " + EXPECT_ID_FOUND + " / " + EXPECT_NO_ID + "）");
    }

    /**
     * 单次识别的总耗时上限（毫秒）。预处理 + 切分 + 匹配全部只作用在小标签 ROI 上，
     * 目标量级是"几十毫秒以内"；超过它就说明这一阶段退化了。
     */
    private static final long RECOGNIZE_TIME_BUDGET_MS = 200L;

    @Test
    public void realSampleFramesProduceTheExpectedTargetIds() throws IOException {
        final String dirsSpec = System.getProperty(SAMPLE_DIR_PROPERTY, "").trim();
        Assume.assumeTrue("未指定 -D" + SAMPLE_DIR_PROPERTY + "，跳过编号识别样本 smoke test",
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
        final TargetIdRecognizer recognizer = new GlyphTemplateTargetIdRecognizer(config);

        final StringBuilder report = new StringBuilder(8192);
        report.append("真实采样帧「点击 → 框 → 标签 → 编号」验证（样本目录：")
                .append(String.join(", ", dirNames)).append("）\n");
        report.append("识别实现：").append(recognizer.name())
                .append("，放大 ").append(config.labelUpscaleFactor).append('x')
                .append("，位数 [").append(config.idMinDigits).append(',')
                .append(config.idMaxDigits).append(']')
                .append("，置信度门槛 ").append(config.minIdConfidence).append('\n');
        report.append(String.format(Locale.ROOT,
                "%-40s %-9s %-28s %-13s %-8s %-10s %6s %5s %5s %5s %5s %-6s %s%n",
                "sample", "idExpect", "labelRoi", "idStatus", "rawText", "targetId",
                "conf", "preMs", "recMs", "parMs", "totMs", "result", "note"));

        final List<String> failures = new ArrayList<>();
        final List<long[]> timings = new ArrayList<>();
        int recognized = 0;
        int recognizedCorrect = 0;

        for (File metaFile : metas) {
            final Map<String, String> meta = readMeta(metaFile);
            final String name = metaFile.getName().substring(0, metaFile.getName().length() - 4);
            final File rgba = new File(metaFile.getParentFile(), name + ".rgba");
            final int width = Integer.parseInt(required(meta, "width"));
            final int height = Integer.parseInt(required(meta, "height"));
            final float touchX = Float.parseFloat(required(meta, "touchX"));
            final float touchY = Float.parseFloat(required(meta, "touchY"));
            final String expect = normalizeIdExpect(meta.get("idExpect"));
            final String note = meta.getOrDefault("note", "");
            final String expectedId = meta.get("targetId");

            final int[] argb = readRgba(rgba, width, height);
            final int[] argbSnapshot = Arrays.copyOf(argb, argb.length);
            final CapturedFrame frame =
                    CapturedFrame.ofImage(ArgbFrameImage.of(width, height, argb), 0L, 0L);

            final List<String> problems = new ArrayList<>();
            boolean hasTruth = true;   // 样本是否给了 targetId 真值（没给时报告里标注）

            // ---- 第一步：点击 → 选中框
            final TargetBoxDetectionResult detection = analyzer.analyze(frame, null, touchX, touchY);
            if (!detection.isHit() || detection.selectedBox == null) {
                problems.add("点击没有命中目标框（status=" + detection.status
                        + ", candidates=" + detection.candidateCount + "）");
            }

            DetectedTargetBox selected = detection.selectedBox;

            // ---- 第二步：选中框 → 标签区域
            LabelExtractionResult labelResult = null;
            if (selected != null) {
                labelResult = extractor.extract(frame, null, selected.rectInVideo);
            }
            if (labelResult == null) {
                report.append(String.format(Locale.ROOT,
                        "%-40s %-9s %-28s %-13s %-8s %-10s %6s %5s %5s %5s %5s %-6s %s%n",
                        name, expect, "-", "NO_FRAME", "-", "-",
                        "-", 0, 0, 0, 0, "FAIL", note));
                for (String problem : problems) {
                    failures.add(name + "：" + problem + " | " + detection.debugString());
                }
                assertArrayEquals("sample " + name + " 修改了输入像素", argbSnapshot, argb);
                continue;
            }

            // ---- 第三步：标签区域 → 编号
            final TargetIdRecognitionResult idResult = recognizer.recognize(labelResult, config);
            timings.add(new long[]{idResult.preprocessMs, idResult.recognizeMs,
                    idResult.parseMs, idResult.processingMs});

            final TargetBoundingBox roi = labelResult.getLabelRectInVideo();
            final boolean isNoIdExpectation = EXPECT_NO_ID.equals(expect);

            if (isNoIdExpectation) {
                // 读不出编号是预期内：只要求"命中语义不倒退" + 状态是失败类
                if (idResult.hasId()) {
                    problems.add("样本期望读不出编号，实际读出 " + idResult.targetId
                            + "（raw=" + idResult.rawText + "）");
                }
                if (!idResult.isHitWithoutId()) {
                    problems.add("识别失败时必须仍然保住命中（HIT_ID_UNKNOWN），实际 status="
                            + idResult.status);
                }
                if (idResult.status == TargetIdRecognitionStatus.SUCCESS) {
                    problems.add("期望 NO_ID，实际 SUCCESS");
                }
            } else if (EXPECT_ID_FOUND.equals(expect)) {
                if (!labelResult.hasLabel()) {
                    problems.add("期望标签存在以便读编号，实际标签状态=" + labelResult.status
                            + "（" + labelResult.debugReason + "）");
                } else if (idResult.status != TargetIdRecognitionStatus.SUCCESS) {
                    problems.add("识别未成功：status=" + idResult.status
                            + "，raw=" + idResult.rawText
                            + "，reason=" + idResult.debugReason);
                } else {
                    recognized++;
                    if (expectedId != null && !expectedId.isEmpty()) {
                        if (expectedId.equals(idResult.targetId)) {
                            recognizedCorrect++;
                        } else {
                            problems.add("编号读错：期望 " + expectedId + "，实际 " + idResult.targetId
                                    + "（raw=" + idResult.rawText + "，conf=" + idResult.confidence + "）");
                        }
                    } else {
                        // 没给真值：只要求"读出了一个编号"，并在报告里显式标注"无真值可比"。
                        recognizedCorrect++;
                        hasTruth = false;
                    }
                    if (idResult.confidence != null
                            && (idResult.confidence < 0f || idResult.confidence > 1f)) {
                        problems.add("置信度越界：" + idResult.confidence);
                    }
                }
            }

            if (idResult.processingMs > RECOGNIZE_TIME_BUDGET_MS) {
                problems.add("识别总耗时 " + idResult.processingMs + "ms 超过预算 "
                        + RECOGNIZE_TIME_BUDGET_MS + "ms");
            }

            report.append(String.format(Locale.ROOT,
                    "%-40s %-9s %-28s %-13s %-8s %-10s %6s %5d %5d %5d %5d %-6s %s%n",
                    name, expect,
                    roi == null ? "-" : describe(roi),
                    idResult.status,
                    idResult.rawText == null ? "-" : '"' + idResult.rawText + '"',
                    idResult.targetId == null ? "-" : idResult.targetId,
                    idResult.confidence == null ? "n/a"
                            : String.format(Locale.ROOT, "%.3f", idResult.confidence),
                    idResult.preprocessMs, idResult.recognizeMs, idResult.parseMs,
                    idResult.processingMs,
                    problems.isEmpty() ? "PASS" : "FAIL",
                    (hasTruth ? "" : "(no-truth) ") + note));

            if (idResult.debugReason != null) {
                report.append("        idReason=").append(idResult.debugReason).append('\n');
            }
            if (!labelResult.hasLabel()) {
                report.append("        labelStatus=").append(labelResult.status)
                        .append(" reason=").append(labelResult.debugReason).append('\n');
            }

            for (String problem : problems) {
                failures.add(name + "：" + problem + " | " + idResult.debugString()
                        + " | label=" + labelResult.debugString());
            }
            // 整条链路都不得改动输入像素
            assertArrayEquals("sample " + name + " 修改了输入像素", argbSnapshot, argb);
        }

        report.append('\n').append(timingSummary(timings, metas.size(), recognized,
                recognizedCorrect));
        report.append('\n');
        writeReport(report.toString());
        System.out.println(report);

        assertTrue("真实样本编号识别验证失败：\n" + report, failures.isEmpty());
    }

    /** 收集目录下"有同名 .rgba"的 .txt —— 报告文件默认写在同目录，不能只看扩展名。 */
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

    private static String describe(TargetBoundingBox box) {
        return String.format(Locale.ROOT, "[%.0f,%.0f,%.0f,%.0f]",
                box.left, box.top, box.right, box.bottom);
    }

    /**
     * 耗时统计 —— 回答"点击一次读编号要多久"。四个数字分开报，
     * 是为了退化时能一眼看出是哪一段变慢了（预处理 / 识别 / 解析）。
     */
    private static String timingSummary(List<long[]> timings, int samples,
                                        int recognized, int correct) {
        long preMax = 0L;
        long recMax = 0L;
        long parMax = 0L;
        long totalMax = 0L;
        long totalSum = 0L;
        for (long[] t : timings) {
            preMax = Math.max(preMax, t[0]);
            recMax = Math.max(recMax, t[1]);
            parMax = Math.max(parMax, t[2]);
            totalMax = Math.max(totalMax, t[3]);
            totalSum += t[3];
        }
        final long average = timings.isEmpty() ? 0L : totalSum / timings.size();
        return String.format(Locale.ROOT,
                "样本 %d 个（成功读出编号 %d，其中与真值一致 %d），耗时 max："
                        + "preprocess=%dms recognize=%dms parse=%dms total=%dms；total 均值=%dms%n"
                        + "说明：单帧单次、只处理小标签 ROI，不含解码/渲染开销；"
                        + "识别器实例在测试内复用，未计入构造成本。",
                samples, recognized, correct, preMax, recMax, parMax, totalMax, average);
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
