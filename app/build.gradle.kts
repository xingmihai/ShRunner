// AGP 9 has built-in Kotlin: do NOT apply org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// CI 把签名信息写进 local.properties（KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD）
// 这里只用 Kotlin 标准库解析，避免在 kts 脚本里引用 java.util.Properties（Kotlin DSL 解析不了）
fun localProp(name: String): String? {
    val f = rootProject.file("local.properties")
    if (!f.exists()) return null
    return f.readLines()
        .firstOrNull { it.trimStart().startsWith("$name=") }
        ?.substringAfter("=")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
}

fun signProp(name: String): String? =
    (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() } ?: localProp(name)

android {
    namespace = "com.shrunner.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shrunner.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        val ksFile = signProp("KEYSTORE_FILE")
        if (ksFile != null && file(ksFile).exists()) {
            create("release") {
                storeFile = file(ksFile)
                storePassword = signProp("KEYSTORE_PASSWORD")
                keyAlias = signProp("KEY_ALIAS")
                keyPassword = signProp("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // 有正式密钥就签名；没有则回落到 debug 密钥，保证 CI 产物可直接安装
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons.extended)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    testImplementation(libs.junit)
}
