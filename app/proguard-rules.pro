# ──────────────────────────────────────────────────────────
# 江师办 R8 / ProGuard 规则
# ──────────────────────────────────────────────────────────
# 开 R8 + shrinkResources 后必须显式 keep 的反射 / 序列化目标。
# 规则范围尽量收紧到具体包名，避免回到"全留"的反优化。
# ──────────────────────────────────────────────────────────

# 保留行号，便于 release 包栈回溯定位
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保留 Kotlin metadata：反射 / 协程 / Compose 都依赖
-keepattributes RuntimeVisible*Annotations,AnnotationDefault,Signature,EnclosingMethod,InnerClasses,*Annotation*

# ── Compose ───────────────────────────────────────────────
# Compose 自带 consumer rules，但 Material3 + 自定义 @Composable 的反射型查找仍需兜底

# ── Kotlin 协程 ────────────────────────────────────────────
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepclassmembernames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepclassmembernames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ── OkHttp / Okio ─────────────────────────────────────────
# OkHttp 4.x 自带 consumer rules，但 Conscrypt / BouncyCastle 替代 provider 走反射加载，需 dontwarn
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.tls.**

# OkHttp logging interceptor 透传，所有内部类都不混淆名字（栈回溯可读）
-keepclassmembers class okhttp3.logging.HttpLoggingInterceptor { *; }

# Okio
-dontwarn okio.**

# ── Jsoup ─────────────────────────────────────────────────
# Jsoup 内部用反射加载 Parser/Cleaner 实现；包内全部 keep 才能跑通 ascx 解析
-dontwarn org.jsoup.**

# ── AndroidX Security Crypto (Tink) ───────────────────────
# EncryptedSharedPreferences 通过 Tink 反射查找 KeyTemplate / AEAD 实现
-keep class com.google.crypto.tink.** { *; }
-keep class com.google.crypto.tink.proto.** { *; }
-keep class com.google.crypto.tink.shaded.protobuf.** { *; }
-dontwarn com.google.crypto.tink.**
-dontwarn com.google.api.client.**
-dontwarn com.google.errorprone.annotations.**

# 保护 SecureCredentialStore 等使用 EncryptedSharedPreferences 的逻辑里 of() 等静态工厂
-keep class androidx.security.crypto.** { *; }
-dontwarn androidx.security.crypto.**

# ── Glance App Widget ─────────────────────────────────────
# Glance 通过反射枚举 GlanceAppWidgetReceiver 子类
-keep class * extends androidx.glance.appwidget.GlanceAppWidgetReceiver { *; }
-keep class * extends androidx.glance.appwidget.GlanceAppWidget { *; }
-keep class cn.jxnu.nvzhuanban.ui.widget.** { *; }
-dontwarn androidx.glance.**

# ── Navigation Compose ────────────────────────────────────

# ── 应用自身的 data 类 ───────────────────────────────────
# data class 在序列化 / Jsoup 转换 / Compose 状态比较时不依赖反射，
# 默认无需 keep。widget 的 ScheduleSnapshot 走手写 JSONObject（字段名是字符串字面量），
# R8 名字混淆不影响它，故也不必 keep。

# ── 其它常见 R8 噪声 ─────────────────────────────────────
-dontwarn java.lang.invoke.StringConcatFactory
-dontwarn javax.annotation.**
