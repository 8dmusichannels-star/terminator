plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.terminator.app"
    compileSdk = 35

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.terminator.app"
        minSdk = 33
        targetSdk = 37
        versionCode = 7
        versionName = "0.3.0"
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
            isMinifyEnabled = true
            if (keystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        getByName("debug") {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
    }
    packaging {
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
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters
                .find { it.filterType.name == "ABI" }
                ?.identifier

            val abiCodes = mapOf(
                "armeabi-v7a" to 1,
                "arm64-v8a" to 2,
                "x86" to 3,
                "x86_64" to 4
            )

            if (abi != null) {
                val baseVersionCode = output.versionCode.orNull ?: 0
                output.versionCode.set(
                    baseVersionCode * 10 + (abiCodes[abi] ?: 0)
                )
            }
        }
    }
}
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
