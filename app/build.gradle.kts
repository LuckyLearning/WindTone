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
val appVersionName = "1.1.66"
val appVersionCode = 76

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
 * 启动诊断构建（**只给排障用，不进发版**）。
 *
 * `-PdiagBuild=true` 时 `BuildConfig.BOOT_DIAG = true`，应用每次冷启动都先停在
 * 一张纯原生 View 的诊断页上（设备信息 / 屏幕与 insets / 上一轮启动阶段表），
 * 点「进入应用」才走 Compose。
 *
 * 之所以做成构建开关而不是运行时开关：车机上「打开全黑」时用户根本点不到任何开关，
 * 只有把判断前移到构建期，才能保证那台机器上一定能看到这一页。
 * 默认 false —— 常规构建、CI 发版都拿不到这一页。
 */
val diagBuild = (findProperty("diagBuild") as String?)?.toBoolean() == true

/**
 * 软件渲染构建（`-PswRender=true`）。
 *
 * Compose 依赖硬件加速画布；部分车机 ROM 的 GL 驱动有问题时，画面会是纯黑但进程
 * 一切正常。这个开关把 `android:hardwareAccelerated` 置为 false 用来做 A/B 对照：
 * 若软件渲染包能正常显示，就能确定是 GPU/驱动一侧的问题。
 */
val swRenderBuild = (findProperty("swRender") as String?)?.toBoolean() == true

/**
 * 半屏对照测试构建（`-Pprobe=split`）。
 *
 * 车机上已经确认：**原生 View 能正常显示整页诊断信息，窗口与 insets 都正常**，
 * 但点了「进入应用」之后仍然全黑。这时候要回答的问题只剩一个：
 * 到底是 Compose 这条渲染链在这台机器上画不出东西，还是我们的界面代码把它画没了？
 *
 * 这个构建把屏幕上下各分一半：上半屏是纯原生 `TextView`，下半屏是一个最小化的
 * `ComposeView`（只有一个色块和一行字，不挂主题、不挂 ViewModel、不读 insets）。
 * 一张照片就能分辨三种情况：
 *   · 两半都有 → Compose 没问题，问题在我们的界面代码里（下一步二分 MainScreen）
 *   · 只有上半屏，下半屏是**黄色** → ComposeView 一像素都没画（渲染链问题）
 *   · 只有上半屏，下半屏是**黑色** → Compose 画了，但画出来的是黑（配色/图层问题）
 * 黄色的用法是故意的：它就是用来区分「什么都没画」与「画了黑的」。
 */
val probeBuild = (findProperty("probe") as String?).orEmpty()

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

// ---------------------------------------------------------------- 第三方凭据
// 百度网盘开放平台的 AppKey / SecretKey：
//   · 本机开发：工程根目录下的 baiduAppKey / SecretKey（已 gitignore，不入库）
//   · CI 构建：仓库 Secrets 注入的环境变量 BAIDU_APP_KEY / BAIDU_SECRET_KEY
// 两者都没有时留空 —— 应用里百度网盘入口会显示「当前构建未配置应用凭据」，
// 而不是让用户对着一个点不动的按钮猜。
fun credential(envName: String, fileName: String): String {
    val fromFile = rootProject.file(fileName).takeIf { it.exists() }?.readText()?.trim().orEmpty()
    return fromFile.ifBlank { System.getenv(envName).orEmpty() }.trim()
}

val baiduAppKey = credential("BAIDU_APP_KEY", "baiduAppKey")
val baiduSecretKey = credential("BAIDU_SECRET_KEY", "SecretKey")

// 阿里云盘开放平台（在线播放）的 ClientId / ClientSecret：
//   · 本机开发：工程根目录下的 aliyunClientId / aliyunClientSecret（已 gitignore，不入库）
//   · CI 构建：仓库 Secrets 注入的环境变量 ALIYUN_CLIENT_ID / ALIYUN_CLIENT_SECRET
// 都没有时留空 —— 应用里阿里云盘入口会显示「当前构建未配置应用凭据」，
// 与百度网盘入口保持同一套兜底语义。
val aliyunClientId = credential("ALIYUN_CLIENT_ID", "aliyunClientId")
val aliyunClientSecret = credential("ALIYUN_CLIENT_SECRET", "aliyunClientSecret")

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
        // 仪器化测试跑在模拟器/真机上；JVM 单测（Robolectric）不需要它
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // 应用内更新：源码仓库是**私有**的，安装包改由公共分发仓库
        // LuckyLearning/Release 按 <软件>/<版本> 目录分发（只保留最近 5 个版本）。
        // 应用读的是该仓库里的 WindTone/latest.json，不需要任何鉴权。
        buildConfigField("String", "RELEASE_OWNER", "\"LuckyLearning\"")
        buildConfigField("String", "RELEASE_REPO", "\"Release\"")
        buildConfigField("String", "RELEASE_BRANCH", "\"main\"")
        buildConfigField("String", "RELEASE_APP_DIR", "\"WindTone\"")
        buildConfigField(
            "String",
            "RELEASE_PAGE_URL",
            "\"https://github.com/LuckyLearning/Release/tree/main/WindTone\""
        )

        // 百度网盘（v1.1.39）：Key 从文件/环境变量读，见文件顶部 credential()
        buildConfigField("String", "BAIDU_APP_KEY", "\"$baiduAppKey\"")
        buildConfigField("String", "BAIDU_SECRET_KEY", "\"$baiduSecretKey\"")

        // 阿里云盘（在线播放）：Key 从文件/环境变量读，见文件顶部 credential()
        buildConfigField("String", "ALIYUN_CLIENT_ID", "\"$aliyunClientId\"")
        buildConfigField("String", "ALIYUN_CLIENT_SECRET", "\"$aliyunClientSecret\"")

        // 启动诊断页（见文件顶部 diagBuild 的说明），默认关闭
        buildConfigField("boolean", "BOOT_DIAG", diagBuild.toString())
        // 半屏对照（见文件顶部 probeBuild 的说明），空串 = 正常构建
        buildConfigField("String", "PROBE", "\"$probeBuild\"")
        // 硬件加速开关由构建参数决定（见 swRenderBuild），供 A/B 对照
        manifestPlaceholders["hwAccel"] = if (swRenderBuild) "false" else "true"
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
        unitTests.all {
            it.useJUnit()
            // 失败用例打全堆栈：CI（JDK 17）与本地（JDK 21）行为有差异时，
            // 只有 "AssertionError at Xxx.kt:56" 这种一行字没法定位是哪条断言
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
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
    implementation(libs.androidx.media3.exoplayer.hls)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 设置域新存储：与 SharedPreferences 双写过渡，见 data/SettingsDataStore.kt
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.pinyin4j)

    // 阿里云盘扫码授权：本地生成登录二维码（见 data/aliyun/AliyunQrLogin.kt）
    implementation(libs.zxing.core)

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

    // 仪器化冒烟测试（模拟器 / 真机）：ActivityScenario 启动主界面，
    // 验证 Room / PlaybackEngine / ViewModel 这棵应用图能真正装配起来。
    // JDK 侧已缓存，离线也能解析。
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.monitor)
}
