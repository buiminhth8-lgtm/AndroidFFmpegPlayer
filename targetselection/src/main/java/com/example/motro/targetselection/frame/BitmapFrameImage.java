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

    /**
     * {@inheritDoc}
     *
     * <p>直接委托 {@code Bitmap.getPixels}：一次调用即完成整块拷贝，没有逐像素 JNI 往返 ——
     * 这正是 ROI 只裁「点击附近一小块」而不是整帧的原因（整帧 {@code getPixels} 在
     * 1080p 上就是 8MB 级别的搬运，而 400×400 的 ROI 只要 640KB）。</p>
     *
     * <p>读取前先在锁内取出位图引用：与 {@link #release()} 竞争时，要么读到有效位图，
     * 要么明确返回 false，不会拿到已回收的对象去读。</p>
     */
    @Override
    public boolean readArgbRegion(int x, int y, int width, int height,
                                  int[] out, int offset, int stride) {
        final Bitmap target;
        synchronized (lock) {
            target = released ? null : bitmap;
        }
        if (target == null || target.isRecycled()) {
            return false;
        }
        target.getPixels(out, offset, stride, x, y, width, height);
        return true;
    }

    @Override
    public String toString() {
        return "BitmapFrameImage{" + width + "x" + height
                + ", ownsBitmap=" + ownsBitmap + ", released=" + isReleased() + "}";
    }
}
