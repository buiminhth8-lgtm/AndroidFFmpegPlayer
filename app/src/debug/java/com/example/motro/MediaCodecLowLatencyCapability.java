package com.example.motro;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.example.motro.MediaCodecLowLatencyReport.Result;

/**
 * Debug-only, one-shot probe for the real HEVC hardware decoder's MediaCodec
 * Low-Latency capability. Read-only: it enumerates codecs and queries
 * capability flags / vendor parameter names, but never calls
 * {@code setParameters()}, never enables Low-Latency, and never alters decoder
 * behaviour. Any temporary decoder is released in a {@code finally} block.
 *
 * <p>API-level branches are guarded with {@code Build.VERSION.SDK_INT} so the
 * probe is safe on {@code minSdk 24} while the app compiles against SDK 36.
 *
 * <p>This class lives in the debug source set, so it is absent from the
 * production APK and cannot affect the player main path.
 */
final class MediaCodecLowLatencyCapability {

    static final String TARGET_CODEC = "c2.qti.hevc.decoder";
    static final String MIME = MediaFormat.MIMETYPE_VIDEO_HEVC; // "video/hevc"

    private MediaCodecLowLatencyCapability() {
    }

    /** Runs the probe; never throws. The caller owns the returned {@link Result}. */
    static Result probe() {
        Result result = new Result();
        try {
            fillDeviceContext(result);
            MediaCodecInfo target = enumerateHevcDecoders(result);
            result.codecFound = target != null;
            if (target != null) {
                fillCodecIdentity(result, target);
                fillLowLatencyCapability(result, target);
                fillVendorParameters(result);
            } else {
                // Decoder absent: low-latency feature cannot be evaluated.
                result.featureLowLatencyAvailable = MediaCodecLowLatencyReport.availabilityState(
                        Build.VERSION.SDK_INT >= 30);
                result.featureLowLatencySupported = MediaCodecLowLatencyReport.UNKNOWN;
                result.featureLowLatencyRequired = MediaCodecLowLatencyReport.UNKNOWN;
                result.vendorParameterProbeSupported = Build.VERSION.SDK_INT >= 31
                        ? MediaCodecLowLatencyReport.UNKNOWN
                        : MediaCodecLowLatencyReport.UNAVAILABLE_API_LEVEL;
            }
            fillApiAvailability(result);
            result.conclusion = MediaCodecLowLatencyReport.conclusion(
                    result.featureLowLatencySupported);
        } catch (Throwable error) {
            // Probe failure must never affect the player main path; report and continue.
            result.probeError = String.valueOf(error);
            if (MediaCodecLowLatencyReport.UNKNOWN.equals(result.conclusion)) {
                result.conclusion = MediaCodecLowLatencyReport.UNKNOWN;
            }
        }
        return result;
    }

