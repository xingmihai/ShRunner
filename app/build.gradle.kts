// AGP 9 has built-in Kotlin: do NOT apply org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.shrunner.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.shrunner.app"
        minSdk = 26
        targetSdk = 37
        versionCode = (providers.gradleProperty("VERSION_CODE").orNull ?: "1").toInt()
        versionName = providers.gradleProperty("VERSION_NAME").orNull ?: "0.1.0"
    }

    signingConfigs {
        // CI 通过 -PKEYSTORE_PATH / -PKEYSTORE_PASSWORD / -PKEY_ALIAS / -PKEY_PASSWORD 注入
        val ksPath = providers.gradleProperty("KEYSTORE_PATH").orNull
        if (!ksPath.isNullOrBlank()) {
            create("release") {
                storeFile = file(ksPath)
                storePassword = providers.gradleProperty("KEYSTORE_PASSWORD").orNull
                keyAlias = providers.gradleProperty("KEY_ALIAS").orNull
                keyPassword = providers.gradleProperty("KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        release {
            // 有正式密钥则用它签名，否则回落到 debug 密钥，保证 CI 产物可直接安装
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
