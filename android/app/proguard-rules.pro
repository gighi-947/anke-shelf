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
