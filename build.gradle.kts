// 根构建脚本：只声明插件，具体版本统一由 gradle/libs.versions.toml 管理
plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9 内置 Kotlin：不能再显式应用 org.jetbrains.kotlin.android，
    // 只保留 Compose 编译器插件
    alias(libs.plugins.kotlin.compose) apply false
}
