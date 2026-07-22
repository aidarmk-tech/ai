plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.binancetrader.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.binancetrader.app"
        minSdk = 26
        targetSdk = 34
        // Overridable from CI (-PverCode / -PverName) so each build is upgradeable.
        versionCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("verName") as String?) ?: "1.0.0"
    }

    signingConfigs {
        // Fixed key so every CI build shares one signature → updates install over
        // each other (the auto-generated debug key differs per CI run otherwise).
        create("stable") {
            storeFile = file("${rootProject.projectDir}/keystore/binancetrader.jks")
            storePassword = "binancetrader"
            keyAlias = "binancetrader"
            keyPassword = "binancetrader"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            signingConfig = signingConfigs.getByName("stable")
            isMinifyEnabled = false
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
