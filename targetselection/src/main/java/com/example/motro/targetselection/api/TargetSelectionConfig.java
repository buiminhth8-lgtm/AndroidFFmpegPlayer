package com.example.motro.targetselection.api;

import com.example.motro.targetselection.frame.FrameCaptureRequest;
import com.example.motro.targetselection.vision.BlueColorThresholds;

/**
 * 目标选择的默认策略，不可变。用 {@link #toBuilder()} 做局部调整，避免 setter 带来的可变共享。
 *
 * <p>与 {@link FrameCaptureRequest} 的分工：本类是**长期默认策略**（控制器用它构造每次的取帧请求、
 * 决定是否汇报未知 id）；请求对象是**单次调用参数**。</p>
 *
 * <p><b>两类配置，动机不同</b>：</p>
 * <ul>
 *   <li><b>管线策略</b>（{@code enabled} / 取帧预算 / 帧龄 / 置信度 / id 汇报）：决定
 *       "要不要做、用多久做"。</li>
 *   <li><b>视觉调参</b>（{@code roiRadius*} / {@code blueHue*} / {@code minSaturation} /
 *       {@code minValue} / {@code minBluePixelCount} / {@code maskDenoiseMinNeighbors}）：
 *       决定"看多大范围、什么颜色算蓝"。这些值<b>必然</b>会随真实无人机画面/推流端调色而变，
 *       所以它们在这里集中暴露，而不是散落成算法里的魔数 —— 现场调参只改这一个对象。</li>
 *   <li><b>标签区域调参</b>（{@code labelSearchHeightPx} / {@code labelHorizontalPaddingPx} /
 *       {@code labelVerticalPaddingPx} / {@code labelMaxDistanceFromBoxPx} /
 *       {@code minLabelCandidate*} / {@code maxLabelCandidateCount}，Slice 7）：
 *       决定"在框顶附近的哪一带、以多大的最小尺寸、找 {@code id:xxxx}"。
 *       换推流端（字号/编号位置/框线粗细变了）时只需要改这一组，
 *       算法里没有任何写死的绝对坐标。</li>
 * </ul>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享。</p>
 */
public final class TargetSelectionConfig {

    /** {@link #maxFrameLongSidePx} 的取值：不缩放。 */
    public static final int NO_SCALE_LIMIT = 0;

    /** {@link #maxFrameAgeNs} 的取值：不限帧龄。 */
    public static final long NO_FRAME_AGE_LIMIT_NS = 0L;

    /**
     * {@link #roiRadiusX}/{@link #roiRadiusY} 的默认值（视频像素）。
     *
     * <p>取 200 的理由：一个无人机目标框的边长通常在数百像素量级，点击点一般落在框内，
     * 半径 200 足以把整条边（含框线粗细与轻微抖动）包进 ROI；再用「只分析 ROI」把
     * 待处理像素从 1080p 的 200 万压到 16 万。范围 150~250 都能工作，越大越不容易漏掉
     * 框边、但越慢。</p>
     */
    public static final int DEFAULT_ROI_RADIUS = 200;

    /** {@link #minBluePixelCount} 的默认值：低于它视为没有蓝色。 */
    public static final int DEFAULT_MIN_BLUE_PIXEL_COUNT = 12;

    /** {@link #maskDenoiseMinNeighbors} 的默认值：删除 8 邻域内没有任何蓝像素的孤立点。 */
    public static final int DEFAULT_MASK_DENOISE_MIN_NEIGHBORS = 1;

    // ---------------------------------------------------------------- 目标框候选恢复（Slice 5）

    /** {@link #maxBoxWidth}/{@link #maxBoxHeight} 的取值：不设上限（交给长宽比与边覆盖率约束）。 */
    public static final int NO_BOX_SIZE_LIMIT = 0;

    /**
     * {@link #minBoxWidth} 的默认值（<b>视频</b>像素）。
     *
     * <p>取 40 的理由有两层：一是它必须小于真实目标框（无人机画面里目标框通常上百像素），
     * 二是它和 {@link #minBoxHeight} 一起承担<b>排除 {@code id:xxxx} 文字</b>的职责 ——
     * 推流端画在框边的编号文字只有几十像素高，只要最小边长大于字号，
     * 「一行文字」就永远凑不出合法矩形（没有足够长的垂直边，也没有第二组水平边）。</p>
     */
    public static final int DEFAULT_MIN_BOX_WIDTH = 40;

    /**
     * {@link #minBoxHeight} 的默认值（<b>视频</b>像素）。
     *
     * <p>这是排除文字块的<b>主要</b>判据：编号文字的字高一般 &lt; 30px，
     * 而框高通常远大于它。注意它按「视频像素」计，因此与分辨率无关；
     * 换更大/更小的推流分辨率时不需要改动。</p>
     */
    public static final int DEFAULT_MIN_BOX_HEIGHT = 40;

    /**
     * {@link #maxBoxAspectRatio} 的默认值：长边/短边不超过 5。
     *
     * <p>目标框（尤其是人物/车辆）接近方形到 3:1；出现 10:1 的"矩形"几乎一定是
     * 两条平行线被误配成框（例如上下两条无关的横线，或文字行被当成一条边）。</p>
     */
    public static final float DEFAULT_MAX_BOX_ASPECT_RATIO = 5f;

    /**
     * {@link #minEdgeCoverage} 的默认值：一条边至少 50% 的像素是蓝的才算"这条边存在"。
     *
     * <p>取 0.5 而不是 0.9 是<b>刻意的</b>：推流端细蓝线经 HEVC + 4:2:0 之后会断裂、
     * 掉色，要求 90% 覆盖等于要求"完整的闭合轮廓"，那正是本 Slice 明确不要的严格条件。
     * 一半覆盖既能容忍断裂，又足以把"偶发几个蓝点"排除在"边"之外。</p>
     */
    public static final float DEFAULT_MIN_EDGE_COVERAGE = 0.5f;

    /** {@link #minPresentEdges} 的默认值：至少两条边达标（允许另外两条缺失或断裂）。 */
    public static final int DEFAULT_MIN_PRESENT_EDGES = 2;

    /**
     * {@link #maxEdgeThicknessPx} 的默认值（<b>视频</b>像素）：一条边线最多 16px 厚。
     *
     * <p>这是区分「细线框」与「一整块蓝色区域」的关键判据，也是本 Slice 适配
     * 推流端叠加层的核心：目标框是几像素宽的<b>空心</b>细线，而画面里的蓝色物体
     * （蓝色车身、蓝色屏幕、蓝色天空）是一整块<b>实心</b>区域。实心区域在投影上表现为
     * "连续很多行都有很高的覆盖率"，于是它形成的"边带"会厚达几十上百像素 ——
     * 直接被这条上限挡掉。</p>
     */
    public static final int DEFAULT_MAX_EDGE_THICKNESS_PX = 16;

    /**
     * {@link #minEdgeSolidity} 的默认值：一条边带在<b>它自己的范围内</b>至少 50% 是蓝的，
     * 才算一段"线"。
     *
     * <p>这条判据切开的是两类<b>看着一样细</b>的东西：真线，与"稀疏碎片"（点状噪声、
     * 虚线残段、断续的碎片）。真实框线的实心度≈1。</p>
     *
     * <p><b>它的职责边界（重要）</b>：横穿这条边的另一条框线留下的几个像素<b>已经</b>在
     * {@code TargetBoxDetector#extractBands} 里被排除在"本边范围"之外了，因此不会再来
     * 稀释实心度。这一分工是必需的 —— 早期版本把横穿像素也算进范围，结果「大框套小框」
     * 里内层框的真边（110px）被外框两条竖边把范围撑成 270px，实心度掉到 0.44 而被丢弃，
     * 内层框永远无法成为候选。所以本阈值现在只回答"这条边自己实不实"。</p>
     *
     * <p>0.5 对真实情况很宽松 —— 一条被 HEVC 吃掉近一半像素的框线仍能通过
     * （实测断裂样本的上边线实心度 0.87）。</p>
     */
    public static final float DEFAULT_MIN_EDGE_SOLIDITY = 0.5f;

