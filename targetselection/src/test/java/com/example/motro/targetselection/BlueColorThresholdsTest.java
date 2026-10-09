package com.example.motro.targetselection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.example.motro.targetselection.vision.BlueColorThresholds;
import com.example.motro.targetselection.vision.RgbHsv;

import org.junit.Test;

/** 「什么算蓝色」的规则契约：默认区间、容错范围、色调环绕、非法值拦截。 */
public class BlueColorThresholdsTest {

    private final BlueColorThresholds defaults = BlueColorThresholds.defaults();

    @Test
    public void defaultsCoverTheCommonPushSideBlues() {
        assertEquals(BlueColorThresholds.DEFAULT_HUE_MIN_DEGREES, defaults.hueMinDegrees, 0f);
        assertEquals(BlueColorThresholds.DEFAULT_HUE_MAX_DEGREES, defaults.hueMaxDegrees, 0f);
        assertFalse(defaults.wrapsAroundHue());

        // 纯蓝 240° / 推流常用的 #0066FF(217°) / #1E90FF(210°) / 压缩偏冷的 250~265°
        assertTrue(defaults.matches(240f, 1f, 1f));
        assertTrue(defaults.matches(217f, 1f, 1f));
        assertTrue(defaults.matches(210f, 1f, 1f));
        assertTrue(defaults.matches(265f, 0.5f, 0.5f));
    }

    @Test
    public void cyanAndOtherHuesAreRejected() {
        // 青 180° 是天空/水面的常见色，必须排除 —— 区间定宽是本案最典型的误检来源
        assertFalse(defaults.matches(180f, 1f, 1f));
        assertFalse(defaults.matches(0f, 1f, 1f));
        assertFalse(defaults.matches(120f, 1f, 1f));
        assertFalse(defaults.matches(300f, 1f, 1f));
        assertFalse(defaults.matches(199f, 1f, 1f));
        assertFalse(defaults.matches(271f, 1f, 1f));
    }

    @Test
    public void lowSaturationOrValueIsRejectedEvenInsideTheHueRange() {
        assertFalse("近乎灰的像素不该算蓝", defaults.matches(240f, 0.29f, 1f));
        assertFalse("太暗的像素不该算蓝", defaults.matches(240f, 1f, 0.19f));
        assertTrue(defaults.matches(240f, 0.30f, 0.20f));
    }

    @Test
    public void compressionShiftedBlueStillMatches() {
        RgbHsv shifted = RgbHsv.fromArgb(TestImages.SHIFTED_BLUE);
        assertTrue(shifted.toString(), defaults.matches(shifted.hue, shifted.saturation, shifted.value));

        RgbHsv light = RgbHsv.fromArgb(TestImages.SHIFTED_BLUE_LIGHT);
        assertTrue(light.toString(), defaults.matches(light.hue, light.saturation, light.value));
    }

    @Test
    public void washedOutBlueIsRejectedBySaturation() {
        RgbHsv washed = RgbHsv.fromArgb(TestImages.WASHED_BLUE);
        assertFalse("色调对但饱和度过低 —— 必须被滤掉",
                defaults.matches(washed.hue, washed.saturation, washed.value));
    }

    @Test
    public void hueRangeCanWrapAroundZero() {
        BlueColorThresholds red = BlueColorThresholds.of(350f, 10f, 0f, 0f);
        assertTrue(red.wrapsAroundHue());
        assertTrue(red.matches(0f, 1f, 1f));
        assertTrue(red.matches(355f, 1f, 1f));
        assertTrue(red.matches(10f, 1f, 1f));
        assertFalse(red.matches(180f, 1f, 1f));
        assertFalse(red.matches(11f, 1f, 1f));
    }

    @Test
    public void descriptionIsStableAndNonEmpty() {
        String described = defaults.describe();
        assertNotNull(described);
        assertTrue(described.contains("hue="));
        assertTrue(described.contains("minSat="));
    }

    @Test
    public void invalidThresholdsAreRejectedAtConstruction() {
        assertRejected(() -> BlueColorThresholds.of(-1f, 270f, 0.3f, 0.2f));
        assertRejected(() -> BlueColorThresholds.of(200f, 360f, 0.3f, 0.2f));
        assertRejected(() -> BlueColorThresholds.of(Float.NaN, 270f, 0.3f, 0.2f));
        assertRejected(() -> BlueColorThresholds.of(200f, 270f, -0.1f, 0.2f));
        assertRejected(() -> BlueColorThresholds.of(200f, 270f, 0.3f, 1.1f));
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
