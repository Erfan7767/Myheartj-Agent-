# Engineering decision: minification is disabled for this project (see app/build.gradle.kts).
# Rules are kept correct for a future minified build.
-keep class com.arenaai.duagents.BuildConfig { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
