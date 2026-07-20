package com.example.motro;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;

public class PlayerLauncherActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player_launcher);

        Button nativeButton = findViewById(R.id.open_native_player_button);
        Button mediaCodecButton = findViewById(R.id.open_mediacodec_player_button);
        Button exoPlayerButton = findViewById(R.id.open_exoplayer_player_button);

        nativeButton.setOnClickListener(v -> startActivity(new Intent(this, MediaPlayerActivity.class)));
        mediaCodecButton.setOnClickListener(v -> startActivity(new Intent(this, MediacodecPlayerActivity.class)));
        exoPlayerButton.setOnClickListener(v -> startActivity(new Intent(this, ExoplayerPlayerActivity.class)));
    }
}
