-dontwarn org.slf4j.helpers.SubstituteLogger

# Protobuf
-keep class chromeos_update_engine.** { *; }
-keep class com.google.protobuf.** { *; }
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { *; }

# Native JNI
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class io.github.long36708.updater.** { *; }

# Vivo SecKey SDK (libvivoseckey.so JNI engine)
# 必须原样保留，否则 R8 混淆/裁剪后 SDKCipherNative.init() 失败，
# UI 会一直卡在"正在初始化加密引擎"。
-keep class com.vivo.seckeysdk.** { *; }
-keep class com.vivo.seckeysdk.utils.** { *; }
-dontwarn com.vivo.seckeysdk.**

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Apache Commons Compress
-dontwarn org.apache.commons.compress.**
-keep class org.apache.commons.compress.** { *; }

# zstd-jni（payload 的 REPLACE_ZSTD 解压）
# .so 里的 JNI 实现用 GetFieldID 按「原始字段名」取私有字段（srcPos/dstPos/srcSize/stream…）。
# `-keepclasseswithmembernames` 只保住 native 方法名，保不住字段名——R8 把它们重命名后
# native 代码找不到字段，ART 直接 abort（JNI DETECTED ERROR: no "J" field "dstPos"）。
# 必须整类保留且不许改成员名，否则正式版点「提取」立即崩溃。
-keep class com.github.luben.zstd.** { *; }
-keepclassmembers class com.github.luben.zstd.** { <fields>; }
-dontwarn com.github.luben.zstd.**

# Kotlin Coroutines
-dontwarn kotlinx.coroutines.**

# Miuix
-keep class top.yukonga.miuix.** { *; }
