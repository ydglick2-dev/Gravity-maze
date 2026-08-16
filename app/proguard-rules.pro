# WebRTC's Java layer is reached from its own native code by name; stripping or renaming any of
# it breaks PeerConnectionFactory at runtime with an opaque UnsatisfiedLinkError.
-keep class org.webrtc.** { *; }
-keepclassmembers class org.webrtc.** { native <methods>; }
-dontwarn org.webrtc.**

# Classes and members our own libkolan.so looks up through JNI.
-keep class il.kolan.audio.NativeEngine { *; }
-keep class il.kolan.audio.NativeEngine$Companion { *; }
-keepclasseswithmembernames class * { native <methods>; }

# kotlinx.serialization keeps generated serializers off the reflection path.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class il.kolan.data.** {
    *** Companion;
}
-keepclasseswithmembers class il.kolan.data.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp ships optional platform integrations that are absent on Android.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
