# Proguard rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep core models for JSON reflection and serialization
-keep class com.codex.chat.core.model.** { *; }
-keep class com.codex.chat.core.provider.** { *; }
-keep class com.codex.chat.core.media.** { *; }
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**
-keep class com.codex.chat.core.mcp.model.** { *; }
-keep class com.codex.chat.core.mcp.approval.** { *; }
-keep class com.codex.chat.core.mcp.server.** { *; }
-keep class com.codex.chat.core.mcp.McpRegistry { *; }
-keep class com.codex.chat.UpdateInfo { *; }

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
