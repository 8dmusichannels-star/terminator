/*
 * Modern terminal for Terminator android
 * Copyright (C) 2026 Zaman Huseyinli
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.terminator.app.ui.settings

/**
 * One entry from the standard X11/`rgb.txt` named-color table: a
 * human-readable [name] (as commonly cased, e.g. "Dodger Blue") plus its
 * opaque ARGB int. [key] is the lowercase, space-stripped lookup form
 * (e.g. "dodgerblue"), matching how X11/CSS traditionally key these names.
 */
data class X11Color(
    val name: String,
    val key: String,
    val argb: Int
)

/**
 * The full classic X11 named-color list (the same ~150 names shipped in
 * X11's `rgb.txt` and adopted by CSS as its extended color keywords).
 * This is the raw, searchable source of every X11 color name Terminator
 * knows about - used to:
 *  - populate the "X11" [PalettePresets] entry (16 representative ANSI
 *    slots drawn from these names), and
 *  - back an X11-name color picker (see [X11ColorPickerDialog]) that can
 *    fill in *any* single slot/fg/bg by name, not just the 16 preset ones.
 *
 * Grayscale "gray0".."gray100"/"grey0".."grey100" step entries are omitted
 * here (100 near-duplicate rows with little practical picker value) - the
 * plain "gray"/"grey" family members below (e.g. "Dim Gray", "Light Gray")
 * are kept.
 */
