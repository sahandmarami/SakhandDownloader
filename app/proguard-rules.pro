# قوانین ProGuard برای Download Manager
-keepattributes Signature, InnerClasses, EnclosingMethod

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers class okhttp3.** { *; }

# کاتلین متادیتا
-keep class kotlin.Metadata { *; }
