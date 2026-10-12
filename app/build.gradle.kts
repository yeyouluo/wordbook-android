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
        versionCode = 10
        versionName = "0.3"
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
    }
}

// 产物文件名带上项目名与版本：dist/WordBook-v0.1-debug.apk / dist/WordBook-v0.1-release.apk
// （AGP 8.5 未暴露 VariantOutput.outputFileName 公共 API，用构建后复制到 dist/ 的方案）
val appVersionName: String = "0.3"
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
}
