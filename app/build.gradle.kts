plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mysoftwares.todo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mysoftwares.todo"
        minSdk = 26
        targetSdk = 35
        // CI sets VERSION_CODE to the build number; the in-app updater compares it.
        val buildNumber = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    // A fixed signing key committed to the repo so every CI build has the same
    // signature. That lets a new APK install over the old one without
    // uninstalling (which would delete all tasks).
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}
