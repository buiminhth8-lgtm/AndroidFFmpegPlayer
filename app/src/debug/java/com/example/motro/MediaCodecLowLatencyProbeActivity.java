package com.example.motro;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

import com.example.motro.MediaCodecLowLatencyReport.Result;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Debug-only, low-intrusion entry point for the MediaCodec Low-Latency
 * capability probe. Launches a single one-shot probe on a background thread,
 * writes the report/JSON to app-private storage, logs it to Logcat, then
 * finishes. No player is created, so the player main path is never touched.
 *
 * <p>Run from adb:
 * <pre>adb shell am start -n com.example.motro/.MediaCodecLowLatencyProbeActivity</pre>
 */
public final class MediaCodecLowLatencyProbeActivity extends Activity {

    private TextView statusView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setBackgroundColor(0x88000000);
        statusView.setPadding(24, 16, 24, 16);
        statusView.setGravity(Gravity.CENTER);
        statusView.setText("MediaCodec Low-Latency probe running...");
        setContentView(statusView);

        Executors.newSingleThreadExecutor().execute(() -> {
            Result result = MediaCodecLowLatencyCapability.probe();
            writeArtifacts(result);
            String summary = "Conclusion: " + result.conclusion
                    + (result.probeError.isEmpty() ? "" : "\nError: " + result.probeError);
            runOnUiThread(() -> {
                statusView.setText(summary);
                finish();
            });
        });
    }

    private void writeArtifacts(Result result) {
        List<String> logLines = MediaCodecLowLatencyReport.renderLog(result);
        for (String line : logLines) {
            Log.i(MediaCodecLowLatencyReport.TAG, line);
        }
        File dir = new File(getExternalFilesDir(null), "mediacodec_low_latency");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        try {
            File report = new File(dir, "probe_report.md");
            try (FileWriter writer = new FileWriter(report, StandardCharsets.UTF_8)) {
                writer.write(MediaCodecLowLatencyReport.renderReport(result));
            }
            File json = new File(dir, "probe_result.json");
            try (FileWriter writer = new FileWriter(json, StandardCharsets.UTF_8)) {
                writer.write(toJson(result).toString(2));
            }
            Log.i(MediaCodecLowLatencyReport.TAG, "probe artifacts written to " + dir.getAbsolutePath());
        } catch (Exception error) {
            Log.e(MediaCodecLowLatencyReport.TAG, "artifact write failed", error);
        }
    }

    private static JSONObject toJson(Result result) throws Exception {
        JSONObject json = new JSONObject();
        json.put("deviceModel", result.deviceModel);
        json.put("manufacturer", result.manufacturer);
        json.put("androidVersion", result.androidVersion);
        json.put("apiLevel", result.apiLevel);
        json.put("platform", result.platform);
        json.put("mime", result.mime);
        json.put("codecFound", result.codecFound);
        json.put("codecName", result.codecName);
        json.put("canonicalName", result.canonicalName);
        json.put("isEncoder", result.isEncoder);
        json.put("hardwareAccelerated", result.hardwareAccelerated);
        json.put("softwareOnly", result.softwareOnly);
        json.put("vendor", result.vendor);
        json.put("supportedTypes", result.supportedTypes);
        json.put("allHevcDecoders", new JSONArray(result.allHevcDecoders));
        json.put("featureLowLatencyAvailable", result.featureLowLatencyAvailable);
        json.put("featureLowLatencySupported", result.featureLowLatencySupported);
        json.put("featureLowLatencyRequired", result.featureLowLatencyRequired);
        json.put("keyLowLatencyAvailable", result.keyLowLatencyAvailable);
        json.put("parameterLowLatencyAvailable", result.parameterLowLatencyAvailable);
        json.put("vendorParameterProbeSupported", result.vendorParameterProbeSupported);
        json.put("vendorParameters", new JSONArray(result.vendorParameters));
        json.put("conclusion", result.conclusion);
        json.put("probeError", result.probeError);
        return json;
    }
}