    /**
     * {@link #minBoxConfidence} 的默认值。
     *
     * <p>置信度 = 边覆盖率均值 × (达标边数/4)，所以 0.3 大致等价于
     * "四条边里<b>至少三条基本达标</b>"。为什么不取 0.25（正好两条边）：
     * 两条平行线（比如两段文字下划线、两条无关横线）配出的"框"覆盖率均值是 0.5、
     * 达标边数 2，刚好拿满 0.25 —— 那是个稳定的误检源。抬到 0.3 以后，
     * 「三边齐全、一边缺失/断裂」仍然通过（0.75 × 3/4 = 0.5625），
     * 而"只有两条边"的形状被挡住。</p>
     */
    public static final float DEFAULT_MIN_BOX_CONFIDENCE = 0.3f;

    /**
     * {@link #touchTolerancePx} 的默认值（<b>视频</b>像素）。
     *
     * <p>手指点击有 ±8~10px 的抖动，而目标框往往只有几十像素高 —— 不设容差
     * 会让"明明点在框上"变成 MISS。12px 大致是半个指头的误差量级。
     * 注意容差只影响<b>是否命中</b>，返回给上层的永远是<b>原始</b>框，
     * 否则下游会拿到一个比实际大一圈的坐标。</p>
     */
    public static final int DEFAULT_TOUCH_TOLERANCE_PX = 12;

    /**
     * {@link #maxCandidateCount} 的默认值：单次检测最多保留 16 个候选。
     *
     * <p>这是对<b>异常掩码</b>的兜底：噪点密集时投影会切出很多"边带"，
     * 两两配对是组合数增长。限制候选数既保证单次耗时可控，
     * 也避免把一堆垃圾候选丢给选择逻辑。</p>
     */
    public static final int DEFAULT_MAX_CANDIDATE_COUNT = 16;

    // ---------------------------------------------------------------- 多目标选择策略（Slice 6）

    /**
     * {@link #exactHitWeight} 的默认值：严格包含命中在总分上的加分。
     *
     * <p>取 1.0 与其它几项同量级，使"点在框里"这一事实本身就足以压过中心距离与面积的差异。
     * 注意排序时 {@code EXACT_HIT &gt; TOLERANCE_HIT} 是<b>硬约束</b>
     * （见 {@link com.example.motro.targetselection.vision.TargetCandidateSelector}），
     * 不依赖这个权重；把它设为 0 也不会让容差命中抢走严格的包含命中，
     * 只会让 {@code totalScore} 这个数字少一项信息。</p>
     */
    public static final float DEFAULT_EXACT_HIT_WEIGHT = 1f;

    /**
     * {@link #centerDistanceWeight} 的默认值：触摸点到框中心的（归一化）距离惩罚。
     *
     * <p>与面积同量级：这是"同时包含时优先更接近触摸点中心的目标"这条产品直觉的权重。</p>
     */
    public static final float DEFAULT_CENTER_DISTANCE_WEIGHT = 1f;

    /**
     * {@link #areaWeight} 的默认值：（归一化）面积惩罚，实现"优先更小、更具体的框"。
     *
     * <p>权重与中心距离持平是刻意的：嵌套框场景里"小框更具体"与"大框更居中"经常同时出现，
     * 两边都保持 1.0 时，谁胜出取决于本样本的真实几何，而不是取决于某个预设偏好。</p>
     */
    public static final float DEFAULT_AREA_WEIGHT = 1f;

    /**
     * {@link #confidenceWeight}/{@link #completenessWeight} 的默认值：框质量的辅助加分。
     *
     * <p>刻意取中心距离/面积的一半：框线画得全不全属于"这个框可不可信"，
     * 而用户想点哪个框属于"几何关系"，后者更直接。辅助项加起来最多 1.0，
     * 不足以单独扭转一次明确的几何劣势。</p>
     */
    public static final float DEFAULT_CONFIDENCE_WEIGHT = 0.5f;

    /** {@link #completenessWeight} 的默认值。见 {@link #DEFAULT_CONFIDENCE_WEIGHT}。 */
    public static final float DEFAULT_COMPLETENESS_WEIGHT = 0.5f;

    /** {@link #preferSmallerBox} 的默认值：开启"更小、更具体优先"。 */
    public static final boolean DEFAULT_PREFER_SMALLER_BOX = true;

    /**
     * {@link #maxRankedCandidates} 的默认值：单次选择最多保留 8 个已排名候选。
     *
     * <p>一次点击能同时命中的框本来就少（个位数），这个上限主要是给
     * <b>异常掩码</b>兜底：宁可丢掉第 9 名之后的名次明细，也不要让调试信息无限膨胀。
     * rank 0 永远保留，因此上限不会影响最终选中结果。</p>
     */
    public static final int DEFAULT_MAX_RANKED_CANDIDATES = 8;

    // ---------------------------------------------------------------- 标签区域提取（Slice 7）

    /**
     * {@link #labelSearchHeightPx} 的默认值（<b>视频</b>像素）：搜索带向框顶<b>上方</b>
     * 延伸的高度。
     *
     * <p>取 48 的理由：推流端的编号文字字高实测在 10~18px 量级（1080p），并且它<b>紧贴</b>
     * 框顶边（间隙 0~2px）甚至与之重叠；48px 足以覆盖"字高 + 若干像素的位置抖动"，
     * 而多算的高度只是多扫几行像素 —— 搜索区域本来就窄，放宽的代价可以忽略。
     * 它是"搜索"的上界，不是"接受"的上界：把远方另一个目标的编号也扫进来之后，
     * 由 {@link #labelMaxDistanceFromBoxPx} 与水平对齐度把它们筛掉。</p>
     */
    public static final int DEFAULT_LABEL_SEARCH_HEIGHT_PX = 48;

    /**
     * {@link #labelHorizontalPaddingPx} 的默认值（视频像素）：搜索带在框的左右各外扩多少。
     *
     * <p>编号一般从框的左边界起画、宽度远小于框宽，所以左右各 16px 主要是给
     * 「编号左端略超出框左边界」和「检测出的框左右各有几像素误差」留余量。
     * 不宜过大：外扩太多会把隔壁目标框的编号也扫进来（虽然排序能把它排到后面，
     * 但白扫的像素是纯浪费）。</p>
     */
    public static final int DEFAULT_LABEL_HORIZONTAL_PADDING_PX = 16;

    /**
     * {@link #labelVerticalPaddingPx} 的默认值（视频像素）：搜索带向框顶<b>下方</b>
     * 压进框内的深度。
     *
     * <p>必须 &gt; 0：实测（1080p 真实推流画面）编号文字的下沿会与框顶边线<b>重叠</b>
     * 若干像素（文字在 y=700~715，框顶边在 y=712~715）。若搜索带严格止于
     * {@code box.top}，文字最下面那几行笔画就被切掉了 —— 外接盒高度随之偏小、
     * 形态统计被扭曲。压进 6px 足以覆盖这种重叠，又不会把框的顶边线整条收进来
     * （收进来也无妨：那是一条与框等宽的横线，会被当作框边线剔除，见
     * {@code LabelRegionExtractor}）。</p>
     */
    public static final int DEFAULT_LABEL_VERTICAL_PADDING_PX = 6;

