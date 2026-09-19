# Proguard rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep core models for JSON reflection and serialization
-keep class com.codex.chat.core.model.** { *; }
-keep class com.codex.chat.core.provider.** { *; }
-keep class com.codex.chat.core.media.** { *; }
-keep class com.caverock.androidsvg.** { *; }
-dontwarn com.caverock.androidsvg.**
-keep class com.codex.chat.core.mcp.model.** { *; }
# Auditoria v1.0.79: mantener toda la cadena mcp.approval regalaba a jadx los
# nombres exactos de las clases de seguridad. Solo se conservan los tipos que
# se serializan o se resuelven por reflexion.
-keep class com.codex.chat.core.mcp.approval.ApprovalRequest { *; }
-keep class com.codex.chat.core.mcp.approval.ApprovalDecision { *; }
-keep class com.codex.chat.core.mcp.approval.ToolRiskLevel { *; }
-keep class com.codex.chat.core.mcp.approval.ApprovalPolicy { *; }
-keep class com.codex.chat.core.mcp.model.** { *; }
-keep class com.codex.chat.core.mcp.McpRegistry { *; }
-keep class com.codex.chat.UpdateInfo { *; }

# OkHttp & Okio
-dontwarn okhttp3.**
-dontwarn okio.**
