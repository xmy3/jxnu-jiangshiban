import java.io.File
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is read from a path outside the repo by default.
// Override with -PnvzhuanbanKeystoreProperties=... or NVZHUANBAN_KEYSTORE_PROPERTIES.
val externalKeystoreProperties = providers.gradleProperty("nvzhuanbanKeystoreProperties")
    .orElse(providers.environmentVariable("NVZHUANBAN_KEYSTORE_PROPERTIES"))
    .orElse(providers.environmentVariable("USERPROFILE").map { "$it/.android/nvzhuanban-signing/keystore.properties" })
val keystorePropertiesFile = sequenceOf(
    externalKeystoreProperties.orNull?.let { file(it) },
    rootProject.file("keystore.properties"),
).filterNotNull().firstOrNull { it.exists() }
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile?.exists() == true) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val hasReleaseSigning = keystorePropertiesFile != null &&
    keystoreProperties.getProperty("storeFile")?.isNotBlank() == true
val releaseStoreFile = keystoreProperties.getProperty("storeFile")
    ?.takeIf { it.isNotBlank() }
    ?.let { path ->
        val f = File(path)
        if (f.isAbsolute) f else File(keystorePropertiesFile!!.parentFile, path)
    }

android {
    namespace = "cn.jxnu.nvzhuanban"
    compileSdk = 36

    defaultConfig {
        applicationId = "cn.jxnu.nvzhuanban"
        minSdk = 26
        targetSdk = 36
        // versionCode 编码：major*100000 + minor*100 + patch（1.0.0 → 100000）。
        // 加宽过一次编码：versionCode 只能单调递增，历史安装包已用到更小的旧编码值，
        // 不要改回更窄的公式或调小数值，否则老用户覆盖安装会被 Android 拒绝。
        versionCode = 100002
        versionName = "1.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = releaseStoreFile
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        // AGP 8.7 lint crashes with lifecycle 2.10's detector.
        // Remove after the Gradle 9.4.1 / AGP 9.x upgrade lands.
        disable += "NullSafeMutableLiveData"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // 把 src/main/baseline-prof.txt 编进 APK 的 profile 在首启后台安装
    // （GitHub 直发 APK，没有 Play 云端 profile 分发可依赖）
    implementation(libs.androidx.profileinstaller)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Network
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.jsoup)

    // Glance widget
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // org.json 真实实现 —— Android stub 在 JVM unit test 里所有方法返回 null，
    // 用到 JSONObject.optString 的解析（GitHubUpdateClient / ScheduleSnapshot）会 NPE。
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
