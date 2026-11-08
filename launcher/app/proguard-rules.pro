# EZCH Launcher release: R8 shrinks and optimises Compose code (smoother HOME on 2 GB TVs).
# Vosk talks to native code through JNA, which uses reflection and JNI callbacks.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class org.vosk.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
# Services/receivers/activities are referenced from the manifest and kept automatically.
# Keep model classes parsed by name in JSON-free reflection-less code paths: nothing else needed.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Claude r84: ComposeA11yGate reads/writes this private field by name (desktop semantics off when only EZCH services are on).
-keepnames class androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat
-keepclassmembernames class androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat { java.util.List _enabledServices; }
