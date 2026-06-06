plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.lampplayer.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.lampplayer.tv"
        minSdk = 21
        targetSdk = 34
        // Overridable from CI (-PverCode / -PverName) so each build is upgradeable.
        versionCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
        versionName = (project.findProperty("verName") as String?) ?: "1.0.0"

        // TV boxes/sticks are ARM only. Dropping x86/x86_64 roughly halves the
        // APK (libVLC ships large native libs per ABI) so it installs on
        // low-storage devices like the Mi TV Stick.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.activity.ktx)

    implementation(libs.androidx.leanback)

    implementation(libs.lifecycle.viewmodel)
    implementation(libs.lifecycle.runtime)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource.okhttp)

    // libVLC — fallback engine for codecs/containers ExoPlayer can't handle
    implementation(libs.libvlc.all)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)

    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)

    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)

    implementation(libs.coroutines.android)

    implementation(libs.glide)
}

kapt {
    correctErrorTypes = true
}
