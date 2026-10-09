package com.example.motro.targetselection.debug;

import com.example.motro.targetselection.api.TouchSelectionRequest;
import com.example.motro.targetselection.geometry.TargetBoundingBox;
import com.example.motro.targetselection.geometry.VideoCoordinateMapper;
import com.example.motro.targetselection.geometry.VideoPointMappingResult;
import com.example.motro.targetselection.geometry.VideoPointMappingStatus;
import com.example.motro.targetselection.geometry.VideoScaleType;

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

    // ---------------------------------------------------------------- 预留字段（本 Slice 不产生）

    /** 预留：检索 ROI（视频坐标）。本 Slice 恒为 null。 */
    public final TargetBoundingBox searchRoi;

    /** 预留：识别出的候选框（视频坐标）。本 Slice 恒为空列表。 */
    public final List<TargetBoundingBox> detectedBoxes;

    /** 预留：最终选中的框（视频坐标）。本 Slice 恒为 null。 */
    public final TargetBoundingBox selectedBox;

    /** 预留：标签识别区域（视频坐标）。本 Slice 恒为 null。 */
    public final TargetBoundingBox labelRoi;

    /** 预留：目标 id。本 Slice 恒为 null。 */
    public final String targetId;

    /** 预留：置信度 [0,1]。本 Slice 恒为 0。 */
    public final float confidence;

    /** 预留：识别耗时（毫秒）。本 Slice 恒为 0。 */
    public final long processingMs;

    private TargetDebugState(Builder builder) {
        this.mapping = builder.mapping;
        this.eventTimeNs = builder.eventTimeNs;
        this.searchRoi = builder.searchRoi;
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
                .detectedBoxes(detectedBoxes)
                .selectedBox(selectedBox)
                .labelRoi(labelRoi)
                .targetId(targetId)
                .confidence(confidence)
                .processingMs(processingMs);
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

    /**
     * 单行调试摘要（与 {@link VideoPointMappingResult#debugString()} 同风格，追加回投影信息）：
     * <pre>
     * status=SUCCESS touch=960.0,540.0 video=1280x720 content=[0,180,1920,900]
     * rotation=0 mirror=false,false scale=FIT_CENTER mappedVideo=640.0,360.0
     * mappedView=960.0,540.0 roundTripErrPx=0.0 inside=true eventTimeNs=...
     * </pre>
     */
    public String debugString() {
        return mapping.debugString()
                + " mappedView=" + mappedViewX + "," + mappedViewY
                + " roundTripErrPx=" + roundTripErrorPx
                + " eventTimeNs=" + eventTimeNs;
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

        /** 预留：检索 ROI。 */
        public Builder searchRoi(TargetBoundingBox searchRoi) {
            this.searchRoi = searchRoi;
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