    /**
     * {@link #labelMaxDistanceFromBoxPx} 的默认值（视频像素）：候选被接受时，
     * 它到 {@code box.top} 的最大垂直距离。
     *
     * <p>为什么比 {@link #DEFAULT_LABEL_SEARCH_HEIGHT_PX} 小：两者分工不同 ——
     * 「搜索」可以放宽（多扫几行几乎不花钱，还能容忍检测出的框顶有几像素偏差），
     * 但「接受」要收紧，否则框上方另一个目标（或另一层叠加文字）的编号会被当成
     * 本框的标签。真实编号到框顶的距离实测 ≤ 3px，32px 已是十倍的余量。</p>
     */
    public static final int DEFAULT_LABEL_MAX_DISTANCE_FROM_BOX_PX = 32;

    /**
     * {@link #minLabelCandidateWidth} 的默认值（<b>视频</b>像素）。
     *
     * <p>取 16：编号至少是 {@code id:1} 这种长度，在 1080p 上宽 40px 上下；
     * 16px 用来挡掉压缩后残留的零星蓝色碎片（它们凑不出这么宽的连续结构）。
     * 按视频像素计，因此换分辨率不需要改。</p>
     */
    public static final int DEFAULT_MIN_LABEL_CANDIDATE_WIDTH = 16;

    /**
     * {@link #minLabelCandidateHeight} 的默认值（<b>视频</b>像素）。
     *
     * <p>取 6：这是<b>排除框边线</b>的一条硬判据。框线厚度实测 4~12px，
     * 文字行高实测 10~18px。压下限到 6 以后，"薄于 6px 的一条横线"直接出局，
     * 而最矮的真实编号（强压缩后字高掉到 8~10px）仍然通过。
     * 它与 {@code LabelRegionExtractor} 里"剔除长横段"是两道互补的防线：
     * 那道按<b>长度</b>挡、这道按<b>厚度</b>挡，粗框线只能靠前者、细框线两者都能挡。</p>
     */
    public static final int DEFAULT_MIN_LABEL_CANDIDATE_HEIGHT = 6;

    /**
     * {@link #maxLabelCandidateCount} 的默认值：单次提取最多保留 8 个候选。
     *
     * <p>一次点击附近真正像编号的文字块本来就没几个；这个上限是给<b>异常画面</b>
     * 兜底（噪声密集时行带会被切得很碎）。rank 0 永远保留，因此上限不影响最终结果，
     * 只限制调试信息膨胀。</p>
     */
    public static final int DEFAULT_MAX_LABEL_CANDIDATE_COUNT = 8;

    // ---------------------------------------------------------------- 编号识别（Slice 8）

    /**
     * {@link #labelUpscaleFactor} 的默认值：标签 ROI 在识别前放大 3 倍。
     *
     * <p>取 3 的理由：真实编号的笔画宽度只有 1~2px（1080p 实测字高 10~18px），
     * 不放大时任何形态判据都被"一个像素的抖动"淹没；放大到 4 倍以上则
     * 预处理耗时按平方增长，而识别率不再明显提升（最近邻放大不会创造新信息）。
     * 3 倍是"够用且便宜"的折中。允许 1（不放大）到 4。</p>
     */
    public static final int DEFAULT_LABEL_UPSCALE_FACTOR = 3;

    /**
     * {@link #minIdConfidence} 的默认值：编号识别的最低<b>规则置信度</b>。
     *
     * <p>取 0.55 而不是 0.8，是因为它衡量的<b>不是概率</b>（见
     * {@code TargetIdRecognitionResult#confidence} 的语义说明）：它是
     * 「字形匹配得有多像」乘上「切出的块有多少被认成了已知字符」，
     * 真实压缩样本实测在 0.6~0.85；而"某个字符没认出来"（记成 {@code ?}）
     * 会把它直接拉到 0.5 以下。0.55 于是恰好切在"全都认出来了"
     * 与"有一个字符不确定"之间 —— 不确定时上游会拿到
     * {@code LOW_CONFIDENCE}（带 {@code rawText}），比一个可疑的编号好。</p>
     */
    public static final float DEFAULT_MIN_ID_CONFIDENCE = 0.55f;

    /**
     * {@link #idMinDigits} 的默认值：编号最少 1 位。
     *
     * <p>为什么不设成 2 或 3：编号是产品语义（{@code id:1} 也是合法目标），
     * 压掉短编号会把合法的低位目标判成"读不出来"。真正需要挡掉的
     * "单个数字的噪声笔画"由置信度与颜色筛选负责。</p>
     */
    public static final int DEFAULT_ID_MIN_DIGITS = 1;

    /**
     * {@link #idMaxDigits} 的默认值：编号最多 6 位。
     *
     * <p>取 6 的理由：它挡的不是"合法但很长的编号"，而是<b>把两行/两个编号连起来</b>
     * 这种典型错误 —— 那是本层最容易发生的失误（字形空隙与字号同量级，
     * 切分稍一放宽就会并排）。6 位（最多 999999 个目标）对任何真实产品都够用，
     * 而连起来的两个编号通常 ≥ 8 位。</p>
     */
    public static final int DEFAULT_ID_MAX_DIGITS = 6;

    /** 是否启用。false 时控制器直接返回 {@code CANCELLED}，完全不触达帧来源与识别实现。 */
    public final boolean enabled;

    /** 每次取帧的时间预算（毫秒），会被写入 {@link FrameCaptureRequest#timeoutMs}。 */
    public final long captureTimeoutMs;

    /** 分析用图最长边上限（像素，0 = 不缩放），决定精度/耗时的取舍。 */
    public final int maxFrameLongSidePx;

    /** 可接受帧龄（纳秒，0 = 不限）；用于避免分析到过旧的帧。 */
    public final long maxFrameAgeNs;

    /** 最小置信度 [0,1]；低于该值的候选按 {@code MISS} 处理。 */
    public final float minConfidence;

    /**
     * 是否汇报“命中但 id 未知”。true → {@code HIT_ID_UNKNOWN}；
     * false → 降级为 {@code MISS}（某些上层只认 id，不希望收到半成品结果）。
     */
    public final boolean reportUnknownTargetId;

    // ---------------------------------------------------------------- 视觉调参（Slice 4）

    /** ROI 水平半径（<b>视频</b>像素，&gt; 0）。点击点两侧各取这么多像素。 */
    public final int roiRadiusX;

    /** ROI 垂直半径（<b>视频</b>像素，&gt; 0）。 */
    public final int roiRadiusY;

    /** 蓝色色调下界（角度，[0,360)）。见 {@link BlueColorThresholds}。 */
    public final float blueHueMinDegrees;

    /** 蓝色色调上界（角度，[0,360)）。小于下界时表示区间跨 0° 环绕。 */
    public final float blueHueMaxDegrees;

    /** 蓝色最低饱和度 [0,1]。 */
    public final float minSaturation;

    /** 蓝色最低明度 [0,1]。 */
    public final float minValue;

    /** 蓝色像素数低于该值即视为没有蓝色（&ge; 0）。 */
    public final int minBluePixelCount;

    /**
     * 掩码去噪阈值：8 邻域内蓝像素少于该值的像素会被删除（[0,8]，0 = 不去噪）。
     * 默认 1（只删除完全孤立的噪声点），刻意不做腐蚀 —— 见
     * {@link com.example.motro.targetselection.vision.BlueMaskDetector}。
     */
    public final int maskDenoiseMinNeighbors;

