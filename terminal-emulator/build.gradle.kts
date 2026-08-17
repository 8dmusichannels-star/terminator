// TERMINATOR :terminal-emulator
// VT100/ANSI parsing + rendering engine.
// Based on TermOne Plus (https://gitlab.com/termapps/termoneplus), itself a fork
// of jackpal/Android-Terminal-Emulator. Original code licensed under Apache
// License 2.0. Modifications and integration code in this module are part of
// the TERMINATOR project and are licensed under GPL-3.0-or-later.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.terminator.emulator"
    // 37: required by Compose 1.12 / compose-bom 2026.08.00 below - see
    // this module's own dependencies block, and app/build.gradle.kts.
    compileSdk = 37

    defaultConfig {
        minSdk = 33

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // 2026.08.00 (Compose 1.12): brings native edge-auto-scroll-while-
    // selecting to SelectionContainer, used by TerminalView's selection
    // overlay below instead of hand-rolled scroll-while-dragging math.
    implementation(platform("androidx.compose:compose-bom:2026.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
}
