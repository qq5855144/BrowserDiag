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
            // 本地默认使用仓库根 keystore/browserdiag-release.keystore（已被 .gitignore 忽略）
            // CI 中可通过环境变量 BROWSERDIAG_KEYSTORE / _PASSWORD / _ALIAS 覆盖
            storeFile = File(System.getenv("BROWSERDIAG_KEYSTORE") ?: "../keystore/browserdiag-release.keystore")
            storePassword = System.getenv("BROWSERDIAG_STORE_PASSWORD") ?: "browserdiag2026"
            keyAlias = System.getenv("BROWSERDIAG_KEY_ALIAS") ?: "browserdiag"
            keyPassword = System.getenv("BROWSERDIAG_KEY_PASSWORD") ?: "browserdiag2026"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // keystore 存在时才启用签名（本地/CI 签名构建；无 keystore 的 PR 验证构建跳过签名）
            val keystoreFile = File(System.getenv("BROWSERDIAG_KEYSTORE") ?: "../keystore/browserdiag-release.keystore")
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
