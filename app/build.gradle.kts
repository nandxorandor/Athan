plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystoreFile: String? = findProperty("AK_KEYSTORE_FILE") as String?
val hasSigningCredentials = keystoreFile != null && file(keystoreFile).exists()

android {
    namespace = "com.ahmedkhalaf.athan"
    compileSdk = 36
    buildToolsVersion = "35.0.1"

    defaultConfig {
        applicationId = "com.ahmedkhalaf.athan"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.2"
    }

    if (hasSigningCredentials) {
        signingConfigs {
            create("release") {
                storeFile = file(keystoreFile!!)
                storePassword = property("AK_KEYSTORE_PASSWORD") as String
                keyAlias = property("AK_ATHAN_KEY_ALIAS") as String
                keyPassword = property("AK_ATHAN_KEY_PASSWORD") as String
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningCredentials) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    androidResources {
        // The athan recordings are already MP3; re-compressing them in the APK
        // wastes build time and saves nothing.
        noCompress += "mp3"
    }

    buildFeatures {
        viewBinding = true
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Offline prayer-time astronomy. No network, no API key, widely trusted.
    implementation("com.batoulapps.adhan:adhan:1.2.1")
}
