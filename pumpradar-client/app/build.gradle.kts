plugins {
    id("com.android.application")
}

android {
    namespace = "com.aidar.pumpradar.client"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aidar.pumpradar.client"
        minSdk = 24
        targetSdk = 35
        versionCode = 4
        versionName = "1.3.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
