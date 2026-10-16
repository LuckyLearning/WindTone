// 注意：脚本里 `java` 会被 JavaPluginExtension 访问器遮蔽，必须显式 import
import java.util.Properties

plugins {
    // 依赖与插件的版本统一由 gradle/libs.versions.toml 管控，见该文件头部说明
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * 版本号单一来源：改版本只改这两行即可。
 * CI（.github/workflows/android-release.yml）会从这里读取并与 tag 做一致性校验。
 *
 * ⚠️ 这两行**故意不放进 Version Catalog**：`scripts/release.sh` 与
 * `.github/release-notes` 渲染器都用 `grep` 按这个固定文本格式解析它们，
 * 搬到 TOML 会连带改掉整条发版链路。第三方依赖版本在 catalog 里，产品版本在这里。
 */
val appVersionName = "1.1.5"
val appVersionCode = 15

/**
 * ABI 分包开关。
 *
 * **只有 CI 显式传 `-PabiSplits=true` 时才开启**：
 *  - 开启后 assembleRelease 产出 `WindTone-v<版本>-<abi>.apk` × N + `-universal.apk`；
 *  - 本地开发默认关闭，`assembleDebug` / `installDebug` 仍然只产出一个包，
 *    不会因为多包让 IDE 部署或调试变麻烦。
 *
 * 说明：本项目的 native 依赖只有 `libandroidx.graphics.path.so`（4 个 ABI 合计约 37KB），
 * 分包带来的体积收益很小（每个包约省几十 KB）。分包的主要价值是让用户在 Releases
 * 页面按设备架构选择、并让车机 / 老设备拿到明确的 v7a 包，而不是压缩体积。
 */
val abiSplitsEnabled = (findProperty("abiSplits") as String?)?.toBoolean() == true

/**
 * Room schema 导出目录。
 *
 * 手写 Migration 的 DDL 必须与 Room 运行时期望的结构**完全一致**，
 * `app/schemas/<数据库类>/<版本>.json` 里存着权威的建表语句与索引。
 * 改表时对照它写迁移，能避免"装到真机上才报 schema 不匹配"。schema 文件入库。
 */
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

/**
 * 签名配置：本地与 CI 使用**同一套**密钥（.toolchain/keystore/windtone.jks）。
 * 密钥本体不进仓库（已 gitignore），CI 侧以 Secrets 注入同样的值。
 *
 * 取值优先级（先命中者胜）：
 *   1. -P 命令行参数            （临时覆盖 / 换密钥调试）
 *   2. 环境变量 WINDTONE_*      （CI：来自 GitHub Secrets）
 *   3. 本地 keystore.properties （已 gitignore，开发机专属）
 *   4. 默认路径 .toolchain/keystore/windtone.jks
 * 口令全部缺失时 Release 不签名，assembleRelease 仍能产出未签名包。
 */
val localSigningProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/** 依次从 -P 参数、环境变量、keystore.properties 取值。 */
fun signingValue(propName: String, envName: String, fileKey: String): String? =
    (findProperty(propName) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: localSigningProps.getProperty(fileKey)?.takeIf { it.isNotBlank() }

val defaultKeystore = rootProject.file(".toolchain/keystore/windtone.jks")
// 相对路径一律相对仓库根解析（CI 传入的 RUNNER_TEMP 绝对路径原样使用）
val configuredKeystore = signingValue("keystorePath", "WINDTONE_KEYSTORE", "storeFile")
    ?.let { rootProject.file(it) }
val keystoreFile: File = listOfNotNull(configuredKeystore, defaultKeystore).firstOrNull { it.exists() }
    ?: configuredKeystore ?: defaultKeystore

val keystoreStorePassword = signingValue("keystorePassword", "WINDTONE_STORE_PASSWORD", "storePassword")
val keystoreKeyAlias = signingValue("keyAlias", "WINDTONE_KEY_ALIAS", "keyAlias")
val keystoreKeyPassword = signingValue("keyPassword", "WINDTONE_KEY_PASSWORD", "keyPassword")
    ?: keystoreStorePassword

android {
    namespace = "com.luckylearning.windtone"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.luckylearning.windtone"
        minSdk = 24
        targetSdk = 35
        versionCode = appVersionCode
        versionName = appVersionName
        vectorDrawables { useSupportLibrary = true }
        resourceConfigurations += listOf("zh", "en")

        // 应用内更新：Release 托管在 GitHub，APK 通过 Releases 资产分发
        buildConfigField("String", "GITHUB_OWNER", "\"LuckyLearning\"")
        buildConfigField("String", "GITHUB_REPO", "\"WindTone\"")
        buildConfigField("String", "GITHUB_RELEASES_URL", "\"https://github.com/LuckyLearning/WindTone/releases\"")
        // APK 命名规则（见文件末尾 androidComponents 的命名逻辑）：
        //  - 不分包：WindTone-v<版本>.apk（历史行为，应用内更新仍兼容）
        //  - 分包：  WindTone-v<版本>-<abi>.apk / WindTone-v<版本>-universal.apk
        buildConfigField("String", "APK_ASSET_TEMPLATE", "\"WindTone-v%s.apk\"")
        buildConfigField("String", "APK_ASSET_TEMPLATE_ABI", "\"WindTone-v%s-%s.apk\"")
        buildConfigField("String", "APK_ASSET_TEMPLATE_UNIVERSAL", "\"WindTone-v%s-universal.apk\"")
    }

    signingConfigs {
        if (keystoreFile.exists() && keystoreStorePassword != null) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystoreStorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // R8：代码压缩 + 混淆 + 资源压缩（预期 10.1MB -> 6~7MB）
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystoreFile.exists() && keystoreStorePassword != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=androidx.media3.common.util.UnstableApi",
        )
    }

    buildFeatures {
        compose = true
        // 应用内更新需要读取 BuildConfig.VERSION_NAME / GITHUB_*
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "META-INF/*.kotlin_module"
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    /**
     * ABI 分包（仅 `-PabiSplits=true` 时生效，见文件顶部的说明）。
     * 保留 universal 包，作为「拿不准架构」与手动下载的兜底。
     */
    splits {
        abi {
            isEnable = abiSplitsEnabled
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        // Room 迁移/DAO 回归测试需要真实 SQLite 与应用 Context，走 Robolectric
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.useJUnit() }
    }
}

/**
 * APK 产物命名。
 *
 *  - 不分包：`WindTone-v<版本>.apk`（debug 追加 `-debug`）
 *  - 分包：  `WindTone-v<版本>-<abi>.apk`；universal 包为 `WindTone-v<版本>-universal.apk`
 *
 * 命名必须与 GitHub Releases 里的资产名一致 —— 应用内更新（UpdateChecker）在
 * Atom 通道下拿不到资产列表，只能按这套规则推导下载地址。
 */
androidComponents {
    onVariants(selector().all()) { variant ->
        val suffix = if (variant.buildType == "release") "" else "-${variant.buildType}"
        variant.outputs.forEach { output ->
            val impl = output as? com.android.build.api.variant.impl.VariantOutputImpl ?: return@forEach
            val abi = impl.filters
                .firstOrNull { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                ?.identifier
            impl.outputFileName.set(
                when {
                    abi != null -> "WindTone-v$appVersionName-$abi$suffix.apk"
                    // 分包开启时，没有 ABI 过滤的那个产物就是 universal 包
                    abiSplitsEnabled -> "WindTone-v$appVersionName-universal$suffix.apk"
                    else -> "WindTone-v$appVersionName$suffix.apk"
                }
            )
        }
    }
}

dependencies {
    // 坐标与版本全部来自 gradle/libs.versions.toml（Version Catalog）。
    // 这里只写 libs.xxx 访问器 —— 任何字面版本号出现在本块都算破坏约定。

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.documentfile)

    // Compose：BOM 对齐全部 compose-* 版本，故下面的库都不带版本号
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.pinyin4j)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.guava)

    // 单元测试（lyrics/ 包为纯 JVM 逻辑，可直接跑）
    testImplementation(libs.junit)
    // Robolectric：让 Room（真实 SQLite）能在 JVM 单测里跑，用于 DAO / CASCADE 回归测试
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Compose UI 冒烟测试：Robolectric + compose rule → 不需要模拟器就能
    // 断言「某块界面渲染出来了」「点某个按钮触发了哪个回调」
    testImplementation(libs.androidx.compose.ui.test.junit4)
    // createComposeRule() 需要一个空壳 ComponentActivity，由它提供（只进 debug 变体）
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
