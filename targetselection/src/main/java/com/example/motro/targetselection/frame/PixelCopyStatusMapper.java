package com.example.motro.targetselection.frame;

/**
 * 把平台回读（此处是 PixelCopy）的返回码翻译成本模块的 {@link FrameCaptureStatus}。
 *
 * <p><b>为什么要有这一层</b>：平台错误码是“实现细节”，一旦泄漏到业务层，
 * 上层就会被某一种平台绑死，也无法为新增来源（相机、录屏、第三方播放器）
 * 定义统一的处理策略。因此业务层只认 {@link FrameCaptureStatus}，
 * 原始码仅作为诊断字段保留在 {@link FrameCaptureResult#rawCode}。</p>
 *
 * <p>本类刻意不 import 任何 Android 类型（下面的常量只是同值镜像），
 * 因此可以在纯 JVM 单测里逐条验证映射表；与平台常量的同值关系由
 * {@code PixelCopyStatusMapperTest} 断言，防止手册性漂移。</p>
 */
public final class PixelCopyStatusMapper {

    /** 镜像 {@code android.view.PixelCopy.SUCCESS}。 */
    public static final int SUCCESS = 0;

    /** 镜像 {@code android.view.PixelCopy.ERROR_UNKNOWN}。 */
    public static final int ERROR_UNKNOWN = 1;

    /** 镜像 {@code android.view.PixelCopy.ERROR_TIMEOUT}。 */
    public static final int ERROR_TIMEOUT = 2;

    /** 镜像 {@code android.view.PixelCopy.ERROR_SOURCE_NO_DATA}。 */
    public static final int ERROR_SOURCE_NO_DATA = 3;

    /** 镜像 {@code android.view.PixelCopy.ERROR_SOURCE_INVALID}。 */
    public static final int ERROR_SOURCE_INVALID = 4;

    /** 镜像 {@code android.view.PixelCopy.ERROR_DESTINATION_INVALID}。 */
    public static final int ERROR_DESTINATION_INVALID = 5;

    private PixelCopyStatusMapper() {
    }

    /**
     * 翻译返回码。
     *
     * <p>映射取舍：</p>
     * <ul>
     *   <li>{@code ERROR_SOURCE_NO_DATA} → {@code NO_FRAME}：Surface 有效但当前没有待合成
     *       的缓冲，属于“还没出图”的正常时序，重试即可，不该报成故障。</li>
     *   <li>{@code ERROR_SOURCE_INVALID} → {@code SOURCE_NOT_READY}：Surface 失效
     *       （窗口被销毁/切换），等下一次生命周期回调。</li>
     *   <li>{@code ERROR_TIMEOUT} → {@code TIMEOUT}：保留语义，便于上层调参数。</li>
     *   <li>{@code ERROR_DESTINATION_INVALID} → {@code CAPTURE_FAILED}：目标位图非法
     *       是实现方的编程错误，必须暴露而不是被当作时序问题吞掉。</li>
     * </ul>
     *
     * @param rawResult 平台原始返回码
     * @return 对应的模块状态，未知码一律 {@code CAPTURE_FAILED}
     */
    public static FrameCaptureStatus map(int rawResult) {
        switch (rawResult) {
            case SUCCESS:
                return FrameCaptureStatus.SUCCESS;
            case ERROR_SOURCE_NO_DATA:
                return FrameCaptureStatus.NO_FRAME;
            case ERROR_SOURCE_INVALID:
                return FrameCaptureStatus.SOURCE_NOT_READY;
            case ERROR_TIMEOUT:
                return FrameCaptureStatus.TIMEOUT;
            default:
                // ERROR_DESTINATION_INVALID / ERROR_UNKNOWN / 未来新增码：一律当作真实故障，
                // 宁可按失败上报，也不要误报成“没帧”而让上层无限重试。
                return FrameCaptureStatus.CAPTURE_FAILED;
        }
    }

    /** 返回码的人可读名字，仅用于日志。 */
    public static String describe(int rawResult) {
        switch (rawResult) {
            case SUCCESS:
                return "SUCCESS";
            case ERROR_UNKNOWN:
                return "ERROR_UNKNOWN";
            case ERROR_TIMEOUT:
                return "ERROR_TIMEOUT";
            case ERROR_SOURCE_NO_DATA:
                return "ERROR_SOURCE_NO_DATA";
            case ERROR_SOURCE_INVALID:
                return "ERROR_SOURCE_INVALID";
            case ERROR_DESTINATION_INVALID:
                return "ERROR_DESTINATION_INVALID";
            default:
                return "UNRECOGNIZED(" + rawResult + ")";
        }
    }
}