    /**
     * 是否在分析结果里保留 ROI 原始像素（仅供调试；默认 false）。
     * 打开后会多一块 ROI 大小的数组，因此<b>不要</b>在正式运行路径上长期开启。
     */
    public final boolean debugMaskEnabled;

    // ---------------------------------------------------------------- 目标框候选恢复（Slice 5）

    /** 候选框最小宽度（<b>视频</b>像素，&gt; 0）。见 {@link #DEFAULT_MIN_BOX_WIDTH}。 */
    public final int minBoxWidth;

    /** 候选框最小高度（<b>视频</b>像素，&gt; 0）。见 {@link #DEFAULT_MIN_BOX_HEIGHT}。 */
    public final int minBoxHeight;

    /** 候选框最大宽度（视频像素，{@link #NO_BOX_SIZE_LIMIT} = 不限）。 */
    public final int maxBoxWidth;

    /** 候选框最大高度（视频像素，{@link #NO_BOX_SIZE_LIMIT} = 不限）。 */
    public final int maxBoxHeight;

    /** 长边/短边上限（&ge; 1）。 */
    public final float maxBoxAspectRatio;

    /** 一条边至少多大比例的像素是蓝的，才算「这条边存在」（(0,1]）。 */
    public final float minEdgeCoverage;

    /** 达标边数下限（[1,4]）。 */
    public final int minPresentEdges;

    /** 边线最大厚度（视频像素，&ge; 1）：超过它就不是细线框，而是实心色块。 */
    public final int maxEdgeThicknessPx;

    /**
     * 一条边带在自身范围内的<b>实心度</b>下限（(0,1]）：像素占比低于它就不算"线"。
     * 用来挡掉点状噪声、虚线残段这类稀疏结构。见 {@link #DEFAULT_MIN_EDGE_SOLIDITY}。
     */
    public final float minEdgeSolidity;

    /** 候选框最低置信度 [0,1]；低于该值的候选被丢弃。 */
    public final float minBoxConfidence;

    /** 触摸命中容差（<b>视频</b>像素，&ge; 0）：判定时把框向外扩这么多，但返回的仍是原始框。 */
    public final int touchTolerancePx;

    /** 单次检测保留的候选上限（&ge; 1），防异常掩码产生组合爆炸。 */
    public final int maxCandidateCount;

    // ---------------------------------------------------------------- 多目标选择策略（Slice 6）

    /** 严格包含命中（{@code EXACT_HIT}）在总分上的加分（&ge; 0）。 */
    public final float exactHitWeight;

    /** 归一化中心距离的惩罚权重（&ge; 0）。 */
    public final float centerDistanceWeight;

    /** 归一化面积的惩罚权重（&ge; 0）；仅在 {@link #preferSmallerBox} 为 true 时生效。 */
    public final float areaWeight;

    /** 框自身置信度的加分权重（&ge; 0）。 */
    public final float confidenceWeight;

    /** 框自身完整度的加分权重（&ge; 0）。 */
    public final float completenessWeight;

    /**
     * 是否让"更小、更具体"的框优先。true 时面积越小总分越高
     * （用于"大框套小框"这类嵌套/重叠场景）；false 时面积完全不参与评分。
     */
    public final boolean preferSmallerBox;

    /** 单次选择保留的已排名候选上限（&ge; 1）；rank 0 永远保留。 */
    public final int maxRankedCandidates;

    // ---------------------------------------------------------------- 标签区域提取（Slice 7）

    /** 标签搜索带在框顶<b>上方</b>的高度（视频像素，&gt; 0）。见 {@link #DEFAULT_LABEL_SEARCH_HEIGHT_PX}。 */
    public final int labelSearchHeightPx;

    /** 标签搜索带在框<b>左右</b>各外扩的宽度（视频像素，&ge; 0）。 */
    public final int labelHorizontalPaddingPx;

    /** 标签搜索带向框顶<b>下方</b>压进框内的深度（视频像素，&ge; 0）。 */
    public final int labelVerticalPaddingPx;

    /** 候选被接受时到 {@code box.top} 的最大垂直距离（视频像素，&ge; 0）。 */
    public final int labelMaxDistanceFromBoxPx;

    /** 标签候选的最小宽度（视频像素，&gt; 0）。 */
    public final int minLabelCandidateWidth;

    /** 标签候选的最小高度（视频像素，&gt; 0）。 */
    public final int minLabelCandidateHeight;

    /** 单次标签提取保留的候选上限（&ge; 1）；rank 0 永远保留。 */
    public final int maxLabelCandidateCount;

    // ---------------------------------------------------------------- 编号识别（Slice 8）

    /**
     * 标签 ROI 在识别前放大的倍数（[1,4]）。见 {@link #DEFAULT_LABEL_UPSCALE_FACTOR}。
     */
    public final int labelUpscaleFactor;

    /** 编号识别的最低规则置信度 [0,1]；低于它报 {@code LOW_CONFIDENCE}。 */
    public final float minIdConfidence;

    /** 可接受的编号最少位数（&ge; 1）。 */
    public final int idMinDigits;

    /** 可接受的编号最多位数（&ge; idMinDigits）。见 {@link #DEFAULT_ID_MAX_DIGITS}。 */
    public final int idMaxDigits;

    private TargetSelectionConfig(Builder builder) {
        this.enabled = builder.enabled;
        this.captureTimeoutMs = builder.captureTimeoutMs;
        this.maxFrameLongSidePx = builder.maxFrameLongSidePx;
        this.maxFrameAgeNs = builder.maxFrameAgeNs;
        this.minConfidence = builder.minConfidence;
        this.reportUnknownTargetId = builder.reportUnknownTargetId;
        this.roiRadiusX = builder.roiRadiusX;
        this.roiRadiusY = builder.roiRadiusY;
        this.blueHueMinDegrees = builder.blueHueMinDegrees;
        this.blueHueMaxDegrees = builder.blueHueMaxDegrees;
        this.minSaturation = builder.minSaturation;
        this.minValue = builder.minValue;
        this.minBluePixelCount = builder.minBluePixelCount;
        this.maskDenoiseMinNeighbors = builder.maskDenoiseMinNeighbors;
        this.debugMaskEnabled = builder.debugMaskEnabled;
        this.minBoxWidth = builder.minBoxWidth;
        this.minBoxHeight = builder.minBoxHeight;
        this.maxBoxWidth = builder.maxBoxWidth;
        this.maxBoxHeight = builder.maxBoxHeight;
        this.maxBoxAspectRatio = builder.maxBoxAspectRatio;
        this.minEdgeCoverage = builder.minEdgeCoverage;
        this.minPresentEdges = builder.minPresentEdges;
        this.maxEdgeThicknessPx = builder.maxEdgeThicknessPx;
        this.minEdgeSolidity = builder.minEdgeSolidity;
        this.minBoxConfidence = builder.minBoxConfidence;
        this.touchTolerancePx = builder.touchTolerancePx;
        this.maxCandidateCount = builder.maxCandidateCount;
        this.exactHitWeight = builder.exactHitWeight;
        this.centerDistanceWeight = builder.centerDistanceWeight;
        this.areaWeight = builder.areaWeight;
        this.confidenceWeight = builder.confidenceWeight;
        this.completenessWeight = builder.completenessWeight;
        this.preferSmallerBox = builder.preferSmallerBox;
        this.maxRankedCandidates = builder.maxRankedCandidates;
        this.labelSearchHeightPx = builder.labelSearchHeightPx;
        this.labelHorizontalPaddingPx = builder.labelHorizontalPaddingPx;
        this.labelVerticalPaddingPx = builder.labelVerticalPaddingPx;
        this.labelMaxDistanceFromBoxPx = builder.labelMaxDistanceFromBoxPx;
        this.minLabelCandidateWidth = builder.minLabelCandidateWidth;
        this.minLabelCandidateHeight = builder.minLabelCandidateHeight;
        this.maxLabelCandidateCount = builder.maxLabelCandidateCount;
        this.labelUpscaleFactor = builder.labelUpscaleFactor;
        this.minIdConfidence = builder.minIdConfidence;
        this.idMinDigits = builder.idMinDigits;
        this.idMaxDigits = builder.idMaxDigits;
    }

