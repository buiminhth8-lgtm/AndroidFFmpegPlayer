package com.example.motro;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.motro.MediaCodecLowLatencyReport.Result;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * JVM unit tests for the pure-Java MediaCodec Low-Latency report model. Covers
 * state classification (SUPPORTED/UNSUPPORTED/UNKNOWN/UNAVAILABLE), the vendor
 * parameter keyword filter, the conclusion mapping, and report/log rendering.
 */
public class MediaCodecLowLatencyReportTest {

    private static Result supportedResult() {
        Result r = new Result();
        r.apiLevel = "33";
        r.mime = "video/hevc";
        r.codecName = "c2.qti.hevc.decoder";
        r.codecFound = true;
        r.canonicalName = "c2.qti.hevc.decoder";
        r.hardwareAccelerated = true;
        r.softwareOnly = false;
        r.vendor = true;
        r.featureLowLatencyAvailable = "AVAILABLE";
        r.featureLowLatencySupported = "SUPPORTED";
        r.featureLowLatencyRequired = "UNSUPPORTED";
        r.keyLowLatencyAvailable = "AVAILABLE";
        r.parameterLowLatencyAvailable = "AVAILABLE";
        r.vendorParameterProbeSupported = "SUPPORTED";
        r.vendorParameters = Arrays.asList("vendor.qti-ext-dec-low-latency.enable");
        r.conclusion = "SUPPORTED";
        return r;
    }

    @Test
    public void availabilityStateDistinguishesAvailableFromApiLevel() {
        assertEquals("AVAILABLE", MediaCodecLowLatencyReport.availabilityState(true));
        assertEquals("UNAVAILABLE_API_LEVEL",
                MediaCodecLowLatencyReport.availabilityState(false));
    }

    @Test
    public void featureStateMapsSupportedUnsupportedUnknownAndApiLevel() {
        assertEquals("SUPPORTED", MediaCodecLowLatencyReport.featureState(true, true));
        assertEquals("UNSUPPORTED", MediaCodecLowLatencyReport.featureState(true, false));
        // null supported means the probe could not determine an answer -> UNKNOWN, never false.
        assertEquals("UNKNOWN", MediaCodecLowLatencyReport.featureState(true, null));
        // API level unavailable short-circuits, regardless of the boolean.
        assertEquals("UNAVAILABLE_API_LEVEL", MediaCodecLowLatencyReport.featureState(false, true));
        assertEquals("UNAVAILABLE_API_LEVEL", MediaCodecLowLatencyReport.featureState(false, null));
    }

    @Test
    public void vendorFilterMatchesKeywordsOnly() {
        List<String> all = Arrays.asList(
                "vendor.qti-ext-dec-low-latency.enable",
                "vendor.qti-ext-dec-reorder.enable",
                "vendor.qti-ext-dec-delay-ms",
                "vendor.qti-ext-dec-avc-intra.profile",
                "vendor.rtc.ext-dec-picture-order",
                "vendor.qti-ext-enc-arbitrary-grid.enable",
                "bitrate-mode");
        List<String> filtered = MediaCodecLowLatencyReport.filterVendorParameters(all);
        assertTrue(filtered.contains("vendor.qti-ext-dec-low-latency.enable"));
        assertTrue(filtered.contains("vendor.qti-ext-dec-reorder.enable"));
        assertTrue(filtered.contains("vendor.qti-ext-dec-delay-ms"));
        // "qti" and "vendor" keywords capture these even without latency/low/delay/reorder.
        assertTrue(filtered.contains("vendor.rtc.ext-dec-picture-order"));
        assertFalse(filtered.contains("bitrate-mode"));
    }

    @Test
    public void vendorFilterHandlesNullAndEmpty() {
        assertTrue(MediaCodecLowLatencyReport.filterVendorParameters(null).isEmpty());
        assertTrue(MediaCodecLowLatencyReport.filterVendorParameters(Collections.<String>emptyList()).isEmpty());
    }

    @Test
    public void conclusionIsOnlySupportedUnsupportedUnknownUnavailable() {
        assertEquals("SUPPORTED", MediaCodecLowLatencyReport.conclusion("SUPPORTED"));
        assertEquals("UNSUPPORTED", MediaCodecLowLatencyReport.conclusion("UNSUPPORTED"));
        assertEquals("UNAVAILABLE", MediaCodecLowLatencyReport.conclusion("UNAVAILABLE_API_LEVEL"));
        assertEquals("UNKNOWN", MediaCodecLowLatencyReport.conclusion("UNKNOWN"));
        assertEquals("UNKNOWN", MediaCodecLowLatencyReport.conclusion(null));
    }

    @Test
    public void renderLogContainsRequiredFieldsAndUnknownIsNotFalse() {
        Result r = new Result();
        r.apiLevel = "33";
        r.mime = "video/hevc";
        r.codecName = "c2.qti.hevc.decoder";
        r.featureLowLatencySupported = "UNKNOWN";
        r.keyLowLatencyAvailable = "UNAVAILABLE_API_LEVEL";
        r.conclusion = "UNKNOWN";
        List<String> lines = MediaCodecLowLatencyReport.renderLog(r);
        String joined = String.join("\n", lines);
        assertTrue(joined.contains("API: 33"));
        assertTrue(joined.contains("Mime: video/hevc"));
        assertTrue(joined.contains("Codec: c2.qti.hevc.decoder"));
        assertTrue(joined.contains("supported=UNKNOWN"));
        assertTrue(joined.contains("UNAVAILABLE_API_LEVEL"));
        assertTrue(joined.contains("Conclusion: UNKNOWN"));
        // Unknown states are never rendered as bare false.
        assertFalse(joined.contains("supported=false"));
    }

    @Test
    public void renderReportContainsSectionsAndConclusion() {
        String report = MediaCodecLowLatencyReport.renderReport(supportedResult());
        assertTrue(report.contains("## Device"));
        assertTrue(report.contains("## HEVC decoder"));
        assertTrue(report.contains("## FEATURE_LowLatency"));
        assertTrue(report.contains("## KEY_LOW_LATENCY / PARAMETER_KEY_LOW_LATENCY"));
        assertTrue(report.contains("## Vendor parameter probe"));
        assertTrue(report.contains("## Conclusion"));
        assertTrue(report.endsWith("SUPPORTED\n"));
        assertTrue(report.contains("vendor.qti-ext-dec-low-latency.enable"));
    }

    @Test
    public void renderReportShowsCodecNotFoundWhenMissing() {
        Result r = supportedResult();
        r.codecFound = false;
        r.codecName = "c2.qti.hevc.decoder";
        r.conclusion = "UNKNOWN";
        String report = MediaCodecLowLatencyReport.renderReport(r);
        assertTrue(report.contains("Found: false"));
        assertTrue(report.endsWith("UNKNOWN\n"));
    }
}
