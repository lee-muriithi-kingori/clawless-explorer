# Minify is disabled; rules kept minimal and safe.
# If minification is enabled later, revisit keep rules for Ktor/Netty/Gson.

# Gson generic signatures used by SettingsManager recents (TypeToken)
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class com.google.gson.** { *; }

# Ktor / Netty reflection
-dontwarn io.netty.**
-dontwarn org.slf4j.**

# Pdfium JNI bindings
-keep class com.github.barteksc.pdfium.** { *; }
