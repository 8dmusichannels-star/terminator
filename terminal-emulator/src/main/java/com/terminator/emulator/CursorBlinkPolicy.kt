/*
 * Terminator - Modern terminal for Android
 *
 * App-wide cursor blink policy (Settings > Keyboard > Cursor blink).
 * OFF (default): never blink. PROGRAM: blink only while the program asked
 * for it (DECSET 12 / DECSCUSR). ALWAYS: blink regardless of the program.
 */
package com.terminator.emulator

object CursorBlinkPolicy {
    const val OFF = "OFF"
    const val PROGRAM = "PROGRAM"
    const val ALWAYS = "ALWAYS"

    @Volatile var mode: String = OFF

    /** Whether the cursor should currently blink given the program's own request. */
    fun shouldBlink(programWantsBlink: Boolean): Boolean = when (mode) {
        ALWAYS -> true
        PROGRAM -> programWantsBlink
        else -> false
    }
}
