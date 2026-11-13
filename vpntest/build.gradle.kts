plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// "LEAD VPN test": tiny helper app. Put it on the VPN app's list (split tunneling) –
// LEAD Launcher stays outside the VPN and lets this helper measure the speed through the VPN.
android {
    namespace = "com.glass.launcher.vpntest"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.glass.launcher.vpntest"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    sourceSets["main"].java.srcDirs("src/main/java", "../shared/src")
    val keyPass: String? = System.getenv("LEAD_KEY_PASSWORD")
    val keyFile = file("../app/release.keystore")
    signingConfigs {
        if (keyPass != null && keyFile.exists()) {
            create("lead") { storeFile = keyFile; storePassword = keyPass; keyAlias = "lead"; keyPassword = keyPass }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("lead")
                ?: throw GradleException("Signing key missing")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}
