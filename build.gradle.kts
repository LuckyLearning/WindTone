plugins {
    // 版本全部集中在 gradle/libs.versions.toml（Version Catalog），这里只做声明、不在根工程应用
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
