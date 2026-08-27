plugins {
    id("com.android.application")
}

android {
    namespace = "com.nexafbproxy.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nexafbproxy.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "3.0.0"
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")

    // JNI bindings for hev-socks5-tunnel
    implementation("com.wgtunnel:hevtunnel:1.0.4")
}
