plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.fz.srttest"
    compileSdk = 36

    defaultConfig {
        // 与正式版 com.fz.yqlandroid 不同包名，可同装对比
        applicationId = "com.fz.srttest"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 便于直接装测试包：release 也用 debug 签名
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.srtdroid.core)
    // OTG 外接 USB 摄像头：AUSBC 3.5.3（ernestp 维护分支），libuvc 须显式声明（libausbc 的 POM 把它标成 runtime）
    implementation("com.github.ernestp.AndroidUSBCamera:libausbc:3.5.3")
    implementation("com.github.ernestp.AndroidUSBCamera:libuvc:3.5.3")
    testImplementation(libs.junit)
}
