package com.example.motro.targetselection;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.vision.BlueMaskAnalyzer;
import com.example.motro.targetselection.vision.BlueMaskResult;
import com.example.motro.targetselection.vision.BlueMaskStatus;

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
 * 真实采样帧的离线 smoke test —— <b>默认跳过</b>，只有显式指定样本目录时运行：
 * <pre>
 *   ./gradlew :targetselection:test --tests '*BlueMaskSampleSmokeTest*' \
 *       -Dtargetselection.sampleDir=&lt;dir&gt;
 * </pre>
 *
 * <p><b>为什么样本不打进仓库</b>：原始视频/帧体积大、来源有版权与保密问题，
 * 而且"把本次实验用的二进制塞进版本库"很快就会变成没人敢删的历史包袱。
 * 于是样本由脚本现场生成（见 {@code tools/roi_blue_mask/build_samples.sh}，它用 ffmpeg
 * 把叠加层画到真实采样帧上并做一次 HEVC 往返，从而带上真实的压缩偏色），
 * 测试只消费目录里的文件。这样既保住可复现性，又不往仓库里放二进制。</p>
 *
 * <p><b>本测试只验证"蓝色像素提取"</b>：断言的是"这块 ROI 里有没有足够的蓝色像素"，
 * <b>不</b>声称识别到了目标 —— 矩形恢复与 id 识别是后续 Slice 的事。</p>
 *
 * <p>样本目录格式（每个样本一对文件）：</p>
 * <ul>
 *   <li>{@code <name>.txt}：{@code key=value} 行，含 {@code width/height/touchX/touchY/expect/note}；
 *       {@code expect} 取 {@code HAS_BLUE} 或 {@code EMPTY}。</li>
 *   <li>{@code <name>.rgba}：行优先 RGBA8888 原始像素。</li>
 * </ul>
 */
public class BlueMaskSampleSmokeTest {

    private static final String SAMPLE_DIR_PROPERTY = "targetselection.sampleDir";
    private static final String REPORT_PROPERTY = "targetselection.sampleReport";

    private static final String EXPECT_HAS_BLUE = "HAS_BLUE";
    private static final String EXPECT_EMPTY = "EMPTY";

    @Test
    public void realSampleFramesProduceTheExpectedMaskVerdicts() throws IOException {
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
                // 只认"有同名 .rgba 的 .txt"才算样本描述：报告文件默认就写在这个目录里，
                // 只按扩展名收会在"跑过一次带报告之后"把报告当成样本读进来，
                // 报出"缺少字段 width"这种与真实缺陷无关的错。
                final String base = child.getName().substring(0, child.getName().length() - 4);
                if (new File(dir, base + ".rgba").isFile()) {
                    metas.add(child);
                }
            }
        }
        Collections.sort(metas, (a, b) -> a.getName().compareTo(b.getName()));
        Assume.assumeTrue("样本目录里没有 .txt 描述文件：" + dir.getAbsolutePath(), !metas.isEmpty());

        final BlueMaskAnalyzer analyzer = new BlueMaskAnalyzer(TargetSelectionConfig.defaults());
        final StringBuilder report = new StringBuilder(2048);
        report.append("真实采样帧蓝色掩码验证（配置：ROI 半径 ")
                .append(TargetSelectionConfig.DEFAULT_ROI_RADIUS)
                .append("px，").append(analyzer.config().blueHueMinDegrees).append("°~")
                .append(analyzer.config().blueHueMaxDegrees).append("°，minSat=")
                .append(analyzer.config().minSaturation).append("，minVal=")
                .append(analyzer.config().minValue).append("）\n");
        report.append(String.format(Locale.ROOT, "%-24s %-9s %11s %11s %6s %-16s %-7s %s%n",
                "sample", "expect", "bluePixels", "blueRatio", "ms", "actual", "result", "note"));

        final List<String> failures = new ArrayList<>();
        for (File metaFile : metas) {
            final Map<String, String> meta = readMeta(metaFile);
            final String name = metaFile.getName().substring(0, metaFile.getName().length() - 4);
            final int width = Integer.parseInt(required(meta, "width"));
            final int height = Integer.parseInt(required(meta, "height"));
            final float touchX = Float.parseFloat(required(meta, "touchX"));
            final float touchY = Float.parseFloat(required(meta, "touchY"));
            final String expect = required(meta, "expect").toUpperCase(Locale.ROOT);
            final String note = meta.getOrDefault("note", "");

            final int[] argb = readRgba(new File(dir, name + ".rgba"), width, height);
            final int[] argbSnapshot = Arrays.copyOf(argb, argb.length);
            final CapturedFrame frame =
                    CapturedFrame.ofImage(ArgbFrameImage.of(width, height, argb), 0L, 0L);

            final BlueMaskResult result = analyzer.analyze(frame, null, touchX, touchY);

            final boolean passed;
            if (EXPECT_HAS_BLUE.equals(expect)) {
                passed = result.status == BlueMaskStatus.SUCCESS && result.bluePixelCount > 0;
            } else if (EXPECT_EMPTY.equals(expect)) {
                passed = result.status == BlueMaskStatus.EMPTY_MASK || result.bluePixelCount == 0;
            } else {
                throw new IllegalStateException("未知的 expect 值：" + expect + "（" + metaFile + "）");
            }

            report.append(String.format(Locale.ROOT, "%-24s %-9s %11d %11.6f %6d %-16s %-7s %s%n",
                    name, expect, result.bluePixelCount, result.bluePixelRatio,
                    result.processingMs, result.status, passed ? "PASS" : "FAIL", note));

            if (!passed) {
                failures.add(name + "：expect=" + expect + " actual=" + result.status
                        + " blue=" + result.bluePixelCount + " " + result.debugString());
            }
            // 分析过程不得改动输入像素（真实样本上再确认一次）
            assertArrayEquals("sample " + name + " 修改了输入像素", argbSnapshot, argb);
        }

        report.append('\n');
        writeReport(report.toString());
        System.out.println(report);

        assertTrue("真实样本验证失败：\n" + report,
                failures.isEmpty());
    }

    /**
     * 把命令行传来的相对路径解析成绝对路径（工程根为基准）。
     *
     * <p>单测的工作目录可能是模块目录也可能是工程根目录，所以相对路径不能直接交给
     * {@code new File(...)}（它会按 {@code user.dir} 解析，得到五花八门的结果）。这里从
     * {@code user.dir} 逐级向上找工程根（含 {@code settings.gradle}），再拼相对路径，
     * 让命令行可以统一写 {@code artifacts/roi_blue_mask_samples} 这种仓库相对形式，
     * 样本目录与报告文件都落在同一个地方。</p>
     */
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
        // 找不到工程根就原样返回，让调用方给出"目录不存在"的失败信息。
        return given;
    }

    /** 缺字段直接失败，并在消息里带上文件路径 —— 生成脚本与测试不同步是常见故障。 */
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
