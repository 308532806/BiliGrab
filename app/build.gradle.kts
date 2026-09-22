plugins {
    id("com.android.application")
}

android {
    namespace = "com.biligrab.downloader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.biligrab.downloader"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    lint {
        abortOnError = false
    }
}

// 本项目刻意不引入任何第三方依赖。
dependencies {
}
