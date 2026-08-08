plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
import java.io.File

android {
    namespace = "com.browserdiag.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.browserdiag.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 11
        versionName = "3.8.0"
    }

    signingConfigs {
        create("release") {
            // 统一使用 project.file()（基于模块目录 android/app/ 解析）：
            // ../keystore = android/keystore/，与 AGP 内部 storeFile 解析基准一致
            // CI：workflow 解码 secrets 到 android/keystore/browserdiag-release.keystore
            // 本地：可将 keystore 放到 android/keystore/，或用 BROWSERDIAG_KEYSTORE 绝对路径覆盖
            val envKs = System.getenv("BROWSERDIAG_KEYSTORE")
            storeFile = if (envKs != null) File(envKs) else file("../keystore/browserdiag-release.keystore")
            storePassword = System.getenv("BROWSERDIAG_STORE_PASSWORD") ?: "browserdiag2026"
            keyAlias = System.getenv("BROWSERDIAG_KEY_ALIAS") ?: "browserdiag"
            keyPassword = System.getenv("BROWSERDIAG_KEY_PASSWORD") ?: "browserdiag2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // keystore 存在时才启用签名（本地/CI 签名构建；无 keystore 的 PR 验证构建跳过签名）
            val envKs = System.getenv("BROWSERDIAG_KEYSTORE")
            val keystoreFile = if (envKs != null) File(envKs) else file("../keystore/browserdiag-release.keystore")
            if (keystoreFile.exists()) {
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
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.webkit:webkit:1.11.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
