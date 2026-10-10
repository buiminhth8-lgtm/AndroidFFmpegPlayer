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

    // ------------------------------------------------------------------ 真实字体字形（Slice 8 第二版）

    /**
     * 用<b>真实字体（Arial）点阵</b>画一段数字串。
     *
     * <p><b>为什么要从七段换成真实字体</b>：七段数字在合成样本上能过，但真实推流帧
     * 用的是 Arial 这类常规字体，形状与七段毫无共同点 —— 拿七段模板去认真实字形，
     * 不是"相似度低一点"而是"根本不是同一个形状空间"，实测真实帧 {@code id:1850}
     * 被读成 {@code 611}。识别实现的模板已换成真实字体点阵，单测的合成图也必须
     * 换成同一套点阵，否则测的还是七段的形状，等于没测到真实能力。</p>
     *
     * <p>点阵与识别实现里的 {@code GLYPH_PATTERNS} 同源（都来自
     * {@code tools/roi_blue_mask/extract_glyph_templates.py} 的 ffmpeg 渲染），
     * 但这里是<b>测试侧独立复制</b>的一份：如果两边共用同一个数组，一旦识别实现的
     * 模板写错，测试也会跟着错、测不出问题。各写一份，"两份点阵能对上"本身就成了一条断言。</p>
     *
     * <p>把 12 行的点阵按最近邻放大到 {@code digitHeight}，每个字符按自己的宽高比
     * 换算宽度，字间留 {@code gap} 像素。</p>
     *
     * @param text        要画的数字串（只允许 {@code '0'}~{@code '9'}, {@code 'i'},
     *                    {@code 'd'}, {@code ':'}）
     * @param digitHeight 字符高度（像素，&ge; 12）
     * @param gap         字符之间的间隙（像素）
     * @return 实际写入的像素数
     */
    static int drawArialLikeText(int[] pixels, int imageWidth, int imageHeight,
                                 int x, int y, String text,
                                 int digitHeight, int gap, int argb) {
        final int templateHeight = ARIAL_GLYPH_HEIGHT;   // 每个点阵都是 12 行
        int written = 0;
        int cursor = x;
        for (int i = 0; i < text.length(); i++) {
            final char ch = text.charAt(i);
            final String[] pattern = arialPattern(ch);
            final int patternWidth = pattern[0].length();
            // 宽度按宽高比换算：高度占满 digitHeight，宽度等比跟进
            final int glyphWidth = Math.max(1, Math.round(digitHeight * (float) patternWidth / templateHeight));
            for (int row = 0; row < digitHeight; row++) {
                // 最近邻：目标行映射回模板行
                final int sourceRow = Math.min(templateHeight - 1, row * templateHeight / digitHeight);
                final String line = pattern[sourceRow];
                for (int column = 0; column < glyphWidth; column++) {
                    final int sourceColumn = Math.min(patternWidth - 1, column * patternWidth / glyphWidth);
                    if (line.charAt(sourceColumn) != '#') {
                        continue;
                    }
                    final int px = cursor + column;
                    final int py = y + row;
                    if (px < 0 || px >= imageWidth || py < 0 || py >= imageHeight) {
                        continue;
                    }
                    pixels[py * imageWidth + px] = argb;
                    written++;
                }
            }
            cursor += glyphWidth + gap;
        }
        return written;
    }

    /** 查一个字符的 Arial 点阵；未知字符返回空。 */
    private static String[] arialPattern(char ch) {
        for (int i = 0; i < ARIAL_GLYPH_CHARS.length; i++) {
            if (ARIAL_GLYPH_CHARS[i] == ch) {
                return ARIAL_GLYPH_PATTERNS[i];
            }
        }
        throw new IllegalArgumentException("no Arial glyph for '" + ch + "'");
    }

    /** 点阵行数（与识别实现的 TEMPLATE_HEIGHT 一致）。 */
    static final int ARIAL_GLYPH_HEIGHT = 12;

    /**
     * Arial 字形点阵（{'#' = 墨水），与识别实现的 {@code GLYPH_PATTERNS} 同源但独立复制。
     * 每行长度不同（＝该字符按宽高比占的列数）。
     */
    private static final char[] ARIAL_GLYPH_CHARS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'i', 'd', ':',
    };

    private static final String[][] ARIAL_GLYPH_PATTERNS = {
            {   // 0
                    "..####..",
                    ".##..##.",
                    "##....##",
                    "##.....#",
                    "#......#",
                    "#......#",
                    "#......#",
                    "#......#",
                    "##.....#",
                    "##....##",
                    ".##..##.",
                    "..####..",
            },
            {   // 1
                    "...#",
                    "..##",
                    ".###",
                    "##.#",
                    "...#",
                    "...#",
                    "...#",
                    "...#",
                    "...#",
                    "...#",
                    "...#",
                    "...#",
            },
            {   // 2
                    "..####..",
                    ".##...##",
                    ".#....##",
                    ".#.....#",
                    "......##",
                    ".....##.",
                    "....###.",
                    "...###..",
                    "..###...",
                    ".##.....",
                    ".##.....",
                    "########",
            },
            {   // 3
                    "..####..",
                    ".##..##.",
                    "##....##",
                    "......##",
                    ".....##.",
                    "...####.",
                    "...#####",
                    "......##",
                    ".......#",
                    "##....##",
                    ".##..###",
                    "..####..",
            },
            {   // 4
                    ".....##.",
                    "....###.",
                    "...####.",
                    "...#.##.",
                    "..#..##.",
                    ".##..##.",
                    ".#...##.",
                    "##...##.",
                    "########",
                    ".....##.",
                    ".....##.",
                    ".....##.",
            },
            {   // 5
                    ".######.",
                    ".##.....",
                    ".#......",
                    ".#......",
                    ".######.",
                    "##....#.",
                    "......##",
                    "......##",
                    "......##",
                    "##....##",
                    ".##..##.",
                    "..####..",
            },
            {   // 6
                    "..#####.",
                    ".##..###",
                    "##....##",
                    "##......",
                    "#.#####.",
                    "###..###",
                    "##....##",
                    "##.....#",
                    "##.....#",
                    "##....##",
                    ".##..##.",
                    "..####..",
            },
            {   // 7
                    "########",
                    "......#.",
                    ".....##.",
                    "....##..",
                    "....##..",
                    "...##...",
                    "...##...",
                    "...#....",
                    "..##....",
                    "..##....",
                    "..#.....",
                    "..#.....",
            },
            {   // 8
                    "..####..",
                    ".##..##.",
                    "##....#.",
                    ".#....#.",
                    ".##..##.",
                    ".#####..",
                    "##...##.",
                    "##....##",
                    "#.....##",
                    "##....##",
                    ".##..##.",
                    "..####..",
            },
            {   // 9
                    "..####..",
                    ".##..##.",
                    "##....#.",
                    "#.....##",
                    "#.....##",
                    "##....##",
                    ".#######",
                    "..###.##",
                    "......##",
                    "##....#.",
                    ".#...##.",
                    "..####..",
            },
            {   // i
                    "#",
                    "#",
                    ".",
                    "#",
                    "#",
                    "#",
                    "#",
                    "#",
                    "#",
                    "#",
                    "#",
                    "#",
            },
            {   // d
                    "......#",
                    "......#",
                    "......#",
                    "..###.#",
                    ".#...##",
                    "##...##",
                    "#.....#",
                    "#.....#",
                    "#.....#",
                    "##...##",
                    ".##..##",
                    "..#####",
            },
            {   // :
                    "##",
                    "##",
                    "##",
                    "..",
                    "..",
                    "..",
                    "..",
                    "..",
                    "..",
                    "##",
                    "##",
                    "##",
            },
    };

    /** 段的位定义（与识别实现同一套命名，两处必须一致）。 */
    static final int SEG_A = 1;
    static final int SEG_B = 1 << 1;
    static final int SEG_C = 1 << 2;
    static final int SEG_D = 1 << 3;
    static final int SEG_E = 1 << 4;
    static final int SEG_F = 1 << 5;
    static final int SEG_G = 1 << 6;

    /** 0~9 各点亮哪些段。 */
    private static final int[][] SEGMENTS_BY_DIGIT = {
            {SEG_A, SEG_B, SEG_C, SEG_D, SEG_E, SEG_F},          // 0
            {SEG_B, SEG_C},                                      // 1
            {SEG_A, SEG_B, SEG_G, SEG_E, SEG_D},                 // 2
            {SEG_A, SEG_B, SEG_G, SEG_C, SEG_D},                 // 3
            {SEG_F, SEG_G, SEG_B, SEG_C},                        // 4
            {SEG_A, SEG_F, SEG_G, SEG_C, SEG_D},                 // 5
            {SEG_A, SEG_F, SEG_G, SEG_E, SEG_C, SEG_D},          // 6
            {SEG_A, SEG_B, SEG_C},                               // 7
            {SEG_A, SEG_B, SEG_C, SEG_D, SEG_E, SEG_F, SEG_G},   // 8
            {SEG_A, SEG_B, SEG_C, SEG_D, SEG_F, SEG_G},          // 9
    };

    private TestImages() {
    }
}
