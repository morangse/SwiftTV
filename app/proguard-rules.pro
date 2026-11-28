# Preserve line numbers for local diagnostic stack traces without exposing source paths.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep Android manifest components explicitly.
-keep class app.swifttv.player.SwiftTvApplication { *; }
-keep class app.swifttv.player.MainActivity { *; }
-keep class app.swifttv.player.BootCompletedReceiver { *; }
-keep class app.swifttv.player.StartupRetryReceiver { *; }
-keep class app.swifttv.player.StartupGuardService { *; }
-keep class app.swifttv.player.StartupRevivalListener { *; }

# Keep Media3 dynamic MediaSource factories loaded reflectively by DefaultMediaSourceFactory.
-keep class androidx.media3.exoplayer.hls.HlsMediaSource$Factory {
    public <init>(androidx.media3.datasource.DataSource$Factory);
}
-keep class androidx.media3.exoplayer.rtsp.RtspMediaSource$Factory {
    public <init>();
}

# Optional JVM/legacy annotations referenced reflectively by OkHttp 3.12 / 4.12 and Okio.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
