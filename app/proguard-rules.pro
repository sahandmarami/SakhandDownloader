# قوانین ProGuard برای سخند دانلود منیجر
-keepattributes Signature, InnerClasses, EnclosingMethod

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keepclassmembers class okhttp3.** { *; }

# کاتلین متادیتا
-keep class kotlin.Metadata { *; }
