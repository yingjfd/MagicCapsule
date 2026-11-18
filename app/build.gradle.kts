import java.util.zip.ZipFile

plugins {
    id("com.android.application")
}

/**
 * MagicCapsule —— 让 MagicOS/HarmonyOS 灵动胶囊变成可变长度岛 / 完整显示歌词。
 *
 * 依赖策略（完全离线，只允许用 ~/.gradle 缓存里真实存在的构件）：
 *  - 缓存里【没有】androidx.appcompat / androidx.activity(compose) / material，
 *    因此设置页用「纯 framework」控件（android.app.Activity + Switch + EditText），
 *    不引入任何 AndroidX UI 依赖，APK 体积极小、离线解析零风险。
 *  - io.github.libxposed:api:102.0.0 用 compileOnly：
 *    与工作区现有可用模块（MagicOriG）同一写法，运行时 api 类由 LSPosed 提供给模块 ClassLoader。
 *    如需把 api 打进 APK，把下面一行改成 implementation 即可（同样能离线编译）。
 */
android {
    namespace = "io.github.yingjfd.magiccapsule"
    compileSdk = 34 // 本机 SDK 只有 platforms/android-34 与 android-37.0（无 android-35）

    defaultConfig {
        applicationId = "io.github.yingjfd.magiccapsule"
        minSdk = 34
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
    }

    /**
     * 本机 ~/.android 不可写（构建沙箱只允许写工作区），AGP 的默认 debug 签名会尝试
     * 在 C:\Users\user\.android 创建 debug.keystore 而失败（validateSigningDebug）。
     * 因此把 debug keystore 复制进工程内（keystore/magiccapsule-debug.jks，
     * 即标准 Android debug 别名 androiddebugkey / 口令 android），显式指定给两个 buildType。
     */
    signingConfigs {
        create("localDebug") {
            storeFile = rootProject.file("keystore/magiccapsule-debug.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("localDebug")
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // 无 release keystore：沿用 debug 签名，保证 release 也能出可安装 APK
            signingConfig = signingConfigs.getByName("localDebug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // 注意：不要在这里写 packaging.resources.excludes 把 META-INF/** 干掉，
    // 否则 LSPosed 的 META-INF/xposed/{module.prop,java_init.list,scope.list}
    // 会被一起剔除，模块将无法被 LSPosed 识别（构建却仍然「成功」）。
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}

/**
 * 打包后校验 LSPosed 现代模块声明是否真的进了 APK。
 * LSPosed 判断「这是不是模块」只看 zip 里有没有 META-INF/xposed/java_init.list。
 */
val verifyXposedModule = tasks.register("verifyXposedModule") {
    group = "verification"
    description = "校验 APK 内 META-INF/xposed 模块声明是否完整"

    val apkDirectory = layout.buildDirectory.dir("outputs/apk")

    doLast {
        val dir = apkDirectory.get().asFile
        val apks = if (dir.exists()) {
            dir.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
        } else emptyList()

        if (apks.isEmpty()) {
            logger.warn("verifyXposedModule: $dir 下没有 APK，跳过校验")
            return@doLast
        }

        var failed = false
        for (apk in apks) {
            logger.lifecycle("verifyXposedModule: 检查 ${apk.name}")
            try {
                ZipFile(apk).use { zip ->
                    for (path in listOf("META-INF/xposed/java_init.list", "META-INF/xposed/module.prop", "META-INF/xposed/scope.list")) {
                        val entry = zip.getEntry(path)
                        if (entry == null) {
                            failed = true
                            logger.error("  [FAIL] 缺少 $path")
                            continue
                        }
                        val lines = zip.getInputStream(entry).bufferedReader().readLines()
                            .map { it.trim() }.filter { it.isNotEmpty() }
                        if (lines.isEmpty()) {
                            failed = true
                            logger.error("  [FAIL] $path 为空")
                        } else {
                            logger.lifecycle("  [OK] $path -> $lines")
                        }
                    }
                }
            } catch (error: Throwable) {
                failed = true
                logger.error("  [FAIL] 读取 ${apk.name} 失败: ${error.message}")
            }
        }
        if (failed) throw GradleException("verifyXposedModule 失败：APK 内 LSPosed 模块声明不完整")
        logger.lifecycle("verifyXposedModule: 全部通过")
    }
}

tasks.matching { it.name.startsWith("assemble") }.configureEach { finalizedBy(verifyXposedModule) }
