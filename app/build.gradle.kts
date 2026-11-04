plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

// Fester Signaturschlüssel aus GitHub-Secrets (siehe README). Nur mit immer demselben Schlüssel
// kann Android eine installierte App aktualisieren, statt sie neu installieren zu müssen.
val signingKeystore: String? = System.getenv("SIGNING_KEYSTORE_FILE")

// Versionscode steigt mit jedem GitHub-Build automatisch; Versionsname aus dem Tag (v2.3 → 2.3).
val runNumber: Int? = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val tagName: String? = System.getenv("GITHUB_REF_NAME")?.takeIf { it.matches(Regex("v\\d+(\\.\\d+)*")) }

android {
    namespace = "de.funkstrecke"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.funkstrecke"
        minSdk = 26
        targetSdk = 35
        versionCode = if (runNumber != null) 100 + runNumber else 4
        versionName = tagName?.removePrefix("v") ?: if (runNumber != null) "2.12-build$runNumber" else "2.12"
    }

    signingConfigs {
        if (signingKeystore != null) {
            create("funkstrecke") {
                storeFile = file(signingKeystore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            if (signingKeystore != null) signingConfig = signingConfigs.getByName("funkstrecke")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingKeystore != null) signingConfig = signingConfigs.getByName("funkstrecke")
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
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    // Geofencing für die Heimatbasis
    implementation("com.google.android.gms:play-services-location:21.3.0")
}