    private static void fillDeviceContext(Result result) {
        result.deviceModel = Build.MODEL;
        result.manufacturer = Build.MANUFACTURER;
        result.androidVersion = Build.VERSION.RELEASE;
        result.apiLevel = String.valueOf(Build.VERSION.SDK_INT);
        if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null && !Build.SOC_MODEL.isEmpty()) {
            result.platform = Build.SOC_MODEL;
        } else {
            result.platform = Build.HARDWARE;
        }
    }

    /** Enumerates HEVC decoders and records all names; returns the target or null. */
    private static MediaCodecInfo enumerateHevcDecoders(Result result) {
        result.mime = MIME;
        MediaCodecInfo target = null;
        List<String> hevcDecoders = new ArrayList<>();
        MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        for (MediaCodecInfo info : infos) {
            if (info.isEncoder()) {
                continue;
            }
            String[] types = info.getSupportedTypes();
            boolean supportsHevc = false;
            for (String type : types) {
                if (MIME.equalsIgnoreCase(type)) {
                    supportsHevc = true;
                    break;
                }
            }
            if (!supportsHevc) {
                continue;
            }
            hevcDecoders.add(info.getName());
            if (TARGET_CODEC.equals(info.getName())) {
                target = info;
            }
        }
        Collections.sort(hevcDecoders);
        result.allHevcDecoders = hevcDecoders;
        if (target == null) {
            result.codecName = TARGET_CODEC;
            result.canonicalName = MediaCodecLowLatencyReport.UNAVAILABLE;
            result.supportedTypes = MediaCodecLowLatencyReport.UNKNOWN;
        }
        return target;
    }

    private static void fillCodecIdentity(Result result, MediaCodecInfo info) {
        result.codecName = info.getName();
        result.isEncoder = info.isEncoder();
        result.supportedTypes = join(info.getSupportedTypes());
        // canonicalName / isHardwareAccelerated / isSoftwareOnly / isVendor: API 29.
        if (Build.VERSION.SDK_INT >= 29) {
            result.canonicalName = info.getCanonicalName();
            result.hardwareAccelerated = info.isHardwareAccelerated();
            result.softwareOnly = info.isSoftwareOnly();
            result.vendor = info.isVendor();
        } else {
            result.canonicalName = MediaCodecLowLatencyReport.UNAVAILABLE_API_LEVEL;
            result.hardwareAccelerated = false;
            result.softwareOnly = false;
            result.vendor = false;
        }
    }

    private static void fillLowLatencyCapability(Result result, MediaCodecInfo info) {
        // FEATURE_LowLatency is an API 30 constant; on older levels it is unavailable.
        boolean featureAvailable = Build.VERSION.SDK_INT >= 30;
        result.featureLowLatencyAvailable =
                MediaCodecLowLatencyReport.availabilityState(featureAvailable);
        if (!featureAvailable) {
            result.featureLowLatencySupported = MediaCodecLowLatencyReport.UNAVAILABLE_API_LEVEL;
            result.featureLowLatencyRequired = MediaCodecLowLatencyReport.UNAVAILABLE_API_LEVEL;
            return;
        }
        try {
            MediaCodecInfo.CodecCapabilities capabilities = info.getCapabilitiesForType(MIME);
            result.featureLowLatencySupported = MediaCodecLowLatencyReport.featureState(
                    true, capabilities.isFeatureSupported(
                            MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency));
            result.featureLowLatencyRequired = MediaCodecLowLatencyReport.featureState(
                    true, capabilities.isFeatureRequired(
                            MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency));
        } catch (RuntimeException error) {
            result.featureLowLatencySupported = MediaCodecLowLatencyReport.UNKNOWN;
            result.featureLowLatencyRequired = MediaCodecLowLatencyReport.UNKNOWN;
            result.probeError = append(result.probeError, "capability query: " + error);
        }
    }

    private static void fillApiAvailability(Result result) {
        boolean lowLatencyKeysAvailable = Build.VERSION.SDK_INT >= 30;
        result.keyLowLatencyAvailable =
                MediaCodecLowLatencyReport.availabilityState(lowLatencyKeysAvailable);
        result.parameterLowLatencyAvailable =
                MediaCodecLowLatencyReport.availabilityState(lowLatencyKeysAvailable);
    }

    /** Read-only vendor parameter probe; the temp codec is always released. */
    private static void fillVendorParameters(Result result) {
        // getSupportedVendorParameters is an API 31 instance method.
        if (Build.VERSION.SDK_INT < 31) {
            result.vendorParameterProbeSupported = MediaCodecLowLatencyReport.UNAVAILABLE_API_LEVEL;
            return;
        }
        MediaCodec codec = null;
        try {
            codec = MediaCodec.createByCodecName(result.codecName);
            List<String> parameters = codec.getSupportedVendorParameters();
            result.vendorParameters =
                    MediaCodecLowLatencyReport.filterVendorParameters(parameters);
            result.vendorParameterProbeSupported = MediaCodecLowLatencyReport.SUPPORTED;
        } catch (IOException | RuntimeException error) {
            result.vendorParameterProbeSupported = MediaCodecLowLatencyReport.UNKNOWN;
            result.probeError = append(result.probeError, "vendor probe: " + error);
        } finally {
            if (codec != null) {
                try {
                    codec.release();
                } catch (RuntimeException ignored) {
                    // Already failing; release best-effort only.
                }
            }
        }
    }

    private static String join(String[] values) {
        if (values == null || values.length == 0) {
            return MediaCodecLowLatencyReport.UNKNOWN;
        }
        return Arrays.toString(values);
    }

    private static String append(String current, String extra) {
        return current == null || current.isEmpty() ? extra : current + "; " + extra;
    }
}
