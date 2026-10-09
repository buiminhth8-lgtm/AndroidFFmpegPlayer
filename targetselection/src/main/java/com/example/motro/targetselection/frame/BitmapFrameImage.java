package com.example.motro.targetselection.frame;

import android.graphics.Bitmap;

/**
 * 基于 Android {@link Bitmap} 的 {@link FrameImage} 实现 —— 绝大多数场景的默认载体。
 *
 * <p><b>所有权（关键，防内存泄漏的第二道闸）</b>：</p>
 * <ul>
 *   <li>{@code ownsBitmap == true}：本对象负责在 {@link #release()} 时 {@code recycle()}。
 *       用于“这次回读产出的、独占的”位图（典型做法：{@code bitmap.copy()} 出一份再交给本类）。
 *       交出所有权后调用方**不得**再回收。</li>
 *   <li>{@code ownsBitmap == false}：只解除引用，不回收。用于共享位图（如播放器复用的缓冲、
 *       由 View 持有的缓存），避免误回收别人还在用的位图 ——
 *       宁可晚回收也不要 use-after-recycle。</li>
 * </ul>
 *
 * <p>释放动作在锁外执行，避免持锁等待系统回收。{@link #release()} 幂等。</p>
 */
public final class BitmapFrameImage implements FrameImage {

    private final Object lock = new Object();
    private final boolean ownsBitmap;

    private Bitmap bitmap;
    private boolean released;
    private final int width;
    private final int height;

    private BitmapFrameImage(Bitmap bitmap, boolean ownsBitmap) {
        this.bitmap = bitmap;
        this.ownsBitmap = ownsBitmap;
        this.width = bitmap.getWidth();
        this.height = bitmap.getHeight();
    }

    /**
     * @param bitmap     非 null
     * @param ownsBitmap true = 由本对象负责 {@code recycle()}
     * @throws IllegalArgumentException bitmap 为 null
     */
    public static BitmapFrameImage of(Bitmap bitmap, boolean ownsBitmap) {
        if (bitmap == null) {
            throw new IllegalArgumentException("bitmap must not be null");
        }
        return new BitmapFrameImage(bitmap, ownsBitmap);
    }

    /** 底层位图；已释放返回 null。 */
    public Bitmap bitmap() {
        synchronized (lock) {
            return released ? null : bitmap;
        }
    }

    public boolean isReleased() {
        synchronized (lock) {
            return released;
        }
    }

    public boolean ownsBitmap() {
        return ownsBitmap;
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public void release() {
        final Bitmap toRecycle;
        synchronized (lock) {
            if (released) {
                return;
            }
            released = true;
            toRecycle = bitmap;
            bitmap = null;
        }
        // 锁外回收，避免持锁期间等待系统资源释放
        if (ownsBitmap && toRecycle != null && !toRecycle.isRecycled()) {
            toRecycle.recycle();
        }
    }

    @Override
    public String toString() {
        return "BitmapFrameImage{" + width + "x" + height
                + ", ownsBitmap=" + ownsBitmap + ", released=" + isReleased() + "}";
    }
}
