// 依赖与插件的版本集中在 gradle/libs.versions.toml（Version Catalog）。
// Gradle 会自动把该文件注册成名为 `libs` 的目录，无需在这里 versionCatalogs { create("libs") }。
// 只在「想改名 / 想加载多个 catalog」时才需要显式声明。

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WindTone"
include(":app")
