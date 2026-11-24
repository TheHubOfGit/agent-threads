# Release build rules. The app uses no reflection; OkHttp and the AndroidX libraries ship their own
# consumer rules. Keep line numbers so crash traces from the watch stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# OkHttp's optional TLS providers are not on Android.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
