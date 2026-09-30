plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val studyKeystore = rootProject.file("../study-release.jks")
val watchReleaseApk = rootProject.file("../android-watch/app/build/outputs/apk/release/app-release.apk")
val watchDebugApk = rootProject.file("../android-watch/app/build/outputs/apk/debug/app-debug.apk")

android {
    namespace = "lt.vilniustech.basketball.cloud"
    compileSdk = 35

    defaultConfig {
        applicationId = "lt.vilniustech.basketball.cloud"
        minSdk = 28
        targetSdk = 35
        versionCode = 67
        versionName = "0.5.10"
        buildConfigField("String", "CLOUD_BASE_URL", "\"https://ft-cloud-vgtu.fly.dev\"")
        buildConfigField("String", "TAILSCALE_URL", "\"https://ft-cloud-vgtu.fly.dev\"")
        buildConfigField("String", "CLOUD_API_KEY", "\"dev-change-me\"")
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
            isDebuggable = true
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

    androidResources {
        noCompress += "apk"
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
            )
            pickFirsts += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
            )
        }
    }
}

tasks.register<Copy>("embedWatchApk") {
    val src = when {
        watchDebugApk.exists() -> watchDebugApk
        watchReleaseApk.exists() -> watchReleaseApk
        else -> null
    }
    onlyIf { src != null }
    from(src)
    into(layout.projectDirectory.dir("src/main/res/raw"))
    rename { "wearable_app.apk" }
}

tasks.named("preBuild").configure {
    dependsOn("embedWatchApk")
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    implementation("androidx.wear:wear-remote-interactions:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    // Phone → Watch Wi‑Fi install (wireless debugging pair + push APK)
    implementation("com.flyfishxu:kadb:1.3.0") {
        exclude(group = "org.jetbrains.kotlin")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
        exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    }
}

configurations.configureEach {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.2.0")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.2.0")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.2.0")
    }
}
