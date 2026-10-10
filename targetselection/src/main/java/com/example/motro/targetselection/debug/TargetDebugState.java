package com.example.motro.targetselection.debug;

import com.example.motro.targetselection.api.TouchSelectionRequest;
import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoPointMappingResult;
import com.example.motro.targetselection.geometry.VideoPointMappingStatus;
import com.example.motro.targetselection.geometry.VideoScaleType;
import com.example.motro.targetselection.vision.BlueMaskResult;
import com.example.motro.targetselection.vision.BlueMaskStatus;
import com.example.motro.targetselection.vision.DetectedTargetBox;
import com.example.motro.targetselection.vision.LabelCandidate;
import com.example.motro.targetselection.vision.LabelExtractionResult;
import com.example.motro.targetselection.vision.LabelExtractionStatus;
import com.example.motro.targetselection.vision.TargetBoxDetectionResult;
import com.example.motro.targetselection.vision.TargetBoxDetectionStatus;
import com.example.motro.targetselection.vision.TargetCandidateScore;
import com.example.motro.targetselection.vision.TargetHitType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 调试可视化所需的全部信息，<b>不可变</b>。它把 {@link VideoPointMappingResult}
 * （状态 + 触摸点 + 视频坐标 + 几何快照）与后续阶段的数据装在一起，供
 * {@link TargetDebugOverlayView} 绘制。
 *
 * <p><b>为什么单独定义，而不是让 Overlay 直接读控制器</b>：Overlay 是纯展示层，
 * 它不该知道控制器、几何来源、识别实现的存在。有了本类，Overlay 只依赖一个数据对象 ——
 * 于是它可以被单测直接构造、可以在没有播放器的环境里绘制、也能被别的调试工具复用。
 * 本类<b>不含任何 Android 类型</b>（纯 JVM），所以状态推导逻辑可以在 JVM 单测里穷举。</p>
 *
 * <p><b>核心字段来自几何推导，不重复存</b>：{@code contentRect / videoWidth / rotation / mirror}
 * 都从 {@link VideoPointMappingResult#geometry} 读，避免同一条几何信息在两处各存一份、
 * 然后其中一份忘了更新（这正是坐标类 bug 的经典来源）。</p>
 *
 * <p><b>回投影（{@link #mappedViewX}/{@link #mappedViewY}）是本类的关键增值</b>：视频坐标
 * 不能直接画在 View 上，必须经 {@link VideoCoordinateMapper#mapVideoToView} 正推回来。
 * 由于它是反解的严格逆，{@code mappedView} 应当还原出原始触摸点 —— 两者之差
 * {@link #roundTripErrorPx} 于是成了一个「免费的」自检指标：正常应≈0，
 * 偏大就说明几何自相矛盾（或点落在 CENTER_CROP 被裁掉的区域）。</p>
 *
 * <p>线程约定：不可变对象（{@code detectedBoxes} 为不可修改视图），可跨线程安全共享 ——
 * 典型用法是在工作线程算好，再切回主线程交给 Overlay。</p>
 */
public final class TargetDebugState {

    /** 无对应坐标时的哨兵值。0 是合法坐标，所以不能用 0 表示「没有」。 */
    public static final float NO_COORDINATE = Float.NaN;

    /**
     * {@link #bluePixelRatio} 的哨兵值：本次没有可用的 ROI（Ratio 本身落在 [0,1]，负数安全）。
     */
    public static final float NO_RATIO = -1f;

    /** 核心映射结果：状态、触摸点、视频坐标、归一化坐标、几何快照。 */
    public final VideoPointMappingResult mapping;

    /** 按下时刻（{@link System#nanoTime()} 基准），未知为 {@link TouchSelectionRequest#NO_EVENT_TIME_NS}。 */
    public final long eventTimeNs;

    /** 视频坐标正推回 View 后的落点 x；无有效坐标时为 {@link #NO_COORDINATE}。 */
    public final float mappedViewX;

    /** 视频坐标正推回 View 后的落点 y；无有效坐标时为 {@link #NO_COORDINATE}。 */
    public final float mappedViewY;

    /**
     * 正反往返误差（像素）＝ {@code |正推(反解(触摸点)) − 触摸点|}。
     * 只有 {@link VideoPointMappingStatus#SUCCESS} 时有效，否则为 {@link #NO_COORDINATE}。
     * 正常应≈0（仅浮点误差量级）；明显偏大说明几何与点不自洽。
     */
    public final float roundTripErrorPx;

    // ---------------------------------------------------------------- 视觉阶段（Slice 4）

    /**
     * 检索 ROI（视频坐标）：点击点附近被裁剪出来做颜色分析的区域。
     * 由 {@link #withBlueMask(BlueMaskResult)} 或 {@link Builder#searchRoi} 填充；
     * 未做视觉分析时为 null（此时叠加层不画 ROI 框）。
     */
    public final TargetBoundingBox searchRoi;

    /** 蓝色掩码分析的状态；未做视觉分析时为 null。 */
    public final BlueMaskStatus roiStatus;

    /** ROI 内判定为蓝色的像素数；未做视觉分析时为 0。 */
    public final int bluePixelCount;

    /**
     * 蓝色像素占 ROI 的比例 [0,1]；没有可用 ROI 时为 {@link #NO_RATIO}。
     * <b>不</b>用 0 表示「没有」—— 0 是「ROI 里确实一个蓝像素都没有」这个有意义的结论。
     */
    public final float bluePixelRatio;

    // ---------------------------------------------------------------- 矩形恢复与命中（Slice 5）

    /**
     * 矩形恢复产生的候选框（视频坐标），按置信度降序，与
     * {@link TargetBoxDetectionResult#candidates} 同序。
     *
     * <p>这里放的是 {@link DetectedTargetBox} 而不是裸的 {@link TargetBoundingBox}：
     * 叠加层的价值一半在"框画在哪"，另一半在"为什么这个框被选中"——
     * 每框的 {@code confidence} 与 {@code completeness} 正是后者的依据，
     * 拆成两个平行列表（矩形一份、评分一份）迟早会出现一份忘了更新的情况。</p>
     *
     * <p>未做矩形恢复时为空列表（叠加层不画任何候选框）。</p>
     */
    public final List<DetectedTargetBox> detectedBoxes;

    /**
     * 矩形恢复的状态；未做矩形恢复时为 null。
     * <p>它把"没有框"（{@link TargetBoxDetectionStatus#NO_BOX}）与"输入有问题"
     * （{@code INVALID_MASK} / {@code INVALID_INPUT}）区分开 —— 叠加层据此显示
     * "为什么一个框都没有"，而不是空着让人猜。</p>
     */
    public final TargetBoxDetectionStatus boxStatus;

    /**
     * 矩形恢复给出的候选总数。与 {@link #detectedBoxes} 的尺寸未必相等：
     * 检测器有候选数上限，超限时会截断，而这里保留的是<b>检测器自报</b>的数值，
     * 便于叠加层显示"检测到 16 个，只画了 5 个"这类信息。
     */
    public final int candidateCount;

    /** 最终选中的框（视频坐标）；本次点击未命中任何候选时为 null。 */
    public final TargetBoundingBox selectedBox;

    // ---------------------------------------------------------------- 多目标选择（Slice 6）

    /**
     * 命中候选的评分明细，按 rank 从 0 递增（rank 0 就是 {@link #selectedBox}）；
     * 未命中或未做选择时为空列表。
     *
     * <p>它比 {@link #detectedBoxes} 更进一层：{@code detectedBoxes} 回答"画面上找到了哪些框"，
     * 这里回答"这次点击里哪些框被点到了、各自排第几、为什么"。叠加层正是靠它
     * 回答"为什么最终选中的是这个框"。</p>
     */
    public final List<TargetCandidateScore> rankedCandidates;

    /** 命中分级（严格包含 / 容差）；未命中或未做选择时为 null。 */
    public final TargetHitType hitType;

    /** 选中框的加权总分；未命中为 0。 */
    public final float selectionScore;

    /** 选择理由（英文，来自 {@code TargetSelectionDecision#reason}）；未做选择时为 null。 */
    public final String selectionReason;

    /** 多目标选择耗时（毫秒）；未做选择时为 0。 */
    public final long selectionMs;

    // ---------------------------------------------------------------- 标签区域提取（Slice 7）

    /**
     * 标签搜索区域（视频坐标）：这次真正扫过的那条窄带（框顶上方 + 左右余量 + 压进框顶一点）。
     * 由 {@link #withLabelExtraction(LabelExtractionResult)} 填充；未做标签提取时为 null。
     *
     * <p>它是排查"标签为什么没找到"的第一手依据：先看这条带画在哪 ——
     * 若它压根没盖住编号，问题在配置或框的位置，而不在文字形态判据。</p>
     */
    public final TargetBoundingBox labelSearchRegion;

    /**
     * 全部标签候选（视频坐标），顺序与提取器给出的确定性顺序一致；
     * 未做标签提取时为空列表。
     *
     * <p>放全部而不是只放选中的那个：调试价值的一半在"为什么是它"，
     * 而答案全在被丢掉的候选里。</p>
     */
    public final List<LabelCandidate> labelCandidates;

    /** 标签提取状态；未做标签提取时为 null。见 {@link LabelExtractionStatus}。 */
    public final LabelExtractionStatus labelStatus;

    /** 选中标签的置信度 [0,1]；未找到标签时为 0。 */
    public final float labelConfidence;

    /** 标签搜索阶段耗时（毫秒）；未做标签提取时为 0。 */
    public final long labelDetectMs;

    /** 标签裁剪阶段耗时（毫秒）；未做标签提取时为 0。 */
    public final long labelCropMs;

    /**
     * 选中的标签区域（视频坐标）。Slice 6 之前是预留字段（恒为 null），
     * Slice 7 起由 {@link #withLabelExtraction(LabelExtractionResult)} 填入；
     * 没找到标签时仍为 null，此时 {@link #labelStatus} 会说明原因。
     */
    public final TargetBoundingBox labelRoi;

    /** 预留：目标 id。本 Slice 恒为 null（标签提取不识别文字内容，见 Slice 7）。 */
    public final String targetId;

    /** 选中框的置信度 [0,1]；未命中时为 0。 */
    public final float confidence;

    /**
     * 本次点击的处理耗时（毫秒）。视觉阶段由 {@link #withBlueMask(BlueMaskResult)} 填入，
     * 矩形恢复阶段由 {@link #withBoxDetection(TargetBoxDetectionResult)} 覆盖为
     * {@code totalMs}（含掩码 + 检测 + 命中三步）；未做任何视觉分析时为 0。
     */
    public final long processingMs;

    /** 矩形恢复耗时（毫秒）；未做矩形恢复时为 0。 */
    public final long detectMs;

    /** 触摸命中判定耗时（毫秒）；未做矩形恢复时为 0。 */
    public final long hitTestMs;

    private TargetDebugState(Builder builder) {
        this.mapping = builder.mapping;
        this.eventTimeNs = builder.eventTimeNs;
        this.searchRoi = builder.searchRoi;
        this.roiStatus = builder.roiStatus;
        this.bluePixelCount = builder.bluePixelCount;
        this.bluePixelRatio = builder.bluePixelRatio;
        this.detectedBoxes = Collections.unmodifiableList(new ArrayList<>(builder.detectedBoxes));
        this.boxStatus = builder.boxStatus;
        this.candidateCount = builder.candidateCount;
        this.selectedBox = builder.selectedBox;
        this.rankedCandidates = Collections.unmodifiableList(new ArrayList<>(builder.rankedCandidates));
        this.hitType = builder.hitType;
        this.selectionScore = builder.selectionScore;
        this.selectionReason = builder.selectionReason;
        this.selectionMs = builder.selectionMs;
        this.labelSearchRegion = builder.labelSearchRegion;
        this.labelCandidates = Collections.unmodifiableList(new ArrayList<>(builder.labelCandidates));
        this.labelStatus = builder.labelStatus;
        this.labelConfidence = builder.labelConfidence;
        this.labelDetectMs = builder.labelDetectMs;
        this.labelCropMs = builder.labelCropMs;
        this.labelRoi = builder.labelRoi;
        this.targetId = builder.targetId;
        this.confidence = builder.confidence;
        this.processingMs = builder.processingMs;
        this.detectMs = builder.detectMs;
        this.hitTestMs = builder.hitTestMs;

        // 回投影：只有真的拿到了视频坐标才谈得上正推
        final float[] projected = projectBack(mapping);
        if (projected == null) {
            this.mappedViewX = NO_COORDINATE;
            this.mappedViewY = NO_COORDINATE;
            this.roundTripErrorPx = NO_COORDINATE;
        } else {
            this.mappedViewX = projected[0];
            this.mappedViewY = projected[1];
            this.roundTripErrorPx = projected[2];
        }
    }

    /**
     * 由一次映射结果构造（最常用入口）。预留字段保持默认值。
     *
     * @param mapping     映射结果，非 null
     * @param eventTimeNs 按下时刻（{@link System#nanoTime()} 基准），未知传
     *                    {@link TouchSelectionRequest#NO_EVENT_TIME_NS}
     * @throws IllegalArgumentException mapping 为 null，或 eventTimeNs 为非法负值
     */
    public static TargetDebugState of(VideoPointMappingResult mapping, long eventTimeNs) {
        return builder(mapping, eventTimeNs).build();
    }

    /**
     * 由一次映射结果构造，且不带触摸时刻。
     *
     * @throws IllegalArgumentException mapping 为 null
     */
    public static TargetDebugState of(VideoPointMappingResult mapping) {
        return of(mapping, TouchSelectionRequest.NO_EVENT_TIME_NS);
    }

    /**
     * 带预留字段的构造入口。
     *
     * @throws IllegalArgumentException mapping 为 null，或 eventTimeNs 为非法负值
     */
    public static Builder builder(VideoPointMappingResult mapping, long eventTimeNs) {
        return new Builder(mapping, eventTimeNs);
    }

    /** 复制并局部替换（例如识别阶段完成后补上 detectedBoxes，而保持触摸信息不变）。 */
    public Builder toBuilder() {
        return new Builder(mapping, eventTimeNs)
                .searchRoi(searchRoi)
                .roiStatus(roiStatus)
                .bluePixelCount(bluePixelCount)
                .bluePixelRatio(bluePixelRatio)
                .detectedBoxes(detectedBoxes)
                .boxStatus(boxStatus)
                .candidateCount(candidateCount)
                .selectedBox(selectedBox)
                .rankedCandidates(rankedCandidates)
                .hitType(hitType)
                .selectionScore(selectionScore)
                .selectionReason(selectionReason)
                .selectionMs(selectionMs)
                .labelSearchRegion(labelSearchRegion)
                .labelCandidates(labelCandidates)
                .labelStatus(labelStatus)
                .labelConfidence(labelConfidence)
                .labelTimings(labelDetectMs, labelCropMs)
                .labelRoi(labelRoi)
                .targetId(targetId)
                .confidence(confidence)
                .processingMs(processingMs)
                .timings(detectMs, hitTestMs);
    }

    /**
     * 复制并挂上一次蓝色掩码分析的结果 —— 视觉阶段的标准接法。
     *
     * <p>它把 {@link BlueMaskResult} 里<b>叠加层真正需要的那几项</b>翻成可视化字段：
     * ROI 矩形（{@link #searchRoi}）、状态（{@link #roiStatus}）、蓝像素计数与占比、
     * 以及处理耗时。掩码本体（{@link BlueMaskResult#mask}）<b>不</b>进本对象：
     * 叠加层不逐像素画掩码（那是后续"矩形恢复"阶段的可视化任务），
     * 在这里挂上去只会让状态对象多背一份几十 KB 的数据。</p>
     *
     * <p>失败结果同样接受：{@code roiStatus} 会如实反映失败原因（例如 {@code OUTSIDE_VIDEO}），
     * 于是调试层能显示"为什么没有 ROI"，而不是简单地空着让人猜。</p>
     *
     * @param result 分析结果，非 null
     * @return 新的状态对象（本对象不变）
     * @throws IllegalArgumentException result 为 null
     */
    public TargetDebugState withBlueMask(BlueMaskResult result) {
        return toBuilder().blueMask(result).build();
    }

    /**
     * 复制并挂上一次矩形恢复 + 命中 + 多目标选择的结果 —— Slice 5/6 的标准接法。
     *
     * <p>把 {@link TargetBoxDetectionResult} 里叠加层需要的几项翻成可视化字段：候选框列表
     * （含每框 confidence/completeness）、状态、候选计数、选中的框、命中分级、
     * 已排名候选的评分明细、以及分步耗时。
     * <b>掩码本体不进本对象</b>（叠加层不逐像素画掩码）。</p>
     *
     * <p>{@link #processingMs} 会被覆盖成结果里的 {@code totalMs}（掩码 + 检测 + 命中 + 选择），
     * 而不是停留在 {@link #withBlueMask(BlueMaskResult)} 那一步的掩码耗时 ——
     * 调试时看的是"这次点击总共花了多久"，分步耗时另由
     * {@link #detectMs}/{@link #hitTestMs}/{@link #selectionMs} 给出。</p>
     *
     * <p>失败结果同样接受：{@code boxStatus} 会如实带上 {@code NO_BOX} 或 {@code INVALID_MASK}，
     * 于是叠加层能显示"为什么没有框"。</p>
     *
     * @param result 检测结果，非 null
     * @return 新的状态对象（本对象不变）
     * @throws IllegalArgumentException result 为 null，或结果自带的耗时/计数为负
     */
    public TargetDebugState withBoxDetection(TargetBoxDetectionResult result) {
        return toBuilder().boxDetection(result).build();
    }

    /**
     * 复制并挂上一次标签区域提取的结果 —— Slice 7 的标准接法。
     *
     * <p>把 {@link LabelExtractionResult} 里叠加层需要的几项翻成可视化字段：
     * 搜索区域、全部候选、选中的标签、状态、置信度、以及搜索/裁剪分步耗时。
     * <b>裁剪出的像素不进本对象</b> —— 叠加层只画矢量矩形与文字，
     * 把几 KB 的 ARGB 数组塞进调试状态只会让它在主线程上多背一份无用数据
     * （真正的消费方是后续的识别阶段，它直接吃 {@code LabelExtractionResult}）。</p>
     *
     * <p>{@link #processingMs} 会被<b>累加</b>上这次标签提取的耗时（而不是覆盖）：
     * 标签提取发生在矩形恢复之后，两者是同一串工作的前后两段，
     * 覆盖会让「这次点击一共花了多久」丢掉前半段。分步耗时另由
     * {@link #labelDetectMs}/{@link #labelCropMs} 给出。</p>
     *
     * <p>失败结果同样接受：{@link #labelStatus} 会如实带上
     * {@code NO_LABEL} / {@code INVALID_BOX} / …，于是叠加层能显示「为什么没有标签」，
     * 而不是空着让人猜。注意 {@code NO_LABEL} <b>不是</b> MISS（见该枚举的说明）。</p>
     *
     * @param result 提取结果，非 null
     * @return 新的状态对象（本对象不变）
     * @throws IllegalArgumentException result 为 null
     */
    public TargetDebugState withLabelExtraction(LabelExtractionResult result) {
        return toBuilder().labelExtraction(result).build();
    }


    // ---------------------------------------------------------------- 几何代理（不解引用 null）

    /** 是否带可用的几何快照。为 false 时下面所有几何代理值都无意义。 */
    public boolean hasGeometry() {
        return mapping.geometry != null && mapping.geometry.isUsable();
    }

    /** 内容区左边界（View 坐标）；{@link #hasGeometry()} 为 false 时返回 0。 */
    public int contentLeft() {
        return mapping.geometry == null ? 0 : mapping.geometry.contentLeft;
    }

    /** 内容区上边界（View 坐标）；{@link #hasGeometry()} 为 false 时返回 0。 */
    public int contentTop() {
        return mapping.geometry == null ? 0 : mapping.geometry.contentTop;
    }

    /** 内容区右边界（View 坐标）；{@link #hasGeometry()} 为 false 时返回 0。 */
    public int contentRight() {
        return mapping.geometry == null ? 0 : mapping.geometry.contentRight;
    }

    /** 内容区下边界（View 坐标）；{@link #hasGeometry()} 为 false 时返回 0。 */
    public int contentBottom() {
        return mapping.geometry == null ? 0 : mapping.geometry.contentBottom;
    }

    /** 视频宽度；几何缺失时为 0。 */
    public int videoWidth() {
        return mapping.geometry == null ? 0 : mapping.geometry.videoWidth;
    }

    /** 视频高度；几何缺失时为 0。 */
    public int videoHeight() {
        return mapping.geometry == null ? 0 : mapping.geometry.videoHeight;
    }

    /** 顺时针旋转角（0/90/180/270）；几何缺失时为 0。 */
    public int rotationDegrees() {
        return mapping.geometry == null ? 0 : mapping.geometry.rotationDegrees;
    }

    /** 显示坐标系水平镜像；几何缺失时为 false。 */
    public boolean mirrorX() {
        return mapping.geometry != null && mapping.geometry.mirrorX;
    }

    /** 显示坐标系垂直镜像；几何缺失时为 false。 */
    public boolean mirrorY() {
        return mapping.geometry != null && mapping.geometry.mirrorY;
    }

    /** 缩放方式；几何缺失时为 {@link VideoScaleType#FIT_CENTER}。 */
    public VideoScaleType scaleType() {
        return mapping.geometry == null ? VideoScaleType.FIT_CENTER : mapping.geometry.scaleType;
    }

    /** 本次映射的结局。 */
    public VideoPointMappingStatus status() {
        return mapping.status;
    }

    /** 触摸点 View 坐标 x。 */
    public float touchViewX() {
        return mapping.viewX;
    }

    /** 触摸点 View 坐标 y。 */
    public float touchViewY() {
        return mapping.viewY;
    }

    /** 映射出的视频坐标 x；非成功时为 {@link VideoPointMappingResult#NO_COORDINATE}。 */
    public float videoX() {
        return mapping.videoX;
    }

    /** 映射出的视频坐标 y；非成功时为 {@link VideoPointMappingResult#NO_COORDINATE}。 */
    public float videoY() {
        return mapping.videoY;
    }

    /** 归一化视频坐标 x；非成功时为 {@link VideoPointMappingResult#NO_COORDINATE}。 */
    public float normalizedX() {
        return mapping.normalizedX;
    }

    /** 归一化视频坐标 y；非成功时为 {@link VideoPointMappingResult#NO_COORDINATE}。 */
    public float normalizedY() {
        return mapping.normalizedY;
    }

    /** 是否有可绘制的回投影落点（即拿到了视频坐标且几何可用）。 */
    public boolean hasMappedViewPoint() {
        return !Float.isNaN(mappedViewX) && !Float.isNaN(mappedViewY);
    }

    /** 是否做过视觉分析（即 {@link #roiStatus} 有值，无论成功还是失败）。 */
    public boolean hasVisionResult() {
        return roiStatus != null;
    }

    /** 是否有可绘制的 ROI 矩形。 */
    public boolean hasSearchRoi() {
        return searchRoi != null;
    }

    /** 是否有可显示的蓝像素占比。 */
    public boolean hasBlueRatio() {
        return bluePixelRatio != NO_RATIO;
    }

    /** 是否做过矩形恢复（即 {@link #boxStatus} 有值，无论成功还是失败）。 */
    public boolean hasBoxDetection() {
        return boxStatus != null;
    }

    /** 是否有可绘制的候选框。 */
    public boolean hasDetectedBoxes() {
        return !detectedBoxes.isEmpty();
    }

    /** 是否有已排名的命中候选（即有框被点到）。 */
    public boolean hasRankedCandidates() {
        return !rankedCandidates.isEmpty();
    }

    /** 命中分级；未命中或未做选择时为 null。 */
    public TargetHitType hitType() {
        return hitType;
    }

    /** 选中框的加权总分；未命中为 0。 */
    public float selectionScore() {
        return selectionScore;
    }

    /** 选择理由（英文）；未做选择时为 null。 */
    public String selectionReason() {
        return selectionReason;
    }

    /** 多目标选择耗时（毫秒）；未做选择时为 0。 */
    public long selectionMs() {
        return selectionMs;
    }

    /** 是否做过标签提取（即 {@link #labelStatus} 有值，无论成功还是失败）。 */
    public boolean hasLabelExtraction() {
        return labelStatus != null;
    }

    /** 是否有可绘制的标签候选。 */
    public boolean hasLabelCandidates() {
        return !labelCandidates.isEmpty();
    }

    /** 是否有可绘制的选中标签区域。 */
    public boolean hasLabelRoi() {
        return labelRoi != null;
    }

    /** 标签提取状态；未做标签提取时为 null。 */
    public LabelExtractionStatus labelStatus() {
        return labelStatus;
    }

    /** 选中标签的置信度 [0,1]；未找到标签时为 0。 */
    public float labelConfidence() {
        return labelConfidence;
    }

    /** 标签搜索耗时（毫秒）；未做标签提取时为 0。 */
    public long labelDetectMs() {
        return labelDetectMs;
    }

    /** 标签裁剪耗时（毫秒）；未做标签提取时为 0。 */
    public long labelCropMs() {
        return labelCropMs;
    }

    /** 本次点击是否命中了一个候选框（叠加层据此把选中框画成高亮样式）。 */
    public boolean isHit() {
        return selectedBox != null;
    }

    /** 矩形恢复的状态；未做矩形恢复时为 null。 */
    public TargetBoxDetectionStatus boxStatus() {
        return boxStatus;
    }

    /** 候选框总数（检测器自报值）；未做矩形恢复时为 0。 */
    public int candidateCount() {
        return candidateCount;
    }

    /** 蓝色掩码分析的状态；未做视觉分析时为 null。 */
    public BlueMaskStatus roiStatus() {
        return roiStatus;
    }

    /** ROI 内判定为蓝色的像素数；未做视觉分析时为 0。 */
    public int bluePixelCount() {
        return bluePixelCount;
    }

    /** 蓝像素占 ROI 的比例 [0,1]；没有可用 ROI 时为 {@link #NO_RATIO}。 */
    public float bluePixelRatio() {
        return bluePixelRatio;
    }

    /**
     * 单行调试摘要（与 {@link VideoPointMappingResult#debugString()} 同风格，追加回投影信息）：
     * <pre>
     * status=SUCCESS touch=960.0,540.0 video=1280x720 content=[0,180,1920,900]
     * rotation=0 mirror=false,false scale=FIT_CENTER mappedVideo=640.0,360.0
     * mappedView=960.0,540.0 roundTripErrPx=0.0 inside=true eventTimeNs=...
     * roi=SUCCESS rect=[...] blue=1832 ratio=0.01145 ms=3
     * boxes=SUCCESS candidates=2 shown=2 hits=2 hitType=EXACT_HIT selected=[...] confidence=1.0
     * selScore=2.91 detectMs=1 hitTestMs=0 selectMs=0 totalMs=4 reason=EXACT_HIT best-of-2 score=2.910
     * label=SUCCESS search=[...] candidates=1 selected=[...] labelConf=0.91 labelDetectMs=0 labelCropMs=0 labelMs=0
     * </pre>
     * <p>未做视觉分析时最后一段整体不出现，保持与 Slice 3 完全一致的输出（旧日志仍然可比）。
     * 做了矩形恢复时，掩码段的 {@code ms=} 会被省略 —— 那时 {@link #processingMs} 已经是
     * 覆盖掩码 + 检测 + 命中的总耗时，同一数值在两段里各印一次只会让人误读成分步耗时。
     * 标签段同理"没做过就整段不输出"，因此只跑到 Slice 6 的日志不受影响。</p>
     */
    public String debugString() {
        final StringBuilder b = new StringBuilder(256);
        b.append(mapping.debugString())
                .append(" mappedView=").append(mappedViewX).append(',').append(mappedViewY)
                .append(" roundTripErrPx=").append(roundTripErrorPx)
                .append(" eventTimeNs=").append(eventTimeNs);
        if (hasVisionResult()) {
            b.append(" roi=").append(roiStatus)
                    .append(" rect=").append(searchRoi)
                    .append(" blue=").append(bluePixelCount)
                    .append(" ratio=").append(hasBlueRatio() ? bluePixelRatio : "n/a");
            if (!hasBoxDetection()) {
                b.append(" ms=").append(processingMs);
            }
        }
        if (hasBoxDetection()) {
            b.append(" boxes=").append(boxStatus)
                    .append(" candidates=").append(candidateCount)
                    .append(" shown=").append(detectedBoxes.size())
                    .append(" hits=").append(rankedCandidates.size())
                    .append(" hitType=").append(hitType == null ? "-" : hitType)
                    .append(" selected=").append(selectedBox)
                    .append(" confidence=").append(confidence)
                    .append(" selScore=").append(selectionScore)
                    .append(" detectMs=").append(detectMs)
                    .append(" hitTestMs=").append(hitTestMs)
                    .append(" selectMs=").append(selectionMs)
                    .append(" totalMs=").append(processingMs);
            if (selectionReason != null) {
                b.append(" reason=").append(selectionReason);
            }
        }
        // Slice 7：标签区域提取。同样"没做过就整段不输出"，于是旧场景的日志逐字不变。
        // 这里刻意不打 processingMs —— 它已是"整条链"的累计耗时（含前面的框检测），
        // 在标签段再印一次会被误读成"标签这一段花了那么久"。标签这一段的耗时看
        // labelDetectMs/labelCropMs/labelMs。
        if (hasLabelExtraction()) {
            b.append(" label=").append(labelStatus)
                    .append(" search=").append(labelSearchRegion == null ? "-" : labelSearchRegion)
                    .append(" candidates=").append(labelCandidates.size())
                    .append(" selected=").append(labelRoi == null ? "-" : labelRoi)
                    .append(" labelConf=").append(labelConfidence)
                    .append(" labelDetectMs=").append(labelDetectMs)
                    .append(" labelCropMs=").append(labelCropMs)
                    .append(" labelMs=").append(labelDetectMs + labelCropMs);
        }
        return b.toString();
    }

    /** 回投影：{@code float[3]} = {viewX, viewY, 往返误差px}；无法正推时返回 null。 */
    private static float[] projectBack(VideoPointMappingResult mapping) {
        if (mapping == null || !mapping.status.isMapped()) {
            return null;
        }
        final float[] viewPoint = VideoCoordinateMapper.mapVideoToView(
                mapping.geometry, mapping.videoX, mapping.videoY);
        if (viewPoint == null) {
            return null;
        }
        final float dx = viewPoint[0] - mapping.viewX;
        final float dy = viewPoint[1] - mapping.viewY;
        return new float[]{viewPoint[0], viewPoint[1], (float) Math.hypot(dx, dy)};
    }

    @Override
    public String toString() {
        return "TargetDebugState{" + debugString() + "}";
    }

    /**
     * 构造器。核心字段（mapping / eventTimeNs）必填，预留字段全部有默认值 ——
     * 这样「只想看触摸映射」的调用方不必为后续阶段的数据写任何占位代码。
     */
    public static final class Builder {

        private final VideoPointMappingResult mapping;
        private long eventTimeNs;
        private TargetBoundingBox searchRoi;
        private BlueMaskStatus roiStatus;
        private int bluePixelCount;
        private float bluePixelRatio = NO_RATIO;
        private List<DetectedTargetBox> detectedBoxes = Collections.emptyList();
        private TargetBoxDetectionStatus boxStatus;
        private int candidateCount;
        private TargetBoundingBox selectedBox;
        private List<TargetCandidateScore> rankedCandidates = Collections.emptyList();
        private TargetHitType hitType;
        private float selectionScore;
        private String selectionReason;
        private long selectionMs;
        private TargetBoundingBox labelSearchRegion;
        private List<LabelCandidate> labelCandidates = Collections.emptyList();
        private LabelExtractionStatus labelStatus;
        private float labelConfidence;
        private long labelDetectMs;
        private long labelCropMs;
        private TargetBoundingBox labelRoi;
        private String targetId;
        private float confidence;
        private long processingMs;
        private long detectMs;
        private long hitTestMs;

        private Builder(VideoPointMappingResult mapping, long eventTimeNs) {
            if (mapping == null) {
                throw new IllegalArgumentException("mapping must not be null");
            }
            if (eventTimeNs < 0L && eventTimeNs != TouchSelectionRequest.NO_EVENT_TIME_NS) {
                throw new IllegalArgumentException("eventTimeNs must be >= 0 or NO_EVENT_TIME_NS but was "
                        + eventTimeNs + "; use a monotonic clock (System.nanoTime()), not wall clock");
            }
            this.mapping = mapping;
            this.eventTimeNs = eventTimeNs;
        }

        /** 检索 ROI（视频坐标）；null 表示本次没有 ROI 可画。 */
        public Builder searchRoi(TargetBoundingBox searchRoi) {
            this.searchRoi = searchRoi;
            return this;
        }

        /** 蓝色掩码分析状态；null 表示本次没做视觉分析。 */
        public Builder roiStatus(BlueMaskStatus roiStatus) {
            this.roiStatus = roiStatus;
            return this;
        }

        /**
         * 一次性把 {@link BlueMaskResult} 的几项可视信息并进来：ROI 矩形、状态、
         * 蓝像素计数与占比、处理耗时（见 {@link TargetDebugState#withBlueMask(BlueMaskResult)}）。
         *
         * @throws IllegalArgumentException result 为 null
         */
        public Builder blueMask(BlueMaskResult result) {
            if (result == null) {
                throw new IllegalArgumentException("result must not be null");
            }
            return searchRoi(result.roiRectInVideo)
                    .roiStatus(result.status)
                    .bluePixelCount(result.bluePixelCount)
                    .bluePixelRatio(result.hasRoi() ? result.bluePixelRatio : NO_RATIO)
                    .processingMs(result.processingMs);
        }

        /** ROI 内蓝像素数。@throws IllegalArgumentException 为负 */
        public Builder bluePixelCount(int bluePixelCount) {
            if (bluePixelCount < 0) {
                throw new IllegalArgumentException("bluePixelCount must be >= 0 but was " + bluePixelCount);
            }
            this.bluePixelCount = bluePixelCount;
            return this;
        }

        /**
         * 蓝像素占比。
         *
         * @throws IllegalArgumentException 既不是 {@link #NO_RATIO}，也不在 [0,1] 内
         */
        public Builder bluePixelRatio(float bluePixelRatio) {
            if (bluePixelRatio != NO_RATIO
                    && (Float.isNaN(bluePixelRatio) || bluePixelRatio < 0f || bluePixelRatio > 1f)) {
                throw new IllegalArgumentException("bluePixelRatio must be within [0,1] or NO_RATIO but was "
                        + bluePixelRatio);
            }
            this.bluePixelRatio = bluePixelRatio;
            return this;
        }

        /** 候选框列表；null 视为空列表。元素不得为 null。 */
        public Builder detectedBoxes(List<DetectedTargetBox> detectedBoxes) {
            if (detectedBoxes == null) {
                this.detectedBoxes = Collections.emptyList();
                return this;
            }
            for (DetectedTargetBox box : detectedBoxes) {
                if (box == null) {
                    throw new IllegalArgumentException("detectedBoxes must not contain null elements");
                }
            }
            this.detectedBoxes = new ArrayList<>(detectedBoxes);
            return this;
        }

        /** 矩形恢复状态；null 表示本次没做矩形恢复。 */
        public Builder boxStatus(TargetBoxDetectionStatus boxStatus) {
            this.boxStatus = boxStatus;
            return this;
        }

        /** 候选框总数（检测器自报值）。@throws IllegalArgumentException 为负 */
        public Builder candidateCount(int candidateCount) {
            if (candidateCount < 0) {
                throw new IllegalArgumentException("candidateCount must be >= 0 but was " + candidateCount);
            }
            this.candidateCount = candidateCount;
            return this;
        }

        /**
         * 一次性把 {@link TargetBoxDetectionResult} 的可视信息并进来：候选框、状态、计数、
         * 选中框、置信度、命中分级、排名明细、耗时明细
         * （见 {@link TargetDebugState#withBoxDetection(TargetBoxDetectionResult)}）。
         *
         * <p>选中框取 {@link DetectedTargetBox#rectInVideo} —— 叠加层要画的是视频坐标矩形，
         * 而 {@link TargetDebugState#selectedBox} 的既有语义就是视频坐标，
         * 这里不做转换、只做取值，避免再引入一处坐标搬运。</p>
         *
         * @throws IllegalArgumentException result 为 null
         */
        public Builder boxDetection(TargetBoxDetectionResult result) {
            if (result == null) {
                throw new IllegalArgumentException("result must not be null");
            }
            final DetectedTargetBox selected = result.selectedBox;
            return detectedBoxes(result.candidates)
                    .boxStatus(result.status)
                    .candidateCount(result.candidateCount)
                    .selectedBox(selected == null ? null : selected.rectInVideo)
                    .rankedCandidates(result.rankedCandidates)
                    .hitType(result.hitType)
                    .selectionScore(result.selectionScore)
                    .selectionReason(result.selectionReason)
                    .confidence(selected == null ? 0f : selected.confidence)
                    // processingMs 覆盖为总耗时：调试看的是"这次点击一共花了多久"
                    .processingMs(result.totalMs)
                    .timings(result.detectMs, result.hitTestMs)
                    .selectionMs(result.selectionMs);
        }

        /**
         * 命中候选的评分明细（rank 0 起）。
         *
         * @throws IllegalArgumentException 列表含 null 元素；{@code selectionScore} 不在 [0,1] 时见
         *         {@link #selectionScore(float)} 的说明（本字段本身不校验范围）
         */
        public Builder rankedCandidates(List<TargetCandidateScore> rankedCandidates) {
            if (rankedCandidates == null) {
                this.rankedCandidates = Collections.emptyList();
                return this;
            }
            for (TargetCandidateScore score : rankedCandidates) {
                if (score == null) {
                    throw new IllegalArgumentException("rankedCandidates must not contain null elements");
                }
            }
            this.rankedCandidates = new ArrayList<>(rankedCandidates);
            return this;
        }

        /** 命中分级；null 表示本次没做选择或未命中。 */
        public Builder hitType(TargetHitType hitType) {
            this.hitType = hitType;
            return this;
        }

        /**
         * 选中框的加权总分。
         *
         * <p><b>不</b>校验范围：加权总分可以是负数（例如容差命中加上较大的中心距离惩罚），
         * 它不是一个概率量。需要 [0,1] 语义请用
         * {@link TargetDebugState#confidence}（那是框自身的置信度）。</p>
         */
        public Builder selectionScore(float selectionScore) {
            if (!Float.isFinite(selectionScore)) {
                throw new IllegalArgumentException("selectionScore must be finite but was " + selectionScore);
            }
            this.selectionScore = selectionScore;
            return this;
        }

        /** 选择理由（英文）；null 表示未做选择。 */
        public Builder selectionReason(String selectionReason) {
            this.selectionReason = selectionReason;
            return this;
        }

        /** 多目标选择耗时。@throws IllegalArgumentException 为负 */
        public Builder selectionMs(long selectionMs) {
            if (selectionMs < 0L) {
                throw new IllegalArgumentException("selectionMs must be >= 0 but was " + selectionMs);
            }
            this.selectionMs = selectionMs;
            return this;
        }

        /**
         * 一次性把 {@link LabelExtractionResult} 的可视信息并进来：搜索区域、全部候选、
         * 选中的标签、状态、置信度、分步耗时
         * （见 {@link TargetDebugState#withLabelExtraction(LabelExtractionResult)}）。
         *
         * <p>选中标签取 {@link LabelExtractionResult#getLabelRectInVideo()} ——
         * 叠加层要画的是视频坐标矩形，与 {@link TargetDebugState#labelRoi} 的既有语义一致，
         * 这里不做转换、只做取值。</p>
         *
         * <p>{@code processingMs} 是<b>累加</b>而不是覆盖：标签提取在矩形恢复之后，
         * 覆盖会让"这次点击一共花了多久"丢掉前半段。</p>
         *
         * @throws IllegalArgumentException result 为 null
         */
        public Builder labelExtraction(LabelExtractionResult result) {
            if (result == null) {
                throw new IllegalArgumentException("result must not be null");
            }
            final LabelCandidate selected = result.selectedLabel;
            return labelSearchRegion(result.labelSearchRegion)
                    .labelCandidates(result.candidates)
                    .labelStatus(result.status)
                    .labelRoi(result.getLabelRectInVideo())
                    .labelConfidence(selected == null ? 0f : selected.confidence)
                    .labelTimings(result.labelDetectMs, result.labelCropMs)
                    .processingMs(processingMs + result.totalMs);
        }

        /** 标签搜索区域（视频坐标）；null 表示本次没做标签提取。 */
        public Builder labelSearchRegion(TargetBoundingBox labelSearchRegion) {
            this.labelSearchRegion = labelSearchRegion;
            return this;
        }

        /** 标签候选列表；null 视为空列表。元素不得为 null。 */
        public Builder labelCandidates(List<LabelCandidate> labelCandidates) {
            if (labelCandidates == null) {
                this.labelCandidates = Collections.emptyList();
                return this;
            }
            for (LabelCandidate candidate : labelCandidates) {
                if (candidate == null) {
                    throw new IllegalArgumentException("labelCandidates must not contain null elements");
                }
            }
            this.labelCandidates = new ArrayList<>(labelCandidates);
            return this;
        }

        /** 标签提取状态；null 表示本次没做标签提取。 */
        public Builder labelStatus(LabelExtractionStatus labelStatus) {
            this.labelStatus = labelStatus;
            return this;
        }

        /** 选中标签的置信度。@throws IllegalArgumentException 不在 [0,1] 内 */
        public Builder labelConfidence(float labelConfidence) {
            if (Float.isNaN(labelConfidence) || labelConfidence < 0f || labelConfidence > 1f) {
                throw new IllegalArgumentException("labelConfidence must be within [0,1] but was "
                        + labelConfidence);
            }
            this.labelConfidence = labelConfidence;
            return this;
        }

        /**
         * 标签提取的分步耗时。
         *
         * @throws IllegalArgumentException 任一为负
         */
        public Builder labelTimings(long labelDetectMs, long labelCropMs) {
            if (labelDetectMs < 0L) {
                throw new IllegalArgumentException("labelDetectMs must be >= 0 but was " + labelDetectMs);
            }
            if (labelCropMs < 0L) {
                throw new IllegalArgumentException("labelCropMs must be >= 0 but was " + labelCropMs);
            }
            this.labelDetectMs = labelDetectMs;
            this.labelCropMs = labelCropMs;
            return this;
        }

        /** 分步耗时。@throws IllegalArgumentException 任一为负 */
        public Builder timings(long detectMs, long hitTestMs) {
            if (detectMs < 0L) {
                throw new IllegalArgumentException("detectMs must be >= 0 but was " + detectMs);
            }
            if (hitTestMs < 0L) {
                throw new IllegalArgumentException("hitTestMs must be >= 0 but was " + hitTestMs);
            }
            this.detectMs = detectMs;
            this.hitTestMs = hitTestMs;
            return this;
        }

        /** 选中的框（视频坐标）。 */
        public Builder selectedBox(TargetBoundingBox selectedBox) {
            this.selectedBox = selectedBox;
            return this;
        }

        /** 选中的标签区域（视频坐标）；null 表示没找到标签或没做标签提取。 */
        public Builder labelRoi(TargetBoundingBox labelRoi) {
            this.labelRoi = labelRoi;
            return this;
        }

        /** 目标 id。本 Slice 恒为 null（标签提取不识别文字内容）。 */
        public Builder targetId(String targetId) {
            this.targetId = targetId;
            return this;
        }

        /** 预留：置信度。@throws IllegalArgumentException 不在 [0,1] 内 */
        public Builder confidence(float confidence) {
            if (Float.isNaN(confidence) || confidence < 0f || confidence > 1f) {
                throw new IllegalArgumentException("confidence must be within [0,1] but was " + confidence);
            }
            this.confidence = confidence;
            return this;
        }

        /** 预留：识别耗时。@throws IllegalArgumentException 为负 */
        public Builder processingMs(long processingMs) {
            if (processingMs < 0L) {
                throw new IllegalArgumentException("processingMs must be >= 0 but was " + processingMs);
            }
            this.processingMs = processingMs;
            return this;
        }

        public TargetDebugState build() {
            return new TargetDebugState(this);
        }
    }
}
