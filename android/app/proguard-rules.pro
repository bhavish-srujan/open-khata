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

# Party/LedgerEntry and MutationSnapshot are Gson-serialized directly (see
# KhatabookRepository's rollbackPayload/MutationSnapshot use for the
# 403-rollback path) — without this, R8 renames their fields and a minified
# build silently fails to restore the pre-mutation snapshot on rollback.
-keep class com.example.khatabook.data.model.** { *; }
-keep class com.example.khatabook.data.repository.MutationSnapshot { *; }
-keep class com.example.khatabook.data.repository.PendingOp { *; }
-keep enum com.example.khatabook.data.repository.PendingOpType { *; }
-keep enum com.example.khatabook.data.repository.OpStatus { *; }

# If you use OkHttp
-keepattributes *Annotation*
-keepclassmembers,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
