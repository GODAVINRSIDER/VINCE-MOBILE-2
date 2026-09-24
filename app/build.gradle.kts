plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.godavin.vince"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.godavin.vince"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "2.0-stage1"
    }

    buildTypes {
        debug { }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    // Secure local storage for the Gemini API key (Stage 1)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Stage 2 - talks directly to the Gemini API, no PC involved
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // PC<->Mobile calling - Firestore for call sessions + voice chunks
    // (deliberately no Firebase Storage - see CallRepository.kt's docstring
    // for why), Anonymous Auth so Firestore's security rules have SOME
    // identity to check against without a login screen.
    implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")
    // lets suspend functions .await() a Firebase Task directly, instead of
    // wrapping every call in a manual callback->coroutine bridge
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
}
