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

# Optional JVM TLS providers referenced reflectively by OkHttp 4.12 on non-Android JVMs.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
