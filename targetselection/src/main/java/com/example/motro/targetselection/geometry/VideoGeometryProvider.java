package com.example.motro.targetselection.geometry;

/**
 * 视频几何的来源。由 app 层实现（从 GL 渲染器、PlayerView、Camera2 Preview 等处取数），
 * 模块内只消费 {@link VideoGeometry}，不感知具体播放器。
 *
 * <p>线程约定：{@link #currentGeometry()} 会在**调用方线程**（通常是点击后的工作线程）被调用，
 * 实现必须返回一个不可变快照，且不得阻塞超过数毫秒 —— 该方法位于点击响应路径上。
 * 实现方需自行保证内部读写的可见性（例如 volatile 或同步块）。</p>
 *
 * <p>所有权：Provider 由 app 层创建与释放，控制器只借用，不负责关闭。</p>
 */
public interface VideoGeometryProvider {

    /**
     * 返回当前渲染几何。
     *
     * @return 快照；无法确定时返回 {@code null} 或 {@link VideoGeometry#EMPTY}，
     *         控制器会据此返回 {@code GEOMETRY_UNAVAILABLE} 而不是猜测坐标
     */
    VideoGeometry currentGeometry();
}
