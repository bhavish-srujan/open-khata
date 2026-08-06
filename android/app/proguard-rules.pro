# Retrofit
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes Exceptions
-keep,allowobfuscation,allowshrinking interface kotlin.coroutines.Continuation

# Gson
-keep class com.google.gson.** { *; }
-keep class com.google.gson.stream.** { *; }

# Keep our DTOs and API interfaces
-keep class com.example.khatabook.data.api.** { *; }

# If you use OkHttp
-keepattributes *Annotation*
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
