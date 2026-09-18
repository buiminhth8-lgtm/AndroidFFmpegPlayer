# JNI_OnLoad finds this class and registers its native methods by exact name
# and descriptor.
-keep,allowoptimization class com.example.motro.ffmpeg.FFmpegNative {
    native <methods>;
}

# JNI_OnLoad finds this nested class by binary name and native code invokes its
# long constructor.
-keep,allowoptimization class com.example.motro.ffmpeg.FFmpegNative$OesFrameListener {
    <init>(long);
}

# The registered setPlayerEventListener descriptor and the native callback
# lookup both use this exact interface/method contract.
-keep,allowoptimization interface com.example.motro.ffmpeg.FFmpegNative$PlayerEventListener {
    void onPlayerEvent(long,java.lang.String,java.lang.String);
}
-keepclassmembers,allowoptimization class * implements com.example.motro.ffmpeg.FFmpegNative$PlayerEventListener {
    void onPlayerEvent(long,java.lang.String,java.lang.String);
}

# Native code obtains the runtime sink class from the callback object and looks
# up only these methods by exact name and descriptor.
-keepclassmembers,allowoptimization class com.example.motro.ffmpeg.LiveAudioPcmSink {
    int onAudioPcm(java.nio.ByteBuffer,int,long);
    int onAudioControl(int);
    int getPlaybackHeadFrames();
}
