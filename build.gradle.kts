// TERMINATOR - Root build.gradle.kts
plugins {
    // 9.1.1: minimum AGP required by Compose 1.12 / compose-bom 2026.08.00
    // (see app/build.gradle.kts), which added native SelectionContainer
    // edge-auto-scroll + rememberSelectionState() - what TerminalView's
    // selection overlay now relies on instead of the old hand-rolled
    // long-press/drag selection code.
    //
    // AGP 9.0+ ships built-in Kotlin support and applies it automatically -
    // do NOT also apply org.jetbrains.kotlin.android here or in any module,
    // it collides with AGP's own Kotlin extension ("Cannot add extension
    // with name 'kotlin', as there is an extension already registered with
    // that name"). See: https://developer.android.com/build/migrate-to-built-in-kotlin
    id("com.android.application") version "9.1.1" apply false
    id("com.android.library") version "9.1.1" apply false
    // Compose compiler is now a separate Kotlin-repo plugin (Kotlin 2.0+),
    // required since Compose 1.12 / compose-bom 2026.08.00 below.
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
