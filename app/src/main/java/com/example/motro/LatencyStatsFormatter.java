package com.example.motro;

import java.util.Locale;

/**
 * Logcat-safe compact latency diagnostics (LAT3 output fix).
 *
 * Pure Java (no android.* dependency) so the formatter is directly unit-testable
 * on the JVM. Produces four short key=value lines that share one snapshot
 * sequence number ({@code seq}) and one Logcat tag:
 *
 * <pre>
 *   FFmpegLatencyStats D seq=123 STATE  ...
 *   FFmpegLatencyStats D seq=123 MEDIA  ...
 *   FFmpegLatencyStats D seq=123 STAGE  ...
 *   FFmpegLatencyStats D seq=123 HEALTH ...
 * </pre>
 *
 * All latency durations are converted to ms here; the internal Stats JSON keeps
 * its original us units untouched. Invalid/unavailable values are rendered as
 * "--" (never fabricated as 0). No line may exceed
 * {@link #MAX_SAFE_LINE_LENGTH} characters and no line contains a newline.
 */
final class LatencyStatsFormatter {

    static final String TAG = "FFmpegLatencyStats";
    static final int MAX_SAFE_LINE_LENGTH = 1500;

    private LatencyStatsFormatter() {
    }

    /** Identity / state / generation / measured FPS. */
    static final class StateInfo {
        final long handle;
        final String state;
        final long videoGen;
        final long stageGen;
        final boolean steady;
        final double decodeFps;
        final double renderFps;
        final String decodeBackend;
        final String frameOutputType;
        final String renderer;
        final long packets;
        final long frames;
        final long rendered;

        StateInfo(long handle, String state, long videoGen, long stageGen, boolean steady,
                  double decodeFps, double renderFps, String decodeBackend,
                  String frameOutputType, String renderer, long packets, long frames,
                  long rendered) {
            this.handle = handle;
            this.state = state;
            this.videoGen = videoGen;
            this.stageGen = stageGen;
            this.steady = steady;
            this.decodeFps = decodeFps;
            this.renderFps = renderFps;
            this.decodeBackend = decodeBackend;
            this.frameOutputType = frameOutputType;
            this.renderer = renderer;
            this.packets = packets;
            this.frames = frames;
            this.rendered = rendered;
        }
    }

    /** LAT1 media backlog: current us values + steady-state p50/p95/p99 (ms). */
    static final class MediaInfo {
        final boolean valid;
        final long demuxUs;
        final long decoderUs;
        final long renderUs;
        final long totalUs;
        final long demuxP50Us;
        final long demuxP95Us;
        final long demuxP99Us;
        final long demuxCount;
        final long decoderP50Us;
        final long decoderP95Us;
        final long decoderP99Us;
        final long decoderCount;
        final long renderP50Us;
        final long renderP95Us;
        final long renderP99Us;
        final long renderCount;
        final long totalP50Us;
        final long totalP95Us;
        final long totalP99Us;
        final long totalCount;

        MediaInfo(boolean valid, long demuxUs, long decoderUs, long renderUs, long totalUs,
                  long demuxP50Us, long demuxP95Us, long demuxP99Us, long demuxCount,
                  long decoderP50Us, long decoderP95Us, long decoderP99Us, long decoderCount,
                  long renderP50Us, long renderP95Us, long renderP99Us, long renderCount,
                  long totalP50Us, long totalP95Us, long totalP99Us, long totalCount) {
            this.valid = valid;
            this.demuxUs = demuxUs;
            this.decoderUs = decoderUs;
            this.renderUs = renderUs;
            this.totalUs = totalUs;
            this.demuxP50Us = demuxP50Us;
            this.demuxP95Us = demuxP95Us;
            this.demuxP99Us = demuxP99Us;
            this.demuxCount = demuxCount;
            this.decoderP50Us = decoderP50Us;
            this.decoderP95Us = decoderP95Us;
            this.decoderP99Us = decoderP99Us;
            this.decoderCount = decoderCount;
            this.renderP50Us = renderP50Us;
            this.renderP95Us = renderP95Us;
            this.renderP99Us = renderP99Us;
            this.renderCount = renderCount;
            this.totalP50Us = totalP50Us;
            this.totalP95Us = totalP95Us;
            this.totalP99Us = totalP99Us;
            this.totalCount = totalCount;
        }
    }

