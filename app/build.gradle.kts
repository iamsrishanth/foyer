plugins {
    id("com.android.application")
}

android {
    namespace = "com.kaera.foyer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kaera.foyer"
        minSdk = 23
        targetSdk = 31
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Source files contain a few non-ASCII glyphs; without this javac uses the platform default.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// Deliberately no dependencies: framework-only, so the build stays small and fast.
