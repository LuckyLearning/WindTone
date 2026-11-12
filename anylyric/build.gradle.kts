// AnyLyric：WindTone 的轻量歌词悬浮窗分支。
//
// 设计约束（见 docs 规划）：
//  · 零第三方运行时依赖 —— 只用 Android SDK + kotlin-stdlib，目标 APK < 1 MB；
//  · 不引 Media3 / Room / Compose / 协程 / 图片库 / 网络库；
//  · 只读旁听 MediaSession，不申请音频焦点、不提供任何播放控制；
//  · minSdk = 31（Android 12），不做向下兼容分支。
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/**
 * AnyLyric 自己的版本号。
 * 与 WindTone 的 1.2.x 主线**互不干涉**，只递增第三位（0.2.x）。
 * 首个公开发布 0.2.0：外观/定位重做、应用内更新都在这一版；车上那批测试包是 0.1.0，
 * 留出版本差，好让「检查更新 → 下载并安装」这条链路能当场验证。
 */
val anyLyricVersionName = "0.2.0"
val anyLyricVersionCode = 2

/**
 * 签名沿用 WindTone 的同一套密钥（同一开发者、同一分发渠道）。
 * 取值优先级与 :app 一致：-P 参数 → WINDTONE_* 环境变量 → keystore.properties → 默认路径。
 * 全部缺失时 Release 不签名，assembleDebug 不受影响。
 */
val localSigningProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(propName: String, envName: String, fileKey: String): String? =
    (findProperty(propName) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: localSigningProps.getProperty(fileKey)?.takeIf { it.isNotBlank() }

val defaultKeystore = rootProject.file(".toolchain/keystore/windtone.jks")
val configuredKeystore = signingValue("keystorePath", "WINDTONE_KEYSTORE", "storeFile")
    ?.let { rootProject.file(it) }
val keystoreFile: File = listOfNotNull(configuredKeystore, defaultKeystore).firstOrNull { it.exists() }
    ?: configuredKeystore ?: defaultKeystore
/**
 * 显式给出 keystore 类型：JKS 与 PKCS12 的差别只在文件头魔数。
 *
 * 不指定时 AGP 走 JDK 默认（Java 9+ 为 PKCS12）；一旦密钥文件其实是 JKS 格式，
 * 就会抛 `Tag number over 30 is not supported` —— JKS 头 FE ED 被当成 ASN.1 的
 * tag 30（0xFE 的低 5 位 = 30）解析，报错完全看不出跟「格式」有关，
 * 排查时会误以为是 secret 内容坏了。这里按魔数自行判定，两种格式都能签。
 */
val keystoreStoreType: String = signingValue("keystoreType", "WINDTONE_KEYSTORE_TYPE", "storeType")
    ?: run {
        val head = try {
            keystoreFile.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
        } catch (_: Throwable) {
            ByteArray(0)
        }
        val isJks = head.size == 4 &&
            head[0] == 0xFE.toByte() && head[1] == 0xED.toByte() &&
            head[2] == 0xFE.toByte() && head[3] == 0xED.toByte()
        if (isJks) "JKS" else "PKCS12"
    }
val keystoreStorePassword = signingValue("keystorePassword", "WINDTONE_STORE_PASSWORD", "storePassword")
val keystoreKeyAlias = signingValue("keyAlias", "WINDTONE_KEY_ALIAS", "keyAlias")
val keystoreKeyPassword = signingValue("keyPassword", "WINDTONE_KEY_PASSWORD", "keyPassword")
    ?: keystoreStorePassword

android {
    namespace = "com.anylyric"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.anylyric"
        minSdk = 31
        targetSdk = 35
        versionCode = anyLyricVersionCode
        versionName = anyLyricVersionName
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        if (keystoreFile.exists() && keystoreStorePassword != null) {
            create("release") {
                storeFile = keystoreFile
                storeType = keystoreStoreType
                storePassword = keystoreStorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // 轻量优先：R8 + 资源压缩，把本就极小的体积再压一档
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystoreFile.exists() && keystoreStorePassword != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // debug 加后缀，避免与 release 互相覆盖（也便于和 WindTone 同机共存调试）
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
    }

    buildFeatures {
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
        // 与 :app 口径一致：构建成功不等于 lint 干净，改完要单独看 lint-results
        abortOnError = false
        checkReleaseBuilds = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            it.useJUnit()
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        val suffix = if (variant.buildType == "release") "" else "-${variant.buildType}"
        variant.outputs.forEach { output ->
            val impl = output as? com.android.build.api.variant.impl.VariantOutputImpl ?: return@forEach
            impl.outputFileName.set("AnyLyric-v$anyLyricVersionName$suffix.apk")
        }
    }
}

dependencies {
    // 运行时不引任何第三方库 —— 见文件头约束。
    testImplementation(libs.junit)
    // 仅测试用：JVM 单测跑的是 android.jar 的空壳，org.json 在 isReturnDefaultValues 下
    // 只会返回默认值，清单解析就断言不了。引一份真实实现只进测试 classpath，不进 APK。
    testImplementation("org.json:json:20240303")
}
