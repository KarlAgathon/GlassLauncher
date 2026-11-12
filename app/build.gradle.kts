plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.glass.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.glass.launcher"
        minSdk = 24
        targetSdk = 34
        // raise both numbers for every new version you publish (needed for web updates)
        versionCode = 42
        versionName = "6.4"
    }

    // Private signing key: it is NOT in this repository. GitHub Actions writes it from the secrets
    // LEAD_KEYSTORE_B64 (the keystore, base64) and LEAD_KEY_PASSWORD to app/release.keystore.
    // Keep your own copy of the keystore safe: every update must be signed with the same key.
    val keyPass: String? = System.getenv("LEAD_KEY_PASSWORD")
    val keyFile = file("release.keystore")
    signingConfigs {
        if (keyPass != null && keyFile.exists()) {
            create("lead") {
                storeFile = keyFile
                storePassword = keyPass
                keyAlias = "lead"
                keyPassword = keyPass
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("lead")
                ?: throw GradleException("Signing key missing: set the GitHub secrets LEAD_KEYSTORE_B64 and LEAD_KEY_PASSWORD")
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
    // Shizuku: on newer Android (e.g. Google TV Streamer) the CPU load can only be read with ADB ("shell") rights
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