object X11Colors {
    val all: List<X11Color> = listOf(
        X11Color("Alice Blue", "aliceblue", 0xFFF0F8FF.toInt()),
        X11Color("Antique White", "antiquewhite", 0xFFFAEBD7.toInt()),
        X11Color("Aqua", "aqua", 0xFF00FFFF.toInt()),
        X11Color("Aquamarine", "aquamarine", 0xFF7FFFD4.toInt()),
        X11Color("Azure", "azure", 0xFFF0FFFF.toInt()),
        X11Color("Beige", "beige", 0xFFF5F5DC.toInt()),
        X11Color("Bisque", "bisque", 0xFFFFE4C4.toInt()),
        X11Color("Black", "black", 0xFF000000.toInt()),
        X11Color("Blanched Almond", "blanchedalmond", 0xFFFFEBCD.toInt()),
        X11Color("Blue", "blue", 0xFF0000FF.toInt()),
        X11Color("Blue Violet", "blueviolet", 0xFF8A2BE2.toInt()),
        X11Color("Brown", "brown", 0xFFA52A2A.toInt()),
        X11Color("Burlywood", "burlywood", 0xFFDEB887.toInt()),
        X11Color("Cadet Blue", "cadetblue", 0xFF5F9EA0.toInt()),
        X11Color("Chartreuse", "chartreuse", 0xFF7FFF00.toInt()),
        X11Color("Chocolate", "chocolate", 0xFFD2691E.toInt()),
        X11Color("Coral", "coral", 0xFFFF7F50.toInt()),
        X11Color("Cornflower Blue", "cornflowerblue", 0xFF6495ED.toInt()),
        X11Color("Cornsilk", "cornsilk", 0xFFFFF8DC.toInt()),
        X11Color("Crimson", "crimson", 0xFFDC143C.toInt()),
        X11Color("Cyan", "cyan", 0xFF00FFFF.toInt()),
        X11Color("Dark Blue", "darkblue", 0xFF00008B.toInt()),
        X11Color("Dark Cyan", "darkcyan", 0xFF008B8B.toInt()),
        X11Color("Dark Goldenrod", "darkgoldenrod", 0xFFB8860B.toInt()),
        X11Color("Dark Gray", "darkgray", 0xFFA9A9A9.toInt()),
        X11Color("Dark Green", "darkgreen", 0xFF006400.toInt()),
        X11Color("Dark Khaki", "darkkhaki", 0xFFBDB76B.toInt()),
        X11Color("Dark Magenta", "darkmagenta", 0xFF8B008B.toInt()),
        X11Color("Dark Olive Green", "darkolivegreen", 0xFF556B2F.toInt()),
        X11Color("Dark Orange", "darkorange", 0xFFFF8C00.toInt()),
        X11Color("Dark Orchid", "darkorchid", 0xFF9932CC.toInt()),
        X11Color("Dark Red", "darkred", 0xFF8B0000.toInt()),
        X11Color("Dark Salmon", "darksalmon", 0xFFE9967A.toInt()),
        X11Color("Dark Sea Green", "darkseagreen", 0xFF8FBC8F.toInt()),
        X11Color("Dark Slate Blue", "darkslateblue", 0xFF483D8B.toInt()),
        X11Color("Dark Slate Gray", "darkslategray", 0xFF2F4F4F.toInt()),
        X11Color("Dark Turquoise", "darkturquoise", 0xFF00CED1.toInt()),
        X11Color("Dark Violet", "darkviolet", 0xFF9400D3.toInt()),
        X11Color("Deep Pink", "deeppink", 0xFFFF1493.toInt()),
        X11Color("Deep Sky Blue", "deepskyblue", 0xFF00BFFF.toInt()),
        X11Color("Dim Gray", "dimgray", 0xFF696969.toInt()),
        X11Color("Dodger Blue", "dodgerblue", 0xFF1E90FF.toInt()),
        X11Color("Firebrick", "firebrick", 0xFFB22222.toInt()),
        X11Color("Floral White", "floralwhite", 0xFFFFFAF0.toInt()),
        X11Color("Forest Green", "forestgreen", 0xFF228B22.toInt()),
        X11Color("Fuchsia", "fuchsia", 0xFFFF00FF.toInt()),
        X11Color("Gainsboro", "gainsboro", 0xFFDCDCDC.toInt()),
        X11Color("Ghost White", "ghostwhite", 0xFFF8F8FF.toInt()),
        X11Color("Gold", "gold", 0xFFFFD700.toInt()),
        X11Color("Goldenrod", "goldenrod", 0xFFDAA520.toInt()),
        X11Color("Gray", "gray", 0xFFBEBEBE.toInt()),
        X11Color("Web Gray", "webgray", 0xFF808080.toInt()),
        X11Color("Green", "green", 0xFF00FF00.toInt()),
        X11Color("Web Green", "webgreen", 0xFF008000.toInt()),
        X11Color("Green Yellow", "greenyellow", 0xFFADFF2F.toInt()),
        X11Color("Honeydew", "honeydew", 0xFFF0FFF0.toInt()),
        X11Color("Hot Pink", "hotpink", 0xFFFF69B4.toInt()),
        X11Color("Indian Red", "indianred", 0xFFCD5C5C.toInt()),
        X11Color("Indigo", "indigo", 0xFF4B0082.toInt()),
        X11Color("Ivory", "ivory", 0xFFFFFFF0.toInt()),
        X11Color("Khaki", "khaki", 0xFFF0E68C.toInt()),
        X11Color("Lavender", "lavender", 0xFFE6E6FA.toInt()),
        X11Color("Lavender Blush", "lavenderblush", 0xFFFFF0F5.toInt()),
        X11Color("Lawn Green", "lawngreen", 0xFF7CFC00.toInt()),
        X11Color("Lemon Chiffon", "lemonchiffon", 0xFFFFFACD.toInt()),
        X11Color("Light Blue", "lightblue", 0xFFADD8E6.toInt()),
        X11Color("Light Coral", "lightcoral", 0xFFF08080.toInt()),
        X11Color("Light Cyan", "lightcyan", 0xFFE0FFFF.toInt()),
        X11Color("Light Goldenrod", "lightgoldenrod", 0xFFEEDD82.toInt()),
        X11Color("Light Goldenrod Yellow", "lightgoldenrodyellow", 0xFFFAFAD2.toInt()),
        X11Color("Light Gray", "lightgray", 0xFFD3D3D3.toInt()),
        X11Color("Light Green", "lightgreen", 0xFF90EE90.toInt()),
        X11Color("Light Pink", "lightpink", 0xFFFFB6C1.toInt()),
        X11Color("Light Salmon", "lightsalmon", 0xFFFFA07A.toInt()),
        X11Color("Light Sea Green", "lightseagreen", 0xFF20B2AA.toInt()),
        X11Color("Light Sky Blue", "lightskyblue", 0xFF87CEFA.toInt()),
        X11Color("Light Slate Gray", "lightslategray", 0xFF778899.toInt()),
        X11Color("Light Steel Blue", "lightsteelblue", 0xFFB0C4DE.toInt()),
        X11Color("Light Yellow", "lightyellow", 0xFFFFFFE0.toInt()),
        X11Color("Lime", "lime", 0xFF00FF00.toInt()),
        X11Color("Lime Green", "limegreen", 0xFF32CD32.toInt()),
        X11Color("Linen", "linen", 0xFFFAF0E6.toInt()),
        X11Color("Magenta", "magenta", 0xFFFF00FF.toInt()),
        X11Color("Maroon", "maroon", 0xFFB03060.toInt()),
        X11Color("Web Maroon", "webmaroon", 0xFF800000.toInt()),
        X11Color("Medium Aquamarine", "mediumaquamarine", 0xFF66CDAA.toInt()),
        X11Color("Medium Blue", "mediumblue", 0xFF0000CD.toInt()),
        X11Color("Medium Orchid", "mediumorchid", 0xFFBA55D3.toInt()),
        X11Color("Medium Purple", "mediumpurple", 0xFF9370DB.toInt()),
        X11Color("Medium Sea Green", "mediumseagreen", 0xFF3CB371.toInt()),
        X11Color("Medium Slate Blue", "mediumslateblue", 0xFF7B68EE.toInt()),
        X11Color("Medium Spring Green", "mediumspringgreen", 0xFF00FA9A.toInt()),
        X11Color("Medium Turquoise", "mediumturquoise", 0xFF48D1CC.toInt()),
        X11Color("Medium Violet Red", "mediumvioletred", 0xFFC71585.toInt()),
        X11Color("Midnight Blue", "midnightblue", 0xFF191970.toInt()),
        X11Color("Mint Cream", "mintcream", 0xFFF5FFFA.toInt()),
        X11Color("Misty Rose", "mistyrose", 0xFFFFE4E1.toInt()),
        X11Color("Moccasin", "moccasin", 0xFFFFE4B5.toInt()),
        X11Color("Navajo White", "navajowhite", 0xFFFFDEAD.toInt()),
        X11Color("Navy Blue", "navyblue", 0xFF000080.toInt()),
        X11Color("Old Lace", "oldlace", 0xFFFDF5E6.toInt()),
        X11Color("Olive", "olive", 0xFF808000.toInt()),
        X11Color("Olive Drab", "olivedrab", 0xFF6B8E23.toInt()),
        X11Color("Orange", "orange", 0xFFFFA500.toInt()),
        X11Color("Orange Red", "orangered", 0xFFFF4500.toInt()),
        X11Color("Orchid", "orchid", 0xFFDA70D6.toInt()),
        X11Color("Pale Goldenrod", "palegoldenrod", 0xFFEEE8AA.toInt()),
        X11Color("Pale Green", "palegreen", 0xFF98FB98.toInt()),
        X11Color("Pale Turquoise", "paleturquoise", 0xFFAFEEEE.toInt()),
        X11Color("Pale Violet Red", "palevioletred", 0xFFDB7093.toInt()),
        X11Color("Papaya Whip", "papayawhip", 0xFFFFEFD5.toInt()),
        X11Color("Peach Puff", "peachpuff", 0xFFFFDAB9.toInt()),
        X11Color("Peru", "peru", 0xFFCD853F.toInt()),
        X11Color("Pink", "pink", 0xFFFFC0CB.toInt()),
        X11Color("Plum", "plum", 0xFFDDA0DD.toInt()),
        X11Color("Powder Blue", "powderblue", 0xFFB0E0E6.toInt()),
        X11Color("Purple", "purple", 0xFFA020F0.toInt()),
        X11Color("Web Purple", "webpurple", 0xFF800080.toInt()),
        X11Color("Rebecca Purple", "rebeccapurple", 0xFF663399.toInt()),
        X11Color("Red", "red", 0xFFFF0000.toInt()),
        X11Color("Rosy Brown", "rosybrown", 0xFFBC8F8F.toInt()),
        X11Color("Royal Blue", "royalblue", 0xFF4169E1.toInt()),
        X11Color("Saddle Brown", "saddlebrown", 0xFF8B4513.toInt()),
        X11Color("Salmon", "salmon", 0xFFFA8072.toInt()),
        X11Color("Sandy Brown", "sandybrown", 0xFFF4A460.toInt()),
        X11Color("Sea Green", "seagreen", 0xFF2E8B57.toInt()),
        X11Color("Seashell", "seashell", 0xFFFFF5EE.toInt()),
        X11Color("Sienna", "sienna", 0xFFA0522D.toInt()),
        X11Color("Silver", "silver", 0xFFC0C0C0.toInt()),
        X11Color("Sky Blue", "skyblue", 0xFF87CEEB.toInt()),
        X11Color("Slate Blue", "slateblue", 0xFF6A5ACD.toInt()),
        X11Color("Slate Gray", "slategray", 0xFF708090.toInt()),
        X11Color("Snow", "snow", 0xFFFFFAFA.toInt()),
        X11Color("Spring Green", "springgreen", 0xFF00FF7F.toInt()),
        X11Color("Steel Blue", "steelblue", 0xFF4682B4.toInt()),
        X11Color("Tan", "tan", 0xFFD2B48C.toInt()),
        X11Color("Teal", "teal", 0xFF008080.toInt()),
        X11Color("Thistle", "thistle", 0xFFD8BFD8.toInt()),
        X11Color("Tomato", "tomato", 0xFFFF6347.toInt()),
        X11Color("Turquoise", "turquoise", 0xFF40E0D0.toInt()),
        X11Color("Violet", "violet", 0xFFEE82EE.toInt()),
        X11Color("Wheat", "wheat", 0xFFF5DEB3.toInt()),
        X11Color("White", "white", 0xFFFFFFFF.toInt()),
        X11Color("White Smoke", "whitesmoke", 0xFFF5F5F5.toInt()),
        X11Color("Yellow", "yellow", 0xFFFFFF00.toInt()),
        X11Color("Yellow Green", "yellowgreen", 0xFF9ACD32.toInt())
    )

    /** Fast lookup by [X11Color.key] (lowercase, no spaces/underscores/hyphens). */
    private val byKey: Map<String, X11Color> = all.associateBy { it.key }

    /**
     * Looks up an X11 color by name, tolerant of case, spaces, underscores
     * and hyphens (e.g. "Dodger Blue", "dodger-blue" and "DODGERBLUE" all
     * resolve to the same entry). Returns null if the name isn't a
     * recognized X11 color.
     */
    fun byName(name: String): X11Color? {
        val normalized = name.trim().lowercase()
            .replace(" ", "")
            .replace("_", "")
            .replace("-", "")
        return byKey[normalized]
    }

    /** Case-insensitive substring search over both display name and key. */
    fun search(query: String): List<X11Color> {
        if (query.isBlank()) return all
        val q = query.trim().lowercase().replace(" ", "")
        return all.filter { it.key.contains(q) || it.name.lowercase().replace(" ", "").contains(q) }
    }
}
