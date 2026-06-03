-keep class com.lampplayer.tv.domain.model.** { *; }
-keep class com.lampplayer.tv.data.tmdb.TmdbMetadata { *; }
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**
# libVLC relies on JNI — keep its classes and native callbacks
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**
-keepattributes Signature
-keepattributes *Annotation*
