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
        // CI sets VERSION_CODE to the number of commits (always grows, never resets);
        // the in-app updater compares it with the latest release.
        val buildNumber = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
        // Repository the updater checks; CI passes the repository that built the app.
        val repo = System.getenv("GITHUB_REPOSITORY") ?: "MySoftwares1996/To-do"
        buildConfigField("String", "UPDATE_REPO", "\"$repo\"")
    }

    // Release signing. The private key lives in GitHub Secrets (see README, "Signing key"); CI writes it to
    // a temporary file and passes its location and passwords in environment variables.
    // Without those variables (e.g. a local build) the old public key in debug.keystore is used.
    val releaseStore = System.getenv("SIGNING_STORE_FILE")
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            if (releaseStore != null) {
                storeFile = file(releaseStore)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (releaseStore != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    // Lint runs as a separate CI step and its report is uploaded; it does not block a release.
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        htmlReport = true
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

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013") // real JSON for backup tests (Android's is a stub on the JVM)
}
