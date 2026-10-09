package com.example.motro.targetselection.frame;

/**
 * 帧获取失败。<b>只用来表达“拿到了但拿不到可用画面”</b>，与“当前没有帧”区分开：
 *
 * <ul>
 *   <li>抛出本异常 → 控制器返回 {@code CAPTURE_FAILED}（解码/渲染/超时等确实失败了）</li>
 *   <li>{@code captureFrame} 返回 {@code null} 或没有图像载荷 → 控制器返回 {@code NO_FRAME}
 *       （属于正常时序，例如刚开始播放还没有首帧）</li>
 * </ul>
 *
 * <p>异常消息会被原样带进 {@code TargetHitResult.reason} 供排查，不要放敏感内容。</p>
 */
public class FrameCaptureException extends Exception {

    private static final long serialVersionUID = 1L;

    public FrameCaptureException(String message) {
        super(message);
    }

    public FrameCaptureException(String message, Throwable cause) {
        super(message, cause);
    }
}
