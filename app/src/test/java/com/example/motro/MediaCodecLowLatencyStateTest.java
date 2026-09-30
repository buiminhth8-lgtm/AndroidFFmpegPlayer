package com.example.motro;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import com.example.motro.ffmpeg.MediaCodecLowLatencyState;
import com.example.motro.ffmpeg.MediaCodecLowLatencyState.Snapshot;

import org.junit.Test;

/** JVM tests for Slice 2/3 state semantics and decoder-instance lifecycle. */
public class MediaCodecLowLatencyStateTest {

    @Test
    public void defaultStateDoesNotClaimLowLatency() {
        Snapshot state = new MediaCodecLowLatencyState().snapshot();
        assertFalse(state.lowLatencyRequested);
        assertEquals(MediaCodecLowLatencyState.UNAVAILABLE, state.lowLatencySupported);
        assertFalse(state.lowLatencyConfigured);
        assertEquals(MediaCodecLowLatencyState.UNKNOWN, state.runtimeApplied);
        assertEquals(MediaCodecLowLatencyState.OFF, state.controlStatus);
        assertEquals(MediaCodecLowLatencyState.UNAVAILABLE, state.component);
    }

    @Test
    public void capabilityMappingPreservesUnknownAndUnavailable() {
        assertEquals(MediaCodecLowLatencyState.SUPPORTED,
                MediaCodecLowLatencyState.capabilityState(true, true));
        assertEquals(MediaCodecLowLatencyState.UNSUPPORTED,
                MediaCodecLowLatencyState.capabilityState(true, false));
        assertEquals(MediaCodecLowLatencyState.UNKNOWN,
                MediaCodecLowLatencyState.capabilityState(true, null));
        assertEquals(MediaCodecLowLatencyState.UNAVAILABLE_API_LEVEL,
                MediaCodecLowLatencyState.capabilityState(false, null));
    }

    @Test
    public void decoderRecreateReplacesConfiguredAndRuntimeState() {
        MediaCodecLowLatencyState model = new MediaCodecLowLatencyState();
        Snapshot first = model.onDecoderCreated(1,
                "hevc_mediacodec", "hevc_mediacodec", "mediacodec",
                true, false, "nv12_cpu", "nv12_gl", "decoder.one", "test",
                "video/hevc", "YES", "YES", true, "SUPPORTED", true,
                "APPLIED", "CONFIGURED", "test applied");
        assertTrue(first.lowLatencyConfigured);
        assertEquals("APPLIED", first.runtimeApplied);

        Snapshot recreated = model.onDecoderCreated(2,
                "hevc_mediacodec", "hevc", "software",
                false, true, "yuv420p_cpu", "yuv_gl", "UNAVAILABLE", "test",
                "video/hevc", "UNKNOWN", "UNKNOWN", true, "UNKNOWN", false,
                "UNKNOWN", MediaCodecLowLatencyState.CONTROL_UNAVAILABLE,
                "hardware fallback");
        assertEquals(2, recreated.generation);
        assertTrue(recreated.lowLatencyRequested);
        assertFalse(recreated.lowLatencyConfigured);
        assertEquals(MediaCodecLowLatencyState.UNKNOWN, recreated.runtimeApplied);
        assertEquals("UNAVAILABLE", recreated.component);
        assertTrue(recreated.hardwareFallback);
    }

    @Test
    public void releaseClearsDecoderEvidence() {
        MediaCodecLowLatencyState model = new MediaCodecLowLatencyState();
        model.onDecoderCreated(3, "hevc_mediacodec", "hevc_mediacodec",
                "mediacodec", true, false, "nv12_cpu", "nv12_gl",
                "c2.vendor.decoder", "test", "video/hevc", "YES", "YES",
                false, "SUPPORTED", false, "UNKNOWN", "OFF", "not requested");
        Snapshot released = model.onDecoderReleased("stop");
        assertEquals(0, released.generation);
        assertEquals(MediaCodecLowLatencyState.UNAVAILABLE, released.component);
        assertEquals(MediaCodecLowLatencyState.UNAVAILABLE, released.lowLatencySupported);
        assertFalse(released.lowLatencyConfigured);
        assertEquals(MediaCodecLowLatencyState.UNKNOWN, released.runtimeApplied);
    }

    @Test
    public void jsonContractContainsIdentityAndFourLowLatencyStates() {
        MediaCodecLowLatencyState model = new MediaCodecLowLatencyState();
        Snapshot state = model.onDecoderCreated(4,
                "hevc_mediacodec", "hevc_mediacodec", "mediacodec",
                true, false, "nv12_cpu", "nv12_gl", "c2.vendor.decoder",
                "MediaCodecList.findDecoderForFormat", "video/hevc", "YES", "YES",
                false, "SUPPORTED", false, "UNKNOWN",
                "OFF",
                "not requested; runtime readback unavailable");
        String json = state.toJsonObject();
        assertTrue(json.contains("\"component\":\"c2.vendor.decoder\""));
        assertTrue(json.contains("\"mime\":\"video/hevc\""));
        assertTrue(json.contains("\"requested\":false"));
        assertTrue(json.contains("\"supported\":\"SUPPORTED\""));
        assertTrue(json.contains("\"configured\":false"));
        assertTrue(json.contains("\"runtimeApplied\":\"UNKNOWN\""));
        assertTrue(json.contains("\"controlStatus\":\"OFF\""));
    }

    @Test
    public void requestedStateSurvivesWithoutDecoderButNeverClaimsConfiguration() {
        Snapshot pending = new MediaCodecLowLatencyState().onDecoderUnavailable(
                true, MediaCodecLowLatencyState.CONTROL_UNAVAILABLE,
                "prebuilt FFmpeg wrapper unavailable");
        assertTrue(pending.lowLatencyRequested);
        assertEquals(MediaCodecLowLatencyState.CONTROL_UNAVAILABLE, pending.controlStatus);
        assertFalse(pending.lowLatencyConfigured);
        assertEquals(MediaCodecLowLatencyState.UNKNOWN, pending.runtimeApplied);
        assertEquals(MediaCodecLowLatencyState.UNAVAILABLE, pending.component);
    }

    @Test
    public void supportedCapabilityDoesNotOverrideUnavailableControlResult() {
        Snapshot state = new MediaCodecLowLatencyState().onDecoderCreated(5,
                "hevc_mediacodec", "hevc_mediacodec", "mediacodec",
                true, false, "nv12_cpu", "nv12_gl", "c2.vendor.decoder",
                "MediaCodecList.findDecoderForFormat", "video/hevc", "YES", "YES",
                true, "SUPPORTED", false, "UNKNOWN",
                MediaCodecLowLatencyState.CONTROL_UNAVAILABLE,
                "prebuilt FFmpeg wrapper unavailable");
        assertTrue(state.lowLatencyRequested);
        assertEquals(MediaCodecLowLatencyState.SUPPORTED, state.lowLatencySupported);
        assertFalse(state.lowLatencyConfigured);
        assertEquals(MediaCodecLowLatencyState.UNKNOWN, state.runtimeApplied);
        assertEquals(MediaCodecLowLatencyState.CONTROL_UNAVAILABLE, state.controlStatus);
    }
}
