package com.example.motro;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Pure-Java data model and renderer for the MediaCodec Low-Latency capability
 * probe (Slice 1). No {@code android.*} dependency, so the classification and
 * rendering logic is directly unit-testable on the JVM — the same way
 * {@link LatencyStatsFormatter} is.
 *
 * <p>This class only <em>describes</em> the probe result and formats it for
 * Logcat and a Markdown report. It never calls MediaCodec and never changes
 * decoder behaviour. The actual MediaCodec queries live in the debug-only
 * {@code MediaCodecLowLatencyCapability} class.
 *
 * <p>Unknown states are explicit ({@link #UNKNOWN}, {@link #UNAVAILABLE},
 * {@link #UNAVAILABLE_API_LEVEL}); nothing is silently reported as {@code
 * false} when the probe could not determine it.
 */
final class MediaCodecLowLatencyReport {

    static final String TAG = "MediaCodecLowLatencyProbe";

    // Conclusion / state vocabulary. "API exists" is never conflated with
    // "codec supports it".
    static final String SUPPORTED = "SUPPORTED";
    static final String UNSUPPORTED = "UNSUPPORTED";
    static final String UNKNOWN = "UNKNOWN";
    static final String UNAVAILABLE = "UNAVAILABLE";
    static final String UNAVAILABLE_API_LEVEL = "UNAVAILABLE_API_LEVEL";
    static final String AVAILABLE = "AVAILABLE";

    // Vendor parameter probe keyword filter (section 4). Deliberately literal:
    // a parameter matches when its lower-cased name contains any keyword.
    private static final String[] VENDOR_KEYWORDS = {
        "latency", "low", "delay", "reorder", "qti", "vendor"
    };

    private MediaCodecLowLatencyReport() {
    }

    /** Flat result record filled by the Android probe. */
    static final class Result {
        // Device context (from android.os.Build, filled by the probe).
        String deviceModel = UNKNOWN;
        String manufacturer = UNKNOWN;
        String androidVersion = UNKNOWN;
        String apiLevel = UNKNOWN;
        String platform = UNKNOWN;

        // Decoder identity (section 1).
        String mime = "video/hevc";
        boolean codecFound;
        String codecName = UNKNOWN;
        String canonicalName = UNAVAILABLE;
        boolean isEncoder;
        boolean hardwareAccelerated;
        boolean softwareOnly;
        boolean vendor;
        String supportedTypes = UNKNOWN;
        List<String> allHevcDecoders = new ArrayList<>();

        // Low-latency capability / API availability (sections 2 & 3).
        String featureLowLatencyAvailable = UNKNOWN;
        String featureLowLatencySupported = UNKNOWN;
        String featureLowLatencyRequired = UNKNOWN;
        String keyLowLatencyAvailable = UNKNOWN;
        String parameterLowLatencyAvailable = UNKNOWN;

        // Vendor parameter probe (section 4).
        String vendorParameterProbeSupported = UNKNOWN;
        List<String> vendorParameters = new ArrayList<>();

        String conclusion = UNKNOWN;
        String probeError = "";
    }

    /** Maps an API-availability flag to AVAILABLE / UNAVAILABLE_API_LEVEL. */
    static String availabilityState(boolean available) {
        return available ? AVAILABLE : UNAVAILABLE_API_LEVEL;
    }

    /**
     * Maps a boolean capability to SUPPORTED / UNSUPPORTED / UNKNOWN /
     * UNAVAILABLE_API_LEVEL. A {@code null} supported value means the probe ran
     * but could not determine the answer (e.g. codec missing or exception).
     */
    static String featureState(boolean apiAvailable, Boolean supported) {
        if (!apiAvailable) {
            return UNAVAILABLE_API_LEVEL;
        }
        if (supported == null) {
            return UNKNOWN;
        }
        return supported ? SUPPORTED : UNSUPPORTED;
    }

    /**
     * Filters vendor parameter names to those containing any probe keyword.
     * Pure String matching so it is deterministic and testable.
     */
    static List<String> filterVendorParameters(List<String> parameters) {
        if (parameters == null || parameters.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> matched = new ArrayList<>();
        for (String parameter : parameters) {
            if (parameter == null) {
                continue;
            }
            String lower = parameter.toLowerCase(Locale.US);
            for (String keyword : VENDOR_KEYWORDS) {
                if (lower.contains(keyword)) {
                    matched.add(parameter);
                    break;
                }
            }
        }
        return matched;
    }

    /** Overall conclusion, derived only from the feature-supported state. */
    static String conclusion(String featureLowLatencySupported) {
        switch (featureLowLatencySupported == null ? UNKNOWN : featureLowLatencySupported) {
            case SUPPORTED:
                return SUPPORTED;
            case UNSUPPORTED:
                return UNSUPPORTED;
            case UNAVAILABLE_API_LEVEL:
                return UNAVAILABLE;
            default:
                return UNKNOWN;
        }
    }

    /** Renders the one-shot Logcat block (one string per line). */
    static List<String> renderLog(Result r) {
        List<String> lines = new ArrayList<>(Arrays.asList(
            "MediaCodecLowLatencyProbe",
            "API: " + r.apiLevel,
            "Android: " + r.androidVersion,
            "Device: " + r.deviceModel + " (" + r.manufacturer + ")",
            "Platform: " + r.platform,
            "Mime: " + r.mime,
            "Codec: " + r.codecName,
            "Canonical: " + r.canonicalName,
            "Found: " + r.codecFound,
            "Hardware: " + r.hardwareAccelerated,
            "SoftwareOnly: " + r.softwareOnly,
            "Vendor: " + r.vendor,
            "Encoder: " + r.isEncoder,
            "SupportedTypes: " + r.supportedTypes,
            "AllHevcDecoders: " + r.allHevcDecoders,
            "FEATURE_LowLatency available=" + r.featureLowLatencyAvailable
                + " supported=" + r.featureLowLatencySupported
                + " required=" + r.featureLowLatencyRequired,
            "KEY_LOW_LATENCY: " + r.keyLowLatencyAvailable,
            "PARAMETER_KEY_LOW_LATENCY: " + r.parameterLowLatencyAvailable,
            "VendorParameterProbe: " + r.vendorParameterProbeSupported,
            "VendorParameters: " + r.vendorParameters,
            "Conclusion: " + r.conclusion,
            "ProbeError: " + (r.probeError.isEmpty() ? "(none)" : r.probeError)
        ));
        return lines;
    }

    /** Renders the Markdown capability report (section 7). */
    static String renderReport(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# MediaCodec Low-Latency Capability Probe\n\n");

        sb.append("## Device\n\n");
        sb.append("- Model: ").append(r.deviceModel).append('\n');
        sb.append("- Manufacturer: ").append(r.manufacturer).append('\n');
        sb.append("- Android: ").append(r.androidVersion).append(" (API ").append(r.apiLevel).append(")\n");
        sb.append("- SoC/platform: ").append(r.platform).append("\n\n");

        sb.append("## HEVC decoder\n\n");
        sb.append("- Mime: ").append(r.mime).append('\n');
        sb.append("- Codec: ").append(r.codecName).append('\n');
        sb.append("- Found: ").append(r.codecFound).append('\n');
        sb.append("- Canonical name: ").append(r.canonicalName).append('\n');
        sb.append("- Encoder: ").append(r.isEncoder).append('\n');
        sb.append("- Hardware accelerated: ").append(r.hardwareAccelerated).append('\n');
        sb.append("- Software only: ").append(r.softwareOnly).append('\n');
        sb.append("- Vendor: ").append(r.vendor).append('\n');
        sb.append("- Supported types: ").append(r.supportedTypes).append('\n');
        sb.append("- All HEVC decoders: ").append(r.allHevcDecoders).append("\n\n");

        sb.append("## FEATURE_LowLatency\n\n");
        sb.append("- Available: ").append(r.featureLowLatencyAvailable).append('\n');
        sb.append("- Supported: ").append(r.featureLowLatencySupported).append('\n');
        sb.append("- Required: ").append(r.featureLowLatencyRequired).append("\n\n");

        sb.append("## KEY_LOW_LATENCY / PARAMETER_KEY_LOW_LATENCY\n\n");
        sb.append("- KEY_LOW_LATENCY: ").append(r.keyLowLatencyAvailable).append('\n');
        sb.append("- PARAMETER_KEY_LOW_LATENCY: ").append(r.parameterLowLatencyAvailable).append("\n\n");

        sb.append("## Vendor parameter probe\n\n");
        sb.append("- Probe supported: ").append(r.vendorParameterProbeSupported).append('\n');
        sb.append("- Vendor parameters (latency/low/delay/reorder/qti/vendor):\n");
        if (r.vendorParameters.isEmpty()) {
            sb.append("  - (none)\n");
        } else {
            for (String parameter : r.vendorParameters) {
                sb.append("  - ").append(parameter).append('\n');
            }
        }
        sb.append('\n');

        if (!r.probeError.isEmpty()) {
            sb.append("## Probe error\n\n").append(r.probeError).append("\n\n");
        }

        sb.append("## Conclusion\n\n");
        sb.append(r.conclusion).append('\n');
        return sb.toString();
    }
}
