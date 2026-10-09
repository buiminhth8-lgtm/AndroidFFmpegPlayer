package com.example.motro.targetselection.frame;

/**
 * 一次取帧的结局。<b>平台无关</b>：具体平台（PixelCopy / MediaCodec / 相机）的原始
 * 错误码只允许出现在 {@link FrameCaptureResult#rawCode} 里做诊断，不得提升到本枚举，
 * 否则业务层会被某一种实现的返回码绑死。
 *
 * <p>为什么要把这些情况分开，而不是笼统的“成功/失败”：</p>
 * <ul>
 *   <li>{@link #NO_FRAME} 与 {@link #CAPTURE_FAILED} 的处置完全不同 ——
 *       前者是正常时序（还没起播），重试即可；后者是真实故障，要暴露出来。</li>
 *   <li>{@link #SOURCE_NOT_READY} 与 {@link #INVALID_SIZE} 区分“View 生命周期没到”
 *       和“拿到了 View 但尺寸不可用”，定位问题时能直接指向不同代码路径。</li>
 *   <li>{@link #TIMEOUT} 与 {@link #CANCELLED} 都是“没拿到帧”，但一个要调超时参数、
 *       一个要查调用方逻辑，混在一起会掩盖问题。</li>
 * </ul>
 */
public enum FrameCaptureStatus {

    /** 取帧成功，{@link FrameCaptureResult} 里带一张可用图像。 */
    SUCCESS,

    /**
     * 来源当前**没有可用画面**（首帧未到、已暂停且无缓存缓冲、Surface 有效但还没出图）。
     * 属于正常时序而非错误，调用方稍后重试即可。
     */
    NO_FRAME,

    /**
     * 承载 View / 平台表面尚未就绪：View 未 attach、Surface 无效、TextureView 不可用、
     * 或来源已 {@code close()}。等下一次生命周期回调后再试。
     */
    SOURCE_NOT_READY,

    /** View 尺寸为 0 或无法据此算出合法的目标位图尺寸（`INVALID_SIZE` 不属于时序问题）。 */
    INVALID_SIZE,

    /**
     * 该来源不支持本次请求（例如在**主线程**上调用阻塞式 {@code capture()}，
     * 或请求了本来源无法满足的特性）。调用方需改用其它入口，重试无用。
     */
    UNSUPPORTED,

    /** 在时间预算内没有拿到结果。注意：此时平台侧可能仍在写目标位图，实现负责不泄漏。 */
    TIMEOUT,

    /** 请求在完成前被取消（调用方 {@code cancel()}、来源被关闭）。 */
    CANCELLED,

    /** 取帧确实失败（创建位图失败、回读失败、平台返回未预期错误码）。 */
    CAPTURE_FAILED;

    /** 是否拿到了可用画面。 */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /** 是否“再试一次可能成功”（时序类问题），区别于需要改代码/改参数的失败。 */
    public boolean isRetryable() {
        switch (this) {
            case NO_FRAME:
            case SOURCE_NOT_READY:
            case TIMEOUT:
                return true;
            default:
                return false;
        }
    }

    /** 是否属于需要上报的异常路径（非成功即失败；调用方据此决定是否提示用户）。 */
    public boolean isFailure() {
        return this != SUCCESS;
    }
}
