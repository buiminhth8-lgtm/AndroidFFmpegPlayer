package com.example.motro.targetselection.vision;

import java.util.BitSet;

/**
 * 把 ROI 像素判成「蓝色 / 非蓝色」的二值掩码 —— 播放器无关、<b>零第三方依赖</b>、
 * 不用 OpenCV，只有 HSV 阈值 + 一步邻域去噪。
 *
 * <p><b>为什么不用 {@code RGB == #0000FF}</b>：推流端的叠加层画的是纯蓝，但 HEVC
 * 无损个鬼 —— 量化与色度子采样会让蓝框解码成 {@code #3C4FD2}、{@code #5A6FD8} 这类值，
 * 而且边缘像素还会与背景混色。精确相等比较会大面积漏检；用 RGB 距离阈值又要同时
 * 对付「亮度变化」与「色调偏移」两个自由度。转 HSV 后把两个自由度拆开
 * （色调决定"是不是蓝"、饱和度/明度决定"够不够浓"）才有一组能覆盖不同推流端与
 * 不同码率的稳定阈值。</p>
 *
 * <p><b>流程</b>：</p>
 * <pre>
 *   ROI ARGB  ──▶ RgbHsv 逐像素  ──▶ BlueColorThresholds  ──▶ 位掩码  ──▶ 邻域去噪  ──▶ BinaryMask
 * </pre>
 *
 * <p><b>去噪为什么只做「去除孤立点」而不做腐蚀/开运算</b>：本阶段最重要的产出是
 * <b>细蓝框线</b>与 <b>蓝色 `id:xxxx` 文字</b>（后续 Slice 7/8 还要用文字做 id 识别）。
 * 形态学腐蚀对 1~2px 宽的线条是致命的 —— 一腐蚀就断线，而断了的框线会让矩形恢复直接失败。
 * 因此这里只删除「8 邻域内一个蓝像素都没有」的点，即压缩噪声里最常见的孤立点；
 * 真实线条上的像素至少有一个邻居，不会被误杀。</p>
 *
 * <p><b>性能</b>：逐像素做一次整数运算的 HSV 转换（{@link RgbHsv#fromArgb(int, float[])}，
 * 复用数组、零分配），去噪只遍历「已置位的像素」而不是整块 ROI，因此开销与蓝像素数成正比
 * 而不是与 ROI 面积成正比。内部的两个 {@link BitSet} 跨调用复用，稳态下每次分析
 * 除了 ROI 像素缓冲之外不产生大对象。</p>
 *
 * <p>线程约定：{@link #detect(TouchRoiResult)} 是 <b>{@code synchronized}</b> 的
 * （复用内部缓冲）；单次调用是纯计算、不阻塞，可安全地从工作线程调用。
 * 不要在主线程 / 解码线程 / 渲染线程调用。</p>
 */
public final class BlueMaskDetector {

    private final BlueColorThresholds thresholds;
    private final int denoiseMinNeighbors;
    private final int minBluePixelCount;
    private final boolean keepDebugRoi;

    private final Object lock = new Object();

    /** 复用缓冲：一次分析的位数据。分析结束前一直由本对象独占。 */
    private BitSet resultBits = new BitSet();

    /** 复用缓冲：去噪的第二块位数据（与 {@link #resultBits} 互换角色）。 */
    private BitSet scratchBits = new BitSet();

    /** 复用缓冲：逐像素 HSV 输出，避免在热循环里分配。 */
    private final float[] hsv = new float[RgbHsv.COMPONENTS];

    /**
     * @param thresholds          蓝色判定规则，非 null
     * @param denoiseMinNeighbors 去噪阈值：8 邻域内蓝像素少于该值的像素会被删除。
     *                            0 = 不去噪；1 = 只删除完全孤立的点（推荐默认）
     * @param minBluePixelCount   低于该数量视为 {@link BlueMaskStatus#EMPTY_MASK}，&ge; 0
     * @param keepDebugRoi        true 时把 ROI 原始像素挂到结果上（仅调试用）
     * @throws IllegalArgumentException thresholds 为 null、去噪阈值不在 [0,8]，或最小像素数为负
     */
    public BlueMaskDetector(BlueColorThresholds thresholds, int denoiseMinNeighbors,
                            int minBluePixelCount, boolean keepDebugRoi) {
        if (thresholds == null) {
            throw new IllegalArgumentException("thresholds must not be null");
        }
        if (denoiseMinNeighbors < 0 || denoiseMinNeighbors > 8) {
            throw new IllegalArgumentException("denoiseMinNeighbors must be within [0,8] but was "
                    + denoiseMinNeighbors);
        }
        if (minBluePixelCount < 0) {
            throw new IllegalArgumentException("minBluePixelCount must be >= 0 but was " + minBluePixelCount);
        }
        this.thresholds = thresholds;
        this.denoiseMinNeighbors = denoiseMinNeighbors;
        this.minBluePixelCount = minBluePixelCount;
        this.keepDebugRoi = keepDebugRoi;
    }

