package com.example.motro.targetselection.vision;

import java.util.BitSet;

/**
 * 二值掩码的<b>紧凑、不可变</b>表示：每个像素 1 bit，按行优先排列。
 *
 * <p><b>为什么不用 {@code byte[]}/{@code int[]} 逐像素一个元素</b>：ROI 默认 400×400 就有
 * 16 万像素，逐像素一字节 = 160KB/次分析，一次点击一份；用 bit 只要 20KB，
 * 而且后续 Slice 的矩形恢复/连通域分析会反复扫描这个掩码，越小越快。
 * 逐像素 {@code int} 更是要 640KB —— 这正是「生产路径不要长期持有多份大 Bitmap/数组」的由来。</p>
 *
 * <p><b>不可变性</b>：构造时把位数据拷成自己的 {@code long[]}，之后不再改动；
 * 共享同一份掩码给多个消费者（调试层、后续识别层）不会互相踩。
 * 计数与「行内连续段」都在构造时一次算好，读的时候是 O(1)/O(段数)，不重复扫描。</p>
 *
 * <p><b>行内连续段（{@link #runs()}）的用途</b>：把「逐个 set 位画点」换成
 * 「一行一个矩形」，绘制与几何统计的开销从 O(蓝像素数) 降到 O(段数) ——
 * 蓝框线/文字在一行里通常只有几段，差两三个数量级。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class BinaryMask {

    private static final int[] NO_RUNS = new int[0];

    private final int width;
    private final int height;
    private final long[] words;
    private final int setCount;
    private final int[] runs;

    private BinaryMask(int width, int height, long[] words, int setCount, int[] runs) {
        this.width = width;
        this.height = height;
        this.words = words;
        this.setCount = setCount;
        this.runs = runs;
    }

    /**
     * 由 {@link BitSet} 构造（拷贝位数据，之后源 BitSet 再改动不影响本对象）。
     *
     * @param bits   位数据，位序号 = {@code y * width + x}；为 null 视为全 0
     * @param width  宽度，&gt;= 0
     * @param height 高度，&gt;= 0
     * @throws IllegalArgumentException 宽高为负
     */
    public static BinaryMask of(BitSet bits, int width, int height) {
        requireSize(width, height);
        if (width == 0 || height == 0) {
            return new BinaryMask(width, height, new long[0], 0, NO_RUNS);
        }
        final long[] words = bits == null ? new long[wordCount(width * height)] : bits.toLongArray();
        final int setCount = bits == null ? 0 : bits.cardinality();
        return new BinaryMask(width, height, words, setCount, computeRuns(words, width, height));
    }

    /** 空掩码（全 0）。 */
    public static BinaryMask empty(int width, int height) {
        return of(null, width, height);
    }

    /**
     * 由逐像素布尔数组构造（单测与离线验证最方便）。
     *
     * @param data   长度必须 &gt;= {@code width * height}，按行优先
     * @param width  宽度，&gt;= 0
     * @param height 高度，&gt;= 0
     * @throws IllegalArgumentException 宽高为负，或数组长度不足
     */
    public static BinaryMask ofBooleans(boolean[] data, int width, int height) {
        requireSize(width, height);
        if (data == null) {
            throw new IllegalArgumentException("data must not be null");
        }
        final int pixels = width * height;
        if (data.length < pixels) {
            throw new IllegalArgumentException("data length " + data.length + " < " + pixels);
        }
        final BitSet bits = new BitSet(pixels);
        for (int i = 0; i < pixels; i++) {
            if (data[i]) {
                bits.set(i);
            }
        }
        return of(bits, width, height);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** 像素总数。 */
    public int pixelCount() {
        return width * height;
    }

    /** 被置位的像素数量。 */
    public int setCount() {
        return setCount;
    }

    /** 是否没有任何置位像素。 */
    public boolean isEmpty() {
        return setCount == 0;
    }

    /** 置位像素占比 [0,1]；像素总数为 0 时返回 0。 */
    public float ratio() {
        final int pixels = pixelCount();
        return pixels == 0 ? 0f : (float) setCount / (float) pixels;
    }

    /**
     * 读取某像素是否置位。<b>越界返回 false</b>（不抛异常）——
     * 邻域去噪的边界处理会大量命中这条路径，用异常表达越界既慢又啰嗦。
     */
    public boolean get(int x, int y) {
        if (x < 0 || y < 0 || x >= width || y >= height) {
            return false;
        }
        final int index = y * width + x;
        final int word = index >>> 6;
        if (word >= words.length) {
            return false;
        }
        return (words[word] & (1L << (index & 63))) != 0L;
    }

    /** 行内连续段的个数（每段都是同一行里一段连续置位像素）。 */
    public int runCount() {
        return runs.length / 3;
    }

    /**
     * 行内连续段，扁平存储为三元组 {@code {y, startX, endX}}，均为<b>闭区间</b>。
     * 长度 = {@link #runCount()} × 3。返回内部数组（不要修改）。
     */
    public int[] runs() {
        return runs;
    }

    @Override
    public String toString() {
        return "BinaryMask{" + width + "x" + height
                + ", set=" + setCount + ", ratio=" + ratio()
                + ", runs=" + runCount() + "}";
    }

    // ---------------------------------------------------------------- 内部

    private static int wordCount(int bits) {
        return (bits + 63) >>> 6;
    }

    private static void requireSize(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("width/height must be >= 0 but were "
                    + width + "x" + height);
        }
    }

    /**
     * 一次扫描把「每行连续置位段」收集成扁平三元组；空掩码直接返回空数组。
     *
     * <p>先数段数再写入（避免装箱的 {@code ArrayList<Integer>}），两遍共用同一段扫描逻辑。</p>
     */
    private static int[] computeRuns(long[] words, int width, int height) {
        if (words.length == 0 || width <= 0 || height <= 0) {
            return NO_RUNS;
        }
        final int segments = collectRuns(words, width, height, null);
        if (segments == 0) {
            return NO_RUNS;
        }
        final int[] out = new int[segments * 3];
        collectRuns(words, width, height, out);
        return out;
    }

    /**
     * 扫描所有行，把连续置位段写进 {@code out}（三元组 {y, startX, endX}，闭区间）；
     * {@code out} 为 null 时只数段数。返回段数。
     *
     * <p><b>游标必须单调前进</b>（{@code x = 段起点 + 段长}，而不是 {@code x = 段长}）：
     * 写错基准会让 {@code x} 在原地踏步 —— 段起点靠右、段长又较短时 {@code x} 反而变小，
     * 于是 while 循环永不退出。这不是理论风险，是本类真踩过的坑，
     * 所以进位表达式写得直白一点、并由 {@link BinaryMaskTest} 用多段图案钉住。</p>
     */
    private static int collectRuns(long[] words, int width, int height, int[] out) {
        int segments = 0;
        for (int y = 0; y < height; y++) {
            final int rowStart = y * width;
            int x = 0;
            while (x < width) {
                final int startOffset = scanFor(words, rowStart + x, width - x, true);
                if (startOffset < 0) {
                    break;
                }
                final int startInRow = x + startOffset;
                final int absoluteStart = rowStart + startInRow;
                // 段起点自身必然是置位的，所以下一个 0 位至少在 +1 处 → 游标严格前进
                final int endOffset = scanFor(
                        words, absoluteStart, rowStart + width - absoluteStart, false);
                if (out != null) {
                    final int endInRow = endOffset < 0 ? width : startInRow + endOffset;
                    out[segments * 3] = y;
                    out[segments * 3 + 1] = startInRow;
                    out[segments * 3 + 2] = endInRow - 1;
                }
                segments++;
                x = endOffset < 0 ? width : startInRow + endOffset;
            }
        }
        return segments;
    }

    /** 在 [from, from+limit) 内找第一个满足 {@code wantSet} 的位，返回相对 {@code from} 的偏移；没有返回 -1。 */
    private static int scanFor(long[] words, int from, int limit, boolean wantSet) {
        for (int i = 0; i < limit; i++) {
            if (bitAt(words, from + i) == wantSet) {
                return i;
            }
        }
        return -1;
    }

    private static boolean bitAt(long[] words, int index) {
        final int word = index >>> 6;
        return word < words.length && (words[word] & (1L << (index & 63))) != 0L;
    }
}
