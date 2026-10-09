package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.vision.RgbHsv;

import org.junit.Test;

/**
 * RGB → HSV 的数值契约。用<b>已知角度</b>的纯色钉死公式 —— 一旦有人"顺手优化"
 * 这段算术（或者把角度制换成 OpenCV 的 [0,180]），这里会立刻红。
 */
public class RgbHsvTest {

    private static final float EPS = 0.01f;

    @Test
    public void pureBlueIs240Degrees() {
        RgbHsv hsv = RgbHsv.fromArgb(TestImages.PURE_BLUE);
        assertEquals(240f, hsv.hue, EPS);
        assertEquals(1f, hsv.saturation, EPS);
        assertEquals(1f, hsv.value, EPS);
    }

    @Test
    public void primariesAndSecondariesMapToExpectedHues() {
        assertEquals(0f, RgbHsv.fromArgb(TestImages.rgb(255, 0, 0)).hue, EPS);
        assertEquals(60f, RgbHsv.fromArgb(TestImages.rgb(255, 255, 0)).hue, EPS);
        assertEquals(120f, RgbHsv.fromArgb(TestImages.rgb(0, 255, 0)).hue, EPS);
        assertEquals(180f, RgbHsv.fromArgb(TestImages.rgb(0, 255, 255)).hue, EPS);
        assertEquals(240f, RgbHsv.fromArgb(TestImages.rgb(0, 0, 255)).hue, EPS);
        assertEquals(300f, RgbHsv.fromArgb(TestImages.rgb(255, 0, 255)).hue, EPS);
    }

    /** 灰阶的色调无定义，约定为 0；但饱和度必然为 0，所以不会被任何蓝色规则接受。 */
    @Test
    public void achromaticColorsHaveZeroSaturation() {
        RgbHsv black = RgbHsv.fromArgb(TestImages.rgb(0, 0, 0));
        assertEquals(0f, black.saturation, EPS);
        assertEquals(0f, black.value, EPS);

        RgbHsv white = RgbHsv.fromArgb(TestImages.rgb(255, 255, 255));
        assertEquals(0f, white.saturation, EPS);
        assertEquals(1f, white.value, EPS);

        RgbHsv gray = RgbHsv.fromArgb(TestImages.rgb(128, 128, 128));
        assertEquals(0f, gray.saturation, EPS);
        assertEquals(128f / 255f, gray.value, EPS);
    }

    /** 暗蓝（压缩后常出现的变暗）色调仍是 240°，只是 V 变小。 */
    @Test
    public void darkerBlueKeepsHueButDropsValue() {
        RgbHsv hsv = RgbHsv.fromArgb(TestImages.rgb(0, 0, 64));
        assertEquals(240f, hsv.hue, EPS);
        assertEquals(64f / 255f, hsv.value, EPS);
    }

    @Test
    public void alphaChannelIsIgnored() {
        RgbHsv opaque = RgbHsv.fromArgb(0xFF0000FF);
        RgbHsv transparent = RgbHsv.fromArgb(0x000000FF);
        assertEquals(opaque.hue, transparent.hue, 0f);
        assertEquals(opaque.saturation, transparent.saturation, 0f);
        assertEquals(opaque.value, transparent.value, 0f);
        assertNotEquals(0x000000FF, 0xFF0000FF);
    }

    @Test
    public void zeroAllocationOverloadMatchesTheObjectForm() {
        float[] out = new float[RgbHsv.COMPONENTS];
        float[] returned = RgbHsv.fromArgb(TestImages.SHIFTED_BLUE, out);
        RgbHsv object = RgbHsv.fromArgb(TestImages.SHIFTED_BLUE);
        assertEquals(object.hue, returned[0], 0f);
        assertEquals(object.saturation, returned[1], 0f);
        assertEquals(object.value, returned[2], 0f);
        assertEquals(3, out.length);
    }

    @Test
    public void zeroAllocationOverloadRejectsBadBuffers() {
        assertRejected(() -> RgbHsv.fromArgb(TestImages.PURE_BLUE, null));
        assertRejected(() -> RgbHsv.fromArgb(TestImages.PURE_BLUE, new float[2]));
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
