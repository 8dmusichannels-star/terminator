package com.terminator.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.terminator.app.settings.SettingsKeys
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Material theme toggle, optional AMOLED Black, and terminal color scheme
 * selection: Material-derived, custom RGB picker, or an imported theme
 * (e.g. Nord-style) via a CSS/bash-based theme file.
 *
 * Whatever is picked here is what the terminal screen actually renders with
 * (see MainActivity, which reads COLOR_SCHEME_MODE / CUSTOM_FG / CUSTOM_BG).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()

    val amoledBlack by repo.flow(SettingsKeys.AMOLED_BLACK, false).collectAsState(initial = false)
    val colorSchemeMode by repo.flow(SettingsKeys.COLOR_SCHEME_MODE, "Material")
        .collectAsState(initial = "Material")
    val customFg by repo.flow(SettingsKeys.CUSTOM_FG, DEFAULT_CUSTOM_FG).collectAsState(initial = DEFAULT_CUSTOM_FG)
    val customBg by repo.flow(SettingsKeys.CUSTOM_BG, DEFAULT_CUSTOM_BG).collectAsState(initial = DEFAULT_CUSTOM_BG)
    var importError by remember { mutableStateOf<String?>(null) }
    var importedFileName by remember { mutableStateOf<String?>(null) }

    // Accepts either:
    //  - simple "key=value" lines, e.g. foreground=#E6E6E6 / background=#000000
    //  - or a small JSON object, e.g. {"foreground":"#E6E6E6","background":"#000000"}
    // (also accepts fg/bg as short aliases). Whatever is parsed is written
    // straight into CUSTOM_FG/CUSTOM_BG, which MainActivity now actually
    // reads for the "Import theme file" mode.
    val themeFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            importError = "Could not read that file"
            return@rememberLauncherForActivityResult
        }
        val parsed = parseThemeFile(text)
        if (parsed == null) {
            importError = "Unrecognized theme file format"
        } else {
            val (fg, bg) = parsed
            scope.launch {
                repo.set(SettingsKeys.CUSTOM_FG, fg)
                repo.set(SettingsKeys.CUSTOM_BG, bg)
                repo.set(SettingsKeys.COLOR_SCHEME_MODE, "Import theme file")
            }
            importError = null
            importedFileName = uri.lastPathSegment ?: "theme file"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Theme") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            SwitchRow("AMOLED Black", amoledBlack) {
                scope.launch { repo.set(SettingsKeys.AMOLED_BLACK, it) }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Terminal color scheme", style = MaterialTheme.typography.labelLarge)
            listOf("Material", "Custom RGB", "Nord", "Import theme file").forEach { option ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = colorSchemeMode == option,
                        onClick = { scope.launch { repo.set(SettingsKeys.COLOR_SCHEME_MODE, option) } }
                    )
                    Text(option)
                }
            }

            if (colorSchemeMode == "Custom RGB") {
                Spacer(modifier = Modifier.height(8.dp))
                CustomRgbEditor(
                    foregroundHex = "#%06X".format(customFg and 0xFFFFFF),
                    backgroundHex = "#%06X".format(customBg and 0xFFFFFF),
                    onForegroundChange = { hex ->
                        parseHexColor(hex)?.let { scope.launch { repo.set(SettingsKeys.CUSTOM_FG, it) } }
                    },
                    onBackgroundChange = { hex ->
                        parseHexColor(hex)?.let { scope.launch { repo.set(SettingsKeys.CUSTOM_BG, it) } }
                    }
                )
            }

            if (colorSchemeMode == "Import theme file") {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    themeFilePicker.launch(arrayOf("text/*", "application/json", "*/*"))
                }) {
                    Text("Import theme file")
                }
                importedFileName?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Imported: $it", style = MaterialTheme.typography.bodySmall)
                }
                importError?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun CustomRgbEditor(
    foregroundHex: String,
    backgroundHex: String,
    onForegroundChange: (String) -> Unit,
    onBackgroundChange: (String) -> Unit
) {
    var fgText by remember(foregroundHex) { mutableStateOf(foregroundHex) }
    var bgText by remember(backgroundHex) { mutableStateOf(backgroundHex) }

    Column {
        OutlinedTextField(
            value = fgText,
            onValueChange = {
                fgText = it
                onForegroundChange(it)
            },
            label = { Text("Text color (hex, e.g. #E6E6E6)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = bgText,
            onValueChange = {
                bgText = it
                onBackgroundChange(it)
            },
            label = { Text("Background color (hex, e.g. #000000)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** ARGB int for #E6E6E6 - matches the previous flatBlack() default foreground. */
const val DEFAULT_CUSTOM_FG = 0xFFE6E6E6.toInt()

/** ARGB int for #000000 - matches the previous flatBlack() default background. */
const val DEFAULT_CUSTOM_BG = 0xFF000000.toInt()

/**
 * Parses a small theme file into (foreground, background) ARGB ints.
 * Supports "key=value" lines and a flat JSON object; returns null if
 * neither foreground nor background could be found.
 */
private fun parseThemeFile(text: String): Pair<Int, Int>? {
    var fg: Int? = null
    var bg: Int? = null

    val trimmed = text.trim()
    if (trimmed.startsWith("{")) {
        runCatching {
            val obj = JSONObject(trimmed)
            fg = firstColorKey(obj, "foreground", "fg", "text")
            bg = firstColorKey(obj, "background", "bg")
        }
    } else {
        trimmed.lineSequence().forEach { line ->
            val cleaned = line.trim()
            if (cleaned.isBlank() || !cleaned.contains('=')) return@forEach
            val (key, value) = cleaned.split('=', limit = 2).map { it.trim() }
            val color = parseHexColor(value) ?: return@forEach
            when (key.lowercase()) {
                "foreground", "fg", "text" -> fg = color
                "background", "bg" -> bg = color
            }
        }
    }

    val resolvedFg = fg ?: DEFAULT_CUSTOM_FG
    val resolvedBg = bg ?: DEFAULT_CUSTOM_BG
    return if (fg == null && bg == null) null else resolvedFg to resolvedBg
}

private fun firstColorKey(obj: JSONObject, vararg keys: String): Int? {
    for (key in keys) {
        if (obj.has(key)) {
            parseHexColor(obj.optString(key))?.let { return it }
        }
    }
    return null
}

private fun parseHexColor(hex: String): Int? {
    return try {
        android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
    } catch (_: IllegalArgumentException) {
        null
    }
}