    /**
     * 默认策略：启用、300ms 取帧预算、分析图最长边 640、不限帧龄、不设置信度门槛、汇报未知 id；
     * ROI 半径 200、蓝色 200°~270° / S≥0.30 / V≥0.20、去噪阈值 1、蓝像素下限 12、debug 关闭；
     * 多目标选择用「EXACT 优先 → 中心距离 1.0 → 面积 1.0（更小优先）→
     * 置信度/完整度各 0.5」的加权分，最多保留 8 个已排名候选；
     * 标签提取在框顶上方 48px、左右各 16px、压进框顶 6px 的带状区域里搜索，
     * 只接受离框顶 32px 以内、尺寸不小于 16×6 的视频像素块，最多保留 8 个候选；
     * 编号识别把标签 ROI 放大 3 倍后做字形匹配，只接受 1~6 位、置信度不低于 0.55 的编号。
     */
    public static TargetSelectionConfig defaults() {
        return new Builder().build();
    }

    public Builder toBuilder() {
        return new Builder()
                .enabled(enabled)
                .captureTimeoutMs(captureTimeoutMs)
                .maxFrameLongSidePx(maxFrameLongSidePx)
                .maxFrameAgeNs(maxFrameAgeNs)
                .minConfidence(minConfidence)
                .reportUnknownTargetId(reportUnknownTargetId)
                .roiRadiusX(roiRadiusX)
                .roiRadiusY(roiRadiusY)
                .blueHueMinDegrees(blueHueMinDegrees)
                .blueHueMaxDegrees(blueHueMaxDegrees)
                .minSaturation(minSaturation)
                .minValue(minValue)
                .minBluePixelCount(minBluePixelCount)
                .maskDenoiseMinNeighbors(maskDenoiseMinNeighbors)
                .debugMaskEnabled(debugMaskEnabled)
                .minBoxWidth(minBoxWidth)
                .minBoxHeight(minBoxHeight)
                .maxBoxWidth(maxBoxWidth)
                .maxBoxHeight(maxBoxHeight)
                .maxBoxAspectRatio(maxBoxAspectRatio)
                .minEdgeCoverage(minEdgeCoverage)
                .minPresentEdges(minPresentEdges)
                .maxEdgeThicknessPx(maxEdgeThicknessPx)
                .minEdgeSolidity(minEdgeSolidity)
                .minBoxConfidence(minBoxConfidence)
                .touchTolerancePx(touchTolerancePx)
                .maxCandidateCount(maxCandidateCount)
                .exactHitWeight(exactHitWeight)
                .centerDistanceWeight(centerDistanceWeight)
                .areaWeight(areaWeight)
                .confidenceWeight(confidenceWeight)
                .completenessWeight(completenessWeight)
                .preferSmallerBox(preferSmallerBox)
                .maxRankedCandidates(maxRankedCandidates)
                .labelSearchHeightPx(labelSearchHeightPx)
                .labelHorizontalPaddingPx(labelHorizontalPaddingPx)
                .labelVerticalPaddingPx(labelVerticalPaddingPx)
                .labelMaxDistanceFromBoxPx(labelMaxDistanceFromBoxPx)
                .minLabelCandidateWidth(minLabelCandidateWidth)
                .minLabelCandidateHeight(minLabelCandidateHeight)
                .maxLabelCandidateCount(maxLabelCandidateCount)
                .labelUpscaleFactor(labelUpscaleFactor)
                .minIdConfidence(minIdConfidence)
                .idMinDigits(idMinDigits)
                .idMaxDigits(idMaxDigits);
    }

    @Override
    public String toString() {
        return "TargetSelectionConfig{enabled=" + enabled
                + ", captureTimeoutMs=" + captureTimeoutMs
                + ", maxFrameLongSidePx=" + maxFrameLongSidePx
                + ", maxFrameAgeNs=" + maxFrameAgeNs
                + ", minConfidence=" + minConfidence
                + ", reportUnknownTargetId=" + reportUnknownTargetId
                + ", roiRadiusX=" + roiRadiusX
                + ", roiRadiusY=" + roiRadiusY
                + ", blueHueMinDegrees=" + blueHueMinDegrees
                + ", blueHueMaxDegrees=" + blueHueMaxDegrees
                + ", minSaturation=" + minSaturation
                + ", minValue=" + minValue
                + ", minBluePixelCount=" + minBluePixelCount
                + ", maskDenoiseMinNeighbors=" + maskDenoiseMinNeighbors
                + ", debugMaskEnabled=" + debugMaskEnabled
                + ", minBoxWidth=" + minBoxWidth
                + ", minBoxHeight=" + minBoxHeight
                + ", maxBoxWidth=" + maxBoxWidth
                + ", maxBoxHeight=" + maxBoxHeight
                + ", maxBoxAspectRatio=" + maxBoxAspectRatio
                + ", minEdgeCoverage=" + minEdgeCoverage
                + ", minPresentEdges=" + minPresentEdges
                + ", maxEdgeThicknessPx=" + maxEdgeThicknessPx
                + ", minEdgeSolidity=" + minEdgeSolidity
                + ", minBoxConfidence=" + minBoxConfidence
                + ", touchTolerancePx=" + touchTolerancePx
                + ", maxCandidateCount=" + maxCandidateCount
                + ", exactHitWeight=" + exactHitWeight
                + ", centerDistanceWeight=" + centerDistanceWeight
                + ", areaWeight=" + areaWeight
                + ", confidenceWeight=" + confidenceWeight
                + ", completenessWeight=" + completenessWeight
                + ", preferSmallerBox=" + preferSmallerBox
                + ", maxRankedCandidates=" + maxRankedCandidates
                + ", labelSearchHeightPx=" + labelSearchHeightPx
                + ", labelHorizontalPaddingPx=" + labelHorizontalPaddingPx
                + ", labelVerticalPaddingPx=" + labelVerticalPaddingPx
                + ", labelMaxDistanceFromBoxPx=" + labelMaxDistanceFromBoxPx
                + ", minLabelCandidateWidth=" + minLabelCandidateWidth
                + ", minLabelCandidateHeight=" + minLabelCandidateHeight
                + ", maxLabelCandidateCount=" + maxLabelCandidateCount
                + ", labelUpscaleFactor=" + labelUpscaleFactor
                + ", minIdConfidence=" + minIdConfidence
                + ", idMinDigits=" + idMinDigits
                + ", idMaxDigits=" + idMaxDigits + "}";
    }

    /** 构造器：非法组合在 {@link #build()} 处一次性失败，避免把坏配置带进点击路径。 */
    public static final class Builder {

        private boolean enabled = true;
        private long captureTimeoutMs = 300L;
        private int maxFrameLongSidePx = 640;
        private long maxFrameAgeNs = NO_FRAME_AGE_LIMIT_NS;
        private float minConfidence = 0f;
        private boolean reportUnknownTargetId = true;

