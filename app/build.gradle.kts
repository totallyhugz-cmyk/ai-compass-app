plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.compass.offline"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.compass.offline"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.4-online-routing"
    }

    buildTypes {
        getByName("debug")   { isDebuggable = true }
        getByName("release") { isMinifyEnabled = false }
    }

    buildFeatures { compose = true }

    // Compose + JVM settings
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Networking for ORS + Nominatim
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")
}
