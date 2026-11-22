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
        versionCode = 81
        versionName = "10.0.5"
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
            // a release must be signed with the LEAD key; a debug compile check (other branches) needs no key
            val wantsRelease = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }
            signingConfig = signingConfigs.findByName("lead")
                ?: if (wantsRelease) throw GradleException("Signing key missing: set the GitHub secrets LEAD_KEYSTORE_B64 and LEAD_KEY_PASSWORD") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // newer Java classes used by the network libraries also on older boxes
        isCoreLibraryDesugaringEnabled = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.kotlin_module")
        }
    }
    kotlinOptions { jvmTarget = "17" }
    // speed test engine shared with the "LEAD VPN test" helper app
    sourceSets["main"].java.srcDirs("src/main/java", "../shared/src")
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Shizuku: on newer Android (e.g. Google TV Streamer) the CPU load can only be read with ADB ("shell") rights
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
    // v10 file manager: QR codes (Phone Connect), Windows/NAS shares (SMB2/3), FTP/FTPS, SFTP
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("commons-net:commons-net:3.11.1")
    implementation("com.github.mwiede:jsch:0.2.21")
}