    /** 当前生效的蓝色判定规则。 */
    public BlueColorThresholds thresholds() {
        return thresholds;
    }

    /**
     * 在 ROI 上提取蓝色掩码。
     *
     * @param roi {@link TouchRoiExtractor} 的结果，可为 null（→ {@code INVALID_FRAME}）
     * @return 非 null 的结果；{@link BlueMaskResult#processingMs} 只统计本方法耗时
     *         （端到端耗时由 {@link BlueMaskAnalyzer} 覆盖）
     */
    public BlueMaskResult detect(TouchRoiResult roi) {
        final long startNs = System.nanoTime();
        if (roi == null) {
            return BlueMaskResult.failure(BlueMaskStatus.INVALID_FRAME, "roi result is null");
        }
        if (!roi.isUsable()) {
            return BlueMaskResult.failure(
                    BlueMaskStatus.fromTouchRoiStatus(roi.status), roi.debugReason);
        }

        final TouchRoi geometry = roi.roi;
        final int[] argb = roi.argb;
        final int width = geometry.frameWidth;
        final int height = geometry.frameHeight;
        final int pixels = width * height;

        synchronized (lock) {
            resultBits.clear();
            // 1) 阈值判定：逐像素 RGB → HSV → 蓝色规则
            for (int i = 0; i < pixels; i++) {
                final float[] out = RgbHsv.fromArgb(argb[i], hsv);
                if (thresholds.matches(out[0], out[1], out[2])) {
                    resultBits.set(i);
                }
            }

            // 2) 去噪：只删除孤立点，保住 1~2px 的细框线与文字笔画
            if (denoiseMinNeighbors > 0) {
                denoiseIsolated(resultBits, scratchBits, width, height);
                final BitSet swap = resultBits;
                resultBits = scratchBits;
                scratchBits = swap;
            }

            final int bluePixelCount = resultBits.cardinality();
            final float bluePixelRatio = (float) bluePixelCount / (float) pixels;
            // 空掩码必然收敛；低于配置下限也提前收敛，省掉下游一次注定失败的矩形恢复。
            // 注意 bluePixelCount/ratio 仍然如实汇报原始值，调参时能看到「差多少」。
            final BlueMaskStatus status =
                    (bluePixelCount == 0 || bluePixelCount < minBluePixelCount)
                            ? BlueMaskStatus.EMPTY_MASK : BlueMaskStatus.SUCCESS;
            // 掩码必须是快照：内部位缓冲下一次分析就会被改写
            final BinaryMask mask = BinaryMask.of(resultBits, width, height);
            final long processingMs = elapsedMs(startNs);
            return BlueMaskResult.of(status, geometry, mask, bluePixelCount, bluePixelRatio,
                    processingMs, keepDebugRoi ? argb : null, null);
        }
    }

    /**
     * 邻域过滤：把 {@code src} 中「8 邻域内置位像素少于 {@code denoiseMinNeighbors}」的
     * 像素写入 {@code dst}。
     *
     * <p>只遍历 {@code src} 中已置位的位（{@link BitSet#nextSetBit(int)}），
     * 因此开销与蓝像素数成正比。写 {@code dst} 时不读 {@code dst}，两块缓冲不会互相干扰。</p>
     */
    private void denoiseIsolated(BitSet src, BitSet dst, int width, int height) {
        dst.clear();
        final int lastIndex = width * height;
        for (int index = src.nextSetBit(0); index >= 0 && index < lastIndex;
                index = src.nextSetBit(index + 1)) {
            final int x = index % width;
            final int y = index / width;
            int neighbors = 0;
            final int minY = Math.max(0, y - 1);
            final int maxY = Math.min(height - 1, y + 1);
            final int minX = Math.max(0, x - 1);
            final int maxX = Math.min(width - 1, x + 1);
            for (int ny = minY; ny <= maxY; ny++) {
                final int rowStart = ny * width;
                for (int nx = minX; nx <= maxX; nx++) {
                    if (nx == x && ny == y) {
                        continue;
                    }
                    if (src.get(rowStart + nx)) {
                        neighbors++;
                    }
                }
            }
            if (neighbors >= denoiseMinNeighbors) {
                dst.set(index);
            }
        }
    }

    private static long elapsedMs(long startNs) {
        final long deltaNs = System.nanoTime() - startNs;
        return deltaNs <= 0L ? 0L : deltaNs / 1_000_000L;
    }
}
