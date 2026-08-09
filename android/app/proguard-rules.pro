# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class io.github.gighi947.ankeshelf.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.gighi947.ankeshelf.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**

# jsoup
-dontwarn org.jsoup.**

# 仪器测试（AndroidJUnitRunner 在 app 进程引用）；正式包保留以便测试与发布同签名运行
-keep class androidx.tracing.Trace { *; }
