package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.api.TargetSelectionConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@link TargetIdRecognizer} 的<b>默认实现</b>：纯 Java 的「局部小图 + 轻量字形匹配」，
 * 播放器无关、零第三方依赖（不引入 OCR SDK / OpenCV / TFLite）。
 *
 * <h2>为什么不引入 OCR SDK（本 Slice 的硬约束）</h2>
 * 需求明确要求"优先局部小图 + 轻量识别"，且模块边界测试
 * （{@code ModuleBoundaryTest}）把 ML Kit / OpenCV / TFLite 全部列为禁止依赖。
 * 更重要的是：本模块的标签是<b>推流端用固定字体画的 Overlay</b>，
 * 字号、颜色、间距在一次推流里是恒定的 —— 这正是模板匹配最擅长的场景，
 * 引入通用 OCR 反而要付模型体积、初始化时间与"识别出画面里任何文字"的语义风险。
 *
 * <h2>为什么模板是「真实字体点阵」而不是七段数码管</h2>
 * 本实现的第一版用的是七段数码管模板（0~9 各由 7 个段位拼出）。
 * 它在自己渲染的合成样本上表现良好，但<b>在真实推流帧上完全读不出编号</b>：
 * 真实标签用的是 {@code Arial} 这类常规字体 —— 斜体感、细笔画、闭合的
 * {@code 8} / {@code 9} 碗形，与七段数码管的"断开的横竖段"在形态上毫无共同点。
 * 用它去匹配真实字形，就像拿算盘珠子去认印刷体数字：不是相似度低一点，
 * 而是<b>根本不是同一个形状空间</b>。
 *
 * <p>因此第二版把模板换成<b>真实字体（Arial）的点阵</b>：用 ffmpeg 的
 * {@code drawtext} 以 48px 渲染每个字符，裁到墨水外接盒，再按面积覆盖降采样成
 * 固定高度的网格（见 {@code tools/roi_blue_mask/extract_glyph_templates.py}，
 * 输出的点阵直接内嵌在 {@link #GLYPH_PATTERNS} 里）。这样模板与真实标签
 * 落在同一个字形空间里，匹配才有意义。</p>
 *
 * <h2>链路（五步，全部只作用于那一小块标签 ROI）</h2>
 * <ol>
 *   <li><b>校验</b>：{@link LabelExtractionResult} 必须带着 label 像素与一致的宽高；
 *       不满足直接 {@link TargetIdRecognitionStatus#INVALID_LABEL}。</li>
 *   <li><b>预处理</b>（{@link #preprocess}）：
 *       <ul>
 *         <li>按 {@code labelUpscaleFactor} 最近邻放大（2~4 倍）。放大是必需的：
 *             真实标签只有十几个像素高，笔画宽度常常只有 1px，不放大则任何
 *             形态判据都被"一个像素的抖动"淹没。</li>
 *         <li>蓝色文字提取 + 抓取前 N% 最亮的蓝像素做二值化（见 {@link #binarizeByTopQuantile}）。
 *             为什么按<b>分位数</b>而不是固定阈值：HEVC 会把笔画边缘糊开，
 *             固定阈值要么把抗锯齿边当笔画（字变胖、相邻字形连在一起），
 *             要么把细笔画整条吃掉。取"最亮的那一档"能自适应压缩强度。</li>
 *       </ul></li>
 *   <li><b>切字符</b>（{@link #segment}）：在二值图上按<b>垂直空白列</b>切出字符块，
 *       并对粘连的宽块按字距均分。用垂直投影而不是连通域：一行编号的字符在水平方向
 *       上是分开的，投影切分不需要访问标记数组、也不会因为"某个笔画断开"而多切一块。</li>
 *   <li><b>逐字符匹配</b>（{@link #matchCharacter}）：把字符块<b>保形</b>归一化到
 *       模板网格，与每个内置字形模板算 IoU，取相似度最高者。</li>
 *   <li><b>解析</b>：把匹配出的字符拼成 {@code rawText}，交给
 *       {@link TargetIdParser} 取号；再按 {@code minIdConfidence} 判一次，
 *       得到 {@link TargetIdRecognitionStatus#SUCCESS} / {@code LOW_CONFIDENCE} / {@code NO_ID}。</li>
 * </ol>
 *
 * <h2>置信度语义（不许伪造）</h2>
 * 本实现给出的置信度是<b>规则量</b>，不是概率：
 * <pre>
 *   confidence = 平均字符相似度 × 位置惩罚
 *   位置惩罚 = 0.5 + 0.5 × (有效字符数 / 切出的字符块数)
 * </pre>
 * 即"字形匹配得像不像"（前半）乘上"切出来的块有多少被认成了已知字符"（后半）。
 * 它在同一字号下可比，跨字号比较无意义 —— 这是模板匹配的固有性质，
 * 与其假装它是概率，不如把语义写清楚。详见
 * {@link TargetIdRecognitionResult#confidence}。</p>
 *
 * <h2>性能</h2>
 * 只处理「标签宽 × 标签高 × upscale²」个像素（真实标签 68×12、放大 3 倍 → 约 7k 像素），
 * 不扫描整帧、不重跑检测、不分配大数组。单次应在<b>个位数到几十毫秒</b>量级，
 * 分步耗时由 {@link TargetIdRecognitionResult#preprocessMs}/
 * {@link #recognizeMs}/{@code parseMs} 给出。</p>
 *
 * <p>线程约定：本类<b>无状态</b>（配置不可变，模板是类级常量，方法内不共享任何缓冲），
 * 可跨线程并发调用；但单次调用应完整跑在一个工作线程上。</p>
 */
public final class GlyphTemplateTargetIdRecognizer implements TargetIdRecognizer {

    /** 实现名，写进结果便于日志区分。改名反映模板已从七段换成真实字体点阵。 */
    public static final String NAME = "glyph-template-v2";

    /**
     * 二值化取"最亮的前多少比例"的蓝像素。
     *
     * <p>取 0.55：真实标签里笔画占多数、抗锯齿边占少数，所以中位数偏上一点
     * 正好把笔画留下、把糊开的边缘切掉。取值靠实测标定（Slice 8 的合成样本 +
     * 真实帧 ROI），<b>不是</b>配置项 —— 它是"压缩糊边"这个物理现象的经验值，
     * 不随推流端调参而变；随画面变的量（放大倍数、颜色范围）才进配置。</p>
     */
    private static final float BINARIZE_TOP_QUANTILE = 0.55f;

    /**
     * 一个字符块至少要有这么多像素才参与匹配。低于它的多半是压缩残留的孤立噪点，
     * 认出来也是噪声字符。
     */
    private static final int MIN_GLYPH_PIXELS = 3;

    /**
     * 字符高度下限（放大后像素）。低于它的块直接丢弃 ——
     * 真实数字在放大 3 倍后至少二十几像素高，只有几个像素高的块不可能是数字。
     */
    private static final int MIN_GLYPH_HEIGHT = 6;

    /**
     * 粘连字符的"字距 / 字高"比例：一个字符的水平推进量约等于字高的这个倍数。
     *
     * <p>用来把粘连成一整块的数字串均分成单个字符。真实标签的字符在水平方向上有
     * 重叠（斜体字形在垂直投影上咬合），只按空列切会把 {@code 1963} 切成一块。</p>
     *
     * <p>取值 0.65 由真实字体标定：Arial 数字的 advance 约 {@code 0.556em}，
     * 而数字的 cap height 约 {@code 0.716em} —— 于是 {@code advance / capHeight ≈ 0.78}。
     * 但子块之间还会因为两侧不留侧边距而略紧，实测 {@code id:1963}（19px 高、109px 宽、
     * 7 个字形）推进量约 {@code 0.65 × 高度}。0.65 比 0.78 略小是刻意的：
     * 宁可把一块多切成几个、交给匹配层用 {@code '?'} 兜住，也不要少切导致两个数字
     * 糊成一个（那会直接认错号）。</p>
     */
    private static final float GLYPH_PITCH_OF_HEIGHT = 0.65f;

    /**
     * 列间隙容忍度：两段墨之间只要隔了不超过这么多空列，就认为它们属于<b>同一个</b>
     * 字符（例如 {@code i} 的点与竖、{@code 8} 被压缩糊断的笔画）。
     *
     * <p>它是"放大后像素"的绝对值，取 2 —— 相当于原始标签里的 1px（放大 2~3 倍）。
     * 再大就会把真正相邻的两个数字并成一块。</p>
     */
    private static final int COLUMN_GAP_TOLERANCE = 2;

    /**
     * 内置字形模板的网格<b>高度</b>（行）。高度取 12：真实标签的字符高约 26~35 像素，
     * 降到 12 行后仍能区分 {@code 3}/{@code 8}/{@code 9} 这类"碗口开合"的差别，
     * 同时把每个模板压到几十比特，匹配代价极低。
     */
    private static final int TEMPLATE_HEIGHT = 12;

    /**
     * 内置字形模板的网格<b>宽度上限</b>（列）。最宽的数字（{@code 4}/{@code 5}/{@code 8}/{@code 9}）
     * 在 12 行网格下约占 8 列，这里留到 12 列以容纳更宽的字符，实际列数由宽高比决定
     * （见 {@link #gridColumns}）。网格用"每行固定 {@code TEMPLATE_WIDTH} 列"的定长数组存，
     * 未使用的列留空 —— 这样 IoU 可以直接按同长数组算，不必先对齐宽度。
     */
    private static final int TEMPLATE_WIDTH = 12;

    /**
     * 内置字形模板：字符 → 点阵（{@code '#'} = 墨水）。
     *
     * <p>点阵来自 {@code Arial} 的真实渲染（{@code tools/roi_blue_mask/extract_glyph_templates.py}）：
     * 每行长度不同（＝该字符按宽高比占的列数），不足 {@link #TEMPLATE_WIDTH} 的列由
     * {@link #materialize} 补齐。每个字符的<b>行数恒为 {@link #TEMPLATE_HEIGHT}</b>。</p>
     *
     * <p>为什么内嵌成字符串字面量而不是运行时从字体读：本模块必须纯 JVM、零依赖、
     * 可在没有字体文件的环境（CI / 单测）里跑。字面量是最直接、最可审计的形式 ——
     * 想换字体，改这一张表即可。</p>
     */
    private static final String[][] GLYPH_PATTERNS = {
            // ---- 数字 0~9
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
            // ---- 前缀字形：i / d / :
            // 它们不需要被"读懂"，只需要被识别出来，这样 rawText 里才会出现 id: 前缀
            // （前缀匹配成功意味着解析不必走"唯一数字串"回退，可信度更高）。
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

    /** 与 {@link #GLYPH_PATTERNS} 一一对应的字符（下标即字符编码来源）。 */
    private static final char[] GLYPH_CHARS = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'i', 'd', ':',
    };

    private final TargetIdParser parser;

    /**
     * @param config 默认策略，非 null；读取 {@code labelUpscaleFactor} /
     *               {@code idMinDigits} / {@code idMaxDigits} / {@code minIdConfidence}
     *               以及蓝色阈值（复用同一套「什么算蓝」的规则，不新建第二套）
     * @throws IllegalArgumentException config 为 null
     */
    public GlyphTemplateTargetIdRecognizer(TargetSelectionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.parser = new TargetIdParser(config);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public TargetIdRecognitionResult recognize(LabelExtractionResult label, TargetSelectionConfig config) {
        if (label == null) {
            throw new IllegalArgumentException("label must not be null");
        }
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        final long startNs = System.nanoTime();

        // ---- 0) 标签可用性
        if (!label.hasLabel()) {
            return TargetIdRecognitionResult.invalidLabel(
                    "label extraction status is " + label.status, elapsedMs(startNs), NAME);
        }
        if (label.labelArgb == null || label.labelWidth <= 0 || label.labelHeight <= 0
                || label.labelArgb.length != label.labelWidth * label.labelHeight) {
            return TargetIdRecognitionResult.invalidLabel(
                    "label pixels are missing or inconsistent: "
                            + (label.labelArgb == null ? "null" : label.labelArgb.length)
                            + " vs " + label.labelWidth + "x" + label.labelHeight,
                    elapsedMs(startNs), NAME);
        }
        // 位数区间在这里显式读一次：它决定 rawText 会不会成为一个可用的编号，
        // 解析器虽然也会用它，但识别层必须先知道自己面对的是几位编号 ——
        // 这让"识别参数只有这一处读"这条约束在代码里可见（见 ModuleBoundaryTest）。
        final int idDigitsMin = config.idMinDigits;
        final int idDigitsMax = config.idMaxDigits;

        // ---- 1) 预处理
        final long preprocessStartNs = System.nanoTime();
        final boolean[] bitmap = preprocess(label, config);
        final long preprocessMs = elapsedMs(preprocessStartNs);
        if (bitmap == null) {
            return TargetIdRecognitionResult.noId(null, null, preprocessMs, 0L, 0L,
                    elapsedMs(startNs), "no blue text pixels survived preprocessing", NAME);
        }
        final int bitmapWidth = label.labelWidth * Math.max(1, config.labelUpscaleFactor);
        final int bitmapHeight = label.labelHeight * Math.max(1, config.labelUpscaleFactor);

        // ---- 2) 切字符 + 3) 逐字符匹配
        final long recognizeStartNs = System.nanoTime();
        final List<Glyph> glyphs = segment(bitmap, bitmapWidth, bitmapHeight);
        if (glyphs.isEmpty()) {
            final long recognizeMs = elapsedMs(recognizeStartNs);
            return TargetIdRecognitionResult.noId("", null, preprocessMs, recognizeMs, 0L,
                    elapsedMs(startNs), "no character-like columns found", NAME);
        }
        final StringBuilder raw = new StringBuilder(glyphs.size());
        float similaritySum = 0f;
        int matched = 0;
        for (int i = 0; i < glyphs.size(); i++) {
            final Match match = matchCharacter(bitmap, bitmapWidth, bitmapHeight, glyphs.get(i));
            raw.append(match.character);
            if (match.character != '?') {
                similaritySum += match.similarity;
                matched++;
            }
        }
        final long recognizeMs = elapsedMs(recognizeStartNs);
        final String rawText = raw.toString();
        final float averageSimilarity = matched == 0 ? 0f : similaritySum / matched;
        final float matchRatio = matched / (float) glyphs.size();
        final float confidence = averageSimilarity * (0.5f + 0.5f * matchRatio);

        // ---- 4) 解析
        final long parseStartNs = System.nanoTime();
        final TargetIdParser.ParsedId parsed = parser.parse(rawText);
        final long parseMs = elapsedMs(parseStartNs);

        if (parsed.digits == null) {
            return TargetIdRecognitionResult.noId(rawText, confidence, preprocessMs, recognizeMs,
                    parseMs, elapsedMs(startNs),
                    "raw text has no usable id in [" + idDigitsMin + "," + idDigitsMax + "] digits"
                            + " (matched=" + matched + "/" + glyphs.size() + ")", NAME);
        }
        if (confidence < config.minIdConfidence) {
            return TargetIdRecognitionResult.lowConfidence(rawText, confidence,
                    preprocessMs, recognizeMs, parseMs, elapsedMs(startNs),
                    "confidence " + confidence + " < minIdConfidence " + config.minIdConfidence, NAME);
        }
        return TargetIdRecognitionResult.success(parsed.digits, rawText, confidence,
                preprocessMs, recognizeMs, parseMs, elapsedMs(startNs), NAME);
    }

    // ------------------------------------------------------------------ 预处理

    /**
     * 放大 + 蓝色文字提取 + 二值化，返回 {@code null} 表示没有一个像素通过颜色筛选。
     *
     * <p><b>不破坏原始 label</b>：输出是一块全新分配的 {@code boolean[]}，
     * 全程只读 {@code label.labelArgb}。</p>
     *
     * @return 行优先的二值图（{@code true} = 文字笔画），尺寸
     *         {@code (w*scale) × (h*scale)}；无蓝像素时为 null
     */
    private static boolean[] preprocess(LabelExtractionResult label, TargetSelectionConfig config) {
        final int scale = Math.max(1, config.labelUpscaleFactor);
        final int sourceWidth = label.labelWidth;
        final int sourceHeight = label.labelHeight;
        final int width = sourceWidth * scale;
        final int height = sourceHeight * scale;

        final BlueColorThresholds thresholds = BlueColorThresholds.of(
                config.blueHueMinDegrees, config.blueHueMaxDegrees,
                config.minSaturation, config.minValue);

        // 逐像素：判定蓝色 + 记下明度，用来做"取最亮的一档"的自适应二值化
        final float[] hsv = new float[RgbHsv.COMPONENTS];
        final float[] luminance = new float[width * height];
        final boolean[] blue = new boolean[width * height];
        boolean anyBlue = false;
        for (int y = 0; y < sourceHeight; y++) {
            for (int x = 0; x < sourceWidth; x++) {
                final int argb = label.labelArgb[y * sourceWidth + x];
                RgbHsv.fromArgb(argb, hsv);
                if (!thresholds.matches(hsv[0], hsv[1], hsv[2])) {
                    continue;
                }
                anyBlue = true;
                // 最近邻放大：把同一个源像素填满目标块
                final int baseX = x * scale;
                final int baseY = y * scale;
                for (int dy = 0; dy < scale; dy++) {
                    final int rowOffset = (baseY + dy) * width + baseX;
                    for (int dx = 0; dx < scale; dx++) {
                        blue[rowOffset + dx] = true;
                        luminance[rowOffset + dx] = hsv[2];
                    }
                }
            }
        }
        if (!anyBlue) {
            return null;
        }
        final boolean[] binarized =
                binarizeByTopQuantile(blue, luminance, width * height, BINARIZE_TOP_QUANTILE);
        stripFrameEdges(binarized, width, height, scale);
        return binarized;
    }

    /**
     * 抹掉"目标框的边线" —— 只抹<b>贴着 ROI 边缘、且又细又孤立</b>的那些整行/整列。
     *
     * <h3>为什么必须做</h3>
     * Slice 7 取标签时只剔掉了"横向长段"（{@code LINE_RUN_FRACTION_OF_BOX_WIDTH}），
     * 但真实 ROI 里仍会残留两类框线：
     * <ul>
     *   <li><b>框顶横线</b>：常常正好落在 ROI 的<b>最后一行</b>，整行 100% 被点亮
     *       （实测 {@code id:1963} 的 ROI 第 19 行是 {@code ####...####}）。</li>
     *   <li><b>框的左边线</b>：当标签从框的左边缘开始（左侧无余量）时，ROI 第一列会带进
     *       框的竖边，形成一列约 4px 宽、几乎满高的墨条。它不是字符，却会被当成一个字切出来
     *       （实测 {@code id:1963} 因此在 {@code id:1963} 前面多切出一个块，读成 9 个字符）。</li>
     * </ul>
     *
     * <h3>为什么不能"见满行/满列就抹"</h3>
     * Arial 的 {@code 7} 顶部横杠在标签里<b>就是</b>几乎满宽（实测覆盖 89%），
     * {@code 2} 的底横、{@code 4} 的中横、{@code 5} 的顶横同理。
     * 早先的实现"整行点亮比例 ≥ 0.8 就清空整行"，会把这些笔画一并抹掉 ——
     * 实测单个 {@code 7} 因此被削成一条斜线，与任何模板都匹配不上（相似度 0.20）。
     *
     * <h3>本实现的三条判据（同时满足才抹）</h3>
     * <ol>
     *   <li><b>够满</b>：整行（列）点亮比例 ≥ {@link #RULE_ROW_COVERAGE}；</li>
     *   <li><b>够细</b>：所在连通带的高度（宽度）≤ {@link #RULE_MAX_THICKNESS} 个<b>原始</b>像素
     *       —— 框线是 2~4px 的细线，而 {@code 7} 的横杠与斜竖连成一体、带子厚得多。
     *       注意阈值必须按原始像素算：二值图已经放大过 {@code scale} 倍，
     *       一条 4px 的框线在图上宽 12px，拿放大后的尺寸去比会把框线判成"太厚"而放过它。</li>
     *   <li><b>贴边</b>：这条带子触到 ROI 的上/下（左/右）边缘。
     *       框线在 ROI 边缘，字形横杠在文字内部。</li>
     * </ol>
     * 三条并集之下，{@code 7} 的横杠（虽然满、但厚且不贴边）被保住，
     * 框顶线与左边线（满、细、贴边）被抹掉。
     */
    private static void stripFrameEdges(boolean[] bitmap, int width, int height, int scale) {
        final int safeScale = Math.max(1, scale);
        final int maxThickness = Math.max(1, RULE_MAX_THICKNESS * safeScale);
        stripFullRowsAtEdges(bitmap, width, height, maxThickness);
        stripFullColumnsAtEdges(bitmap, width, height, maxThickness);
    }

    /** 抹掉"贴上下边、又细又满"的行带（框的上下横线）。 */
    private static void stripFullRowsAtEdges(boolean[] bitmap, int width, int height,
                                             int maxThickness) {
        final int full = Math.max(1, Math.round(width * RULE_ROW_COVERAGE));
        int y = 0;
        while (y < height) {
            final int rowOffset = y * width;
            int lit = 0;
            for (int x = 0; x < width; x++) {
                if (bitmap[rowOffset + x]) {
                    lit++;
                }
            }
            if (lit < full) {
                y++;
                continue;
            }
            // 从 y 起是"满行"带；扩张到不再满行，得到带高 [y, bandEnd]
            int bandEnd = y;
            while (bandEnd + 1 < height) {
                final int nextOffset = (bandEnd + 1) * width;
                int nextLit = 0;
                for (int x = 0; x < width; x++) {
                    if (bitmap[nextOffset + x]) {
                        nextLit++;
                    }
                }
                if (nextLit < full) {
                    break;
                }
                bandEnd++;
            }
            final int thickness = bandEnd - y + 1;
            final boolean touchesEdge = (y == 0) || (bandEnd == height - 1);
            if (thickness <= maxThickness && touchesEdge) {
                for (int row = y; row <= bandEnd; row++) {
                    final int offset = row * width;
                    for (int x = 0; x < width; x++) {
                        bitmap[offset + x] = false;
                    }
                }
            }
            y = bandEnd + 1;
        }
    }

    /** 抹掉"贴左右边、又细又满"的列带（框的左右竖线）。 */
    private static void stripFullColumnsAtEdges(boolean[] bitmap, int width, int height,
                                                int maxThickness) {
        final int full = Math.max(1, Math.round(height * RULE_ROW_COVERAGE));
        int x = 0;
        while (x < width) {
            int lit = 0;
            for (int y = 0; y < height; y++) {
                if (bitmap[y * width + x]) {
                    lit++;
                }
            }
            if (lit < full) {
                x++;
                continue;
            }
            int bandEnd = x;
            while (bandEnd + 1 < width) {
                int nextLit = 0;
                for (int y = 0; y < height; y++) {
                    if (bitmap[y * width + bandEnd + 1]) {
                        nextLit++;
                    }
                }
                if (nextLit < full) {
                    break;
                }
                bandEnd++;
            }
            final int thickness = bandEnd - x + 1;
            final boolean touchesEdge = (x == 0) || (bandEnd == width - 1);
            if (thickness <= maxThickness && touchesEdge) {
                for (int col = x; col <= bandEnd; col++) {
                    for (int y = 0; y < height; y++) {
                        bitmap[y * width + col] = false;
                    }
                }
            }
            x = bandEnd + 1;
        }
    }

    /**
     * "整行被点亮"的判据阈值：一行的点亮比例 ≥ 它就被当成框线候选。
     *
     * <p>取 0.8：数字最宽的一行也只占自身宽度的一部分，真正占满整行的是框线。</p>
     */
    private static final float RULE_ROW_COVERAGE = 0.8f;

    /**
     * 框线候选带的最大厚度（行数 / 列数）。超过它就不再当成框线。
     *
     * <p>取 4：真实框线放大后是 6~12px 厚（upscale 3 时）；而 Arial {@code 7} 的
     * 顶部横杠与斜竖连成一体，整条带子从顶到底都"有墨"，厚度远超 4，于是被保住。
     * 这条判据是"抹框线"与"保住字形横杠"之间的分水岭。</p>
     */
    private static final int RULE_MAX_THICKNESS = 4;

    /**
     * "竖线型候选"的宽度上限：宽 ≤ 最宽候选块 × 它，才可能被判为框竖边。
     *
     * <p>取 0.25：真实字体里最宽的字符（{@code 0/6/8/9}）与最窄的笔画块相差数倍，
     * 框竖边比最宽字符窄得多。0.25 是"明显更窄"这条线的保守取值。</p>
     */
    private static final float RULE_VERTICAL_MAX_WIDTH_FRACTION = 0.25f;

    /**
     * "竖线型候选"的高度下限：高 ≥ 文字带高度 × 它，才可能被判为框竖边。
     *
     * <p>取 0.8：框竖边几乎顶满整条文字带；而真正窄的字符 {@code i} / {@code :}
     * 高度只占文字带的一小部分（{@code i} 的竖笔避开顶部的点、{@code :} 只是两个点），
     * 于是不会被误伤。</p>
     */
    private static final float RULE_VERTICAL_MIN_HEIGHT_FRACTION = 0.8f;

    /**
     * 在蓝像素集合里取"最亮的前 {@code topQuantile} 比例"作为笔画。
     *
     * <p>用直方图（256 档）求分位阈值，避免排序整块像素（{@code n log n} ——
     * 标签虽小但放大后也有上万像素，这里只要 {@code n}）。</p>
     */
    private static boolean[] binarizeByTopQuantile(boolean[] blue, float[] luminance,
                                                   int pixelCount, float topQuantile) {
        final int[] histogram = new int[256];
        int blueCount = 0;
        for (int i = 0; i < pixelCount; i++) {
            if (blue[i]) {
                histogram[Math.min(255, Math.max(0, Math.round(luminance[i] * 255f)))]++;
                blueCount++;
            }
        }
        final int target = Math.max(1, Math.round(blueCount * topQuantile));
        int accumulated = 0;
        int thresholdBin = 0;
        for (int bin = 255; bin >= 0; bin--) {
            accumulated += histogram[bin];
            if (accumulated >= target) {
                thresholdBin = bin;
                break;
            }
        }
        final float threshold = thresholdBin / 255f;
        final boolean[] bitmap = new boolean[pixelCount];
        for (int i = 0; i < pixelCount; i++) {
            bitmap[i] = blue[i] && luminance[i] >= threshold;
        }
        return bitmap;
    }

    // ------------------------------------------------------------------ 字符切分

    /** 一个字符块的外接盒（放大后像素，闭区间）。 */
    private static final class Glyph {

        final int left;
        final int top;
        final int right;
        final int bottom;

        Glyph(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() {
            return right - left + 1;
        }

        int height() {
            return bottom - top + 1;
        }

        @Override
        public String toString() {
            return "[" + left + "," + top + ".." + right + "," + bottom
                    + " w=" + width() + " h=" + height() + "]";
        }
    }

    /**
     * 切字符块。走的是<b>列投影 + 连通域联合</b>：
     *
     * <ol>
     *   <li><b>列投影</b>：把垂直投影里"有墨"的列连成段，中间空列超过
     *       {@link #COLUMN_GAP_TOLERANCE} 就断开。容忍几列空白是为了不把
     *       「{@code i} 的点 + 竖」、「被压缩糊断的笔画」切成两块。</li>
     *   <li><b>连通域联合</b>：对每一段，用 8 连通标记找出该段里的独立墨团，
     *       再把"水平区间互相重叠"的墨团并成一个字符（例如 {@code i} 的点与竖、
     *       {@code :} 的上下两点、{@code =} 式断裂笔画）。</li>
     *   <li><b>宽块均分</b>：并完之后如果某个字符块仍然明显比"一个字的宽度"宽
     *       （真实字体的数字相互咬合、整串 {@code 1963} 可能连成一个连通域），
     *       按"字距 ≈ 字高 × {@link #GLYPH_PITCH_OF_HEIGHT}"均分。</li>
     * </ol>
     *
     * <p>然后丢掉太矮（{@link #MIN_GLYPH_HEIGHT}）、太小（{@link #MIN_GLYPH_PIXELS}）
     * 的块 —— 它们是压缩残留的噪点，不是字符。</p>
     */
    private static List<Glyph> segment(boolean[] bitmap, int width, int height) {
        final int[] columnCounts = new int[width];
        for (int x = 0; x < width; x++) {
            int count = 0;
            for (int y = 0; y < height; y++) {
                if (bitmap[y * width + x]) {
                    count++;
                }
            }
            columnCounts[x] = count;
        }

        final List<int[]> spans = new ArrayList<>(16);   // {left, right}
        int runStart = -1;
        int lastInk = -1;
        for (int x = 0; x < width; x++) {
            final boolean hasInk = columnCounts[x] > 0;
            if (hasInk) {
                if (runStart < 0) {
                    runStart = x;
                }
                lastInk = x;
            } else if (runStart >= 0 && x - lastInk > COLUMN_GAP_TOLERANCE) {
                spans.add(new int[]{runStart, lastInk});
                runStart = -1;
            }
        }
        if (runStart >= 0) {
            spans.add(new int[]{runStart, lastInk});
        }

        final List<Glyph> glyphs = new ArrayList<>(spans.size());
        for (int[] span : spans) {
            // 一段 = 一个候选字符（或几个粘连字符）。先在段内做连通域分析，
            // 把重叠的墨团并起来；并完再看是否需要按字距均分。
            final List<Glyph> components = connectedComponents(bitmap, width, height,
                    span[0], span[1]);
            final List<Glyph> merged = mergeOverlapping(bitmap, width, height, components);
            for (Glyph glyph : merged) {
                if (glyph.height() < MIN_GLYPH_HEIGHT) {
                    continue;   // 太矮：不可能是字符
                }
                final int pitch = Math.max(MIN_GLYPH_HEIGHT,
                        Math.round(glyph.height() * GLYPH_PITCH_OF_HEIGHT));
                final int estimatedCount = Math.max(1, Math.round(glyph.width() / (float) pitch));
                if (estimatedCount <= 1) {
                    glyphs.add(glyph);
                } else {
                    // 宽度明显超过一个字符：按字距均分（真实字体数字互相咬合时会走到这里）
                    glyphs.addAll(splitEvenly(bitmap, width, height, glyph, estimatedCount));
                }
            }
        }
        glyphs.removeIf(g -> countPixels(bitmap, width, g) < MIN_GLYPH_PIXELS);
        discardVerticalRules(glyphs);
        return glyphs;
    }

    /**
     * 丢掉"竖线型"候选 —— 目标框的左右<b>竖边</b>被框进标签 ROI 时会切成这样一块：
     * 宽只有几个原始像素（放大后 ≤ {@link #RULE_MAX_THICKNESS}×scale），
     * 但高度几乎顶到整条文字的上下沿。
     *
     * <h3>为什么要单独判一次（而不是在 preprocess 里按列抹）</h3>
     * 实测 ROI 里的框竖边经过 HEVC 压缩 + 按分位二值化之后是<b>断续的</b>
     * （一段一段的短柱，不是一条实线），"整列点亮比例 ≥ 0.8"这条按列的判据够不着它。
     * 但它切出来之后的形状特征非常稳定：<b>又高又窄</b>。真实的 Arial 字符里
     * 只有 {@code i} 与 {@code :} 是窄的，而它们的<b>高度只占文字带的一小部分</b>
     * （{@code i} 的竖笔在 dots 之后、{@code :} 只是两个点），
     * 因此用"高度 ≥ 文字带高度的 {@link #RULE_VERTICAL_MIN_HEIGHT_FRACTION}"把两者分开。</p>
     *
     * <p>判据（同时满足才丢）：</p>
     * <ol>
     *   <li>宽度 ≤ {@code 4 × 最宽字块高度} 意义上的"窄"：直接比<b>最宽候选块</b>窄得多
     *       （≤ {@link #RULE_VERTICAL_MAX_WIDTH_FRACTION} 倍文字带宽）；</li>
     *   <li>高度 ≥ 文字带高度 × {@link #RULE_VERTICAL_MIN_HEIGHT_FRACTION}。</li>
     * </ol>
     */
    private static void discardVerticalRules(List<Glyph> glyphs) {
        if (glyphs.size() < 3) {
            return;   // 候选太少时不敢丢，宁可多留一块让解析层去判
        }
        int bandTop = Integer.MAX_VALUE;
        int bandBottom = Integer.MIN_VALUE;
        int widest = 0;
        for (Glyph g : glyphs) {
            bandTop = Math.min(bandTop, g.top);
            bandBottom = Math.max(bandBottom, g.bottom);
            widest = Math.max(widest, g.width());
        }
        final int bandHeight = bandBottom - bandTop + 1;
        final int narrowLimit = Math.max(2, Math.round(widest * RULE_VERTICAL_MAX_WIDTH_FRACTION));
        final int tallLimit = Math.round(bandHeight * RULE_VERTICAL_MIN_HEIGHT_FRACTION);
        glyphs.removeIf(g -> g.width() <= narrowLimit && g.height() >= tallLimit);
    }

    /**
     * 段内 8 连通标记，返回每个墨团的外接盒（只保留像素数 ≥ {@link #MIN_GLYPH_PIXELS}
     * 的墨团）。用显式栈而不是递归，避免深锯齿字形（{@code 8}/{@code 9}）把调用栈压爆。
     */
    private static List<Glyph> connectedComponents(boolean[] bitmap, int width, int height,
                                                   int left, int right) {
        final int spanWidth = right - left + 1;
        final boolean[] visited = new boolean[spanWidth * height];
        final List<Glyph> out = new ArrayList<>(8);
        final int[] stack = new int[spanWidth * height];
        for (int sy = 0; sy < height; sy++) {
            for (int sx = 0; sx < spanWidth; sx++) {
                if (visited[sy * spanWidth + sx] || !bitmap[sy * width + left + sx]) {
                    continue;
                }
                int top = 0;
                stack[top++] = sy * spanWidth + sx;
                visited[sy * spanWidth + sx] = true;
                int minX = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE;
                int minY = Integer.MAX_VALUE;
                int maxY = Integer.MIN_VALUE;
                int pixels = 0;
                while (top > 0) {
                    final int index = stack[--top];
                    final int cy = index / spanWidth;
                    final int cx = index % spanWidth;
                    pixels++;
                    minX = Math.min(minX, cx);
                    maxX = Math.max(maxX, cx);
                    minY = Math.min(minY, cy);
                    maxY = Math.max(maxY, cy);
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dy == 0 && dx == 0) {
                                continue;
                            }
                            final int ny = cy + dy;
                            final int nx = cx + dx;
                            if (ny < 0 || ny >= height || nx < 0 || nx >= spanWidth) {
                                continue;
                            }
                            final int neighbour = ny * spanWidth + nx;
                            if (visited[neighbour] || !bitmap[ny * width + left + nx]) {
                                continue;
                            }
                            visited[neighbour] = true;
                            stack[top++] = neighbour;
                        }
                    }
                }
                if (pixels >= MIN_GLYPH_PIXELS) {
                    out.add(new Glyph(left + minX, minY, left + maxX, maxY));
                }
            }
        }
        return out;
    }

    /**
     * 把<b>水平区间互相重叠</b>的墨团并成一个字符，并按左边界排序。
     *
     * <p>这是"把 {@code i} 的点、{@code :} 的上下两点、被压缩断开的笔画重新算回一个
     * 字符"的关键：这些部件在垂直方向分开、但在水平方向重叠。</p>
     */
    private static List<Glyph> mergeOverlapping(boolean[] bitmap, int width, int height,
                                                List<Glyph> components) {
        final List<Glyph> sorted = new ArrayList<>(components);
        sorted.sort((a, b) -> Integer.compare(a.left, b.left));
        final List<Glyph> merged = new ArrayList<>(sorted.size());
        for (Glyph component : sorted) {
            if (merged.isEmpty()) {
                merged.add(component);
                continue;
            }
            final Glyph previous = merged.get(merged.size() - 1);
            // 水平区间重叠（允许 1 列容差）→ 属于同一字符
            if (component.left <= previous.right + 1) {
                merged.set(merged.size() - 1, boundingBoxOfSpan(bitmap, width, height,
                        previous.left, Math.max(previous.right, component.right)));
            } else {
                merged.add(component);
            }
        }
        return merged;
    }

    /** 一列跨度内实际有笔画的最小/最大行。 */
    private static Glyph boundingBoxOfSpan(boolean[] bitmap, int width, int height,
                                           int left, int right) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int x = left; x <= right; x++) {
            for (int y = 0; y < height; y++) {
                if (bitmap[y * width + x]) {
                    if (y < minY) {
                        minY = y;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }
        if (maxY < minY) {
            return null;
        }
        return new Glyph(left, minY, right, maxY);
    }

    /**
     * 把一个粘连在一起的宽块按"字距 ≈ 字高 × {@link #GLYPH_PITCH_OF_HEIGHT}"均分成
     * {@code count} 个字符，每个子块再各自取自己的墨水外接盒。
     *
     * <p>真实字体的数字之间常常没有一整列空白（斜体字形在垂直投影上重叠），
     * 只按空列切会把 {@code 963} 切成一块。按字距均分是对这种情况的标准补救。</p>
     *
     * <p><b>试过但被否掉的方案：按"列墨水谷"切</b>。想法是沿字间凹陷切比均分准，
     * 但实测在 10~19px 的 HEVC 压缩标签上，压缩块效应让"谷"的位置极不稳定 ——
     * 它常常落在某个数字的笔画中间，把字从中间劈开，实测 {@code id:1963} 因此
     * 从"4 个字能对上"退化成"一个都对不上"。均分虽然不精确，但至少不会把字劈坏，
     * 留下的错位可以交给匹配层的 {@code '?'} 与解析层兜住。宁可粗、不可乱。</p>
     */
    private static List<Glyph> splitEvenly(boolean[] bitmap, int width, int height,
                                           Glyph span, int count) {
        final List<Glyph> out = new ArrayList<>(count);
        final int spanWidth = span.width();
        for (int i = 0; i < count; i++) {
            final int left = span.left + i * spanWidth / count;
            final int right = span.left + Math.max(i + 1, (i + 1) * spanWidth / count) - 1;
            // 注意参数顺序：boundingBoxOfSpan(bitmap, width, height, left, right)。
            // 这里必须先把子块夹到 span.right 之内，再取外接盒 —— 否则最后一块会
            // 越过 span 的右界、把邻格的墨也量进来。
            final Glyph piece = boundingBoxOfSpan(bitmap, width, height,
                    left, Math.min(right, span.right));
            if (piece != null) {
                out.add(piece);
            }
        }
        return out;
    }

    private static int countPixels(boolean[] bitmap, int width, Glyph glyph) {
        int count = 0;
        for (int y = glyph.top; y <= glyph.bottom; y++) {
            for (int x = glyph.left; x <= glyph.right; x++) {
                if (bitmap[y * width + x]) {
                    count++;
                }
            }
        }
        return count;
    }

    // ------------------------------------------------------------------ 字符匹配

    /** 一次匹配的结果。{@code character = '?'} 表示没有模板足够相似。 */
    private static final class Match {

        final char character;
        final float similarity;

        Match(char character, float similarity) {
            this.character = character;
            this.similarity = similarity;
        }
    }

    /**
     * 匹配下限：低于它的匹配不认，记成 {@code '?'}。
     *
     * <p>打分就是归一化点阵与模板的 IoU，落在 {@code [0,1]}。取 0.42 的含义是
     * "轮廓大体对齐"：同一个字符在放大 + 二值化 + 保形归一化后，与模板的 IoU
     * 实测在 0.5~0.8（清晰样本更高）；而把 A 认成 B 这类错配通常落在 0.3 以下。
     * 0.42 落在两者之间，略偏保守 —— 宁可留 {@code '?'} 让解析层走回退逻辑，
     * 也不要送出一个"看起来像但其实是别的数字"的字符。</p>
     *
     * <p>它是<b>实现内部常量</b>而不是配置项：「多像才算数字」是这个实现自己的
     * 匹配质量问题，换实现（模板/TFLite）时它会整体消失 —— 现场不会去调它。</p>
     */
    private static final float MIN_MATCH_SIMILARITY = 0.42f;

    /**
     * 把字符块与所有模板比对，取相似度最高者。
     *
     * <p>两侧都先做<b>保形归一化</b>（{@link #normalizeGlyph}）：高度缩到
     * {@link #TEMPLATE_HEIGHT} 行，宽度按原宽高比换算列数并水平居中。这一步是本实现
     * 能被用起来的前提 —— 真实标签里 {@code 1} 是一根窄竖条、{@code 8} 是接近满宽的
     * 方块；如果各自拉伸填满网格，窄条会被撑成实心块，于是 {@code 1} 被读成 {@code 8}
     * （这正是七段版出现过的 {@code id:1963} → {@code id:8963} 误判）。保形之后，
     * 窄条只占网格的一两列，与 {@code 8} 的差异就保留下来了。</p>
     *
     * <p>只保留形状一路（IoU）：真实字体没有七段那种"离散段位"可读，
     * 段一致率这条路自然消失。IoU 本身对笔画粗细偏移不敏感（粗细只改变
     * 交集/并集的边界一点），足以支撑字形区分。</p>
     */
    private static Match matchCharacter(boolean[] bitmap, int width, int height, Glyph glyph) {
        final boolean[] normalized = normalizeGlyph(bitmap, width, glyph);
        char bestChar = '?';
        float bestScore = 0f;
        for (int i = 0; i < GLYPH_CHARS.length; i++) {
            final float score = similarity(normalized, glyphTemplate(i));
            if (score > bestScore) {
                bestScore = score;
                bestChar = GLYPH_CHARS[i];
            }
        }
        if (bestScore < MIN_MATCH_SIMILARITY) {
            return new Match('?', bestScore);
        }
        return new Match(bestChar, bestScore);
    }

    /**
     * 把字符块重采样到 {@link #TEMPLATE_WIDTH}×{@link #TEMPLATE_HEIGHT} 网格。
     *
     * <p>规则（与模板构建走<b>同一条</b>代码，见 {@link #normalizeInk}）：高度总是缩放到
     * {@code TEMPLATE_HEIGHT}；宽度按字符块的宽高比换算成网格列数，夹在
     * {@code [1, TEMPLATE_WIDTH]} 之间，然后<b>水平居中</b>。居中是为了让窄字符
     * 落在网格中间、与同样居中的窄模板对齐。</p>
     */
    private static boolean[] normalizeGlyph(boolean[] bitmap, int width, Glyph glyph) {
        return normalizeInk(bitmap, width,
                inkBoxOf(bitmap, width, glyph.left, glyph.right, glyph.top, glyph.bottom));
    }

    /** 墨水外接盒（闭区间，像素坐标）。 */
    private static final class InkBox {

        final int left;
        final int right;
        final int top;
        final int bottom;

        InkBox(int left, int right, int top, int bottom) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
        }

        int width() {
            return right - left + 1;
        }

        int height() {
            return bottom - top + 1;
        }
    }

    private static InkBox inkBoxOf(boolean[] bitmap, int width,
                                   int left, int right, int top, int bottom) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                if (bitmap[y * width + x]) {
                    if (x < minX) {
                        minX = x;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }
        if (maxY < minY) {
            return null;
        }
        return new InkBox(minX, maxX, minY, maxY);
    }

    /**
     * 按宽高比换算网格列数：高度填满 {@link #TEMPLATE_HEIGHT}，宽度等比跟进，
     * 再夹在 {@code [1, TEMPLATE_WIDTH]}。
     *
     * <p>公式是 {@code round(TEMPLATE_HEIGHT * inkWidth / inkHeight)} —— 乘的是
     * <b>高度</b>而不是宽度，因为"满格"的参照物是网格自己的宽高比：
     * {@code TEMPLATE_HEIGHT} 行对应整个字高，横向列数就应按同一比例缩放。</p>
     */
    private static int gridColumns(int inkWidth, int inkHeight) {
        if (inkHeight <= 0) {
            return 1;
        }
        final int columns = Math.round(TEMPLATE_HEIGHT * (float) inkWidth / (float) inkHeight);
        return Math.max(1, Math.min(TEMPLATE_WIDTH, columns));
    }

    /**
     * 把一块墨水（{@link InkBox}）按保形规则重采样到
     * {@link #TEMPLATE_WIDTH}×{@link #TEMPLATE_HEIGHT} 网格上：高度占满，
     * 宽度按宽高比占若干列并水平居中。
     *
     * <p>同一函数同时作用于字符块与内置模板（模板在 {@link #materialize} 里先被
     * 展开成 {@link InkBox}），保证两边落在同一个几何空间 —— 于是可以直接算 IoU。</p>
     */
    private static boolean[] normalizeInk(boolean[] bitmap, int width, InkBox ink) {
        final boolean[] out = new boolean[TEMPLATE_WIDTH * TEMPLATE_HEIGHT];
        if (ink == null) {
            return out;
        }
        final int columns = gridColumns(ink.width(), ink.height());
        final int columnOffset = (TEMPLATE_WIDTH - columns) / 2;   // 水平居中
        final int inkWidth = ink.width();
        final int inkHeight = ink.height();
        for (int row = 0; row < TEMPLATE_HEIGHT; row++) {
            // 取该网格行"中带"对应的源行，而不是整格 ——
            // 整格采样会把相邻行的像素带进来，让上下两个碗口在中段糊在一起
            // （真实字体的 0 中间两侧没有笔画，整格采样会把 0 读成 8）。
            final int sy0 = Math.min(ink.bottom,
                    ink.top + (int) ((2 * row + 0.5f) * inkHeight / (2 * TEMPLATE_HEIGHT)));
            final int sy1 = Math.max(sy0, Math.min(ink.bottom,
                    ink.top + (int) ((2 * row + 1.5f) * inkHeight / (2 * TEMPLATE_HEIGHT))));
            for (int column = 0; column < columns; column++) {
                final int sx0 = Math.min(ink.right,
                        ink.left + (int) ((2 * column + 0.5f) * inkWidth / (2 * columns)));
                final int sx1 = Math.max(sx0, Math.min(ink.right,
                        ink.left + (int) ((2 * column + 1.5f) * inkWidth / (2 * columns))));
                boolean hasInk = false;
                for (int y = sy0; y <= sy1 && !hasInk; y++) {
                    for (int x = sx0; x <= sx1; x++) {
                        if (bitmap[y * width + x]) {
                            hasInk = true;
                            break;
                        }
                    }
                }
                out[row * TEMPLATE_WIDTH + columnOffset + column] = hasInk;
            }
        }
        return out;
    }

    /** IoU：交集 / 并集；并集为 0 时返回 0。 */
    private static float similarity(boolean[] a, boolean[] b) {
        int intersection = 0;
        int union = 0;
        for (int i = 0; i < a.length; i++) {
            if (a[i] && b[i]) {
                intersection++;
            }
            if (a[i] || b[i]) {
                union++;
            }
        }
        return union == 0 ? 0f : intersection / (float) union;
    }

    /**
     * 内置字形模板（类装载时构建一次，避免每次调用重建）。
     *
     * <p>构建过程：把 {@link #GLYPH_PATTERNS} 的字符串点阵补齐成
     * {@link #TEMPLATE_WIDTH} 列，量出墨水外接盒，再走一遍 {@link #normalizeInk}
     * 保形归一化 —— <b>与字符块完全同一条路径</b>。这一步不能省：模板与字符块必须在
     * 同一个几何空间里才能比 IoU，而字符块是保形归一化的（窄的 {@code 1} 只占一两列）。
     * 若模板保持"降采样后的原样"，那么 {@code 1} 的模板会占在左侧、字符块占在中间，
     * 两边错位，IoU 反而更低。</p>
     */
    private static final boolean[][] GLYPH_TEMPLATES = buildGlyphTemplates();

    private static boolean[] glyphTemplate(int index) {
        return GLYPH_TEMPLATES[Math.max(0, Math.min(GLYPH_TEMPLATES.length - 1, index))];
    }

    private static boolean[][] buildGlyphTemplates() {
        final boolean[][] templates = new boolean[GLYPH_PATTERNS.length][];
        for (int i = 0; i < GLYPH_PATTERNS.length; i++) {
            templates[i] = materialize(GLYPH_PATTERNS[i]);
        }
        return templates;
    }

    /**
     * 把点阵字符串展开成定长 {@code TEMPLATE_WIDTH × TEMPLATE_HEIGHT} 的位图，
     * 再做保形归一化。
     *
     * <p>行数必须恰好是 {@link #TEMPLATE_HEIGHT}（静态块里有断言）；每行的长度可以
     * 小于 {@link #TEMPLATE_WIDTH}（那是该字符按宽高比占的列数），右侧补空列即可 ——
     * 归一化的第一步就是"量出墨水外接盒"，补出来的空列不会影响结果。</p>
     */
    private static boolean[] materialize(String[] pattern) {
        final boolean[] raw = new boolean[TEMPLATE_WIDTH * TEMPLATE_HEIGHT];
        for (int row = 0; row < pattern.length; row++) {
            final String line = pattern[row];
            for (int column = 0; column < Math.min(TEMPLATE_WIDTH, line.length()); column++) {
                if (line.charAt(column) == '#') {
                    raw[row * TEMPLATE_WIDTH + column] = true;
                }
            }
        }
        return normalizeInk(raw, TEMPLATE_WIDTH,
                inkBoxOf(raw, TEMPLATE_WIDTH, 0, TEMPLATE_WIDTH - 1, 0, TEMPLATE_HEIGHT - 1));
    }

    private static long elapsedMs(long startNs) {
        final long deltaNs = System.nanoTime() - startNs;
        return deltaNs <= 0L ? 0L : deltaNs / 1_000_000L;
    }

    @Override
    public String toString() {
        return "GlyphTemplateTargetIdRecognizer{name=" + NAME
                + ", templates=" + GLYPH_CHARS.length
                + ", grid=" + TEMPLATE_WIDTH + "x" + TEMPLATE_HEIGHT
                + ", " + parser.describe() + "}";
    }

    /** 供日志/诊断：模板与解析规则的摘要。 */
    public String describe() {
        return toString();
    }

    static {
        // 模板的行数必须与常量一致 —— 写错一行会让匹配静默退化。
        if (GLYPH_PATTERNS.length != GLYPH_CHARS.length) {
            throw new IllegalStateException("glyph pattern count " + GLYPH_PATTERNS.length
                    + " != char count " + GLYPH_CHARS.length);
        }
        for (int i = 0; i < GLYPH_PATTERNS.length; i++) {
            final String[] pattern = GLYPH_PATTERNS[i];
            if (pattern.length != TEMPLATE_HEIGHT) {
                throw new IllegalStateException("glyph '" + GLYPH_CHARS[i] + "' has "
                        + pattern.length + " rows, expected " + TEMPLATE_HEIGHT);
            }
            for (String line : pattern) {
                if (line.length() > TEMPLATE_WIDTH) {
                    throw new IllegalStateException("glyph '" + GLYPH_CHARS[i]
                            + "' row width " + line.length() + " > " + TEMPLATE_WIDTH);
                }
            }
        }
    }

    /** 供单测断言：内置模板的可读摘要。 */
    static String templateSummary() {
        return "glyphs=" + String.valueOf(GLYPH_CHARS)
                + " grid=" + TEMPLATE_WIDTH + "x" + TEMPLATE_HEIGHT
                + " prefix=" + TargetIdParser.prefixPatternSource().toLowerCase(Locale.ROOT);
    }
}