        private int roiRadiusX = DEFAULT_ROI_RADIUS;
        private int roiRadiusY = DEFAULT_ROI_RADIUS;
        private float blueHueMinDegrees = BlueColorThresholds.DEFAULT_HUE_MIN_DEGREES;
        private float blueHueMaxDegrees = BlueColorThresholds.DEFAULT_HUE_MAX_DEGREES;
        private float minSaturation = BlueColorThresholds.DEFAULT_MIN_SATURATION;
        private float minValue = BlueColorThresholds.DEFAULT_MIN_VALUE;
        private int minBluePixelCount = DEFAULT_MIN_BLUE_PIXEL_COUNT;
        private int maskDenoiseMinNeighbors = DEFAULT_MASK_DENOISE_MIN_NEIGHBORS;
        private boolean debugMaskEnabled = false;

        private int minBoxWidth = DEFAULT_MIN_BOX_WIDTH;
        private int minBoxHeight = DEFAULT_MIN_BOX_HEIGHT;
        private int maxBoxWidth = NO_BOX_SIZE_LIMIT;
        private int maxBoxHeight = NO_BOX_SIZE_LIMIT;
        private float maxBoxAspectRatio = DEFAULT_MAX_BOX_ASPECT_RATIO;
        private float minEdgeCoverage = DEFAULT_MIN_EDGE_COVERAGE;
        private int minPresentEdges = DEFAULT_MIN_PRESENT_EDGES;
        private int maxEdgeThicknessPx = DEFAULT_MAX_EDGE_THICKNESS_PX;
        private float minEdgeSolidity = DEFAULT_MIN_EDGE_SOLIDITY;
        private float minBoxConfidence = DEFAULT_MIN_BOX_CONFIDENCE;
        private int touchTolerancePx = DEFAULT_TOUCH_TOLERANCE_PX;
        private int maxCandidateCount = DEFAULT_MAX_CANDIDATE_COUNT;

        private float exactHitWeight = DEFAULT_EXACT_HIT_WEIGHT;
        private float centerDistanceWeight = DEFAULT_CENTER_DISTANCE_WEIGHT;
        private float areaWeight = DEFAULT_AREA_WEIGHT;
        private float confidenceWeight = DEFAULT_CONFIDENCE_WEIGHT;
        private float completenessWeight = DEFAULT_COMPLETENESS_WEIGHT;
        private boolean preferSmallerBox = DEFAULT_PREFER_SMALLER_BOX;
        private int maxRankedCandidates = DEFAULT_MAX_RANKED_CANDIDATES;

        private int labelSearchHeightPx = DEFAULT_LABEL_SEARCH_HEIGHT_PX;
        private int labelHorizontalPaddingPx = DEFAULT_LABEL_HORIZONTAL_PADDING_PX;
        private int labelVerticalPaddingPx = DEFAULT_LABEL_VERTICAL_PADDING_PX;
        private int labelMaxDistanceFromBoxPx = DEFAULT_LABEL_MAX_DISTANCE_FROM_BOX_PX;
        private int minLabelCandidateWidth = DEFAULT_MIN_LABEL_CANDIDATE_WIDTH;
        private int minLabelCandidateHeight = DEFAULT_MIN_LABEL_CANDIDATE_HEIGHT;
        private int maxLabelCandidateCount = DEFAULT_MAX_LABEL_CANDIDATE_COUNT;

        private int labelUpscaleFactor = DEFAULT_LABEL_UPSCALE_FACTOR;
        private float minIdConfidence = DEFAULT_MIN_ID_CONFIDENCE;
        private int idMinDigits = DEFAULT_ID_MIN_DIGITS;
        private int idMaxDigits = DEFAULT_ID_MAX_DIGITS;

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder captureTimeoutMs(long captureTimeoutMs) {
            if (captureTimeoutMs < 0) {
                throw new IllegalArgumentException("captureTimeoutMs must be >= 0 but was " + captureTimeoutMs);
            }
            this.captureTimeoutMs = captureTimeoutMs;
            return this;
        }

        public Builder maxFrameLongSidePx(int maxFrameLongSidePx) {
            if (maxFrameLongSidePx < 0) {
                throw new IllegalArgumentException("maxFrameLongSidePx must be >= 0 but was " + maxFrameLongSidePx);
            }
            this.maxFrameLongSidePx = maxFrameLongSidePx;
            return this;
        }

        public Builder maxFrameAgeNs(long maxFrameAgeNs) {
            if (maxFrameAgeNs < 0) {
                throw new IllegalArgumentException("maxFrameAgeNs must be >= 0 but was " + maxFrameAgeNs);
            }
            this.maxFrameAgeNs = maxFrameAgeNs;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,1] 内 */
        public Builder minConfidence(float minConfidence) {
            if (Float.isNaN(minConfidence) || minConfidence < 0f || minConfidence > 1f) {
                throw new IllegalArgumentException("minConfidence must be within [0,1] but was " + minConfidence);
            }
            this.minConfidence = minConfidence;
            return this;
        }

        public Builder reportUnknownTargetId(boolean reportUnknownTargetId) {
            this.reportUnknownTargetId = reportUnknownTargetId;
            return this;
        }

        /** @throws IllegalArgumentException 不为正 */
        public Builder roiRadiusX(int roiRadiusX) {
            if (roiRadiusX <= 0) {
                throw new IllegalArgumentException("roiRadiusX must be > 0 but was " + roiRadiusX);
            }
            this.roiRadiusX = roiRadiusX;
            return this;
        }

        /** @throws IllegalArgumentException 不为正 */
        public Builder roiRadiusY(int roiRadiusY) {
            if (roiRadiusY <= 0) {
                throw new IllegalArgumentException("roiRadiusY must be > 0 but was " + roiRadiusY);
            }
            this.roiRadiusY = roiRadiusY;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,360) 内或非有限值 */
        public Builder blueHueMinDegrees(float blueHueMinDegrees) {
            requireHue(blueHueMinDegrees, "blueHueMinDegrees");
            this.blueHueMinDegrees = blueHueMinDegrees;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,360) 内或非有限值 */
        public Builder blueHueMaxDegrees(float blueHueMaxDegrees) {
            requireHue(blueHueMaxDegrees, "blueHueMaxDegrees");
            this.blueHueMaxDegrees = blueHueMaxDegrees;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,1] 内 */
        public Builder minSaturation(float minSaturation) {
            requireUnitRange(minSaturation, "minSaturation");
            this.minSaturation = minSaturation;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,1] 内 */
        public Builder minValue(float minValue) {
            requireUnitRange(minValue, "minValue");
            this.minValue = minValue;
            return this;
        }

        /** @throws IllegalArgumentException 为负 */
        public Builder minBluePixelCount(int minBluePixelCount) {
            if (minBluePixelCount < 0) {
                throw new IllegalArgumentException("minBluePixelCount must be >= 0 but was "
                        + minBluePixelCount);
            }
            this.minBluePixelCount = minBluePixelCount;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,8] 内 */
        public Builder maskDenoiseMinNeighbors(int maskDenoiseMinNeighbors) {
            if (maskDenoiseMinNeighbors < 0 || maskDenoiseMinNeighbors > 8) {
                throw new IllegalArgumentException("maskDenoiseMinNeighbors must be within [0,8] but was "
                        + maskDenoiseMinNeighbors);
            }
            this.maskDenoiseMinNeighbors = maskDenoiseMinNeighbors;
            return this;
        }

        public Builder debugMaskEnabled(boolean debugMaskEnabled) {
            this.debugMaskEnabled = debugMaskEnabled;
            return this;
        }

