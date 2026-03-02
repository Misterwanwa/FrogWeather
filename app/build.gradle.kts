plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.frogweather"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.frogweather"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        // the weather API key is expected to be provided in gradle.properties as
        // OPENWEATHER_API_KEY.  you can also hardcode here but do not commit keys.
        buildConfigField("String", "OPENWEATHER_API_KEY", 
            project.findProperty("OPENWEATHER_API_KEY")?.let { "\"$it\"" } ?: "\"YOUR_API_KEY_HERE\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.1")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.compose.ui:ui:1.5.0")
    implementation("androidx.compose.material:material:1.5.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.5.0")
    debugImplementation("androidx.compose.ui:ui-tooling:1.5.0")

    // Material Components
    implementation("com.google.android.material:material:1.9.0")

    // coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // location services
    implementation("com.google.android.gms:play-services-location:21.0.1")

    // networking (Retrofit + Moshi + logging)
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.9.0")
    implementation("com.squareup.moshi:moshi:1.15.2")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
    implementation("com.squareup.okhttp3:logging-interceptor:4.11.0")
    // image loading (for future icons/backgrounds)
    implementation("io.coil-kt:coil-compose:2.3.0")
}
