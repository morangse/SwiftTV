# Keep the ADB client and its crypto stack intact when minification is enabled.
-keep class com.flyfishxu.kadb.** { *; }
-keep class com.flyfish233.crypto.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
