import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().also { props ->
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { props.load(it) }
}

android {
    namespace = "com.studyontv.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.studyontv.tv"
        minSdk = 21          // Android 5.0
        targetSdk = 34
        testInstrumentationRunner = "com.studyontv.tv.RegressionInstrumentation"
        versionCode = 52
        versionName = "0.9.5"
        // 带电视桌面的包在 desktop 这个 buildType 里改成 true
        buildConfigField("boolean", "DESKTOP", "false")
        // 仅本地隐私版（EU Local-Only，Issue #175）：通过 -PlocalOnly=true 构建，彻底关闭一切公网连接与上报
        val localOnly = (project.findProperty("localOnly")?.toString()?.toBoolean() == true) ||
            (System.getenv("LOCAL_ONLY")?.equals("true", ignoreCase = true) == true)
        buildConfigField("boolean", "LOCAL_ONLY", localOnly.toString())
        // 崩溃上报的默认地址/令牌：从 local.properties 或环境变量注入（都不入库），
        // 留空表示不内置——设备只在本机保留日志，家长可在面板里自己填。
        val crashEndpoint = if (localOnly) "" else (localProps.getProperty("CRASH_ENDPOINT") ?: System.getenv("CRASH_ENDPOINT") ?: "")
        val crashToken = if (localOnly) "" else (localProps.getProperty("CRASH_TOKEN") ?: System.getenv("CRASH_TOKEN") ?: "")
        buildConfigField("String", "CRASH_ENDPOINT", "\"$crashEndpoint\"")
        buildConfigField("String", "CRASH_TOKEN", "\"$crashToken\"")
    }

    flavorDimensions += "device"
    productFlavors {
        create("tv") {
            dimension = "device"
            applicationId = "com.studyontv.tv"
        }
        create("mobile") {
            dimension = "device"
            applicationId = "com.studyontv.mobile"
        }
    }

    signingConfigs {
        // 固定调试签名：每次构建签名一致，新版才能直接覆盖安装（不用先卸载）
        getByName("debug") {
            val ks = file("debug.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            val ks = localProps.getProperty("KEYSTORE_PATH", "")
            if (ks.isNotEmpty()) {
                storeFile = file(ks)
                storePassword = localProps.getProperty("KEYSTORE_PASS")
                keyAlias = localProps.getProperty("KEY_ALIAS")
                keyPassword = localProps.getProperty("KEY_PASS")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            val cfg = signingConfigs.getByName("release")
            if (cfg.storeFile != null) signingConfig = cfg
        }
        // 带电视桌面的包：默认发的包不声明 HOME（有的电视商店因此拒装），
        // 需要桌面的人从 GitHub Release 下这个包。源码在 src/desktop/。
        // 包名、版本号和普通包一致，两个包可以互相覆盖安装，家长数据不会丢。
        create("desktop") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            // 内测包：版本号和图标名都带标记，家长一眼能和默认包区分；
            // 版本号带 -beta 后，蒲公英上的正式版也不会把内测包判成「有新版本」
            versionNameSuffix = "-beta"
            buildConfigField("boolean", "DESKTOP", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    lint {
        abortOnError = false
    }

    buildFeatures {
        buildConfig = true
    }

    testOptions {
        // 让纯 JVM 单元测试碰到未调用的 Android SDK 空壳方法时返回默认值而非抛 RuntimeException("Stub!")
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 一键授权用：通过本机 ADB（5555）给自己开无障碍/使用情况等权限
    implementation("dev.mobile:dadb:1.2.10")
    // Bugly 崩溃上报（用户同意后才初始化，见 BuglyInit.kt）
    implementation(files("libs/bugly-crashreport.aar"))
    testImplementation("junit:junit:4.13.2")
}

// 把网页和题库同步进 assets（仓库里只维护 app/web、admin、data/seed.json 三份源）
val repoRoot = rootProject.projectDir.parentFile.parentFile
val syncWebAssets by tasks.registering(Sync::class) {
    from(File(repoRoot, "app/web")) { into("web") }
    from(File(repoRoot, "admin")) { into("admin") }
    from(File(repoRoot, "data/seed.json")) { rename { "questions.json" } }
    from(File(repoRoot, "shared/katex")) { into("web/assets/vendor/katex") }
    from(File(repoRoot, "shared/math.js")) { into("web/assets/js") }
    from(File(repoRoot, "data/holidays")) { into("holidays") }
    into(layout.projectDirectory.dir("src/main/assets"))
}

tasks.named("preBuild") { dependsOn(syncWebAssets) }