        /** @throws IllegalArgumentException 不为正 */
        public Builder minBoxWidth(int minBoxWidth) {
            if (minBoxWidth <= 0) {
                throw new IllegalArgumentException("minBoxWidth must be > 0 but was " + minBoxWidth);
            }
            this.minBoxWidth = minBoxWidth;
            return this;
        }

        /** @throws IllegalArgumentException 不为正 */
        public Builder minBoxHeight(int minBoxHeight) {
            if (minBoxHeight <= 0) {
                throw new IllegalArgumentException("minBoxHeight must be > 0 but was " + minBoxHeight);
            }
            this.minBoxHeight = minBoxHeight;
            return this;
        }

        /**
         * @param maxBoxWidth 最大宽度（视频像素）；{@link #NO_BOX_SIZE_LIMIT} 表示不限
         * @throws IllegalArgumentException 为负，或小于 {@link #minBoxWidth}（且不为 0）
         */
        public Builder maxBoxWidth(int maxBoxWidth) {
            if (maxBoxWidth < 0) {
                throw new IllegalArgumentException("maxBoxWidth must be >= 0 but was " + maxBoxWidth);
            }
            if (maxBoxWidth != NO_BOX_SIZE_LIMIT && maxBoxWidth < minBoxWidth) {
                throw new IllegalArgumentException("maxBoxWidth must be >= minBoxWidth ("
                        + minBoxWidth + ") or " + NO_BOX_SIZE_LIMIT + " but was " + maxBoxWidth);
            }
            this.maxBoxWidth = maxBoxWidth;
            return this;
        }

        /**
         * @param maxBoxHeight 最大高度（视频像素）；{@link #NO_BOX_SIZE_LIMIT} 表示不限
         * @throws IllegalArgumentException 为负，或小于 {@link #minBoxHeight}（且不为 0）
         */
        public Builder maxBoxHeight(int maxBoxHeight) {
            if (maxBoxHeight < 0) {
                throw new IllegalArgumentException("maxBoxHeight must be >= 0 but was " + maxBoxHeight);
            }
            if (maxBoxHeight != NO_BOX_SIZE_LIMIT && maxBoxHeight < minBoxHeight) {
                throw new IllegalArgumentException("maxBoxHeight must be >= minBoxHeight ("
                        + minBoxHeight + ") or " + NO_BOX_SIZE_LIMIT + " but was " + maxBoxHeight);
            }
            this.maxBoxHeight = maxBoxHeight;
            return this;
        }

        /** @throws IllegalArgumentException 小于 1 或非有限值 */
        public Builder maxBoxAspectRatio(float maxBoxAspectRatio) {
            if (!Float.isFinite(maxBoxAspectRatio) || maxBoxAspectRatio < 1f) {
                throw new IllegalArgumentException("maxBoxAspectRatio must be >= 1 but was " + maxBoxAspectRatio);
            }
            this.maxBoxAspectRatio = maxBoxAspectRatio;
            return this;
        }

