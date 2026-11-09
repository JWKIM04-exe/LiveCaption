plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.livecaption.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.livecaption.app"
        minSdk = 29          // 안드로이드 10 이상 (다른 앱 소리 캡처가 가능한 최소 버전)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // 매번 같은 키로 서명해서, 새 버전을 설치할 때 기존 앱을 지우지 않아도 되게 함
    signingConfigs {
        create("fixed") {
            storeFile = file("caption.keystore")
            storePassword = "livecaption"
            keyAlias = "caption"
            keyPassword = "livecaption"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    androidResources {
        noCompress += listOf("onnx")
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // sherpa-onnx (폰 안에서 돌아가는 음성인식 엔진). GitHub Actions가 빌드 전에 받아서 app/libs 에 넣습니다.
    implementation(files("libs/sherpa-onnx.aar"))
}
