plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.terminator.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.terminator.app"
        minSdk = 33
        targetSdk = 35
        versionCode = 6
        versionName = "0.2.2"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    val keystorePath = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: throw GradleException("KEYSTORE_PASSWORD not set")
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: throw GradleException("KEY_ALIAS not set")
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: throw GradleException("KEY_PASSWORD not set")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    packaging {
        // Universal + per-ABI splits handled in CI (see .github/workflows/build.yml)
        jniLibs {
            useLegacyPackaging = false
        }
    }
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}
// Reproducible/third-party build support (e.g. F-Droid): when no keystore
// secrets are present, we build an UNSIGNED release APK instead of failing.
// CI (.github/workflows/build.yml) always provides KEYSTORE_PATH and
// produces a signed release; this branch only triggers for builds run
// outside that pipeline (F-Droid build server, local reproducibility
// checks, third-party builders).
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    doFirst {
        if (System.getenv("KEYSTORE_PATH") == null) {
            logger.warn("KEYSTORE_PATH not set — building UNSIGNED release APK. This is expected for F-Droid / reproducible builds; CI provides signing secrets for official releases.")
        }
    }
}
dependencies {
    implementation(project(":terminal-emulator"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.google.android.material:material:1.12.0")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
