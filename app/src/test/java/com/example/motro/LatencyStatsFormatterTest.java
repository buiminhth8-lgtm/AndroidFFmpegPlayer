package com.example.motro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Unit tests for the LAT3 compact latency diagnostics formatter. The formatter
 * is pure Java (no android.* dependency), so these run on the JVM and cover:
 * valid values, invalid values, us->ms conversion, generation, p50/p95/p99,
 * health counters, snapshot sequence, and per-line length / newline safety.
 */
public class LatencyStatsFormatterTest {

    private static final long SEQ = 123;

    private static LatencyStatsFormatter.StateInfo stateInfo() {
        return new LatencyStatsFormatter.StateInfo(
                7, "PLAYING", 8, 8, true,
                24.94, 24.94, "mediacodec", "nv12_cpu", "nv12_gl",
                1234, 1233, 1230);
    }

    private static LatencyStatsFormatter.MediaInfo validMedia() {
        return new LatencyStatsFormatter.MediaInfo(
                true,
                0, 42019, 0, 42019,
                22, 49, 91, 1024,
                42019, 51600, 67400, 1024,
                10, 18, 54, 1024,
                42019, 51600, 67400, 1024);
    }

    private static LatencyStatsFormatter.MediaInfo invalidMedia() {
        return new LatencyStatsFormatter.MediaInfo(
                false,
                -1, -1, -1, -1,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0);
    }

    private static LatencyStatsFormatter.StageInfo validStage() {
        return new LatencyStatsFormatter.StageInfo(
                22, 49, 91, 1024,
                42019, 51600, 67400, 1024,
                10, 18, 54, 1024,
                2730, 4990, 6290, 1024,
                45200, 54300, 66700, 1024);
    }

    private static LatencyStatsFormatter.StageInfo notReadyStage() {
        return new LatencyStatsFormatter.StageInfo(
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0);
    }

    private static LatencyStatsFormatter.HealthInfo healthInfo() {
        return new LatencyStatsFormatter.HealthInfo(
                2179, 1024, 512,
                1, 1, 0, 1, 0,
                0, 0, 0, 0);
    }

    private static LatencyStatsFormatter.PreT0Info validPreT0() {
        return new LatencyStatsFormatter.PreT0Info(
                39800, 44200, 80100, 1024,
                40000, 41500, 80000, 1024,
                40000, 12, 4,
                2, 1, 0, 0,
                0, 1, 0, 0);
    }

    private static LatencyStatsFormatter.PreT0Info notReadyPreT0() {
        return new LatencyStatsFormatter.PreT0Info(
                0, 0, 0, 0,
                0, 0, 0, 0,
                -1, 0, 0,
                0, 0, 0, 0,
                0, 0, 0, 0);
    }

    private static LatencyStatsFormatter.E2EInfo validE2E() {
        return new LatencyStatsFormatter.E2EInfo(
                "none", "auto_time", 90000,
                1755000000123456789L, 3, 2);
    }

    private static LatencyStatsFormatter.E2EInfo notReadyE2E() {
        return new LatencyStatsFormatter.E2EInfo(
                "none", "unknown", 0, -1, 0, 0);
    }

    @Test
    public void stateLineContainsIdentityGenerationAndFps() {
        String line = LatencyStatsFormatter.stateLine(SEQ, stateInfo());
        assertTrue(line, line.startsWith("seq=123 STATE"));
        assertTrue(line, line.contains(" handle=7"));
        assertTrue(line, line.contains(" state=PLAYING"));
        assertTrue(line, line.contains(" videoGen=8"));
        assertTrue(line, line.contains(" stageGen=8"));
        assertTrue(line, line.contains(" steady=1"));
        assertTrue(line, line.contains(" decodeFps=24.9"));
        assertTrue(line, line.contains(" renderFps=24.9"));
        assertTrue(line, line.contains(" backend=mediacodec"));
        assertTrue(line, line.contains(" output=nv12_cpu"));
        assertTrue(line, line.contains(" renderer=nv12_gl"));
        assertTrue(line, line.contains(" packets=1234"));
        assertTrue(line, line.contains(" frames=1233"));
        assertTrue(line, line.contains(" rendered=1230"));
    }

