// TERMINATOR - Root build.gradle.kts
plugins {
    // 9.1.1: minimum AGP required by Compose 1.12 / compose-bom 2026.08.00
    // (see app/build.gradle.kts), which added native SelectionContainer
    // edge-auto-scroll + rememberSelectionState() - what TerminalView's
    // selection overlay now relies on instead of the old hand-rolled
    // long-press/drag selection code.
    id("com.android.application") version "9.1.1" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.buildDir)
}