    /** LAT2/LAT3 local stage distribution p50/p95/p99 (us; rendered as ms). */
    static final class StageInfo {
        final long demuxP50Us;
        final long demuxP95Us;
        final long demuxP99Us;
        final long demuxCount;
        final long decodeP50Us;
        final long decodeP95Us;
        final long decodeP99Us;
        final long decodeCount;
        final long queueP50Us;
        final long queueP95Us;
        final long queueP99Us;
        final long queueCount;
        final long renderP50Us;
        final long renderP95Us;
        final long renderP99Us;
        final long renderCount;
        final long totalP50Us;
        final long totalP95Us;
        final long totalP99Us;
        final long totalCount;

        StageInfo(long demuxP50Us, long demuxP95Us, long demuxP99Us, long demuxCount,
                  long decodeP50Us, long decodeP95Us, long decodeP99Us, long decodeCount,
                  long queueP50Us, long queueP95Us, long queueP99Us, long queueCount,
                  long renderP50Us, long renderP95Us, long renderP99Us, long renderCount,
                  long totalP50Us, long totalP95Us, long totalP99Us, long totalCount) {
            this.demuxP50Us = demuxP50Us;
            this.demuxP95Us = demuxP95Us;
            this.demuxP99Us = demuxP99Us;
            this.demuxCount = demuxCount;
            this.decodeP50Us = decodeP50Us;
            this.decodeP95Us = decodeP95Us;
            this.decodeP99Us = decodeP99Us;
            this.decodeCount = decodeCount;
            this.queueP50Us = queueP50Us;
            this.queueP95Us = queueP95Us;
            this.queueP99Us = queueP99Us;
            this.queueCount = queueCount;
            this.renderP50Us = renderP50Us;
            this.renderP95Us = renderP95Us;
            this.renderP99Us = renderP99Us;
            this.renderCount = renderCount;
            this.totalP50Us = totalP50Us;
            this.totalP95Us = totalP95Us;
            this.totalP99Us = totalP99Us;
            this.totalCount = totalCount;
        }
    }

    /** LAT2/LAT3 diagnostics health counters. */
    static final class HealthInfo {
        final long samples;
        final long dist;
        final long mediaDist;
        final long decoderUnmatched;
        final long renderUnmatched;
        final long forcedEvict;
        final long reset;
        final long clockAnomaly;
        final long videoPtsBackward;
        final long decoderPtsBackward;
        final long decodedPtsBackward;
        final long renderedPtsBackward;

        HealthInfo(long samples, long dist, long mediaDist, long decoderUnmatched,
                   long renderUnmatched, long forcedEvict, long reset, long clockAnomaly,
                   long videoPtsBackward, long decoderPtsBackward, long decodedPtsBackward,
                   long renderedPtsBackward) {
            this.samples = samples;
            this.dist = dist;
            this.mediaDist = mediaDist;
            this.decoderUnmatched = decoderUnmatched;
            this.renderUnmatched = renderUnmatched;
            this.forcedEvict = forcedEvict;
            this.reset = reset;
            this.clockAnomaly = clockAnomaly;
            this.videoPtsBackward = videoPtsBackward;
            this.decoderPtsBackward = decoderPtsBackward;
            this.decodedPtsBackward = decodedPtsBackward;
            this.renderedPtsBackward = renderedPtsBackward;
        }
    }