        /** @throws IllegalArgumentException 不在 (0,1] 内 */
        public Builder minEdgeCoverage(float minEdgeCoverage) {
            if (Float.isNaN(minEdgeCoverage) || minEdgeCoverage <= 0f || minEdgeCoverage > 1f) {
                throw new IllegalArgumentException("minEdgeCoverage must be within (0,1] but was "
                        + minEdgeCoverage);
            }
            this.minEdgeCoverage = minEdgeCoverage;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [1,4] 内 */
        public Builder minPresentEdges(int minPresentEdges) {
            if (minPresentEdges < 1 || minPresentEdges > 4) {
                throw new IllegalArgumentException("minPresentEdges must be within [1,4] but was "
                        + minPresentEdges);
            }
            this.minPresentEdges = minPresentEdges;
            return this;
        }

        /** @throws IllegalArgumentException 不为正 */
        public Builder maxEdgeThicknessPx(int maxEdgeThicknessPx) {
            if (maxEdgeThicknessPx <= 0) {
                throw new IllegalArgumentException("maxEdgeThicknessPx must be > 0 but was "
                        + maxEdgeThicknessPx);
            }
            this.maxEdgeThicknessPx = maxEdgeThicknessPx;
            return this;
        }

        /**
         * 边带实心度下限。
         *
         * @throws IllegalArgumentException 不在 (0,1] 内
         */
        public Builder minEdgeSolidity(float minEdgeSolidity) {
            if (Float.isNaN(minEdgeSolidity) || minEdgeSolidity <= 0f || minEdgeSolidity > 1f) {
                throw new IllegalArgumentException("minEdgeSolidity must be within (0,1] but was "
                        + minEdgeSolidity);
            }
            this.minEdgeSolidity = minEdgeSolidity;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,1] 内 */
        public Builder minBoxConfidence(float minBoxConfidence) {
            requireUnitRange(minBoxConfidence, "minBoxConfidence");
            this.minBoxConfidence = minBoxConfidence;
            return this;
        }

        /** @throws IllegalArgumentException 为负 */
        public Builder touchTolerancePx(int touchTolerancePx) {
            if (touchTolerancePx < 0) {
                throw new IllegalArgumentException("touchTolerancePx must be >= 0 but was "
                        + touchTolerancePx);
            }
            this.touchTolerancePx = touchTolerancePx;
            return this;
        }

        /** @throws IllegalArgumentException 小于 1 */
        public Builder maxCandidateCount(int maxCandidateCount) {
            if (maxCandidateCount < 1) {
                throw new IllegalArgumentException("maxCandidateCount must be >= 1 but was "
                        + maxCandidateCount);
            }
            this.maxCandidateCount = maxCandidateCount;
            return this;
        }

        /** @throws IllegalArgumentException 为负或非有限值 */
        public Builder exactHitWeight(float exactHitWeight) {
            this.exactHitWeight = requireWeight(exactHitWeight, "exactHitWeight");
            return this;
        }

        /** @throws IllegalArgumentException 为负或非有限值 */
        public Builder centerDistanceWeight(float centerDistanceWeight) {
            this.centerDistanceWeight = requireWeight(centerDistanceWeight, "centerDistanceWeight");
            return this;
        }

        /** @throws IllegalArgumentException 为负或非有限值 */
        public Builder areaWeight(float areaWeight) {
            this.areaWeight = requireWeight(areaWeight, "areaWeight");
            return this;
        }

        /** @throws IllegalArgumentException 为负或非有限值 */
        public Builder confidenceWeight(float confidenceWeight) {
            this.confidenceWeight = requireWeight(confidenceWeight, "confidenceWeight");
            return this;
        }

        /** @throws IllegalArgumentException 为负或非有限值 */
        public Builder completenessWeight(float completenessWeight) {
            this.completenessWeight = requireWeight(completenessWeight, "completenessWeight");
            return this;
        }

        public Builder preferSmallerBox(boolean preferSmallerBox) {
            this.preferSmallerBox = preferSmallerBox;
            return this;
        }

        /** @throws IllegalArgumentException 小于 1 */
        public Builder maxRankedCandidates(int maxRankedCandidates) {
            if (maxRankedCandidates < 1) {
                throw new IllegalArgumentException("maxRankedCandidates must be >= 1 but was "
                        + maxRankedCandidates);
            }
            this.maxRankedCandidates = maxRankedCandidates;
            return this;
        }

        /** @throws IllegalArgumentException 不为正 */
        public Builder labelSearchHeightPx(int labelSearchHeightPx) {
            if (labelSearchHeightPx <= 0) {
                throw new IllegalArgumentException("labelSearchHeightPx must be > 0 but was "
                        + labelSearchHeightPx);
            }
            this.labelSearchHeightPx = labelSearchHeightPx;
            return this;
        }

        /** @throws IllegalArgumentException 为负 */
        public Builder labelHorizontalPaddingPx(int labelHorizontalPaddingPx) {
            if (labelHorizontalPaddingPx < 0) {
                throw new IllegalArgumentException("labelHorizontalPaddingPx must be >= 0 but was "
                        + labelHorizontalPaddingPx);
            }
            this.labelHorizontalPaddingPx = labelHorizontalPaddingPx;
            return this;
        }

        /**
         * 搜索带压进框顶的深度。
         *
         * @throws IllegalArgumentException 为负，或大于等于 {@link #labelSearchHeightPx}
         *         （压进框里的深度比向上搜索的高度还大，说明这一对参数被设反了）
         */
        public Builder labelVerticalPaddingPx(int labelVerticalPaddingPx) {
            if (labelVerticalPaddingPx < 0) {
                throw new IllegalArgumentException("labelVerticalPaddingPx must be >= 0 but was "
                        + labelVerticalPaddingPx);
            }
            if (labelVerticalPaddingPx >= labelSearchHeightPx) {
                throw new IllegalArgumentException("labelVerticalPaddingPx (" + labelVerticalPaddingPx
                        + ") must be < labelSearchHeightPx (" + labelSearchHeightPx + ")");
            }
            this.labelVerticalPaddingPx = labelVerticalPaddingPx;
            return this;
        }

        /** @throws IllegalArgumentException 为负 */
        public Builder labelMaxDistanceFromBoxPx(int labelMaxDistanceFromBoxPx) {
            if (labelMaxDistanceFromBoxPx < 0) {
                throw new IllegalArgumentException("labelMaxDistanceFromBoxPx must be >= 0 but was "
                        + labelMaxDistanceFromBoxPx);
            }
            this.labelMaxDistanceFromBoxPx = labelMaxDistanceFromBoxPx;
            return this;
        }

        /** @throws IllegalArgumentException 不为正 */
        public Builder minLabelCandidateWidth(int minLabelCandidateWidth) {
            if (minLabelCandidateWidth <= 0) {
                throw new IllegalArgumentException("minLabelCandidateWidth must be > 0 but was "
                        + minLabelCandidateWidth);
            }
            this.minLabelCandidateWidth = minLabelCandidateWidth;
            return this;
        }

        /** @throws IllegalArgumentException 不为正 */
        public Builder minLabelCandidateHeight(int minLabelCandidateHeight) {
            if (minLabelCandidateHeight <= 0) {
                throw new IllegalArgumentException("minLabelCandidateHeight must be > 0 but was "
                        + minLabelCandidateHeight);
            }
            this.minLabelCandidateHeight = minLabelCandidateHeight;
            return this;
        }

        /** @throws IllegalArgumentException 小于 1 */
        public Builder maxLabelCandidateCount(int maxLabelCandidateCount) {
            if (maxLabelCandidateCount < 1) {
                throw new IllegalArgumentException("maxLabelCandidateCount must be >= 1 but was "
                        + maxLabelCandidateCount);
            }
            this.maxLabelCandidateCount = maxLabelCandidateCount;
            return this;
        }

        /**
         * 标签 ROI 放大倍数。
         *
         * @throws IllegalArgumentException 不在 [1,4] 内（0 会让预处理直接除零，
         *         大于 4 只会让耗时按平方增长而识别率不再提升）
         */
        public Builder labelUpscaleFactor(int labelUpscaleFactor) {
            if (labelUpscaleFactor < 1 || labelUpscaleFactor > 4) {
                throw new IllegalArgumentException("labelUpscaleFactor must be within [1,4] but was "
                        + labelUpscaleFactor);
            }
            this.labelUpscaleFactor = labelUpscaleFactor;
            return this;
        }

        /** @throws IllegalArgumentException 不在 [0,1] 内 */
        public Builder minIdConfidence(float minIdConfidence) {
            requireUnitRange(minIdConfidence, "minIdConfidence");
            this.minIdConfidence = minIdConfidence;
            return this;
        }

        /** @throws IllegalArgumentException 小于 1 */
        public Builder idMinDigits(int idMinDigits) {
            if (idMinDigits < 1) {
                throw new IllegalArgumentException("idMinDigits must be >= 1 but was " + idMinDigits);
            }
            this.idMinDigits = idMinDigits;
            return this;
        }

        /**
         * 编号最多位数。
         *
         * @throws IllegalArgumentException 小于 {@link #idMinDigits}
         */
        public Builder idMaxDigits(int idMaxDigits) {
            if (idMaxDigits < idMinDigits) {
                throw new IllegalArgumentException("idMaxDigits (" + idMaxDigits
                        + ") must be >= idMinDigits (" + idMinDigits + ")");
            }
            this.idMaxDigits = idMaxDigits;
            return this;
        }

        /**
         * @throws IllegalArgumentException 任一视觉阈值非法（这里复用
         *         {@link BlueColorThresholds#of} 的校验，保证「配置里能过」等价于
         *         「检测器构造时能过」，不会出现"配置合法但分析器一构造就抛"）
         */
        public TargetSelectionConfig build() {
            BlueColorThresholds.of(blueHueMinDegrees, blueHueMaxDegrees, minSaturation, minValue);
            // 上限与下限的关系在这里再查一次：setter 是按调用顺序逐个校验的，
            // 若先设 max 后设 min，单次调用时无法发现矛盾，必须在这里兜住。
            requireSizeLimitsConsistent(maxBoxWidth, minBoxWidth, "maxBoxWidth", "minBoxWidth");
            requireSizeLimitsConsistent(maxBoxHeight, minBoxHeight, "maxBoxHeight", "minBoxHeight");
            // 同上：setter 是按调用顺序逐个校验的，先设 searchHeight 再设 verticalPadding
            // 也必须在 build() 处被认定为一对矛盾（否则搜索带会"压进框里比向上还深"）。
            if (labelVerticalPaddingPx >= labelSearchHeightPx) {
                throw new IllegalArgumentException("labelVerticalPaddingPx (" + labelVerticalPaddingPx
                        + ") must be < labelSearchHeightPx (" + labelSearchHeightPx + ")");
            }
            // 编号位数的上下限同理：先设 max 再设 min 时单次调用看不出来。
            if (idMaxDigits < idMinDigits) {
                throw new IllegalArgumentException("idMaxDigits (" + idMaxDigits
                        + ") must be >= idMinDigits (" + idMinDigits + ")");
            }
            return new TargetSelectionConfig(this);
        }

        private static void requireSizeLimitsConsistent(int max, int min, String maxName, String minName) {
            if (max != NO_BOX_SIZE_LIMIT && max < min) {
                throw new IllegalArgumentException(maxName + " must be >= " + minName + " ("
                        + min + ") or " + NO_BOX_SIZE_LIMIT + " but was " + max);
            }
        }

        private static void requireHue(float value, String name) {
            if (Float.isNaN(value) || Float.isInfinite(value) || value < 0f || value >= 360f) {
                throw new IllegalArgumentException(name + " must be within [0,360) but was " + value);
            }
        }

        private static void requireUnitRange(float value, String name) {
            if (Float.isNaN(value) || value < 0f || value > 1f) {
                throw new IllegalArgumentException(name + " must be within [0,1] but was " + value);
            }
        }

        /** 权重必须是非负有限值：负权重会把"越远越好"这类反直觉行为带进评分。 */
        private static float requireWeight(float value, String name) {
            if (!Float.isFinite(value) || value < 0f) {
                throw new IllegalArgumentException(name + " must be finite and >= 0 but was " + value);
            }
            return value;
        }
    }
}
