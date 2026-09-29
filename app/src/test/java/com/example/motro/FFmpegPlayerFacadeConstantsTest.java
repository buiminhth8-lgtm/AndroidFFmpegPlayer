package com.example.motro;

import static org.junit.Assert.assertEquals;

import com.example.motro.ffmpeg.FFmpegNative;
import com.example.motro.ffmpeg.FFmpegPlayer;

import org.junit.Test;

public class FFmpegPlayerFacadeConstantsTest {

    @Test
    public void thermalPaletteConstantsMatchLegacyBridge() {
        assertEquals(FFmpegNative.THERMAL_PALETTE_ORIGINAL,
                FFmpegPlayer.THERMAL_PALETTE_ORIGINAL);
        assertEquals(FFmpegNative.THERMAL_PALETTE_WHITE_HOT,
                FFmpegPlayer.THERMAL_PALETTE_WHITE_HOT);
        assertEquals(FFmpegNative.THERMAL_PALETTE_IRONBOW,
                FFmpegPlayer.THERMAL_PALETTE_IRONBOW);
    }
}
