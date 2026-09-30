package com.example.motro.ffmpeg;

/**
 * MediaCodec Low-Latency 的纯状态模型。该类不访问 MediaCodec，也不下发任何参数；
 * 它只保存一次 decoder session 的可观测证据，并在实例重建时整体替换，避免旧状态残留。
 */
public final class MediaCodecLowLatencyState {

    public static final String SUPPORTED = "SUPPORTED";
    public static final String UNSUPPORTED = "UNSUPPORTED";
    public static final String UNKNOWN = "UNKNOWN";
    public static final String UNAVAILABLE = "UNAVAILABLE";
    public static final String UNAVAILABLE_API_LEVEL = "UNAVAILABLE_API_LEVEL";
    public static final String YES = "YES";
    public static final String NO = "NO";

    private Snapshot current = Snapshot.unavailable("decoder unavailable");

    /** API/capability 三态映射；无法查询时必须保留 UNKNOWN。 */
    public static String capabilityState(boolean apiAvailable, Boolean supported) {
        if (!apiAvailable) {
            return UNAVAILABLE_API_LEVEL;
        }
        if (supported == null) {
            return UNKNOWN;
        }
        return supported ? SUPPORTED : UNSUPPORTED;
    }

    /** 用新 decoder session 的完整证据替换旧状态。 */
    public synchronized Snapshot onDecoderCreated(
            long generation,
            String requestedDecoder,
            String actualDecoder,
            String decodeBackend,
            boolean hardwareDecode,
            boolean hardwareFallback,
            String frameOutputType,
            String renderer,
            String component,
            String componentSource,
            String mime,
            String hardwareAccelerated,
            String vendor,
            boolean lowLatencyRequested,
            String lowLatencySupported,
            boolean lowLatencyConfigured,
            String runtimeApplied,
            String reason) {
        current = new Snapshot(generation,
                value(requestedDecoder), value(actualDecoder), value(decodeBackend),
                hardwareDecode, hardwareFallback, value(frameOutputType), value(renderer),
                value(component), value(componentSource), value(mime),
                value(hardwareAccelerated), value(vendor), lowLatencyRequested,
                value(lowLatencySupported), lowLatencyConfigured,
                value(runtimeApplied), value(reason));
        return current;
    }

    /** prepare/stop/release 前清空实例证据；configured/runtime 不跨实例继承。 */
    public synchronized Snapshot onDecoderReleased(String reason) {
        current = Snapshot.unavailable(reason);
        return current;
    }

    public synchronized Snapshot snapshot() {
        return current;
    }

    private static String value(String value) {
        return value == null || value.isEmpty() ? UNKNOWN : value;
    }

    /** 不可变 decoder session 快照。 */
    public static final class Snapshot {
        public final long generation;
        public final String requestedDecoder;
        public final String actualDecoder;
        public final String decodeBackend;
        public final boolean hardwareDecode;
        public final boolean hardwareFallback;
        public final String frameOutputType;
        public final String renderer;
        public final String component;
        public final String componentSource;
        public final String mime;
        public final String hardwareAccelerated;
        public final String vendor;
        public final boolean lowLatencyRequested;
        public final String lowLatencySupported;
        public final boolean lowLatencyConfigured;
        public final String runtimeApplied;
        public final String reason;

        Snapshot(long generation, String requestedDecoder, String actualDecoder,
                 String decodeBackend, boolean hardwareDecode, boolean hardwareFallback,
                 String frameOutputType, String renderer, String component,
                 String componentSource, String mime, String hardwareAccelerated,
                 String vendor, boolean lowLatencyRequested, String lowLatencySupported,
                 boolean lowLatencyConfigured, String runtimeApplied, String reason) {
            this.generation = generation;
            this.requestedDecoder = requestedDecoder;
            this.actualDecoder = actualDecoder;
            this.decodeBackend = decodeBackend;
            this.hardwareDecode = hardwareDecode;
            this.hardwareFallback = hardwareFallback;
            this.frameOutputType = frameOutputType;
            this.renderer = renderer;
            this.component = component;
            this.componentSource = componentSource;
            this.mime = mime;
            this.hardwareAccelerated = hardwareAccelerated;
            this.vendor = vendor;
            this.lowLatencyRequested = lowLatencyRequested;
            this.lowLatencySupported = lowLatencySupported;
            this.lowLatencyConfigured = lowLatencyConfigured;
            this.runtimeApplied = runtimeApplied;
            this.reason = reason;
        }

        static Snapshot unavailable(String reason) {
            return new Snapshot(0, UNKNOWN, UNKNOWN, UNKNOWN,
                    false, false, UNKNOWN, UNKNOWN, UNAVAILABLE, UNAVAILABLE,
                    UNAVAILABLE, UNKNOWN, UNKNOWN, false, UNAVAILABLE, false, UNKNOWN,
                    value(reason));
        }

        /** 供 Stats observer 生成嵌套 mediaCodec JSON，也便于 JVM 测试固定字段契约。 */
        public String toJsonObject() {
            return "{"
                    + "\"component\":\"" + escape(component) + "\","
                    + "\"componentSource\":\"" + escape(componentSource) + "\","
                    + "\"mime\":\"" + escape(mime) + "\","
                    + "\"hardware\":\"" + escape(hardwareAccelerated) + "\","
                    + "\"vendor\":\"" + escape(vendor) + "\","
                    + "\"lowLatency\":{"
                    + "\"requested\":" + lowLatencyRequested + ","
                    + "\"supported\":\"" + escape(lowLatencySupported) + "\","
                    + "\"configured\":" + lowLatencyConfigured + ","
                    + "\"runtimeApplied\":\"" + escape(runtimeApplied) + "\","
                    + "\"reason\":\"" + escape(reason) + "\"}}";
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r");
        }
    }
}
