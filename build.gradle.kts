// 根构建脚本：只声明 AGP 版本，不 apply。
// AGP 8.5.2 / Gradle 8.9 与工作区现有工程保持一致（缓存里已验证存在）。
plugins {
    id("com.android.application") version "8.5.2" apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
