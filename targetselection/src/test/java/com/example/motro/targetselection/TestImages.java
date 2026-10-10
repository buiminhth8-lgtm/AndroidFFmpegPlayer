package com.example.motro.targetselection;

import java.util.Arrays;

/**
 * 单测用的像素图案工具。<b>全部是纯算术</b>，不依赖 Android，也不依赖任何图片解码库 ——
 * 于是「蓝框 / 蓝文字 / 压缩偏蓝 / 无目标的背景」这些场景可以在 JVM 里被精确构造，
 * 断言也就能落到具体像素而不是"看起来差不多"。
 */
final class TestImages {

    /** 推流端画框常用的纯蓝。 */
    static final int PURE_BLUE = rgb(0, 0, 255);

    /** HEVC 量化后常见的偏冷蓝（色调仍 ≈236°，饱和度/明度略降）。 */
    static final int SHIFTED_BLUE = rgb(60, 70, 200);

    /** 另一种压缩偏移：更亮、更淡的蓝（≈222°）。 */
    static final int SHIFTED_BLUE_LIGHT = rgb(80, 110, 180);

    /** 偏灰的"伪蓝"：色调对，但饱和度过低 —— 必须被 minSaturation 滤掉。 */
    static final int WASHED_BLUE = rgb(140, 150, 190);

    /** 常见背景色。 */
    static final int GRAY_BACKGROUND = rgb(120, 118, 112);
    static final int CYAN_BACKGROUND = rgb(0, 200, 230);
    static final int GREEN_BACKGROUND = rgb(60, 140, 70);

    static int rgb(int r, int g, int b) {
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    static int[] solid(int width, int height, int argb) {
        final int[] pixels = new int[width * height];
        Arrays.fill(pixels, argb);
        return pixels;
    }

    /** 实心矩形，越界自动裁剪（返回实际写入的像素数）。 */
    static int fillRect(int[] pixels, int imageWidth, int imageHeight,
                        int x, int y, int width, int height, int argb) {
        int written = 0;
        for (int row = y; row < y + height; row++) {
            if (row < 0 || row >= imageHeight) {
                continue;
            }
            for (int column = x; column < x + width; column++) {
                if (column < 0 || column >= imageWidth) {
                    continue;
                }
                pixels[row * imageWidth + column] = argb;
                written++;
            }
        }
        return written;
    }

    /** 空心矩形（目标框线）：{@code thickness} 像素宽，用于模拟推流端画的蓝框。 */
    static int strokeRect(int[] pixels, int imageWidth, int imageHeight,
                          int x, int y, int width, int height, int thickness, int argb) {
        int written = 0;
        written += fillRect(pixels, imageWidth, imageHeight, x, y, width, thickness, argb);
        written += fillRect(pixels, imageWidth, imageHeight,
                x, y + height - thickness, width, thickness, argb);
        written += fillRect(pixels, imageWidth, imageHeight, x, y, thickness, height, argb);
        written += fillRect(pixels, imageWidth, imageHeight,
                x + width - thickness, y, thickness, height, argb);
        return written;
    }

    /**
     * 模拟蓝色 {@code id:xxxx} 文字：{@code count} 个「空心小方块」并排。
     *
     * <p>为什么用空心方块而不是真字形：本阶段要验证的是「细笔画在不同阈值/去噪下
     * 能否保留」，而空心方块正好是<b>细笔画 + 内部镂空 + 笔画间留白</b>的组合，
     * 对去噪策略的考验比实心块更严苛；同时它不需要字体文件，测试完全可复现。</p>
     */
    static int drawLabelGlyphs(int[] pixels, int imageWidth, int imageHeight,
                               int x, int y, int glyphWidth, int glyphHeight,
                               int count, int argb) {
        return drawLabelGlyphs(pixels, imageWidth, imageHeight,
                x, y, glyphWidth, glyphHeight, count, 2, argb);
    }

    /**
     * 与 {@link #drawLabelGlyphs} 相同，但<b>字形间距可指定</b>。
     *
     * <p>需要它是因为真实推流端的字形间距比"紧排"大得多：实测 1080p 下
     * {@code id:1850} 的 {@code d} 与 {@code :} 之间空了约 10px，而两个相邻目标的编号
     * 之间也是约 10px —— 用默认的 2px 间距根本复现不出这个（本 Slice 最关键的）
     * 歧义场景。</p>
     */
    static int drawLabelGlyphs(int[] pixels, int imageWidth, int imageHeight,
                               int x, int y, int glyphWidth, int glyphHeight,
                               int count, int gap, int argb) {
        int written = 0;
        int cursor = x;
        for (int i = 0; i < count; i++) {
            written += strokeRect(pixels, imageWidth, imageHeight,
                    cursor, y, glyphWidth, glyphHeight, 1, argb);
            cursor += glyphWidth + gap;
        }
        return written;
    }

    private TestImages() {
    }
}
