package com.example.motro;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.rtmp.RtmpDataSource;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.ui.PlayerView;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public class ExoplayerPlayerActivity extends Activity {

    private static final String TAG = "ExoPlayerLatencyDemo";
    private static final String DEFAULT_SOURCE = "rtsp://192.168.1.101:554/main.mov";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong loadedBytes = new AtomicLong(0);
    private final AtomicLong droppedFrames = new AtomicLong(0);
    private final AtomicLong videoFrameProcessingCount = new AtomicLong(0);
    private final AtomicLong totalVideoFrameProcessingOffsetUs = new AtomicLong(0);

    private PlayerView playerView;
    private EditText sourceEditText;
    private Switch lowLatencySwitch;
    private Switch rtspTcpSwitch;
    private Switch muteSwitch;
    private Button nativeFallbackButton;
    private TextView statsTextView;
    private TextView logTextView;

    private ExoPlayer player;
    private boolean destroyed;
    private long startRequestTimeMs;
    private long firstReadyTimeMs;
    private long firstFrameTimeMs;
    private long lastStatsTimeMs;
    private long lastStatsBytes;
    private int lastRenderedFrames;
    private String playbackStateText = "idle";
    private String sourceTypeText = "";
    private String videoFormatText = "";
    private String videoSizeText = "";
    private String lastError = "";
    private String lastCompatibilityHint = "";

    private final Runnable statsRunnable = new Runnable() {
        @Override
        public void run() {
            updateStatsText();
            if (!destroyed) {
                mainHandler.postDelayed(this, 1000);
            }
        }
    };

    private final TransferListener transferListener = new TransferListener() {
        @Override
        public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }

        @Override
        public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }

        @Override
        public void onBytesTransferred(DataSource source, DataSpec dataSpec, boolean isNetwork, int bytesTransferred) {
            if (bytesTransferred > 0) {
                loadedBytes.addAndGet(bytesTransferred);
            }
        }

        @Override
        public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_exoplayer_player);
        bindViews();
        initDefaults();
        bindActions();
        mainHandler.post(statsRunnable);
        appendLog("ExoPlayer latency demo ready");
    }

    private void bindViews() {
        playerView = findViewById(R.id.exoplayer_player_view);
        sourceEditText = findViewById(R.id.exoplayer_source_edit);
        lowLatencySwitch = findViewById(R.id.exoplayer_low_latency_switch);
        rtspTcpSwitch = findViewById(R.id.exoplayer_rtsp_tcp_switch);
        muteSwitch = findViewById(R.id.exoplayer_mute_switch);
        nativeFallbackButton = findViewById(R.id.exoplayer_native_fallback_button);
        statsTextView = findViewById(R.id.exoplayer_stats_text);
        logTextView = findViewById(R.id.exoplayer_log_text);
    }

    private void initDefaults() {
        sourceEditText.setText(DEFAULT_SOURCE);
        lowLatencySwitch.setChecked(true);
        rtspTcpSwitch.setChecked(false);
        muteSwitch.setChecked(true);
        playerView.setKeepScreenOn(true);
        playerView.setUseController(false);
    }

    private void bindActions() {
        Button startButton = findViewById(R.id.exoplayer_start_button);
        Button stopButton = findViewById(R.id.exoplayer_stop_button);
        Button infoButton = findViewById(R.id.exoplayer_info_button);
        Button clearLogButton = findViewById(R.id.exoplayer_clear_log_button);

        startButton.setOnClickListener(v -> startPlayback());
        stopButton.setOnClickListener(v -> stopPlayback("user"));
        infoButton.setOnClickListener(v -> appendLog(buildInfoText()));
        nativeFallbackButton.setOnClickListener(v -> openNativePlayerWithCurrentSource());
        clearLogButton.setOnClickListener(v -> logTextView.setText(""));
    }

    private void startPlayback() {
        hideKeyboard();
        String source = sourceEditText.getText().toString().trim();
        if (TextUtils.isEmpty(source)) {
            toast("Please enter a source URL");
            return;
        }

        stopPlayback("restart");
        resetMetrics();

        boolean lowLatency = lowLatencySwitch.isChecked();
        boolean forceRtspTcp = rtspTcpSwitch.isChecked();
        sourceTypeText = detectSourceType(source);
        appendLog("start source=" + source
                + " type=" + sourceTypeText
                + " lowLatency=" + lowLatency
                + " rtspTcp=" + forceRtspTcp);

        DefaultLoadControl loadControl = buildLoadControl(lowLatency);
        player = new ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .build();
        player.setVolume(muteSwitch.isChecked() ? 0f : 1f);
        playerView.setPlayer(player);
        player.addListener(buildPlayerListener());
        player.addAnalyticsListener(buildAnalyticsListener());

        try {
            MediaSource mediaSource = buildMediaSource(source, forceRtspTcp);
            startRequestTimeMs = System.currentTimeMillis();
            player.setMediaSource(mediaSource);
            player.prepare();
            player.play();
            playbackStateText = "preparing";
        } catch (Throwable t) {
            lastError = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            appendLog("start failed: " + lastError);
            toast("ExoPlayer failed: " + lastError);
            stopPlayback("start failed");
        }
    }

    private DefaultLoadControl buildLoadControl(boolean lowLatency) {
        DefaultLoadControl.Builder builder = new DefaultLoadControl.Builder()
                .setPrioritizeTimeOverSizeThresholds(true);
        if (lowLatency) {
            builder.setBufferDurationsMs(250, 1000, 50, 100);
        } else {
            builder.setBufferDurationsMs(15000, 50000, 2500, 5000);
        }
        return builder.build();
    }

    private MediaSource buildMediaSource(String source, boolean forceRtspTcp) {
        Uri uri = Uri.parse(source);
        MediaItem mediaItem = new MediaItem.Builder().setUri(uri).build();
        String lower = source.toLowerCase(Locale.US);
        if (lower.startsWith("rtsp://") || lower.startsWith("rtsps://")) {
            return new RtspMediaSource.Factory()
                    .setForceUseRtpTcp(forceRtspTcp)
                    .setTimeoutMs(5000)
                    .createMediaSource(mediaItem);
        }
        if (lower.startsWith("rtmp://") || lower.startsWith("rtmps://")) {
            return new ProgressiveMediaSource.Factory(
                    new RtmpDataSource.Factory().setTransferListener(transferListener))
                    .createMediaSource(mediaItem);
        }
        if (lower.contains(".m3u8")) {
            return new HlsMediaSource.Factory(defaultDataSourceFactory())
                    .setAllowChunklessPreparation(true)
                    .createMediaSource(mediaItem);
        }
        return new ProgressiveMediaSource.Factory(defaultDataSourceFactory())
                .createMediaSource(mediaItem);
    }

    private DataSource.Factory defaultDataSourceFactory() {
        return new DefaultDataSource.Factory(this).setTransferListener(transferListener);
    }

    private Player.Listener buildPlayerListener() {
        return new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                playbackStateText = playbackStateName(playbackState);
                if (playbackState == Player.STATE_READY && firstReadyTimeMs <= 0) {
                    firstReadyTimeMs = System.currentTimeMillis();
                    appendLog("STATE_READY in " + readyMs() + " ms");
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                lastError = buildPlayerErrorMessage(error);
                appendLog("player error: " + lastError);
                lastCompatibilityHint = buildCompatibilityHint(lastError);
                if (!TextUtils.isEmpty(lastCompatibilityHint)) {
                    appendLog(lastCompatibilityHint);
                }
                if (isMissingFmtpRtspError(lastError)) {
                    playbackStateText = "rtsp_sdp_incompatible";
                    appendLog("Use Open Native FFmpeg to test this source through the FFmpeg demux/decode path.");
                }
                updateStatsText();
                toast("ExoPlayer error: " + lastError);
            }

            @Override
            public void onVideoSizeChanged(VideoSize videoSize) {
                videoSizeText = videoSize.width + "x" + videoSize.height;
            }
        };
    }

    private AnalyticsListener buildAnalyticsListener() {
        return new AnalyticsListener() {
            @Override
            public void onRenderedFirstFrame(EventTime eventTime, Object output, long renderTimeMs) {
                if (firstFrameTimeMs <= 0) {
                    firstFrameTimeMs = System.currentTimeMillis();
                    appendLog("first frame rendered in " + firstFrameMs() + " ms");
                }
            }

            @Override
            public void onDroppedVideoFrames(EventTime eventTime, int droppedFrameCount, long elapsedMs) {
                droppedFrames.addAndGet(Math.max(0, droppedFrameCount));
            }

            @Override
            public void onVideoInputFormatChanged(EventTime eventTime, Format format, DecoderReuseEvaluation decoderReuseEvaluation) {
                videoFormatText = format.sampleMimeType
                        + " " + format.width + "x" + format.height
                        + " bitrate=" + format.bitrate;
            }

            @Override
            public void onVideoFrameProcessingOffset(EventTime eventTime, long totalProcessingOffsetUs, int frameCount) {
                totalVideoFrameProcessingOffsetUs.set(totalProcessingOffsetUs);
                videoFrameProcessingCount.set(frameCount);
            }
        };
    }

    private void stopPlayback(String reason) {
        if (player == null) {
            return;
        }
        appendLog("stop reason=" + reason);
        try {
            player.stop();
        } catch (Throwable ignored) {
        }
        try {
            player.release();
        } catch (Throwable ignored) {
        }
        playerView.setPlayer(null);
        player = null;
        playbackStateText = "stopped";
        updateStatsText();
    }

    private void resetMetrics() {
        loadedBytes.set(0);
        droppedFrames.set(0);
        videoFrameProcessingCount.set(0);
        totalVideoFrameProcessingOffsetUs.set(0);
        startRequestTimeMs = 0;
        firstReadyTimeMs = 0;
        firstFrameTimeMs = 0;
        lastStatsTimeMs = 0;
        lastStatsBytes = 0;
        lastRenderedFrames = 0;
        playbackStateText = "idle";
        videoFormatText = "";
        videoSizeText = "";
        lastError = "";
        lastCompatibilityHint = "";
    }

    private void updateStatsText() {
        long nowMs = System.currentTimeMillis();
        ExoPlayer currentPlayer = player;
        DecoderCounters counters = currentPlayer == null ? null : currentPlayer.getVideoDecoderCounters();
        int rendered = 0;
        int dropped = 0;
        int maxConsecutiveDropped = 0;
        if (counters != null) {
            counters.ensureUpdated();
            rendered = counters.renderedOutputBufferCount;
            dropped = counters.droppedBufferCount;
            maxConsecutiveDropped = counters.maxConsecutiveDroppedBufferCount;
        }

        long bytes = loadedBytes.get();
        if (lastStatsTimeMs <= 0) {
            lastStatsTimeMs = nowMs;
            lastStatsBytes = bytes;
            lastRenderedFrames = rendered;
        }
        long deltaMs = Math.max(1, nowMs - lastStatsTimeMs);
        double renderFps = Math.max(0, rendered - lastRenderedFrames) * 1000.0 / deltaMs;
        double transferKb = Math.max(0, bytes - lastStatsBytes) * 1000.0 / deltaMs / 1024.0;
        double bitrateKbps = Math.max(0, bytes - lastStatsBytes) * 8.0 / deltaMs;

        lastStatsTimeMs = nowMs;
        lastStatsBytes = bytes;
        lastRenderedFrames = rendered;

        long bufferedMs = 0;
        long positionMs = 0;
        int bufferedPercent = 0;
        if (currentPlayer != null) {
            bufferedMs = Math.max(0, currentPlayer.getBufferedPosition() - currentPlayer.getCurrentPosition());
            positionMs = currentPlayer.getCurrentPosition();
            bufferedPercent = currentPlayer.getBufferedPercentage();
        }
        long avgOffsetUs = averageFrameProcessingOffsetUs();

        statsTextView.setText("state=" + playbackStateText
                + " type=" + emptyAsDash(sourceTypeText)
                + "\nready=" + readyMs() + "ms"
                + " firstFrame=" + firstFrameMs() + "ms"
                + " pos=" + positionMs + "ms"
                + " buf=" + bufferedMs + "ms/" + bufferedPercent + "%"
                + "\nrenderFps=" + formatOne(renderFps)
                + " rendered=" + rendered
                + " dropped=" + Math.max(dropped, droppedFrames.get())
                + " maxDrop=" + maxConsecutiveDropped
                + "\nbitrate=" + formatKbps(bitrateKbps)
                + " transfer=" + formatKbPerSec(transferKb)
                + " loaded=" + formatBytes(bytes)
                + "\nsize=" + emptyAsDash(videoSizeText)
                + " format=" + emptyAsDash(videoFormatText)
                + "\nframeOffsetAvgUs=" + avgOffsetUs
                + " error=" + emptyAsDash(lastError)
                + "\ncompat=" + emptyAsDash(lastCompatibilityHint));
    }

    private long readyMs() {
        if (startRequestTimeMs <= 0 || firstReadyTimeMs < startRequestTimeMs) {
            return -1;
        }
        return firstReadyTimeMs - startRequestTimeMs;
    }

    private long firstFrameMs() {
        if (startRequestTimeMs <= 0 || firstFrameTimeMs < startRequestTimeMs) {
            return -1;
        }
        return firstFrameTimeMs - startRequestTimeMs;
    }

    private long averageFrameProcessingOffsetUs() {
        long count = videoFrameProcessingCount.get();
        return count <= 0 ? 0 : totalVideoFrameProcessingOffsetUs.get() / count;
    }

    private String playbackStateName(int state) {
        switch (state) {
            case Player.STATE_IDLE:
                return "idle";
            case Player.STATE_BUFFERING:
                return "buffering";
            case Player.STATE_READY:
                return "ready";
            case Player.STATE_ENDED:
                return "ended";
            default:
                return "unknown";
        }
    }

    private String detectSourceType(String source) {
        String lower = source.toLowerCase(Locale.US);
        if (lower.startsWith("rtsp://") || lower.startsWith("rtsps://")) {
            return "RTSP";
        }
        if (lower.startsWith("rtmp://") || lower.startsWith("rtmps://")) {
            return "RTMP";
        }
        if (lower.contains(".m3u8")) {
            return "HLS";
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            return "HTTP";
        }
        return "FILE/OTHER";
    }

    private String buildInfoText() {
        return "ExoPlayer latency test\n"
                + "RTSP: RtspMediaSource, TCP=" + rtspTcpSwitch.isChecked() + "\n"
                + "RTMP: RtmpDataSource + ProgressiveMediaSource\n"
                + "HLS: HlsMediaSource\n"
                + "HTTP/file: ProgressiveMediaSource\n"
                + "LowLatency=" + lowLatencySwitch.isChecked()
                + " buffer=250~1000ms when enabled\n"
                + "Note: RTSP byte transfer may be unavailable because it does not use DefaultDataSource.\n"
                + "RTSP compatibility: Media3 requires standard SDP fmtp for H.264/H.265.\n"
                + "Open Native FFmpeg keeps the same URL and uses FFmpeg for non-standard RTSP streams.";
    }

    private String buildPlayerErrorMessage(PlaybackException error) {
        StringBuilder out = new StringBuilder();
        out.append(error.getErrorCodeName());
        if (!TextUtils.isEmpty(error.getMessage())) {
            out.append(": ").append(error.getMessage());
        }
        Throwable cause = error.getCause();
        int depth = 0;
        while (cause != null && depth < 6) {
            out.append(" <- ")
                    .append(cause.getClass().getSimpleName());
            if (!TextUtils.isEmpty(cause.getMessage())) {
                out.append(": ").append(cause.getMessage());
            }
            cause = cause.getCause();
            depth++;
        }
        return out.toString();
    }

    private boolean isMissingFmtpRtspError(String errorText) {
        String lower = errorText == null ? "" : errorText.toLowerCase(Locale.US);
        return lower.contains("missing attribute fmtp");
    }

    private String buildCompatibilityHint(String errorText) {
        String lower = errorText == null ? "" : errorText.toLowerCase(Locale.US);
        if (lower.contains("missing attribute fmtp")) {
            return "RTSP SDP compatibility issue: server DESCRIBE response is missing a=fmtp for H.264/H.265. "
                    + "Media3 ExoPlayer rejects this stream before decode. RTSP TCP/UDP and low-latency settings will not fix it. "
                    + "Fix the RTSP server SDP, use an HLS/HTTP stream, or open this source in the FFmpeg Native Player.";
        }
        if (lower.contains("unsupported") || lower.contains("malformed")) {
            return "Media3 parser/decoder compatibility issue. Compare with FFmpeg Native Player to confirm whether the stream is non-standard.";
        }
        return "";
    }

    private void openNativePlayerWithCurrentSource() {
        String source = sourceEditText.getText().toString().trim();
        if (TextUtils.isEmpty(source)) {
            toast("Please enter a source URL");
            return;
        }
        Intent intent = new Intent(this, MediaPlayerActivity.class);
        intent.putExtra(MediaPlayerActivity.EXTRA_URL, source);
        intent.putExtra(MediaPlayerActivity.EXTRA_HARDWARE_DECODE, true);
        intent.putExtra(MediaPlayerActivity.EXTRA_RTSP_TRANSPORT, rtspTcpSwitch.isChecked() ? "tcp" : "udp");
        intent.putExtra(MediaPlayerActivity.EXTRA_LATENCY_MODE,
                lowLatencySwitch.isChecked() ? "ultra_low_latency" : "balanced");
        appendLog("open Native FFmpeg source=" + source
                + " hardwareDecode=true"
                + " rtspTransport=" + (rtspTcpSwitch.isChecked() ? "tcp" : "udp")
                + " latency=" + (lowLatencySwitch.isChecked() ? "ultra_low_latency" : "balanced"));
        startActivity(intent);
    }

    private String formatOne(double value) {
        return String.format(Locale.US, "%.1f", value);
    }

    private String formatKbps(double value) {
        if (value >= 1000.0) {
            return String.format(Locale.US, "%.2fMbps", value / 1000.0);
        }
        return String.format(Locale.US, "%.0fkbps", value);
    }

    private String formatKbPerSec(double value) {
        if (value >= 1024.0) {
            return String.format(Locale.US, "%.2fMB/s", value / 1024.0);
        }
        return String.format(Locale.US, "%.0fKB/s", value);
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.2fMB", bytes / 1024.0 / 1024.0);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.1fKB", bytes / 1024.0);
        }
        return bytes + "B";
    }

    private String emptyAsDash(String value) {
        return TextUtils.isEmpty(value) ? "--" : value;
    }

    private void appendLog(String message) {
        Log.d(TAG, message);
        String old = logTextView.getText().toString();
        String next = old + "\n" + message;
        if (next.length() > 16000) {
            next = next.substring(next.length() - 16000);
        }
        logTextView.setText(next.trim());
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null && getCurrentFocus() != null) {
            imm.hideSoftInputFromWindow(getCurrentFocus().getWindowToken(), 0);
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacks(statsRunnable);
        stopPlayback("destroy");
        super.onDestroy();
    }
}
