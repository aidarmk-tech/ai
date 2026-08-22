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
        versionCode = 1
        versionName = "0.1.0"
        val serverUrl = System.getenv("TRADELAB_SERVER_URL") ?: "http://10.0.2.2:8000"
        val readToken = System.getenv("TRADELAB_READ_TOKEN") ?: "change-me"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
        buildConfigField("String", "READ_TOKEN", "\"$readToken\"")
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
