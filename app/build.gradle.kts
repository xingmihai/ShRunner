plugins {
    alias(libs.plugins.android.application)
    // AGP 9 起 Kotlin 由 Android 插件内置提供，这里只应用 Compose 编译器插件
    alias(libs.plugins.kotlin.compose)
}

// 签名配置：优先读 local.properties（不入库），读不到再退回环境变量（CI 用）
// 这里手写解析，避免 Kotlin DSL 里 java.* 与插件访问器重名导致的解析歧义
val keystoreProps: Map<String, String> = rootProject.file("local.properties")
    .takeIf { it.exists() }
    ?.readLines(Charsets.UTF_8)
    ?.mapNotNull { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return@mapNotNull null
        val idx = trimmed.indexOf('=')
        if (idx <= 0) null else trimmed.substring(0, idx).trim() to trimmed.substring(idx + 1).trim()
    }
    ?.toMap()
    ?: emptyMap()

fun signingValue(key: String): String? =
    keystoreProps[key]?.takeIf { it.isNotBlank() } ?: System.getenv(key)?.takeIf { it.isNotBlank() }

val keystoreFile = signingValue("KEYSTORE_FILE")?.let { rootProject.file(it) }?.takeIf { it.exists() }
val keystorePassword = signingValue("KEYSTORE_PASSWORD")
val keyAliasValue = signingValue("KEY_ALIAS")
val keyPasswordValue = signingValue("KEY_PASSWORD")
val canSign = keystoreFile != null && keystorePassword != null && keyAliasValue != null && keyPasswordValue != null

android {
    namespace = "com.xingmihai.shrunner"
    compileSdk = 37
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.xingmihai.shrunner"
        minSdk = 26
        // Android 17（API 37）
        targetSdk = 37
        versionCode = 28
        versionName = "0.4.3"
    }

    signingConfigs {
        if (canSign) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
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
            if (canSign) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (canSign) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // 内置 PTY 工具：编译 src/main/cpp/shpty.c 为可执行文件，
    // 产出的 libshpty.so 会随 APK 安装到 nativeLibraryDir，开箱即用
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.material3.adaptive)
    debugImplementation(libs.compose.ui.tooling)
}