    static String stateLine(long seq, StateInfo info) {
        return "seq=" + seq
                + " STATE"
                + " handle=" + info.handle
                + " state=" + sanitize(info.state)
                + " videoGen=" + info.videoGen
                + " stageGen=" + info.stageGen
                + " steady=" + bool(info.steady)
                + " decodeFps=" + fp1(info.decodeFps)
                + " renderFps=" + fp1(info.renderFps)
                + " backend=" + sanitize(info.decodeBackend)
                + " output=" + sanitize(info.frameOutputType)
                + " renderer=" + sanitize(info.renderer)
                + " packets=" + info.packets
                + " frames=" + info.frames
                + " rendered=" + info.rendered;
    }

    static String mediaLine(long seq, MediaInfo info) {
        return "seq=" + seq
                + " MEDIA mediaMs"
                + " cur demux=" + ms(info.demuxUs)
                + " decoder=" + ms(info.decoderUs)
                + " render=" + ms(info.renderUs)
                + " total=" + ms(info.totalUs)
                + " valid=" + bool(info.valid)
                + " dist demux=" + tripleMs(info.demuxP50Us, info.demuxP95Us, info.demuxP99Us, info.demuxCount)
                + " decoder=" + tripleMs(info.decoderP50Us, info.decoderP95Us, info.decoderP99Us, info.decoderCount)
                + " render=" + tripleMs(info.renderP50Us, info.renderP95Us, info.renderP99Us, info.renderCount)
                + " total=" + tripleMs(info.totalP50Us, info.totalP95Us, info.totalP99Us, info.totalCount);
    }

    static String stageLine(long seq, StageInfo info) {
        return "seq=" + seq
                + " STAGE stageMs p50/p95/p99"
                + " demux=" + tripleMs(info.demuxP50Us, info.demuxP95Us, info.demuxP99Us, info.demuxCount)
                + " decode=" + tripleMs(info.decodeP50Us, info.decodeP95Us, info.decodeP99Us, info.decodeCount)
                + " queue=" + tripleMs(info.queueP50Us, info.queueP95Us, info.queueP99Us, info.queueCount)
                + " render=" + tripleMs(info.renderP50Us, info.renderP95Us, info.renderP99Us, info.renderCount)
                + " total=" + tripleMs(info.totalP50Us, info.totalP95Us, info.totalP99Us, info.totalCount);
    }

    static String healthLine(long seq, HealthInfo info) {
        return "seq=" + seq
                + " HEALTH"
                + " samples=" + info.samples
                + " dist=" + info.dist
                + " mediaDist=" + info.mediaDist
                + " decoderUnmatched=" + info.decoderUnmatched
                + " renderUnmatched=" + info.renderUnmatched
                + " forcedEvict=" + info.forcedEvict
                + " reset=" + info.reset
                + " clockAnomaly=" + info.clockAnomaly
                + " ptsBackward=" + info.videoPtsBackward + "/" + info.decoderPtsBackward
                + "/" + info.decodedPtsBackward + "/" + info.renderedPtsBackward;
    }

    /** us -> ms with two decimals; negative/invalid renders as "--". */
    private static String ms(long us) {
        return us < 0 ? "--" : String.format(Locale.US, "%.2f", us / 1000.0);
    }

    /** us -> ms with three decimals; negative/invalid renders as "--". */
    private static String ms3(long us) {
        return us < 0 ? "--" : String.format(Locale.US, "%.3f", us / 1000.0);
    }

    /**
     * p50/p95/p99 triple in ms. A distribution that has not reached steady state
     * (count == 0) or contains invalid values renders "--/--/--", never zeros.
     */
    private static String tripleMs(long p50Us, long p95Us, long p99Us, long count) {
        if (count <= 0 || p50Us < 0 || p95Us < 0 || p99Us < 0) {
            return "--/--/--";
        }
        return ms3(p50Us) + "/" + ms3(p95Us) + "/" + ms3(p99Us);
    }

    private static String bool(boolean value) {
        return value ? "1" : "0";
    }

    private static String fp1(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    /** Defensive: strip control whitespace so a single log line is never split. */
    private static String sanitize(String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        return value.replace('\n', ' ').replace('\r', ' ');
    }
}
