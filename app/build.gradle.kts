// 注意：脚本里 `java` 会被 JavaPluginExtension 访问器遮蔽，必须显式 import
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/**
 * 版本号单一来源：改版本只改这两行即可。
 * CI（.github/workflows/android-release.yml）会从这里读取并与 tag 做一致性校验。
 */
val appVersionName = "1.0.6"
val appVersionCode = 8

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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    implementation("androidx.documentfile:documentfile:1.0.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("com.belerweb:pinyin4j:2.5.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.guava:guava:33.0.0-android")

    // 单元测试（lyrics/ 包为纯 JVM 逻辑，可直接跑）
    testImplementation("junit:junit:4.13.2")
}
