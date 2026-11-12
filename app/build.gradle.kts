plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glass.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glass.launcher"
        minSdk = 23
        targetSdk = 34
        // raise both numbers for every new version you publish (needed for web updates)
        versionCode = 23
        versionName = "4.1"
    }

    // Fixed key so every build can update the previous one.
    // You can replace glass.keystore with your own key (keep it the same forever after).
    signingConfigs {
        create("glass") {
            storeFile = file("glass.keystore")
            storePassword = "glasslauncher"
            keyAlias = "glass"
            keyPassword = "glasslauncher"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("glass") }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("glass")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
