package com.example.motro.targetselection.geometry;

/**
 * 「View 坐标 → 视频像素坐标」的完整结果，不可变。上层据此拿到准确坐标，也能一眼看懂
 * 几何是怎么算的（{@link #debugString()}）。
 *
 * <p><b>坐标系与归一化定义</b>：</p>
 * <ul>
 *   <li>{@link #viewX}/{@link #viewY}：输入原样保留，View 坐标（原点在 View 左上角，像素）。</li>
 *   <li>{@link #videoX}/{@link #videoY}：**解码帧**坐标（原点在解码帧左上角，像素，
 *       尺度为 {@link VideoGeometry#videoWidth}/{@code videoHeight}）。仅在
 *       {@link #status} 为 {@link VideoPointMappingStatus#SUCCESS} 时有效，否则为
 *       {@link #NO_COORDINATE}（{@code NaN}，不是 0 —— 0 是合法坐标）。</li>
 *   <li>{@link #normalizedX}/{@link #normalizedY}：上面的视频坐标除以视频宽高，落在 [0,1]；
 *       用于与识别结果做尺度无关的比较。非成功时为 {@link #NO_COORDINATE}。</li>
 * </ul>
 *
 * <p><b>不变量</b>（构造处强制，见各工厂方法）：{@code status == SUCCESS}
 * ⟺ {@link #insideVideoContent} ⟺ 视频坐标 &amp; 归一化坐标均非 NaN。其余状态一律不带坐标，
 * 避免上层误把 0 或上次残留当成有效命中点。</p>
 *
 * <p>线程约定：不可变对象，可跨线程安全共享（可从工作线程传回主线程更新 UI）。</p>
 */
public final class VideoPointMappingResult {

    /** 未映射时坐标字段的哨兵值。0 是合法坐标，所以不能用 0 表示“没有”。 */
    public static final float NO_COORDINATE = Float.NaN;

    /** 本次映射的结局。 */
    public final VideoPointMappingStatus status;

    /** 输入点 View 坐标 x（原样保留，便于日志比对）。 */
    public final float viewX;

    /** 输入点 View 坐标 y（原样保留）。 */
    public final float viewY;

    /** 视频（解码帧）像素 x；非成功时为 {@link #NO_COORDINATE}。 */
    public final float videoX;

    /** 视频（解码帧）像素 y；非成功时为 {@link #NO_COORDINATE}。 */
    public final float videoY;

    /** {@link #videoX} 相对视频宽度的归一化值 [0,1]；非成功时为 {@link #NO_COORDINATE}。 */
    public final float normalizedX;

    /** {@link #videoY} 相对视频高度的归一化值 [0,1]；非成功时为 {@link #NO_COORDINATE}。 */
    public final float normalizedY;

    /** 点是否落在可见视频区域内。仅 {@code SUCCESS} 为 true。 */
    public final boolean insideVideoContent;

    /**
     * 本次映射所用的几何快照（可能为 null，例如几何来源直接返回 null）。
     * 仅用于诊断与 {@link #debugString()}，不改写、不持有播放器引用。
     */
    public final VideoGeometry geometry;

    private VideoPointMappingResult(VideoPointMappingStatus status,
                                    VideoGeometry geometry,
                                    float viewX, float viewY,
                                    float videoX, float videoY,
                                    float normalizedX, float normalizedY,
                                    boolean insideVideoContent) {
        this.status = status;
        this.geometry = geometry;
        this.viewX = viewX;
        this.viewY = viewY;
        this.videoX = videoX;
        this.videoY = videoY;
        this.normalizedX = normalizedX;
        this.normalizedY = normalizedY;
        this.insideVideoContent = insideVideoContent;
    }

    /**
     * 成功映射。由 {@link VideoCoordinateMapper} 在完成“去 offset → 归一化 → 反镜像 →
     * 反旋转 → 裁剪”后调用；坐标必须是有限值。
     *
     * @throws IllegalArgumentException 坐标含 NaN / Infinity
     */
    public static VideoPointMappingResult success(VideoGeometry geometry,
                                                  float viewX, float viewY,
                                                  float videoX, float videoY,
                                                  float normalizedX, float normalizedY) {
        requireFinite(videoX, "videoX");
        requireFinite(videoY, "videoY");
        requireFinite(normalizedX, "normalizedX");
        requireFinite(normalizedY, "normalizedY");
        return new VideoPointMappingResult(VideoPointMappingStatus.SUCCESS, geometry,
                viewX, viewY, videoX, videoY, normalizedX, normalizedY, true);
    }

    /** 点在可见视频区域之外（黑边 / View 之外）：保留输入点，但不给坐标。 */
    public static VideoPointMappingResult outsideVideo(VideoGeometry geometry, float viewX, float viewY) {
        return new VideoPointMappingResult(VideoPointMappingStatus.OUTSIDE_VIDEO, geometry,
                viewX, viewY, NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, false);
    }

    /** 几何未知或退化，无法映射。 */
    public static VideoPointMappingResult invalidGeometry(VideoGeometry geometry, float viewX, float viewY) {
        return new VideoPointMappingResult(VideoPointMappingStatus.INVALID_GEOMETRY, geometry,
                viewX, viewY, NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, NO_COORDINATE, false);
    }

    /** 是否拿到了可用视频坐标（等价于 {@code status == SUCCESS}）。 */
    public boolean hasVideoCoordinates() {
        return status.isMapped();
    }

    /**
     * 单行调试摘要，格式稳定（便于日志抓取与人工比对）：
     * <pre>
     * status=SUCCESS view=960.0,540.0 content=[0,0,1920,1080] video=1280x720
     * rotation=0 mirror=false,false scale=FIT_CENTER mapped=640.0,360.0 inside=true
     * </pre>
     */
    public String debugString() {
        final String content = geometry == null
                ? "-"
                : "[" + geometry.contentLeft + "," + geometry.contentTop + ","
                        + geometry.contentRight + "," + geometry.contentBottom + "]";
        final String video = geometry == null
                ? "-"
                : geometry.videoWidth + "x" + geometry.videoHeight;
        final String rotation = geometry == null ? "-" : String.valueOf(geometry.rotationDegrees);
        final String mirror = geometry == null
                ? "-"
                : geometry.mirrorX + "," + geometry.mirrorY;
        final String scale = geometry == null ? "-" : String.valueOf(geometry.scaleType);
        return "status=" + status
                + " view=" + viewX + "," + viewY
                + " content=" + content
                + " video=" + video
                + " rotation=" + rotation
                + " mirror=" + mirror
                + " scale=" + scale
                + " mapped=" + videoX + "," + videoY
                + " inside=" + insideVideoContent;
    }

    private static void requireFinite(float value, String name) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            throw new IllegalArgumentException(name + " must be finite but was " + value);
        }
    }

    @Override
    public String toString() {
        return "VideoPointMappingResult{" + debugString() + "}";
    }
}
