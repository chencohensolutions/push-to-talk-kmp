# ML Kit's code scanner (the enrollment QR code) finds its component registrars by name and makes them by reflection:
# without their constructors it logs NoSuchMethodException and the scanner doesn't start (as in journal-client-kmp).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.**.*Registrar { <init>(); }

# libsignal's Rust code calls back into its Java classes by name (@CalledFromNative): stores, records, exceptions.
-keep class org.signal.libsignal.** { *; }
-keep interface org.signal.libsignal.** { *; }
-dontwarn org.signal.libsignal.**
# Our libsignal stores are called from native code through libsignal's interfaces.
-keep class com.chencohen.ptt.crypto.SignalStore { *; }

# LiteRT from Google Play services (Silero, and Your picture's face models, 2026-11-24): its factory is made by
# reflection, so R8 must keep it.
-keep class com.google.android.gms.tflite.** { *; }
-keep class org.tensorflow.lite.** { *; }
