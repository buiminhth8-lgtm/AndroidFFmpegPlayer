package com.example.motro.targetselection.vision;

import com.example.motro.targetselection.api.TargetSelectionConfig;
import com.example.motro.targetselection.frame.CapturedFrame;
import com.example.motro.targetselection.frame.FrameImage;
import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoGeometry;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 「已选中的目标框 → 它的 {@code id:xxxx} 标签区域」—— Slice 7 的核心实现，
 * 播放器无关、纯 JVM、零第三方依赖（没有 OpenCV / ML Kit / TFLite）。
 *
 * <h2>输入输出</h2>
 * <pre>
 *   入参：CapturedFrame + selectedBox.rectInVideo（视频像素）+ TargetSelectionConfig
 *        （可选 VideoGeometry：帧被缩放过时用它把视频矩形折算到分析帧，缺失则视作 1:1）
 *   出参：LabelExtractionResult{ status, labelSearchRegion, candidates, selectedLabel,
 *                                labelRectInFrame, labelArgb, labelDetectMs, labelCropMs, totalMs }
 * </pre>
 * <p><b>本类不做 OCR</b>：它只回答「框顶附近哪一小块是编号文字」，绝不回答「编号是几」。
 * 于是「找区域」与「读数字」可以各自演进、各自验收 —— 找区域错了会表现为 ROI 画偏，
 * 读数字错了会表现为 id 值错，两者的排查方向完全不同。</p>
 *
 * <h2>为什么不能直接拿 {@code TargetBoxDetector} 的结果改改就用</h2>
 * 矩形恢复要的是「长直线 + 成对」，而标签恰好是它的反面：<b>短、碎、镂空</b>。
 * 同一套判据没法同时服务两者（放宽尺寸就会把 {@code id:1963} 当成一个框，
 * 这正是 Slice 5 的 {@code minBoxWidth/minBoxHeight} 要挡的事）。
 * 所以这里另起一套<b>文本形态</b>判据，但<b>复用同一条颜色规则</b>
 * （{@link BlueColorThresholds}，阈值仍全部来自 {@link TargetSelectionConfig}）——
 * 「什么算蓝」只有一份，「什么算文字」才是本类新增的判断。</p>
 *
 * <h2>算法（五步，全部只作用于框顶附近那一小块）</h2>
 * <ol>
 *   <li><b>搜索区域</b>：由 {@code selectedBox} 直接构造（<b>不</b>依赖点击点）：
 *       <pre>
 *         left   = box.left  - labelHorizontalPaddingPx
 *         right  = box.right + labelHorizontalPaddingPx
 *         top    = box.top   - labelSearchHeightPx
 *         bottom = box.top   + labelVerticalPaddingPx   ← 允许压进框顶一点
 *       </pre>
 *       逐边夹进视频边界；再经 {@link VideoCoordinateMapper#mapVideoToFrame} 折算到分析帧。
 *       <b>不写死绝对坐标</b>：真实画面里标签贴在框顶边之上（实测间隙 0~2px），
 *       所以「框顶往上」是主搜索方向，但高度、左右余量、压进框里的深度都来自配置。</li>
 *   <li><b>蓝像素</b>：在搜索区域内逐像素用 {@link BlueColorThresholds#matches} 判定，
 *       建一张紧凑位掩码（{@link BinaryMask}）。</li>
 *   <li><b>剔除框边线</b>：{@link BinaryMask#runs()} 里长度超过「框宽 ×
 *       {@link #LINE_RUN_FRACTION_OF_BOX_WIDTH}」的横段一定是框线（或别的目标框的边缘、
 *       或它被压缩切碎的残段），不是笔画 —— 直接丢掉。
 *       这一步不能省：实测标签与框顶边会<b>重叠</b>在同一批行上，
 *       不剔掉的话文字与边线会被并成一条带，候选框会横跨整个框宽。</li>
 *   <li><b>行带分组（两遍）</b>：先用很紧的间隙把「行相邻且几乎相接」的短段聚成
 *       <b>字形簇</b>，再按「水平够近 <b>且</b> 垂直范围至少重叠一半」把字形簇合并成
 *       <b>文本行</b>（见 {@link #groupIntoBands}）。这正是「文字」与「一块蓝色区域」的
 *       分界：文字是一行里好几段互不相连的笔画，色块是一行里一整段。
 *       第二遍的垂直判据是必需的 —— 实测「一个编号内部的字形空隙」和
 *       「两个相邻目标的编号间距」都是约 10px，只有垂直方向能区分它们。</li>
 *   <li><b>文本形态打分 + 确定性排序</b>：见 {@link #textLikeScore} 与
 *       {@link #RANKING}。过滤（尺寸、离框顶距离、文本形态下限）全部走配置项，
 *       排序用显式比较器逐级 tie-break，<b>不依赖集合遍历顺序</b>。</li>
 * </ol>
 * <p>最后从<b>已经读进来的那块缓冲</b>里裁出选中标签的像素（不再回读一次帧），
 * 可选的 1px padding 只为吸收取整误差。</p>
 *
 * <h2>性能</h2>
 * 只处理「框宽 × 搜索高度」这一小块（1080p 上一个 240px 宽的框约 270×54 视频像素，
 * 折算到 640 长的分析帧上不到 2k 像素），不重新扫描整帧、不重跑矩形恢复、
 * 不分配大 Bitmap。单次调用应在<b>亚毫秒到几毫秒</b>量级；分步耗时由
 * {@link LabelExtractionResult#labelDetectMs}/{@link LabelExtractionResult#labelCropMs}
 * 给出，不用改代码加打点。
 *
 * <h2>调用时机（性能要求的落点，与 Slice 4/5 一致）</h2>
 * 只在<b>用户点击</b>时执行一次，绝不每帧调用；必须跑在<b>工作线程</b>上
 * （内部有帧像素回读）；不要放解码/渲染线程。
 *
 * <p><b>所有权</b>：不拥有传入的帧（调用方负责 {@code release()}），
 * 不长期持有任何 Bitmap —— 结果里只有一小块裁剪后的 ARGB 数组。</p>
 *
 * <p>线程约定：本类无状态（配置不可变，方法内不共享任何缓冲），可跨线程并发调用；
 * 但单次调用应完整跑在一个工作线程上。</p>
 */
public final class LabelRegionExtractor {

    // ------------------------------------------------------------------ 文本形态判据（内部常量）

    /**
     * 一条横段长于「框宽 × 该比例」时，它一定是框线或别的目标框的边缘，不是笔画。
     *
     * <p>为什么用<b>框宽</b>作基准而不是绝对像素：标签宽度与框宽没有固定关系
     * （同一个推流端的字号固定，框却大小不一），但框的边线长度恒等于框宽。
     * 实测 {@code id:1850} 宽约 56px、框宽 244px（比例 0.23），而框顶边线比例恒为 1.0，
     * 两者相差一个数量级。
     * <b>按段</b>（一个字形笔画）而不是按整行判断：<code>id:1963</code> 里最长的
     * 单个笔画也只有十几像素。</p>
     *
     * <p>取 0.25 而不是更宽松的值，是因为实测发现框顶边的<b>抗锯齿残段</b>会落在
     * 搜索带内（例如 t=45 帧的 { 1610,1722 } 框：真边宽 113px，而 y=884 一行的残段被
     * 色度子采样切成了 45px / 32px / 16px 三段）。0.5 只挡得住完整边线，
     * 挡不住这些残段 —— 它们会被并进标签带，把 ROI 从 60px 撑到 83px。
     * {@link #LINE_RUN_MIN_PX} 的下限保证窄框（框宽 70px 的实测目标）不会被误伤，
     * 而实测标签的最长单笔画横段是 16px，离下限还有余量。</p>
     *
     * <p>它不属于 {@link TargetSelectionConfig}：它是「结构判据」（边线必然与框等宽），
     * 不是「感知阈值」（现场不会因为光照变化去调它）。配置里留给现场的永远是那些
     * 会随画面变化的量。</p>
     */
    private static final float LINE_RUN_FRACTION_OF_BOX_WIDTH = 0.25f;

    /** 框很窄时 {@link #LINE_RUN_FRACTION_OF_BOX_WIDTH} 会压到几像素，用这个下限兜住。 */
    private static final int LINE_RUN_MIN_PX = 24;

    /** 行带允许跨越的空行数：文字行有抖动，相邻行之间偶尔会空 1~2 行。 */
    private static final int TEXT_LINE_GAP_PX = 2;

    /**
     * 第一遍（字形簇）允许的<b>字形内部</b>水平空隙，单位是空像素个数。
     * 刻意贴得很紧：它只负责把同一个字形被压缩/抗锯齿劈开的两半接回去，
     * 不做任何「跨字形」的判断 —— 那是第二遍的事。
     */
    private static final int GLYPH_JOIN_MAX_EMPTY_PX = 2;

    /**
     * 第二遍（合并成文本行）允许的<b>字形之间</b>水平空隙 = 搜索高度 × 该比例。
     * 用搜索高度（配置量）而不是常数，是为了让这个空隙跟着几何尺度一起缩放 ——
     * 帧被缩小到 1/3 时它必须跟着变窄，否则会把隔壁目标的编号并进来。
     */
    private static final float TEXT_GLYPH_GAP_FRACTION_OF_SEARCH_HEIGHT = 0.25f;

    /** 字形空隙的局部像素下限/上限（见 {@link #TEXT_GLYPH_GAP_FRACTION_OF_SEARCH_HEIGHT}）。 */
    private static final int TEXT_GLYPH_GAP_MIN_PX = 1;
    private static final int TEXT_GLYPH_GAP_MAX_PX = 12;

    /**
     * 两个字形簇要算「同一行文字」时，垂直范围至少要有这么高的重叠比例。
     *
     * <p>这是本类区分「一个编号内部的字形间隙」与「隔壁目标的编号」的<b>唯一</b>可靠依据，
     * 而且是实测逼出来的：真实推流端在 {@code id} 与 {@code :1850} 之间的字形空隙约 10px，
     * 两个相邻目标的编号之间也只有约 10px —— 水平间隙完全同量级。
     * 差别在垂直方向：同一个编号的字形基线一致（重叠 6/6、10/10 行），
     * 而相邻两个目标的编号各行其是（实测只重叠 4/10 行）。</p>
     */
    private static final float TEXT_SAME_LINE_MIN_VERTICAL_OVERLAP = 0.5f;

    /** 合并文本行时的扫描顺序：与输入顺序无关，保证结果可复现。 */
    private static final Comparator<Band> BAND_ORDER = (a, b) -> {
        int result = Integer.compare(a.minY, b.minY);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(a.minX, b.minX);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(a.maxY, b.maxY);
        if (result != 0) {
            return result;
        }
        return Integer.compare(a.maxX, b.maxX);
    };

    /** 文本块长宽比的合理区间；细于 12:1 是直线，接近 1:1 是方块，都不像一行编号。 */
    private static final float TEXT_MIN_ASPECT = 1.2f;
    private static final float TEXT_MAX_ASPECT = 12f;

    /** 行内多段性/填充率/长宽比三项在 {@link #textLikeScore} 里的权重（和为 1）。 */
    private static final float RUNS_SCORE_WEIGHT = 0.5f;
    private static final float FILL_SCORE_WEIGHT = 0.3f;
    private static final float ASPECT_SCORE_WEIGHT = 0.2f;

    /** 文本形态得分低于它就丢弃：实心色块与长直线在这一项上直落 0。 */
    private static final float MIN_TEXT_LIKE_SCORE = 0.40f;

    /** 裁剪时在候选矩形外扩的像素数，只为吸收取整带来的半像素误差（不做"美化"）。 */
    private static final int LABEL_CROP_PADDING_PX = 1;

    /** {@link LabelCandidate#confidence} 的合成权重（和为 1），仅用于展示与同分兜底。 */
    private static final float CONFIDENCE_TEXT_WEIGHT = 0.50f;
    private static final float CONFIDENCE_ALIGNMENT_WEIGHT = 0.35f;
    private static final float CONFIDENCE_PROXIMITY_WEIGHT = 0.15f;

    /**
     * 候选排序的<b>唯一出处</b>：逐级比较、任一级分出胜负即返回，因此结果与输入顺序
     * <b>无关</b>，也不依赖排序算法是否稳定（见 {@code LabelRegionExtractorTest} 的
     * 顺序无关性用例）。优先级与需求一致：
     * <ol>
     *   <li>离框顶更近（{@code distanceToBox} 升序）—— 编号就压在框顶边上，越近越像它自己的；</li>
     *   <li>与框的水平跨度更重合（{@code alignmentScore} 降序）；</li>
     *   <li>更像一行文字（{@code textLikeScore} 降序）；</li>
     *   <li>更小更具体（面积升序）；</li>
     *   <li>上边界、左边界、宽、高（升序）—— 最后的兜底，只为让同分情况下结果<b>唯一确定</b>。</li>
     * </ol>
     */
    private static final Comparator<LabelCandidate> RANKING = (a, b) -> {
        int result = Float.compare(a.distanceToBox, b.distanceToBox);
        if (result != 0) {
            return result;
        }
        result = Float.compare(b.alignmentScore, a.alignmentScore);
        if (result != 0) {
            return result;
        }
        result = Float.compare(b.textLikeScore, a.textLikeScore);
        if (result != 0) {
            return result;
        }
        result = Float.compare(a.area(), b.area());
        if (result != 0) {
            return result;
        }
        result = Float.compare(a.rectInVideo.top, b.rectInVideo.top);
        if (result != 0) {
            return result;
        }
        result = Float.compare(a.rectInVideo.left, b.rectInVideo.left);
        if (result != 0) {
            return result;
        }
        result = Float.compare(a.rectInVideo.width(), b.rectInVideo.width());
        if (result != 0) {
            return result;
        }
        return Float.compare(a.rectInVideo.height(), b.rectInVideo.height());
    };

    private final TargetSelectionConfig config;

    /**
     * @param config 默认策略，非 null；搜索区域尺寸、裁剪下限、候选上限、蓝色阈值
     *               全部从它读取（<b>不</b>在这里复制一份，避免"改了配置但行为没变"）
     * @throws IllegalArgumentException config 为 null
     */
    public LabelRegionExtractor(TargetSelectionConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("config must not be null");
        }
        this.config = config;
    }

    /**
     * 从一帧里提取已选中目标框的标签区域。
     *
     * <p>{@code geometry} 为 null 时视作「分析帧 == 视频帧」（1:1）。需要它的理由是
     * {@code CapturedFrame} 是<b>从 View 回读</b>、并可能被
     * {@link TargetSelectionConfig#maxFrameLongSidePx} 缩小过的另一幅图；
     * 此时 {@code selectedBox.rectInVideo}（视频像素）与帧内下标之间差一个缩放，
     * 直接把视频坐标当下标裁像素会系统性取错区域。几何与 Slice 4/5 用的是同一份约定。</p>
     *
     * @param frame       帧，非 null；其图像载荷在调用期间必须有效
     * @param geometry    几何快照，可为 null（null → 帧坐标 == 视频坐标）
     * @param selectedBox 已选中的目标框（视频像素），非 null
     * @return 非 null 的结果；{@code totalMs} 只覆盖 Label 提取这一段
     */
    public LabelExtractionResult extract(CapturedFrame frame, VideoGeometry geometry,
                                        TargetBoundingBox selectedBox) {
        final long startNs = System.nanoTime();

        if (frame == null) {
            return LabelExtractionResult.failure(LabelExtractionStatus.INVALID_FRAME,
                    "frame is null", elapsedMs(startNs));
        }
        final FrameImage image = frame.image();
        if (image == null) {
            return LabelExtractionResult.failure(LabelExtractionStatus.INVALID_FRAME,
                    "frame has no image payload (empty or already released)", elapsedMs(startNs));
        }
        final int frameWidth = frame.width;
        final int frameHeight = frame.height;
        if (frameWidth <= 0 || frameHeight <= 0) {
            return LabelExtractionResult.failure(LabelExtractionStatus.INVALID_FRAME,
                    "frame size is " + frameWidth + "x" + frameHeight, elapsedMs(startNs));
        }
        // 载体尺寸与 frame 记录不一致：坐标会静默错位，硬拦住
        if (image.width() != frameWidth || image.height() != frameHeight) {
            return LabelExtractionResult.failure(LabelExtractionStatus.INVALID_FRAME,
                    "image size " + image.width() + "x" + image.height()
                            + " does not match frame " + frameWidth + "x" + frameHeight,
                    elapsedMs(startNs));
        }
        if (selectedBox == null) {
            return LabelExtractionResult.failure(LabelExtractionStatus.INVALID_BOX,
                    "selectedBox is null", elapsedMs(startNs));
        }
        if (selectedBox.isEmpty()) {
            return LabelExtractionResult.failure(LabelExtractionStatus.INVALID_BOX,
                    "selectedBox is degenerate: " + selectedBox, elapsedMs(startNs));
        }

        final boolean hasGeometry = geometry != null && geometry.isUsable();
        final int videoWidth = hasGeometry ? geometry.videoWidth : frameWidth;
        final int videoHeight = hasGeometry ? geometry.videoHeight : frameHeight;
        if (videoWidth <= 0 || videoHeight <= 0) {
            return LabelExtractionResult.failure(LabelExtractionStatus.INVALID_FRAME,
                    "video size is " + videoWidth + "x" + videoHeight, elapsedMs(startNs));
        }
        if (selectedBox.right <= 0f || selectedBox.bottom <= 0f
                || selectedBox.left >= videoWidth || selectedBox.top >= videoHeight) {
            return LabelExtractionResult.failure(LabelExtractionStatus.OUT_OF_BOUNDS,
                    "selectedBox " + selectedBox + " does not intersect video "
                            + videoWidth + "x" + videoHeight, elapsedMs(startNs));
        }

        final long detectStartNs = System.nanoTime();

        // 1) 搜索区域：框顶之上 labelSearchHeightPx，左右各留 labelHorizontalPaddingPx，
        //    并允许向下压进框顶 labelVerticalPaddingPx（实测文字会与框顶边重叠）。
        //    全部来自配置，绝不写死坐标 —— 换推流端只改配置。
        final TargetBoundingBox rawSearch = TargetBoundingBox.of(
                selectedBox.left - config.labelHorizontalPaddingPx,
                selectedBox.top - config.labelSearchHeightPx,
                selectedBox.right + config.labelHorizontalPaddingPx,
                selectedBox.top + config.labelVerticalPaddingPx);
        // 逐边夹进视频边界：靠画面顶/左/右边缘的目标框只会得到一个"变小但合法"的区域
        final TargetBoundingBox searchVideo = TargetBoundingBox.of(
                Math.max(0f, rawSearch.left),
                Math.max(0f, rawSearch.top),
                Math.min(videoWidth, rawSearch.right),
                Math.min(videoHeight, rawSearch.bottom));
        if (searchVideo.isEmpty()) {
            return LabelExtractionResult.failure(LabelExtractionStatus.OUT_OF_BOUNDS,
                    "label search region collapsed after clipping: " + searchVideo,
                    elapsedMs(startNs));
        }

        // 2) 视频矩形 → 分析帧矩形（唯一通道是 VideoCoordinateMapper，本类不含任何缩放公式）
        final RoiRect searchLocal = toFrameRect(hasGeometry ? geometry : null,
                frameWidth, frameHeight, searchVideo);
        final int searchWidth = searchLocal.width();
        final int searchHeight = searchLocal.height();

        // 3) 一次像素回读（只读这一小块，不碰整帧；不修改传入的帧）
        final int[] regionArgb = new int[searchWidth * searchHeight];
        final FrameImage target = frame.image();
        if (target == null) {
            return LabelExtractionResult.failure(LabelExtractionStatus.INVALID_FRAME,
                    "frame was released while extracting the label region", elapsedMs(startNs));
        }
        if (!target.readArgbRegion(searchLocal.left, searchLocal.top, searchWidth, searchHeight,
                regionArgb, 0, searchWidth)) {
            return LabelExtractionResult.failure(LabelExtractionStatus.INVALID_FRAME,
                    "frame image does not support ARGB readout", elapsedMs(startNs));
        }

        // 4) 蓝像素 + 剔除框边线 + 行带分组 + 确定性排序
        final List<LabelCandidate> candidates = findCandidates(regionArgb, searchLocal, searchVideo,
                selectedBox);
        final long labelDetectMs = elapsedMs(detectStartNs);

        if (candidates.isEmpty()) {
            return LabelExtractionResult.noLabel(searchVideo, labelDetectMs, elapsedMs(startNs),
                    "no text-like blue region near the box top");
        }

        // 5) 裁剪选中的标签 —— 从已经读进来的缓冲里取，不再回读一次帧
        final LabelCandidate selected = candidates.get(0);
        final long cropStartNs = System.nanoTime();
        // rectInLocal 是"搜索区域局部"坐标（0 起），裁剪就按这套下标走；
        // 对外的帧坐标矩形是它加上搜索区域原点，两者都由同一份 RoiRect 推导，不会各写一份。
        final RoiRect cropLocal = paddedCrop(selected.rectInLocal, searchWidth, searchHeight);
        final int cropWidth = cropLocal.width();
        final int cropHeight = cropLocal.height();
        final int[] labelArgb = new int[cropWidth * cropHeight];
        for (int row = 0; row < cropHeight; row++) {
            System.arraycopy(regionArgb, (cropLocal.top + row) * searchWidth + cropLocal.left,
                    labelArgb, row * cropWidth, cropWidth);
        }
        final RoiRect cropFrame = RoiRect.of(
                searchLocal.left + cropLocal.left, searchLocal.top + cropLocal.top,
                searchLocal.left + cropLocal.right, searchLocal.top + cropLocal.bottom);
        final long labelCropMs = elapsedMs(cropStartNs);

        return LabelExtractionResult.success(searchVideo, candidates, selected,
                cropFrame, labelArgb, cropWidth, cropHeight,
                labelDetectMs, labelCropMs, elapsedMs(startNs),
                describe(selected, candidates.size()));
    }

    /**
     * {@link #extract(CapturedFrame, VideoGeometry, TargetBoundingBox)} 的便捷重载
     * （不传几何 → 视作分析帧与视频 1:1）。
     */
    public LabelExtractionResult extract(CapturedFrame frame, TargetBoundingBox selectedBox) {
        return extract(frame, null, selectedBox);
    }

    /** 当前生效的默认策略。 */
    public TargetSelectionConfig config() {
        return config;
    }

    // ------------------------------------------------------------------ 候选搜索

    /**
     * 从搜索区域的 ARGB 缓冲里找出全部标签候选（已排序、已限量）。
     *
     * @param regionArgb  搜索区域像素（行优先）
     * @param searchLocal 搜索区域在分析帧里的矩形（闭区间）
     * @param searchVideo 搜索区域在视频坐标里的矩形
     * @param selectedBox 已选中的目标框（视频坐标）
     * @return 不可变的候选列表；无候选时为空列表
     */
    private List<LabelCandidate> findCandidates(int[] regionArgb, RoiRect searchLocal,
                                                TargetBoundingBox searchVideo,
                                                TargetBoundingBox selectedBox) {
        // 帧像素 → 视频像素。搜索区域的视频矩形与实际读到的帧矩形的比例就是它，
        // 不需要第二套换算（与 RoiRect#toVideoRect 用的是同一个比例）。
        final float scaleX = searchVideo.width() / searchLocal.width();
        final float scaleY = searchVideo.height() / searchLocal.height();

        // 蓝色判定复用 Slice 4 的规则对象，阈值仍全部来自配置 —— 本类不新建第二套蓝色阈值
        final BlueColorThresholds thresholds = BlueColorThresholds.of(
                config.blueHueMinDegrees, config.blueHueMaxDegrees,
                config.minSaturation, config.minValue);

        final int width = searchLocal.width();
        final int height = searchLocal.height();
        final boolean[] blue = new boolean[regionArgb.length];
        final float[] hsv = new float[RgbHsv.COMPONENTS];
        boolean anyBlue = false;
        for (int i = 0; i < regionArgb.length; i++) {
            RgbHsv.fromArgb(regionArgb[i], hsv);
            if (thresholds.matches(hsv[0], hsv[1], hsv[2])) {
                blue[i] = true;
                anyBlue = true;
            }
        }
        if (!anyBlue) {
            return Collections.emptyList();
        }
        // 用 BinaryMask 承载：runs() 是已经测过的行内连续段实现，本类不必再写一套扫描
        final BinaryMask mask = BinaryMask.ofBooleans(blue, width, height);

        // 框边线（以及别的目标框的边缘）与框等宽；标签的单个笔画只有十几像素
        final float lineRunMinPx = Math.max(LINE_RUN_MIN_PX,
                selectedBox.width() * LINE_RUN_FRACTION_OF_BOX_WIDTH);
        final int lineRunMinLocal = Math.max(2, Math.round(lineRunMinPx / scaleX));

        final int glyphGapLocal = clampInt(Math.round(
                        config.labelSearchHeightPx * TEXT_GLYPH_GAP_FRACTION_OF_SEARCH_HEIGHT / scaleX),
                TEXT_GLYPH_GAP_MIN_PX, TEXT_GLYPH_GAP_MAX_PX);

        final List<Band> bands = groupIntoBands(mask, lineRunMinLocal, glyphGapLocal);

        final List<LabelCandidate> candidates = new ArrayList<>(bands.size());
        for (int i = 0; i < bands.size(); i++) {
            final Band band = bands.get(i);
            final float videoWidth = band.width() * scaleX;
            final float videoHeight = band.height() * scaleY;
            if (videoWidth < config.minLabelCandidateWidth
                    || videoHeight < config.minLabelCandidateHeight) {
                continue;
            }
            final RoiRect local = RoiRect.of(band.minX, band.minY, band.maxX, band.maxY);
            // 局部 → 视频只走 RoiRect.toVideoRect 这一条通道（公式只有一份）
            final TargetBoundingBox videoRect =
                    local.toVideoRect(searchVideo, width, height);

            // 只接受"贴在框顶附近"的候选：太靠上多半是别的目标或别的叠加层文字
            final float signedGap = selectedBox.top - videoRect.bottom;
            if (signedGap > config.labelMaxDistanceFromBoxPx
                    || signedGap < -config.labelVerticalPaddingPx) {
                continue;
            }
            final float textLike = textLikeScore(band, videoWidth, videoHeight);
            if (textLike < MIN_TEXT_LIKE_SCORE) {
                continue;
            }
            final float alignment = alignmentScore(videoRect, selectedBox);
            final float proximity = proximityScore(signedGap);
            final float confidence = CONFIDENCE_TEXT_WEIGHT * textLike
                    + CONFIDENCE_ALIGNMENT_WEIGHT * alignment
                    + CONFIDENCE_PROXIMITY_WEIGHT * proximity;
            candidates.add(LabelCandidate.of(videoRect, local, Math.abs(signedGap), signedGap,
                    alignment, textLike, confidence));
        }

        candidates.sort(RANKING);
        if (candidates.size() > config.maxLabelCandidateCount) {
            candidates.subList(config.maxLabelCandidateCount, candidates.size()).clear();
        }
        return candidates;
    }

    /**
     * 把掩码里的短段聚成「文本行带」——<b>两遍</b>。
     *
     * <p><b>为什么不直接做 2D 连通域</b>：编号是一行文字，几何上永远不会上下换行；
     * 而行带分组比连通域少一整趟栈/队列与一个访问标记数组，且顺手就把「同一行的
     * 若干笔画属于同一块」这件事表达出来了 —— 连通域反而要额外再把它们并回去。</p>
     *
     * <p><b>为什么是两遍而不是一遍</b>：单遍只能拿一个水平间隙做判据，而这个判据在
     * 真实画面上<b>必然二选一失败</b>（实测）：
     * 放大到 10px 才接得住 {@code id:1850} 里 {@code d} 与 {@code :} 之间的空隙，
     * 可是两个相邻目标的编号之间也正是 10px —— 放大的同时就把隔壁编号并了进来。
     * 所以这里把「接笔画」与「认同一行」拆开：</p>
     * <ol>
     *   <li>{@link #clusterTouchingRuns} 用很紧的间隙（{@link #GLYPH_JOIN_MAX_EMPTY_PX}）
     *       把行相邻、几乎相接的段聚成<b>字形簇</b>；</li>
     *   <li>{@link #mergeIntoTextLines} 用「水平够近 <b>且</b> 垂直范围至少重叠一半」
     *       把字形簇合并成<b>文本行</b> —— 后半条才是「同一行文字」的结构性证据。</li>
     * </ol>
     *
     * <p>超过框宽 {@link #LINE_RUN_FRACTION_OF_BOX_WIDTH} 的横段在进入分组<b>之前</b>
     * 就被丢掉了（那是框线），所以框顶边不会把整行带撑到整个框宽 —— 这正是
     * "缩小到 {@code [id:1963]} 而不是整块大区域"的关键一步。</p>
     */
    private static List<Band> groupIntoBands(BinaryMask mask, int lineRunMinLocal, int glyphGapLocal) {
        return mergeIntoTextLines(clusterTouchingRuns(mask, lineRunMinLocal), glyphGapLocal);
    }

    /**
     * 第一遍：行相邻且几乎相接的短段 → <b>字形簇</b>。
     *
     * @param lineRunMinLocal 横段长度达到它就判定为框边线并丢弃（局部像素）
     */
    private static List<Band> clusterTouchingRuns(BinaryMask mask, int lineRunMinLocal) {
        final int[] runs = mask.runs();
        final List<Band> closed = new ArrayList<>();
        final List<Band> open = new ArrayList<>();
        int currentRow = -1;

        for (int i = 0; i < runs.length; i += 3) {
            final int y = runs[i];
            final int start = runs[i + 1];
            final int end = runs[i + 2];
            if (end - start + 1 >= lineRunMinLocal) {
                continue;   // 框边线的横段，不是笔画
            }
            if (y != currentRow) {
                currentRow = y;
                // 行号只会递增，隔了太多行的簇再也不可能长出新笔画 —— 就地收口
                for (int b = open.size() - 1; b >= 0; b--) {
                    if (y - open.get(b).lastRow > TEXT_LINE_GAP_PX) {
                        closed.add(open.remove(b));
                    }
                }
            }
            Band owner = null;
            for (int b = 0; b < open.size(); b++) {
                final Band band = open.get(b);
                if (y - band.lastRow <= TEXT_LINE_GAP_PX
                        && band.closeToHorizontally(start, end, GLYPH_JOIN_MAX_EMPTY_PX)) {
                    owner = band;
                    break;
                }
            }
            if (owner == null) {
                owner = new Band();
                open.add(owner);
            }
            owner.add(y, start, end);
        }
        closed.addAll(open);
        return closed;
    }

    /**
     * 第二遍：字形簇 → <b>文本行</b>。
     *
     * <p>合并判据必须<b>同时</b>满足两条（见 {@link #sameTextLine}）：
     * 水平空隙不超过 {@code glyphGapLocal}，且垂直范围至少重叠
     * {@link #TEXT_SAME_LINE_MIN_VERTICAL_OVERLAP}。</p>
     *
     * <p>每轮只合并「排序后第一对」满足条件的簇，然后重新排序再找，直到无可合并。
     * 因为簇列表每轮都按 {@link #BAND_ORDER} 排序，结果与输入顺序无关；
     * 簇的数量是「一行文字的字形块数」（十几个），所以这个 O(n²) 的循环代价可以忽略。</p>
     */
    private static List<Band> mergeIntoTextLines(List<Band> clusters, int glyphGapLocal) {
        final List<Band> lines = new ArrayList<>(clusters);
        lines.sort(BAND_ORDER);
        boolean merged = true;
        while (merged) {
            merged = false;
            for (int i = 0; i < lines.size() && !merged; i++) {
                for (int j = i + 1; j < lines.size(); j++) {
                    final Band first = lines.get(i);
                    final Band second = lines.get(j);
                    if (sameTextLine(first, second, glyphGapLocal)) {
                        first.absorb(second);
                        lines.remove(j);
                        lines.sort(BAND_ORDER);
                        merged = true;
                        break;
                    }
                }
            }
        }
        return lines;
    }

    /**
     * 两个字形簇是否属于同一行文字：水平够近 <b>且</b> 垂直范围至少重叠一半。
     *
     * <p>垂直那条不能省，理由见 {@link #TEXT_SAME_LINE_MIN_VERTICAL_OVERLAP}：
     * 同一行文字的基线一致、笔画高度相近；相邻目标的编号虽然可能只隔十来像素，
     * 却往往落在不同的行高度上。</p>
     */
    private static boolean sameTextLine(Band a, Band b, int glyphGapLocal) {
        final int overlapTop = Math.max(a.minY, b.minY);
        final int overlapBottom = Math.min(a.maxY, b.maxY);
        final int overlap = overlapBottom - overlapTop + 1;
        if (overlap <= 0) {
            return false;
        }
        final int shorter = Math.min(a.height(), b.height());
        if (overlap < shorter * TEXT_SAME_LINE_MIN_VERTICAL_OVERLAP) {
            return false;
        }
        return horizontalGapBetween(a, b) <= glyphGapLocal;
    }

    /** 两个簇之间的水平空隙（空像素个数）；已经重叠时返回 0。 */
    private static int horizontalGapBetween(Band a, Band b) {
        if (a.maxX < b.minX) {
            return b.minX - a.maxX - 1;
        }
        if (b.maxX < a.minX) {
            return a.minX - b.maxX - 1;
        }
        return 0;
    }

    // ------------------------------------------------------------------ 打分

    /**
     * 「这一块像不像一行文字」的得分 [0,1]。三项加权：
     * <ul>
     *   <li><b>行内多段性</b>：每行平均有几段互不相连的笔画。
     *       文字是好几段（{@code id:1963} 一行有 6~10 段），长直线与实心色块恒为 1 段。</li>
     *   <li><b>填充率</b>：笔画像素 / 外接盒面积。文字是<b>镂空</b>的（实测 0.2~0.45），
     *       实心色块与一段实线接近 1。</li>
     *   <li><b>长宽比</b>：文字是 2:1~12:1 的横条；更细是直线，更方是色块。</li>
     * </ul>
     * 三项<b>同时</b>成立才算文字，因此这个分数天然把「框的一条边线」压到 0 附近
     * （多段性 0、填充率≈1、长宽比 75:1）。{@code confidence} 里再叠上位置与对齐度，
     * 但排序不靠它 —— 见 {@link #RANKING}。
     */
    private static float textLikeScore(Band band, float videoWidth, float videoHeight) {
        final float aspect = videoHeight <= 0f ? 0f : videoWidth / videoHeight;
        final float aspectScore;
        if (aspect < TEXT_MIN_ASPECT) {
            aspectScore = clamp01(aspect / TEXT_MIN_ASPECT);
        } else if (aspect > TEXT_MAX_ASPECT) {
            aspectScore = clamp01(1f - (aspect - TEXT_MAX_ASPECT) / TEXT_MAX_ASPECT);
        } else {
            aspectScore = 1f;
        }

        final float meanRunsPerRow = band.rowCount() <= 0
                ? 0f : band.runCount / (float) band.rowCount();
        // 平均 1.2 段/行 → 0；4.2 段/行及以上 → 1
        final float runsScore = clamp01((meanRunsPerRow - 1.2f) / 3f);

        final int box = band.width() * band.height();
        final float fill = box <= 0 ? 1f : band.pixelCount / (float) box;
        // 填充率 0.45 及以下 → 1；0.90 及以上 → 0
        final float fillScore = clamp01(1f - (fill - 0.45f) / 0.45f);

        return RUNS_SCORE_WEIGHT * runsScore
                + FILL_SCORE_WEIGHT * fillScore
                + ASPECT_SCORE_WEIGHT * aspectScore;
    }

    /**
     * 候选与 {@code selectedBox} 的<b>水平对齐度</b> [0,1]：候选宽度里有多大比例落在
     * 框的水平跨度内。
     *
     * <p>它同时照顾「与框左对齐」与「与框中心对齐」两种排版（两种情况下都是 1.0），
     * 真正的作用是<b>把相邻目标的编号推开</b> —— 隔壁那个框的编号多半落在本框跨度之外，
     * 这一项会把它压到接近 0，于是即使它离框顶更近也赢不了排序的第一优先级之外的第二项。
     * 用「比例」而不是「到左边的距离」还有一个好处：它对标签长度不敏感，
     * {@code id:1} 与 {@code id:19633} 得到同一套评价。</p>
     */
    private static float alignmentScore(TargetBoundingBox labelRect, TargetBoundingBox box) {
        final float left = Math.max(labelRect.left, box.left);
        final float right = Math.min(labelRect.right, box.right);
        if (right <= left || labelRect.width() <= 0f) {
            return 0f;
        }
        return clamp01((right - left) / labelRect.width());
    }

    /** 离框顶越近越可信；{@code gap} 为 0（正贴着框顶边）时为 1。 */
    private float proximityScore(float signedGap) {
        final float limit = Math.max(1f, config.labelMaxDistanceFromBoxPx);
        return clamp01(1f - Math.abs(signedGap) / limit);
    }

    // ------------------------------------------------------------------ 坐标与裁剪

    /**
     * 视频矩形 → 分析帧矩形（闭区间整数下标）。
     *
     * <p>几何缺失（或换算失败）时退化为 1:1；换算走
     * {@link VideoCoordinateMapper#mapVideoToFrame}，本类<b>不含</b>任何缩放/旋转/镜像公式。
     * 两个对角点各自折算后取 min/max：90°/270° 旋转下轴对齐矩形仍然是轴对齐的，
     * 因此取 min/max 就够，不需要四角都算。</p>
     */
    private static RoiRect toFrameRect(VideoGeometry geometry, int frameWidth, int frameHeight,
                                       TargetBoundingBox videoRect) {
        final float left;
        final float top;
        final float right;
        final float bottom;
        if (geometry == null) {
            left = videoRect.left;
            top = videoRect.top;
            right = videoRect.right;
            bottom = videoRect.bottom;
        } else {
            final float[] first = VideoCoordinateMapper.mapVideoToFrame(
                    geometry, frameWidth, frameHeight, videoRect.left, videoRect.top);
            final float[] second = VideoCoordinateMapper.mapVideoToFrame(
                    geometry, frameWidth, frameHeight, videoRect.right, videoRect.bottom);
            if (first == null || second == null) {
                left = videoRect.left;
                top = videoRect.top;
                right = videoRect.right;
                bottom = videoRect.bottom;
            } else {
                left = Math.min(first[0], second[0]);
                top = Math.min(first[1], second[1]);
                right = Math.max(first[0], second[0]);
                bottom = Math.max(first[1], second[1]);
            }
        }
        final int x0 = clampInt((int) Math.floor(left), 0, frameWidth - 1);
        final int y0 = clampInt((int) Math.floor(top), 0, frameHeight - 1);
        final int x1 = clampInt((int) Math.ceil(right) - 1, x0, frameWidth - 1);
        final int y1 = clampInt((int) Math.ceil(bottom) - 1, y0, frameHeight - 1);
        return RoiRect.of(x0, y0, x1, y1);
    }

    /**
     * 候选矩形外扩 {@link #LABEL_CROP_PADDING_PX} 像素后夹回搜索区域。
     *
     * <p>入参与出参都是<b>搜索区域局部</b>坐标（0 起），夹取上限就是搜索区域自身的宽高 ——
     * 与真正读进来的那块缓冲一一对应，不需要再多一层坐标换算。</p>
     */
    private static RoiRect paddedCrop(RoiRect label, int searchWidth, int searchHeight) {
        final int left = clampInt(label.left - LABEL_CROP_PADDING_PX, 0, searchWidth - 1);
        final int top = clampInt(label.top - LABEL_CROP_PADDING_PX, 0, searchHeight - 1);
        final int right = clampInt(label.right + LABEL_CROP_PADDING_PX, left, searchWidth - 1);
        final int bottom = clampInt(label.bottom + LABEL_CROP_PADDING_PX, top, searchHeight - 1);
        return RoiRect.of(left, top, right, bottom);
    }

    private static String describe(LabelCandidate selected, int candidateCount) {
        if (candidateCount <= 1) {
            return "only-candidate text=" + selected.textLikeScore;
        }
        return "best-of-" + candidateCount
                + " text=" + selected.textLikeScore
                + " align=" + selected.alignmentScore
                + " dist=" + selected.distanceToBox;
    }

    private static int clampInt(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, value));
    }

    private static long elapsedMs(long startNs) {
        final long deltaNs = System.nanoTime() - startNs;
        return deltaNs <= 0L ? 0L : deltaNs / 1_000_000L;
    }

    // ------------------------------------------------------------------ 辅助类型

    /**
     * 一行文字（或它的一段）的外接盒 + 形态统计量。可变、仅在本类内部使用，
     * 不对外暴露 —— 它是一个纯计算中间量，没有跨线程或跨阶段的生命期。
     */
    private static final class Band {

        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;

        /** 最近一次加入笔画的<b>行号</b>，用于判断"还能不能继续长大"。 */
        int lastRow = -1;

        /** 笔画像素总数（只用短段，长直线段已在分组前剔除）。 */
        int pixelCount;

        /** 段数（一行里连续的一小截笔画算一段）。 */
        int runCount;

        /**
         * 有笔画的行集合。用位图而不是计数，是因为第二遍合并两个簇时行范围会取并集，
         * 直接相加会把重叠的行算两遍 —— 而"每行平均几段"正是拿它当分母的。
         */
        final BitSet rows = new BitSet();

        void add(int y, int start, int end) {
            rows.set(y);
            lastRow = y;
            if (y < minY) {
                minY = y;
            }
            if (y > maxY) {
                maxY = y;
            }
            if (start < minX) {
                minX = start;
            }
            if (end > maxX) {
                maxX = end;
            }
            pixelCount += end - start + 1;
            runCount++;
        }

        /** 并入另一个簇（第二遍：字形簇 → 文本行）。 */
        void absorb(Band other) {
            if (other.minX < minX) {
                minX = other.minX;
            }
            if (other.maxX > maxX) {
                maxX = other.maxX;
            }
            if (other.minY < minY) {
                minY = other.minY;
            }
            if (other.maxY > maxY) {
                maxY = other.maxY;
            }
            if (other.lastRow > lastRow) {
                lastRow = other.lastRow;
            }
            rows.or(other.rows);
            pixelCount += other.pixelCount;
            runCount += other.runCount;
        }

        /**
         * 这段与此簇已覆盖的水平范围是否够近。
         *
         * @param maxEmptyPx 允许的空隙<b>像素个数上限</b>（含）——「空 2 像素」就是
         *                   {@code start == maxX + 3}，所以比较时用的是 {@code maxEmptyPx + 1}
         */
        boolean closeToHorizontally(int start, int end, int maxEmptyPx) {
            return start <= maxX + maxEmptyPx + 1 && end >= minX - maxEmptyPx - 1;
        }

        int width() {
            return maxX - minX + 1;
        }

        int height() {
            return maxY - minY + 1;
        }

        /** 有笔画的行数。 */
        int rowCount() {
            return rows.cardinality();
        }
    }
}
