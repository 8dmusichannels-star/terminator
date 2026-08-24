plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.terminator.app"
    // 37: required by Compose 1.12 / compose-bom 2026.08.00 below, which
    // Compose always targets against the latest compileSdk.
    compileSdk = 37

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    defaultConfig {
        applicationId = "com.terminator.app"
        minSdk = 33
        targetSdk = 37
        versionCode = 14
        versionName = "0.8.1"
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // No kotlinOptions block needed: with AGP 9's built-in Kotlin support,
    // kotlin.compilerOptions.jvmTarget defaults to
    // android.compileOptions.targetCompatibility automatically.
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
    // 2026.08.00 (Compose 1.12): brings native edge-auto-scroll-while-
    // selecting and rememberSelectionState()/SelectionState to
    // SelectionContainer - what TerminalView's selection overlay
    // (terminal-emulator module) now relies on instead of the app's old
    // hand-rolled long-press/drag selection + Copy plumbing.
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    // SVG rendering for session picture support (Settings > Sessions > edit
    // session > picture, plus the titlebar picture - see
    // TerminatorTitleBar.kt). AndroidSVG (Apache 2.0) rather than pulling in
    // a full image-loading library like Coil/Glide just for this one format:
    // it's a small, pure-Kotlin/Java, no-native-code library that parses an
    // SVG document straight to an Android Canvas/Picture, which is exactly
    // what's needed here since every other session-picture format already
    // goes through plain BitmapFactory. Picked over the AOSP
    // androidx.graphics.shapes / VectorDrawable path because those require
    // an SVG to be precompiled to a VectorDrawable XML at build time - not
    // usable here, where the SVG is an arbitrary file the user picks at
    // runtime via a content picker, not a bundled app resource.
    implementation("com.caverock:androidsvg-aar:1.4")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
