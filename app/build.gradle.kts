plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.example.wordbook"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.wordbook"
        minSdk = 26
        targetSdk = 34
        versionCode = 12
        versionName = "0.3.2"
    }

    // 签名密钥不进仓库（见 .gitignore）：本机若放了 app/keystore/wordbook.keystore，
    // debug 与 release 共用它，两种包可互相覆盖安装且不丢数据；
    // 其他人 clone 后没有这个文件也能正常构建（debug 用 Android Studio 默认调试签名）。
    val appKeystore = file("keystore/wordbook.keystore")
    signingConfigs {
        if (appKeystore.exists()) {
            create("app") {
                storeFile = appKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        debug {
            if (appKeystore.exists()) signingConfig = signingConfigs.getByName("app")
        }
        release {
            if (appKeystore.exists()) signingConfig = signingConfigs.getByName("app")
            // 自用 APK：不混淆、不裁资源，行为与 debug 一致，只去掉调试开销
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // 让设置页直接读 BuildConfig.VERSION_NAME / VERSION_CODE：
        // 版本号只在 defaultConfig 里写一次，界面文案不会再和实际版本对不上。
        buildConfig = true
    }

    testOptions {
        unitTests {
            // Robolectric 需要真实的资源（strings/layout）才能渲染 Compose
            isIncludeAndroidResources = true
            // Robolectric 首次运行会去 maven 下 android-all 运行时 jar。
            // 受限网络（公司网按 TLS 指纹拦 Java 流量）下把它指向本仓库的本地代理；
            // 没有配 mavenProxy 时完全不加这个参数，普通网络行为不变。
            val proxy: String? = System.getenv("WORD_BOOK_MAVEN_PROXY")?.trim()?.takeIf { it.isNotEmpty() }
                ?: rootProject.file("local.properties")
                    .takeIf { it.exists() }
                    // 不用 java.util.Properties：在 android {} 块里 `java` 是 JavaPluginExtension，
                    // 拿不到 java.util.*（local.properties 就是简单的 key=value，直接按行取即可）
                    ?.readLines()
                    ?.firstOrNull { it.trimStart().startsWith("mavenProxy=") }
                    ?.substringAfter("mavenProxy=")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            if (proxy != null) {
                all { it.systemProperty("robolectric.dependency.repo.url", "$proxy/repo.maven.apache.org/maven2") }
            }
        }
    }
}

// 产物文件名带上项目名与版本：dist/WordBook-v0.1-debug.apk / dist/WordBook-v0.1-release.apk
// （AGP 8.5 未暴露 VariantOutput.outputFileName 公共 API，用构建后复制到 dist/ 的方案）
val appVersionName: String = "0.3.2"
val distDir = rootProject.layout.projectDirectory.dir("dist")

tasks.register<Copy>("distDebug") {
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(distDir)
    // 用字符串重载而不是 Lambda：Lambda 会捕获脚本对象，导致配置缓存不可用
    rename("app-debug\\.apk", "WordBook-v$appVersionName-debug.apk")
}

tasks.register<Copy>("distRelease") {
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(distDir)
    rename("app-release\\.apk", "WordBook-v$appVersionName-release.apk")
}

// 打完包自动把产物复制成带名字的版本，保持"一条命令出包"
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy("distDebug") }
tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy("distRelease") }

dependencies {
    implementation(libs.androidx.core.ktx)

    // Compose（BOM 统一管理版本）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    // 更全的图标集合（extended）
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Room 本地数据库
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 网络与 JSON（调 DeepSeek API）
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)

    debugImplementation(libs.androidx.ui.tooling)

    // 单元测试：Robolectric 上跑 Compose UI 测试（无需真机/模拟器）
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
