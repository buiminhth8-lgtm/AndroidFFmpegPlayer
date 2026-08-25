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
 *   FFmpegLatencyStats D seq=123 PRET0  ...
 *   FFmpegLatencyStats D seq=123 E2E    ...
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

    /** LAT5 pre-T0 av_read_frame / video packet return diagnostics (ms). */
    static final class PreT0Info {
        final long readP50Us;
        final long readP95Us;
        final long readP99Us;
        final long readCount;
        final long gapP50Us;
        final long gapP95Us;
        final long gapP99Us;
        final long gapCount;
        final long ptsDeltaAvgUs;
        final long fastReturnCount;
        final long maxFastBurst;
        final long stall100;
        final long stall250;
        final long stall500;
        final long stall1000;
        final long eagain;
        final long timeout;
        final long eof;
        final long error;

        PreT0Info(long readP50Us, long readP95Us, long readP99Us, long readCount,
                  long gapP50Us, long gapP95Us, long gapP99Us, long gapCount,
                  long ptsDeltaAvgUs, long fastReturnCount, long maxFastBurst,
                  long stall100, long stall250, long stall500, long stall1000,
                  long eagain, long timeout, long eof, long error) {
            this.readP50Us = readP50Us;
            this.readP95Us = readP95Us;
            this.readP99Us = readP99Us;
            this.readCount = readCount;
            this.gapP50Us = gapP50Us;
            this.gapP95Us = gapP95Us;
            this.gapP99Us = gapP99Us;
            this.gapCount = gapCount;
            this.ptsDeltaAvgUs = ptsDeltaAvgUs;
            this.fastReturnCount = fastReturnCount;
            this.maxFastBurst = maxFastBurst;
            this.stall100 = stall100;
            this.stall250 = stall250;
            this.stall500 = stall500;
            this.stall1000 = stall1000;
            this.eagain = eagain;
            this.timeout = timeout;
            this.eof = eof;
            this.error = error;
        }
    }

    /** LAT6 end-to-end timebase bridge status (receiver side only). */
    static final class E2EInfo {
        final String mode;
        final String rxSync;
        final long rtpClockRate;
        final long t0WallNs;
        final long generation;
        final long resets;
        final long syncErrorUs;
        final boolean e2eValid;
        final boolean srValid;
        final long srCount;
        final long sendToT0LastUs;
        final long sendToT0P50Us;
        final long sendToT0P95Us;
        final long sendToT0P99Us;
        final long sendToT0Count;
        final long anomalyCount;
        final long sameFrameUnmatchedCount;

        E2EInfo(String mode, String rxSync, long rtpClockRate,
                long t0WallNs, long generation, long resets,
                long syncErrorUs, boolean e2eValid,
                boolean srValid, long srCount, long sendToT0LastUs,
                long sendToT0P50Us, long sendToT0P95Us, long sendToT0P99Us,
                long sendToT0Count, long anomalyCount, long sameFrameUnmatchedCount) {
            this.mode = mode;
            this.rxSync = rxSync;
            this.rtpClockRate = rtpClockRate;
            this.t0WallNs = t0WallNs;
            this.generation = generation;
            this.resets = resets;
            this.syncErrorUs = syncErrorUs;
            this.e2eValid = e2eValid;
            this.srValid = srValid;
            this.srCount = srCount;
            this.sendToT0LastUs = sendToT0LastUs;
            this.sendToT0P50Us = sendToT0P50Us;
            this.sendToT0P95Us = sendToT0P95Us;
            this.sendToT0P99Us = sendToT0P99Us;
            this.sendToT0Count = sendToT0Count;
            this.anomalyCount = anomalyCount;
            this.sameFrameUnmatchedCount = sameFrameUnmatchedCount;
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

    static String preT0Line(long seq, PreT0Info info) {
        return "seq=" + seq
                + " PRET0"
                + " readMs=" + tripleMs(info.readP50Us, info.readP95Us, info.readP99Us, info.readCount)
                + " videoGapMs=" + tripleMs(info.gapP50Us, info.gapP95Us, info.gapP99Us, info.gapCount)
                + " ptsDeltaMs=" + ms3(info.ptsDeltaAvgUs)
                + " fast=" + info.fastReturnCount
                + " maxBurst=" + info.maxFastBurst
                + " stall=" + info.stall100 + "/" + info.stall250 + "/" + info.stall500 + "/" + info.stall1000
                + " eagain=" + info.eagain
                + " timeout=" + info.timeout
                + " eof=" + info.eof
                + " error=" + info.error;
    }

    /**
     * LAT6 E2E timebase line. mode=rtcp_sr means a real SR anchor and
     * same-AVPacket PRFT mapping were observed. sendToT0Ms is emitted only
     * after the independent cross-device clock-error gate passes. RTP-mapped
     * media time is not capture time and is not proven socket-send time.
     */
    static String e2eLine(long seq, E2EInfo info) {
        return "seq=" + seq
                + " E2E"
                + " mode=" + sanitize(info.mode)
                + " sync=" + sanitize(info.rxSync)
                + " syncErrMs=" + ms3(info.syncErrorUs)
                + " rtpClock=" + (info.rtpClockRate > 0 ? String.valueOf(info.rtpClockRate) : "--")
                + " srCount=" + info.srCount
                + " srValid=" + bool(info.srValid)
                + " sendToT0Ms="
                + tripleMs(info.sendToT0P50Us, info.sendToT0P95Us, info.sendToT0P99Us, info.sendToT0Count)
                + " samples=" + info.sendToT0Count
                + " anomaly=" + info.anomalyCount
                + " unmatched=" + info.sameFrameUnmatchedCount
                + " t0WallNs=" + (info.t0WallNs >= 0 ? String.valueOf(info.t0WallNs) : "--")
                + " gen=" + info.generation
                + " resets=" + info.resets
                + " valid=" + bool(info.e2eValid);
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
