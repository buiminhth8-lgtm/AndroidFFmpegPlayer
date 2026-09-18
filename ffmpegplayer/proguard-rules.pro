# Keep JNI-accessed classes/methods (names must survive R8 minify)
-keep class com.example.motro.ffmpeg.FFmpegNative { *; }
-keep class com.example.motro.ffmpeg.FFmpegNative$* { *; }
-keep class com.example.motro.ffmpeg.LiveAudioPcmSink {
    <init>(...);
    int onAudioPcm(java.nio.ByteBuffer,int,long);
    int onAudioControl(int);
    int getPlaybackHeadFrames();
}
# Keep public facade API (direct Java reference, not JNI, but ensure consumer keeps it)
-keep class com.example.motro.ffmpeg.FFmpegPlayer { *; }
-keep class com.example.motro.ffmpeg.FFmpegPlayer$* { *; }
