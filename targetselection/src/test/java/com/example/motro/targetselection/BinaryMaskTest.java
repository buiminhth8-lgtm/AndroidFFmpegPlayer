package com.example.motro.targetselection;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.vision.BinaryMask;

import org.junit.Test;

import java.util.BitSet;

/** 紧凑位掩码的读契约：位序、计数、比例、行内连续段、不可变性。 */
public class BinaryMaskTest {

    @Test
    public void emptyMaskHasNoBitsAndZeroRatio() {
        BinaryMask mask = BinaryMask.empty(8, 4);
        assertTrue(mask.isEmpty());
        assertEquals(0, mask.setCount());
        assertEquals(0f, mask.ratio(), 0f);
        assertEquals(32, mask.pixelCount());
        assertEquals(0, mask.runCount());
        assertEquals(0, mask.runs().length);
        assertFalse(mask.get(0, 0));
    }

    @Test
    public void zeroSizedMaskIsLegalAndEmpty() {
        BinaryMask mask = BinaryMask.empty(0, 0);
        assertEquals(0, mask.pixelCount());
        assertEquals(0f, mask.ratio(), 0f);
        assertFalse(mask.get(0, 0));
    }

    @Test
    public void bitOrderIsRowMajor() {
        boolean[] data = new boolean[8 * 3];
        data[0] = true;              // (0,0)
        data[7] = true;              // (7,0)
        data[8] = true;              // (0,1)
        BinaryMask mask = BinaryMask.ofBooleans(data, 8, 3);

        assertTrue(mask.get(0, 0));
        assertTrue(mask.get(7, 0));
        assertTrue(mask.get(0, 1));
        assertFalse(mask.get(1, 0));
        assertFalse(mask.get(0, 2));
        assertEquals(3, mask.setCount());
        assertEquals(3f / 24f, mask.ratio(), 1e-6f);
    }

    @Test
    public void outOfRangeReadsReturnFalseInsteadOfThrowing() {
        BinaryMask mask = BinaryMask.empty(4, 4);
        assertFalse(mask.get(-1, 0));
        assertFalse(mask.get(0, -1));
        assertFalse(mask.get(4, 0));
        assertFalse(mask.get(0, 4));
        assertFalse(mask.get(1000, 1000));
    }

    @Test
    public void rowRunsAreClosedIntervalsInRowMajorOrder() {
        boolean[] data = new boolean[8 * 3];
        set(data, 8, 0, 0);
        set(data, 8, 1, 0);
        set(data, 8, 2, 0);
        set(data, 8, 5, 0);
        set(data, 8, 6, 0);
        set(data, 8, 3, 1);
        for (int x = 0; x < 8; x++) {
            set(data, 8, x, 2);
        }
        BinaryMask mask = BinaryMask.ofBooleans(data, 8, 3);

        assertEquals(4, mask.runCount());
        // {y, startX, endX} 三元组，闭区间
        assertArrayEquals(new int[]{
                0, 0, 2,
                0, 5, 6,
                1, 3, 3,
                2, 0, 7}, mask.runs());
    }

    /**
     * 回归：段起点靠右、段又很短时，扫描游标必须依然严格前进。
     *
     * <p>这里的图案刻意造成「第一段起点（11）大于第二段之前的位置」这种关系 ——
     * 如果行内扫描把「段长」当成「段起点」使用，游标就会原地踏步，
     * 整个掩码构造变成死循环（这是本类真踩过的坑，写这个用例就是为了钉住它）。</p>
     */
    @Test
    public void shortDistantRunsPerRowTerminateAndStayAccurate() {
        boolean[] data = new boolean[16 * 2];
        set(data, 16, 3, 0);
        set(data, 16, 11, 0);
        set(data, 16, 2, 1);
        set(data, 16, 12, 1);

        BinaryMask mask = BinaryMask.ofBooleans(data, 16, 2);

        assertEquals(4, mask.runCount());
        assertArrayEquals(new int[]{
                0, 3, 3,
                0, 11, 11,
                1, 2, 2,
                1, 12, 12}, mask.runs());
        assertEquals(4, mask.setCount());
    }

    @Test
    public void maskSnapshotsTheSourceBitSet() {
        BitSet bits = new BitSet(8);
        bits.set(0);
        BinaryMask mask = BinaryMask.of(bits, 8, 1);
        assertEquals(1, mask.setCount());

        // 构造之后改源位集：掩码必须不变（否则"结果被下一次分析改写"的别名 bug 会复现）
        bits.set(5);
        bits.clear(0);
        assertEquals(1, mask.setCount());
        assertTrue(mask.get(0, 0));
        assertFalse(mask.get(5, 0));
    }

    @Test
    public void fullMaskHasRatioOneAndOneRunPerRow() {
        BinaryMask mask = BinaryMask.ofBooleans(allTrue(5 * 2), 5, 2);
        assertEquals(10, mask.setCount());
        assertEquals(1f, mask.ratio(), 1e-6f);
        assertEquals(2, mask.runCount());
        assertArrayEquals(new int[]{0, 0, 4, 1, 0, 4}, mask.runs());
    }

    @Test
    public void trailingZeroWordsDoNotBreakReads() {
        // 只置最高位 → toLongArray 长度正好；只置第 0 位 → 尾部全 0 的字会被裁掉
        BitSet bits = new BitSet();
        bits.set(63);
        BinaryMask mask = BinaryMask.of(bits, 64, 1);
        assertFalse(mask.get(0, 0));
        assertTrue(mask.get(63, 0));
        assertEquals(1, mask.setCount());
    }

    @Test
    public void invalidSizesAndDataLengthAreRejected() {
        assertRejected(() -> BinaryMask.empty(-1, 4));
        assertRejected(() -> BinaryMask.ofBooleans(new boolean[4], 4, 2));
        assertRejected(() -> BinaryMask.ofBooleans(null, 4, 2));
    }

    // ---------------------------------------------------------------- helpers

    private static void set(boolean[] data, int width, int x, int y) {
        data[y * width + x] = true;
    }

    private static boolean[] allTrue(int length) {
        boolean[] data = new boolean[length];
        for (int i = 0; i < length; i++) {
            data[i] = true;
        }
        return data;
    }

    private interface ThrowingRunnable {
        void run();
    }

    private static void assertRejected(ThrowingRunnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 预期
        }
    }
}
