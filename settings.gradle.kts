// MagicCapsule —— LSPosed 模块（libxposed API 102）
// 完全离线构建：所有仓库声明仅为让缓存中的模块元数据可被解析，
// 实际构建必须加 --offline，避免 TLS 握手失败。
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
        maven("https://api.xposed.info/")
        maven("https://s01.oss.sonatype.org/content/repositories/releases/")
    }
}

rootProject.name = "MagicCapsule"
include(":app")
