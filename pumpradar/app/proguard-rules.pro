# Room / kotlinx.serialization keep rules (release only; minify disabled in MVP).
-keepclassmembers class **$$serializer { *; }
-keep,includedescriptorclasses class com.aidar.pumpradar.**$$serializer { *; }
-keepclassmembers class com.aidar.pumpradar.** {
    kotlinx.serialization.KSerializer serializer(...);
}
