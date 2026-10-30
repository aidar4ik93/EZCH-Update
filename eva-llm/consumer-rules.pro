# JNI entry points and the byte sink called from C++.
-keep class com.example.homeezch.llm.EvaLlmNative { *; }
-keep interface com.example.homeezch.llm.EvaLlmNative$Sink { *; }
-keep class * implements com.example.homeezch.llm.EvaLlmNative$Sink { *; }
