/*
 * Terminator - Modern terminal for Android
 *
 * Character-set designation and translation tables for SCS ("ESC ( X",
 * "ESC ) X", "ESC * X", "ESC + X" and the 96-character forms
 * "ESC - X", "ESC . X", "ESC / X"), including the VT220 National
 * Replacement Character Sets (NRCS) and the DEC / ISO Latin-1 supplemental
 * sets. Kept free of any Android dependency so it can be unit-tested on a
 * plain JVM.
 */
package com.terminator.emulator

/** One entry per distinct translation the emulator knows how to apply. */
internal enum class CharsetId {
    ASCII,
    /** DEC special graphics (line drawing) - translated by TerminalEmulator itself. */
    GRAPHICS,
    UK,
    DUTCH,
    FINNISH,
    FRENCH,
    FRENCH_CANADIAN,
    GERMAN,
    ITALIAN,
    NORWEGIAN_DANISH,
    SPANISH,
    SWEDISH,
    SWISS,
    PORTUGUESE,
    /** ISO 8859-1 right half (96-char set, "ESC - A"). */
    LATIN1_SUPP,
    /** DEC Supplemental Graphic (94-char set, "ESC ( % 5"). */
    DEC_SUPP
}

internal object Nrcs {

    /**
     * The twelve code positions that national replacement sets are allowed
     * to redefine, in ascending order: # @ [ \ ] ^ _ ` { | } ~
     */
    private const val POSITIONS = "#@[\\]^_`{|}~"

    // Each table lists the replacement for the character at the same index
    // in POSITIONS (the original character where the set leaves it alone).
    private val TABLES: Map<CharsetId, String> = mapOf(
        CharsetId.UK to "£@[\\]^_`{|}~",
        CharsetId.DUTCH to "£¾ĳ½|^_`¨ƒ¼´",
        CharsetId.FINNISH to "#@ÄÖÅÜ_éäöåü",
        CharsetId.FRENCH to "£à°ç§^_`éùè¨",
        CharsetId.FRENCH_CANADIAN to "#àâçêî_ôéùèû",
        CharsetId.GERMAN to "#§ÄÖÜ^_`äöüß",
        CharsetId.ITALIAN to "£§°çé^_ùàòèì",
        CharsetId.NORWEGIAN_DANISH to "#ÄÆØÅÜ_äæøåü",
        CharsetId.SPANISH to "£§¡Ñ¿^_`°ñç~",
        CharsetId.SWEDISH to "#ÉÄÖÅÜ_éäöåü",
        CharsetId.SWISS to "ùàéçêîèôäöüû",
        CharsetId.PORTUGUESE to "#@ÃÇÕ^_`ãçõ~"
    )

    /** DEC Supplemental differs from Latin-1 in exactly these GR positions. */
    private val DEC_SUPP_OVERRIDES: Map<Int, Char> = mapOf(
        0xA8 to '¤', 0xD7 to 'Œ', 0xF7 to 'œ', 0xDD to 'Ÿ', 0xFD to 'ÿ'
    )

    /** True for the sets that only exist while DECNRCM (mode 42) is on. */
    private fun isNational(id: CharsetId) = when (id) {
        CharsetId.ASCII, CharsetId.GRAPHICS, CharsetId.UK,
        CharsetId.LATIN1_SUPP, CharsetId.DEC_SUPP -> false
        else -> true
    }

    /**
     * Resolves a 94-character SCS designation ("ESC ( <final>" with an
     * optional "%" intermediate before the final byte).
     *
     * National sets are only honoured while [nrcsEnabled] (DECNRCM) is set,
     * like a real VT220; otherwise they fall back to ASCII.
     */
    fun designate94(percent: Boolean, final: Char, nrcsEnabled: Boolean): CharsetId {
        val id = if (percent) {
            when (final) {
                '5' -> CharsetId.DEC_SUPP
                '6' -> CharsetId.PORTUGUESE
                else -> CharsetId.ASCII
            }
        } else {
            when (final) {
                'B', '1' -> CharsetId.ASCII
                '0', '2' -> CharsetId.GRAPHICS
                'A' -> CharsetId.UK
                '4' -> CharsetId.DUTCH
                '5', 'C' -> CharsetId.FINNISH
                'R' -> CharsetId.FRENCH
                'Q' -> CharsetId.FRENCH_CANADIAN
                'K' -> CharsetId.GERMAN
                'Y' -> CharsetId.ITALIAN
                'E', '6' -> CharsetId.NORWEGIAN_DANISH
                'Z' -> CharsetId.SPANISH
                'H', '7' -> CharsetId.SWEDISH
                '=' -> CharsetId.SWISS
                else -> CharsetId.ASCII
            }
        }
        return if (isNational(id) && !nrcsEnabled) CharsetId.ASCII else id
    }

    /** Resolves a 96-character designation ("ESC - X" / "ESC . X" / "ESC / X"). */
    fun designate96(final: Char): CharsetId =
        if (final == 'A') CharsetId.LATIN1_SUPP else CharsetId.ASCII

    /**
     * Translates one GL character through [id]. GRAPHICS is not handled
     * here (the emulator owns that table); every other set returns the
     * character unchanged when it has no override for it.
     */
    fun translate(id: CharsetId, c: Char): Char {
        when (id) {
            CharsetId.ASCII, CharsetId.GRAPHICS -> return c
            CharsetId.LATIN1_SUPP, CharsetId.DEC_SUPP -> {
                val code = c.code
                if (code < 0x20 || code > 0x7F) return c
                val hi = code + 0x80
                return if (id == CharsetId.DEC_SUPP) DEC_SUPP_OVERRIDES[hi] ?: hi.toChar() else hi.toChar()
            }
            else -> {
                val table = TABLES[id] ?: return c
                val idx = POSITIONS.indexOf(c)
                return if (idx >= 0) table[idx] else c
            }
        }
    }

    // Exposed for tests only.
    internal fun tableFor(id: CharsetId): String? = TABLES[id]
    internal const val POSITIONS_FOR_TEST = POSITIONS
}