    @Test
    public void stateLineReplacesNewlinesInState() {
        LatencyStatsFormatter.StateInfo dirty = new LatencyStatsFormatter.StateInfo(
                7, "PLAY\nING", 8, 8, true,
                24.9, 24.9, "mediacodec", "nv12_cpu", "nv12_gl",
                1, 1, 1);
        String line = LatencyStatsFormatter.stateLine(SEQ, dirty);
        assertFalse(line, line.contains("\n"));
        assertTrue(line, line.contains(" state=PLAY ING"));
    }

    @Test
    public void mediaLineConvertsUsToMsAndShowsPercentiles() {
        String line = LatencyStatsFormatter.mediaLine(SEQ, validMedia());
        assertTrue(line, line.startsWith("seq=123 MEDIA mediaMs"));
        assertTrue(line, line.contains(" cur demux=0.00"));
        assertTrue(line, line.contains(" decoder=42.02"));
        assertTrue(line, line.contains(" render=0.00"));
        assertTrue(line, line.contains(" total=42.02"));
        assertTrue(line, line.contains(" valid=1"));
        assertTrue(line, line.contains(" dist demux=0.022/0.049/0.091"));
        assertTrue(line, line.contains(" decoder=42.019/51.600/67.400"));
        assertTrue(line, line.contains(" render=0.010/0.018/0.054"));
        assertTrue(line, line.contains(" total=42.019/51.600/67.400"));
    }

    @Test
    public void mediaLineShowsInvalidAsDashNotZero() {
        String line = LatencyStatsFormatter.mediaLine(SEQ, invalidMedia());
        assertTrue(line, line.startsWith("seq=123 MEDIA mediaMs"));
        assertTrue(line, line.contains(" total=--"));
        assertTrue(line, line.contains(" valid=0"));
        assertTrue(line, line.contains(" dist demux=--/--/--"));
        assertFalse(line, line.contains("total=0"));
        assertFalse(line, line.contains(" demux=0"));
        assertFalse(line, line.contains(" decoder=0"));
    }

    @Test
    public void stageLineShowsPacketReadyTotalPercentiles() {
        String line = LatencyStatsFormatter.stageLine(SEQ, validStage());
        assertTrue(line, line.startsWith("seq=123 STAGE stageMs p50/p95/p99"));
        assertTrue(line, line.contains(" demux=0.022/0.049/0.091"));
        assertTrue(line, line.contains(" decode=42.019/51.600/67.400"));
        assertTrue(line, line.contains(" queue=0.010/0.018/0.054"));
        assertTrue(line, line.contains(" render=2.730/4.990/6.290"));
        assertTrue(line, line.contains(" total=45.200/54.300/66.700"));
    }

    @Test
    public void stageLineShowsNotReadyAsDash() {
        String line = LatencyStatsFormatter.stageLine(SEQ, notReadyStage());
        assertTrue(line, line.startsWith("seq=123 STAGE stageMs p50/p95/p99"));
        assertTrue(line, line.contains(" demux=--/--/--"));
        assertTrue(line, line.contains(" decode=--/--/--"));
        assertTrue(line, line.contains(" queue=--/--/--"));
        assertTrue(line, line.contains(" render=--/--/--"));
        assertTrue(line, line.contains(" total=--/--/--"));
        assertFalse(line, line.contains("decode=0"));
    }

    @Test
    public void healthLineShowsCounters() {
        String line = LatencyStatsFormatter.healthLine(SEQ, healthInfo());
        assertTrue(line, line.startsWith("seq=123 HEALTH"));
        assertTrue(line, line.contains(" samples=2179"));
        assertTrue(line, line.contains(" dist=1024"));
        assertTrue(line, line.contains(" mediaDist=512"));
        assertTrue(line, line.contains(" decoderUnmatched=1"));
        assertTrue(line, line.contains(" renderUnmatched=1"));
        assertTrue(line, line.contains(" forcedEvict=0"));
        assertTrue(line, line.contains(" reset=1"));
        assertTrue(line, line.contains(" clockAnomaly=0"));
        assertTrue(line, line.contains(" ptsBackward=0/0/0/0"));
    }

