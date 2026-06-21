plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.adshield.vpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adshield.vpn"
        // TileService (Quick Settings) requires API 24.
        minSdk = 24
        // Target 33 on purpose: Android 14 (34) requires a foregroundServiceType
        // for every FGS; staying at 33 keeps the VPN foreground service simple
        // while still installing and running fine on Android 14 devices.
        targetSdk = 33
        // Overridable from CI (-PverCode / -PverName) so each build upgrades cleanly.
        versionCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("verName") as String?) ?: "1.0.0"
    }

    signingConfigs {
        // Same fixed key as the rest of the repo so every CI build shares one
        // signature → updates install over each other.
        create("stable") {
            storeFile = file("${rootProject.projectDir}/keystore/lampplayer.jks")
            storePassword = "lampplayer"
            keyAlias = "lampplayer"
            keyPassword = "lampplayer"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            signingConfig = signingConfigs.getByName("stable")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.lifecycle.runtime)
    implementation(libs.material)
    implementation(libs.coroutines.android)
    implementation(libs.work.runtime)
    implementation(libs.okhttp)
}
