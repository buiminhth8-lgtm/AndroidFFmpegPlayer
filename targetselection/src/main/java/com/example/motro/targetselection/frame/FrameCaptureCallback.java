package com.example.motro.targetselection.frame;

/**
 * 异步取帧的完成通知。**保证恰好被调用一次**，无论成功、失败还是取消。
 *
 * <p>实现方约定（由 {@link AbstractFrameSource} 保证，自定义实现请遵守）：</p>
 * <ul>
 *   <li>回调在调用方指定的 Executor 上执行，不在调用线程上执行；</li>
 *   <li>结果由回调方接管：必须调用 {@link FrameCaptureResult#takeFrame()}
 *       或 {@link FrameCaptureResult#release()}，否则位图不会被回收；</li>
 *   <li>抛出异常不会改变“只回调一次”的保证，但会污染该 Executor 的线程，请勿抛出。</li>
 * </ul>
 */
public interface FrameCaptureCallback {

    /**
     * 取帧结束。
     *
     * @param result 非 null，可能成功也可能失败；所有权见类注释
     */
    void onCaptureCompleted(FrameCaptureResult result);
}
