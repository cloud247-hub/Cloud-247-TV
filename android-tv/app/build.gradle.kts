plugins {
    id("com.android.application")
}

android {
    namespace = "no.cloud247.tv"
    compileSdk = 36

    defaultConfig {
        applicationId = "no.cloud247.tv"
        minSdk = 23
        targetSdk = 36
        versionCode = 10100
        versionName = "1.1.0"
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
}


dependencies {
    val media3 = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("com.google.zxing:core:3.5.4")
}
