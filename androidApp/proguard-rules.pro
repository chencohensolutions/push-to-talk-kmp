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
