package com.example.motro.targetselection.debug;

import com.example.motro.targetselection.api.TouchSelectionRequest;
import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoPointMappingResult;
import com.example.motro.targetselection.geometry.VideoPointMappingStatus;
import com.example.motro.targetselection.geometry.VideoScaleType;
import com.example.motro.targetselection.vision.BlueMaskResult;
import com.example.motro.targetselection.vision.BlueMaskStatus;

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

    // ---------------------------------------------------------------- 预留字段（后续 Slice 产生）

    /** 预留：识别出的候选框（视频坐标）。矩形恢复之前恒为空列表。 */
    public final List<TargetBoundingBox> detectedBoxes;

    /** 预留：最终选中的框（视频坐标）。本 Slice 恒为 null。 */
    public final TargetBoundingBox selectedBox;

    /** 预留：标签识别区域（视频坐标）。本 Slice 恒为 null。 */
    public final TargetBoundingBox labelRoi;

    /** 预留：目标 id。本 Slice 恒为 null。 */
    public final String targetId;

    /** 预留：置信度 [0,1]。本 Slice 恒为 0。 */
    public final float confidence;

    /**
     * 本次点击的处理耗时（毫秒）。视觉阶段由 {@link #withBlueMask(BlueMaskResult)} 填入
     * （ROI 裁剪 + 颜色分析的总耗时）；未做视觉分析时为 0。
     */
    public final long processingMs;

    private TargetDebugState(Builder builder) {
        this.mapping = builder.mapping;
        this.eventTimeNs = builder.eventTimeNs;
        this.searchRoi = builder.searchRoi;
        this.roiStatus = builder.roiStatus;
        this.bluePixelCount = builder.bluePixelCount;
        this.bluePixelRatio = builder.bluePixelRatio;
        this.detectedBoxes = Collections.unmodifiableList(new ArrayList<>(builder.detectedBoxes));
        this.selectedBox = builder.selectedBox;
        this.labelRoi = builder.labelRoi;
        this.targetId = builder.targetId;
        this.confidence = builder.confidence;
        this.processingMs = builder.processingMs;

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
                .selectedBox(selectedBox)
                .labelRoi(labelRoi)
                .targetId(targetId)
                .confidence(confidence)
                .processingMs(processingMs);
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
     * </pre>
     * <p>未做视觉分析时最后一段整体不出现，保持与 Slice 3 完全一致的输出（旧日志仍然可比）。</p>
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
                    .append(" ratio=").append(hasBlueRatio() ? bluePixelRatio : "n/a")
                    .append(" ms=").append(processingMs);
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
        private List<TargetBoundingBox> detectedBoxes = Collections.emptyList();
        private TargetBoundingBox selectedBox;
        private TargetBoundingBox labelRoi;
        private String targetId;
        private float confidence;
        private long processingMs;

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

        /** 预留：候选框列表；null 视为空列表。元素不得为 null。 */
        public Builder detectedBoxes(List<TargetBoundingBox> detectedBoxes) {
            if (detectedBoxes == null) {
                this.detectedBoxes = Collections.emptyList();
                return this;
            }
            for (TargetBoundingBox box : detectedBoxes) {
                if (box == null) {
                    throw new IllegalArgumentException("detectedBoxes must not contain null elements");
                }
            }
            this.detectedBoxes = new ArrayList<>(detectedBoxes);
            return this;
        }

        /** 预留：选中的框。 */
        public Builder selectedBox(TargetBoundingBox selectedBox) {
            this.selectedBox = selectedBox;
            return this;
        }

        /** 预留：标签识别区域。 */
        public Builder labelRoi(TargetBoundingBox labelRoi) {
            this.labelRoi = labelRoi;
            return this;
        }

        /** 预留：目标 id。 */
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