    @Test
    public void preT0LineShowsReadGapPtsDeltaBurstAndStalls() {
        String line = LatencyStatsFormatter.preT0Line(SEQ, validPreT0());
        assertTrue(line, line.startsWith("seq=123 PRET0"));
        assertTrue(line, line.contains(" readMs=39.800/44.200/80.100"));
        assertTrue(line, line.contains(" videoGapMs=40.000/41.500/80.000"));
        assertTrue(line, line.contains(" ptsDeltaMs=40.000"));
        assertTrue(line, line.contains(" fast=12"));
        assertTrue(line, line.contains(" maxBurst=4"));
        assertTrue(line, line.contains(" stall=2/1/0/0"));
        assertTrue(line, line.contains(" eagain=0"));
        assertTrue(line, line.contains(" timeout=1"));
        assertTrue(line, line.contains(" eof=0"));
        assertTrue(line, line.contains(" error=0"));
    }

    @Test
    public void preT0LineShowsNotReadyAsDash() {
        String line = LatencyStatsFormatter.preT0Line(SEQ, notReadyPreT0());
        assertTrue(line, line.startsWith("seq=123 PRET0"));
        assertTrue(line, line.contains(" readMs=--/--/--"));
        assertTrue(line, line.contains(" videoGapMs=--/--/--"));
        assertTrue(line, line.contains(" ptsDeltaMs=--"));
        assertFalse(line, line.contains("readMs=0"));
        assertFalse(line, line.contains("videoGapMs=0"));
    }

    @Test
    public void e2eLineShowsBridgeStateWithoutFabricatedLatency() {
        String line = LatencyStatsFormatter.e2eLine(SEQ, validE2E());
        assertTrue(line, line.startsWith("seq=123 E2E"));
        assertTrue(line, line.contains(" mode=none"));
        assertTrue(line, line.contains(" sync=auto_time"));
        assertTrue(line, line.contains(" syncErrMs=--"));
        assertTrue(line, line.contains(" rtpClock=90000"));
        assertTrue(line, line.contains(" sendToT0Ms=--/--/--"));
        assertTrue(line, line.contains(" t0WallNs=1755000000123456789"));
        assertTrue(line, line.contains(" gen=3"));
        assertTrue(line, line.contains(" resets=2"));
        assertTrue(line, line.contains(" valid=0"));
    }

    @Test
    public void e2eLineShowsUnavailableAsDashNotZero() {
        String line = LatencyStatsFormatter.e2eLine(SEQ, notReadyE2E());
        assertTrue(line, line.startsWith("seq=123 E2E"));
        assertTrue(line, line.contains(" sync=unknown"));
        assertTrue(line, line.contains(" rtpClock=--"));
        assertTrue(line, line.contains(" t0WallNs=--"));
        assertFalse(line, line.contains("rtpClock=0"));
        assertFalse(line, line.contains("t0WallNs=0"));
    }

    @Test
    public void allLinesShareSameSequenceAndStayShortAndSingleLine() {
        String[] lines = new String[]{
                LatencyStatsFormatter.stateLine(SEQ, stateInfo()),
                LatencyStatsFormatter.mediaLine(SEQ, validMedia()),
                LatencyStatsFormatter.stageLine(SEQ, validStage()),
                LatencyStatsFormatter.preT0Line(SEQ, validPreT0()),
                LatencyStatsFormatter.e2eLine(SEQ, validE2E()),
                LatencyStatsFormatter.healthLine(SEQ, healthInfo())
        };
        int maxLength = 0;
        for (String line : lines) {
            assertTrue(line, line.startsWith("seq=123 "));
            assertFalse(line, line.contains("\n"));
            assertFalse(line, line.contains("\r"));
            assertTrue("line too long: " + line.length(), line.length() < LatencyStatsFormatter.MAX_SAFE_LINE_LENGTH);
            maxLength = Math.max(maxLength, line.length());
        }
        System.out.println("MAX_COMPACT_LOG_LENGTH=" + maxLength);
        assertEquals(6, lines.length);
    }
}
