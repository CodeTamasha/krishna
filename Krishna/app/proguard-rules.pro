# OkHttp
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
# Gson
-keep class com.google.gson.** { *; }
-keepclassmembers class * { @com.google.gson.annotations.* <fields>; }
