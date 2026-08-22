plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aidar.tradelab"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aidar.tradelab"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "0.2.5"
        val serverUrl = System.getenv("TRADELAB_SERVER_URL") ?: "https://45.150.37.187"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { buildConfig = true }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
}
