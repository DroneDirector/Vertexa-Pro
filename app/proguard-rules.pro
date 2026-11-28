# JNI bridge: native methods and callbacks are looked up by name
-keep class com.dronedirector.vertexapro.viewer.NativeBridge { *; }
-keep class com.dronedirector.vertexapro.viewer.SiblingResolver { *; }
-keepclasseswithmembernames class * { native <methods>; }

# ONNX Runtime (AI select) uses JNI and reflection
-keep class ai.onnxruntime.** { *; }
