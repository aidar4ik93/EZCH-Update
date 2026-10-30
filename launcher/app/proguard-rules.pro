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
