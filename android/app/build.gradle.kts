import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 签名信息一律不入库：从 android/keystore.properties 读取（缺失则只出 debug 包）
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.lichtcui.pokelauncher"
    compileSdk = 35

    defaultConfig {
        // bundle id 与鸿蒙版一致（跨生态不冲突，便于文档与清单复用）
        applicationId = "com.lichtcui.pokelauncher"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-p0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // 本地 HTTP 服务（把沙箱 game 目录以固定 origin 提供给 WebView）
    implementation(libs.nanohttpd)
    // 资源包下载：Range 断点续传 + 流式写盘 + 测速探测的超时/取消
    implementation(libs.okhttp)
    // 版本门禁 + 后台静音（WebViewCompat.setAudioMuted）
    implementation(libs.androidx.webkit)

    testImplementation(libs.junit)
    // 单测里能真的解析 JSON（android.jar 里的 org.json 是空实现，会抛 "Stub!"）
    testImplementation(libs.json)
}

/**
 * 跨端共享代码校验：`shared/` 目录（两端共用的 JS 与数据）由 `scripts/gen-shared.mjs` 生成成
 * 两端源码，改了没重新生成 / 两端常量分叉时让构建直接失败。
 *
 * 保险丝的位置在编译之前（`preBuild`）：否则「改了 shared/ 忘了跑生成器」的后果是两端行为
 * 静默不一致，比编译错误难查得多。详见 shared/README.md。
 */
val nodeBin: String = providers.environmentVariable("NODE_BIN").orNull
    // DevEco 自带的 node（macOS 常见位置）：Android Studio 起的 Gradle 有时 PATH 里没有 node
    ?: listOf("/Applications/DevEco-Studio.app/Contents/tools/node/bin/node")
        .firstOrNull { File(it).canExecute() }
    ?: "node"

val checkSharedCode by tasks.registering(Exec::class) {
    group = "verification"
    description = "校验 shared/ 与两端生成代码是否同步（node scripts/gen-shared.mjs --check）"
    // android/ 的上一级才是仓库根
    workingDir = rootProject.projectDir.parentFile
    commandLine(nodeBin, "scripts/gen-shared.mjs", "--check")
}

tasks.named("preBuild") { dependsOn(checkSharedCode) }
