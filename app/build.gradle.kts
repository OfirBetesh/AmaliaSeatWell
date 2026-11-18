plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ofir.amaliaseatwell"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ofir.amaliaseatwell"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Fixed key committed to the repo so every CI build has the same signature
    // and can be installed over the previous one without losing saved codes.
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("keystore/amalia.jks")
            storePassword = "amalia123"
            keyAlias = "amalia"
            keyPassword = "amalia123"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("shared")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
