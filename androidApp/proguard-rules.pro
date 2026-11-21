# ML Kit's code scanner (the enrollment QR code) finds its component registrars by name and makes them by reflection:
# without their constructors it logs NoSuchMethodException and the scanner doesn't start (as in journal-client-kmp).
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
-keep class com.google.mlkit.**.*Registrar { <init>(); }
