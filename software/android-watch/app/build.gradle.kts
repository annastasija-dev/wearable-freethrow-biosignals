plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val samsungAar = file("libs/samsung-health-sensor-api.aar")
val studyKeystore = rootProject.file("../study-release.jks")

android {
    namespace = "lt.vilniustech.basketball.watch"
    compileSdk = 35

    defaultConfig {
        applicationId = "lt.vilniustech.basketball.watch"
        minSdk = 30
        targetSdk = 35
        versionCode = 53
        versionName = "0.5.2"
        buildConfigField("Boolean", "HAS_SAMSUNG_SDK", samsungAar.exists().toString())
    }

    signingConfigs {
        create("study") {
            storeFile = studyKeystore
            storePassword = "ftstudy-change-me"
            keyAlias = "ftstudy"
            keyPassword = "ftstudy-change-me"
        }
    }

    buildTypes {
        debug {
            // Same study key so WearablePkgInstaller accepts embedded companion over Bluetooth.
            if (studyKeystore.exists()) {
                signingConfig = signingConfigs.getByName("study")
            }
        }
        release {
            isMinifyEnabled = false
            if (studyKeystore.exists()) {
                signingConfig = signingConfigs.getByName("study")
            }
        }
    }

    buildFeatures {
        buildConfig = true
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.wear:wear:1.3.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    compileOnly("com.google.android.wearable:wearable:2.9.0")

    if (samsungAar.exists()) {
        implementation(files(samsungAar))
        android.sourceSets.getByName("main").java.srcDir("src/samsung/java")
    }
}
