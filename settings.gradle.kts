// 受限网络（例如公司网络按 TLS 指纹拦截 Java 流量）下，可以启动本仓库的
// tools/maven-proxy.py，并在 local.properties 里写一行：
//     mavenProxy=http://127.0.0.1:18080
// 这样代理仓库会被放到最前；不写这一行就是标准仓库，对普通网络零影响。
//
// 注意：pluginManagement 块访问不到脚本级变量（Gradle Kotlin DSL 的限制），
// 所以那里用纯 Java 读一遍 local.properties（同时也支持同名环境变量）。

pluginManagement {
    val proxy: String? = System.getenv("WORD_BOOK_MAVEN_PROXY")?.trim()?.takeIf { it.isNotEmpty() }
        ?: run {
            val f = java.io.File("local.properties")
            if (!f.exists()) null
            else java.util.Properties().apply { f.inputStream().use { load(it) } }
                .getProperty("mavenProxy")?.trim()?.takeIf { it.isNotEmpty() }
        }
    repositories {
        if (proxy != null) {
            maven { url = uri("$proxy/plugins.gradle.org/m2") }
            maven { url = uri("$proxy/dl.google.com/dl/android/maven2") }
            maven { url = uri("$proxy/repo.maven.apache.org/maven2") }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        val proxy: String? = System.getenv("WORD_BOOK_MAVEN_PROXY")?.trim()?.takeIf { it.isNotEmpty() }
            ?: run {
                val f = java.io.File("local.properties")
                if (!f.exists()) null
                else java.util.Properties().apply { f.inputStream().use { load(it) } }
                    .getProperty("mavenProxy")?.trim()?.takeIf { it.isNotEmpty() }
            }
        if (proxy != null) {
            maven { url = uri("$proxy/plugins.gradle.org/m2") }
            maven { url = uri("$proxy/dl.google.com/dl/android/maven2") }
            maven { url = uri("$proxy/repo.maven.apache.org/maven2") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "WordBook"
include(":app")
