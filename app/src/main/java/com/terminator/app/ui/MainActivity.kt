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

package com.terminator.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.tween
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.text.TextRange
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.terminator.app.TerminatorApp
import com.terminator.app.session.SessionForegroundService
import com.terminator.app.settings.SettingsKeys
import com.terminator.app.ui.settings.DEFAULT_CUSTOM_BG
import com.terminator.app.ui.settings.DEFAULT_CUSTOM_FG
import com.terminator.app.ui.settings.KeymapEntry
import com.terminator.app.ui.settings.SettingsActivity
import com.terminator.app.ui.settings.decodeKeymaps
import com.terminator.app.ui.theme.TerminatorTheme
import com.terminator.emulator.TerminalEmulator
import com.terminator.emulator.TerminalPalette
import com.terminator.emulator.TerminalView

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels {
        viewModelFactory {
            initializer {
                val app = application as TerminatorApp
                MainViewModel(app, app.sessionRepository, filesDir, app.settingsRepository, app.terminfoDir)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Targeting API 35 makes the platform force edge-to-edge, and on
        // API 35 that ALSO breaks windowSoftInputMode="adjustResize": the
        // framework stops padding the window's root view for the IME, so
        // trying to opt back out with setDecorFitsSystemWindows(true) is
        // unreliable across OEMs/versions here. The robust fix (per current
        // Android guidance) is to embrace edge-to-edge and consume the IME
        // inset ourselves in Compose via Modifier.imePadding() below, rather
        // than depend on the window physically resizing.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // setDecorFitsSystemWindows(false) alone leaves the platform's
        // default scrim behind the status/navigation bars - a translucent
        // dark-gray protection layer, not true black - so AMOLED Black
        // never actually reached those strips even though the rest of the
        // UI was pure #000000. Explicitly zeroing both bar colors and
        // disabling their contrast enforcement removes that scrim so the
        // bars show whatever's drawn underneath (this app's real black).
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }

        // Default session on a genuinely fresh launch (app icon, launcher) -
        // handled in MainViewModel.init by opening the flagged-default
        // entry. Notification taps carry their own target session and are
        // handled below instead, via handleNotificationIntent - launchMode
        // singleTask (see manifest) is what makes that distinction possible
        // at all: without it, tapping the notification while the app was
        // already running span up a brand new MainActivity/MainViewModel
        // pair with an empty liveSessions map, so every notification tap
        // looked like "open a new session" even when N others were already
        // running and simply became unreachable from the UI (still alive in
        // the old, now-orphaned ViewModel, but nothing pointed at it).
        //
        // SessionForegroundService itself is no longer started from here -
        // TerminatorApp.observeSessionServiceLifecycle now starts/stops it
        // based on the actual running-session count, so it only exists
        // (and only shows its notification) while a session genuinely is.
        handleNotificationIntent(intent)

        setContent {
            val app = application as TerminatorApp
            val repo = app.settingsRepository

            val amoledBlack by repo.flow(SettingsKeys.AMOLED_BLACK, false).collectAsState(initial = false)
            val wallpaperUriStr by repo.flow(SettingsKeys.WALLPAPER_URI, "").collectAsState(initial = "")
            // Runner toolbar's save icon - exports the active (or split
            // secondary) pane's full terminal output. Always available, no
            // toggle: unlike the old clipboard-history log this replaced,
            // there's no persisted state to gate - it just reads whatever
            // the session's buffer currently holds at export time.
            val terminalExportLauncher = rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
            ) { uri -> uri?.let { viewModel.exportSessionOutput(it) } }
            val blurAlpha by repo.flow(SettingsKeys.BACKGROUND_ALPHA, 0.3f).collectAsState(initial = 0.3f)
            val backgroundBlur by repo.flow(SettingsKeys.BACKGROUND_BLUR, 0f).collectAsState(initial = 0f)
            val showTitlebar by repo.flow(SettingsKeys.SHOW_TITLEBAR, true).collectAsState(initial = true)
            val virtualKeysEnabled by repo.flow(SettingsKeys.VIRTUAL_KEYS, true).collectAsState(initial = true)
            val softKeyboardEnabled by repo.flow(SettingsKeys.SOFT_KEYBOARD, true).collectAsState(initial = true)
            val textSize by repo.flow(SettingsKeys.TEXT_SIZE, 14f).collectAsState(initial = 14f)
            val colorSchemeMode by repo.flow(SettingsKeys.COLOR_SCHEME_MODE, "Material")
                .collectAsState(initial = "Material")
            val customFg by repo.flow(SettingsKeys.CUSTOM_FG, DEFAULT_CUSTOM_FG).collectAsState(initial = DEFAULT_CUSTOM_FG)
            val customBg by repo.flow(SettingsKeys.CUSTOM_BG, DEFAULT_CUSTOM_BG).collectAsState(initial = DEFAULT_CUSTOM_BG)
            // Settings > Theme > "Custom Palette" - all 16 ANSI slots, distinct
            // from CUSTOM_FG/CUSTOM_BG above. See PaletteThemes.kt.
            val customPaletteColorsJson by repo.flow(SettingsKeys.CUSTOM_PALETTE_COLORS, "")
                .collectAsState(initial = "")
            val customPaletteFg by repo.flow(
                SettingsKeys.CUSTOM_PALETTE_FG,
                com.terminator.app.ui.settings.PalettePresets.default.foreground
            ).collectAsState(initial = com.terminator.app.ui.settings.PalettePresets.default.foreground)
            val customPaletteBg by repo.flow(
                SettingsKeys.CUSTOM_PALETTE_BG,
                com.terminator.app.ui.settings.PalettePresets.default.background
            ).collectAsState(initial = com.terminator.app.ui.settings.PalettePresets.default.background)
            // Settings > Theme > "Override ANSI colors too" and "Separate
            // error/status colors" - see TerminalPalette.materialOverride()/
            // withStatusColors() for what each actually changes.
            val materialColorOverride by repo.flow(SettingsKeys.MATERIAL_COLOR_OVERRIDE, false)
                .collectAsState(initial = false)
            val statusColorsEnabled by repo.flow(SettingsKeys.STATUS_COLORS_ENABLED, false)
                .collectAsState(initial = false)
            val statusErrorColor by repo.flow(
                SettingsKeys.STATUS_ERROR_COLOR,
                com.terminator.app.ui.settings.DEFAULT_STATUS_ERROR
            ).collectAsState(initial = com.terminator.app.ui.settings.DEFAULT_STATUS_ERROR)
            val statusWarningColor by repo.flow(
                SettingsKeys.STATUS_WARNING_COLOR,
                com.terminator.app.ui.settings.DEFAULT_STATUS_WARNING
            ).collectAsState(initial = com.terminator.app.ui.settings.DEFAULT_STATUS_WARNING)
            // Settings > Appearance > "Pinch to zoom". On by default -
            // matches the gesture's previous always-on behaviour.
            val zoomEnabled by repo.flow(SettingsKeys.ZOOM_ENABLED, true).collectAsState(initial = true)
            val fontFamilySetting by repo.flow(SettingsKeys.FONT_FAMILY, "Monospace").collectAsState(initial = "Monospace")
            val bellEnabled by repo.flow(SettingsKeys.BELL_ENABLED, true).collectAsState(initial = true)
            val useCustomSound by repo.flow(SettingsKeys.USE_CUSTOM_SOUND, false).collectAsState(initial = false)
            val customSoundUri by repo.flow(SettingsKeys.CUSTOM_SOUND_URI, "").collectAsState(initial = "")
            val horizontalModeEnabled by repo.flow(SettingsKeys.HORIZONTAL_MODE, true)
                .collectAsState(initial = true)
            // Forced terminal width from Settings > Appearance > "Terminal
            // width" slider. Was persisted by AppearanceSettingsScreen but
            // never read anywhere - the pty always got whatever column count
            // the viewport happened to auto-fit to.
            val columnsSetting by repo.flow(SettingsKeys.COLUMNS, 80f).collectAsState(initial = 80f)
            // Settings > Display > "Show statusbar". Was persisted by
            // DisplaySettingsScreen but never read - the system status bar
            // stayed hidden (edge-to-edge) regardless of this toggle.
            val showStatusbar by repo.flow(SettingsKeys.SHOW_STATUSBAR, false).collectAsState(initial = false)
            // Settings > Keyboard > Input Mode. See the keyboardOptions
            // wiring on the hidden input field below for what each mode
            // actually changes.
            val inputMode by repo.flow(SettingsKeys.INPUT_MODE, "Default").collectAsState(initial = "Default")
            // Settings > Keyboard > "Keyboard shortcuts & keymapper" - named
            // macros the user built from VirtualKey combos. Was persisted by
            // KeymapperScreen but never surfaced anywhere the user could
            // actually trigger one.
            val keymapsJson by repo.flow(SettingsKeys.KEYMAPS, "").collectAsState(initial = "")
            val keymaps = remember(keymapsJson) { decodeKeymaps(keymapsJson) }

            // This setting was persisted by DisplaySettingsScreen but never
            // actually read anywhere, so toggling "Horizontal (landscape)
            // mode" had zero effect on the app. Wiring it to the activity's
            // requestedOrientation is what actually locks/unlocks landscape.
            LaunchedEffect(horizontalModeEnabled) {
                requestedOrientation = if (horizontalModeEnabled) {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR
                } else {
                    android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                }
            }

            // Same "persisted but never applied" bug as horizontalModeEnabled
            // above: actually show/hide the system status bar in step with
            // the Settings > Display toggle instead of it always staying
            // hidden from the edge-to-edge setup in onCreate().
            LaunchedEffect(showStatusbar) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                if (showStatusbar) {
                    controller.show(WindowInsetsCompat.Type.statusBars())
                } else {
                    controller.hide(WindowInsetsCompat.Type.statusBars())
                }
            }

            TerminatorTheme(amoledBlack = amoledBlack) {
                val state by viewModel.uiState.collectAsState()
                // LocalSoftwareKeyboardController (Compose's IME abstraction) used to be
                // read here and called from a couple of spots below, but it turned out
                // unreliable for driving the keyboard specifically around the toolbar
                // Copy/Paste/Cancel restore path and the plain tap-to-toggle path -
                // logcat confirmed the *decision* of when to show/hide was always
                // correct, but the on-screen result still came out backwards (open
                // keyboard closing, closed keyboard opening, or flipping back a moment
                // later). WindowInsetsControllerCompat (insetsController below) talks to
                // the same platform IME control surface this file already uses
                // successfully for the status bar, and is what every keyboard show/hide
                // in this file now goes through instead - see insetsController's own doc.
                val insetsController = remember {
                    WindowInsetsControllerCompat(window, window.decorView)
                }
                // Used to defer insetsController.show(ime()) to a post-frame
                // callback in the Copy/Paste/Cancel/text-page-close restore
                // paths below - see those call sites' comments for why a
                // synchronous show() right after requestFocus() races the
                // focus-driven show request the platform already fires on
                // its own, instead of reinforcing it.
                val currentView = LocalView.current
                val focusManager = LocalFocusManager.current
                val focusRequester = remember { FocusRequester() }
                var keyboardOpen by remember { mutableStateOf(false) }
                // hiddenFieldFocused / textPageFieldFocused still exist
                // because VirtualKeyBar and the toolbar callbacks use them
                // to decide WHERE to send focus back to - but they are no
                // longer what keyboardOpen itself is computed from. They
                // used to be: keyboardOpen = hiddenFieldFocused ||
                // textPageFieldFocused. Focus is Compose's own bookkeeping
                // about which composable *would* receive input, not a
                // report from the system about whether the IME is
                // actually on screen - the two can and did desync:
                // requestFocus() succeeding is not a guarantee the system
                // decides to (re)show the IME for it (a well-documented
                // Compose/platform gap once the IME has already fully
                // hidden), and conversely a field can hold focus with the
                // IME never having been told to show. That gap is what
                // kept surfacing as "kapaliyken aciliyor ama acikken
                // kapaniyor" no matter how the two focus booleans were
                // combined or debounced - the inputs themselves weren't
                // the right signal.
                //
                // WindowInsets.ime is the system's own report of the IME's
                // current inset (>0 while any portion of the keyboard is
                // occupying screen space), so it can never disagree with
                // what the user is actually looking at. It's snapshot-
                // state-backed, so reading it directly into keyboardOpen
                // (no LaunchedEffect indirection - that would cost an
                // extra recomposition frame of lag on every show/hide)
                // recomposes this exact frame whenever the real keyboard
                // shows/hides/animates - including the platform-driven
                // cases (back button, switching apps, an IME the user
                // closed with its own dismiss control) that focus-only
                // tracking could never see happen at all.
                keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                // keyboardOpen (above) is a live, per-frame read of an
                // ANIMATING inset - while the IME is actually in the
                // middle of showing or hiding, it can read a transient
                // value that doesn't match what's about to be on screen
                // a moment later (e.g. still 0 a frame or two into a
                // show animation). That's harmless for keyboardOpen's
                // main use (driving this frame's UI, which is supposed
                // to track the animation live), but it's exactly what
                // was breaking keyboardWasOpenBeforeSelection below:
                // that field takes ONE snapshot of keyboardOpen at the
                // moment a long-press is recognized, and if that
                // snapshot lands mid-animation it captures the opposite
                // of what the user actually left on screen - the
                // toolbar then faithfully restores/dismisses the wrong
                // thing on Copy/Paste/Cancel ("kapaliyken açılıyor,
                // açikken kapaniyor"). settledKeyboardOpen only updates
                // once the inset has stopped moving for a short debounce
                // window, so anything that needs "was the keyboard
                // really open" rather than "what is the inset doing
                // right now this frame" reads this instead.
                var settledKeyboardOpen by remember { mutableStateOf(false) }
                LaunchedEffect(keyboardOpen) {
                    delay(120)
                    settledKeyboardOpen = keyboardOpen
                }
                // Ground truth for "did WE just tell the keyboard to open or
                // close", updated synchronously at every one of this file's
                // own show()/hide() call sites (tap-to-toggle, Copy/Paste/
                // Cancel, text-page close). settledKeyboardOpen above is a
                // debounced *observation* of the animating IME inset - it's
                // still needed for the very first long-press (nothing has
                // told the keyboard to do anything yet, so there's no
                // intent to read), but relying on it alone for every
                // Copy/Paste/Cancel/tap-toggle in a row meant a long-press
                // that landed while the previous debounce hadn't caught up
                // yet (e.g. two Cancel taps close together, or a fast
                // Cancel-then-reselect) could capture a stale snapshot from
                // before the app's own most recent show()/hide() call -
                // that's what made Cancel/Copy/Paste occasionally close the
                // keyboard completely on a second press instead of
                // restoring it. Reading this flag first, falling back to
                // settledKeyboardOpen only when it's never been set, closes
                // that gap: it can never be stale relative to this file's
                // own actions because it's written in the same call that
                // performs the action.
                var lastKeyboardIntentOpen by remember { mutableStateOf<Boolean?>(null) }
                // Falls back to keyboardOpen (the live inset read) instead of
                // settledKeyboardOpen when this app hasn't shown/hidden the
                // keyboard itself yet. This only matters for the very first
                // long-press-to-select of a session: at that point the
                // finger has already been held stationary past the long-
                // press timeout (400ms+), which is well past settled-
                // Keyboard's own 120ms debounce window - so keyboardOpen is
                // no less reliable here than settledKeyboardOpen would be,
                // and reading it directly closes a gap where a long-press
                // landing inside that 120ms window (e.g. right after the
                // user manually dismissed the IME via the system back
                // gesture, with no show()/hide() call from this file to set
                // lastKeyboardIntentOpen) could still capture a stale
                // settledKeyboardOpen and have Copy/Paste/Cancel wrongly
                // reopen or reclose the keyboard on that first use.
                fun effectiveKeyboardWasOpen(): Boolean = lastKeyboardIntentOpen ?: keyboardOpen
                var hiddenFieldFocused by remember { mutableStateOf(false) }
                var textPageFieldFocused by remember { mutableStateOf(false) }
                // Titlebar "+" button state: whether the running-session
                // picker popup (QuickAddSessionPickerDialog) is showing.
                // See onQuickAddClicked below for why "+" opens this instead
                // of calling viewModel.duplicateActiveSession() directly.
                var showQuickAddPicker by remember { mutableStateOf(false) }
                // CTRL/ALT on the virtual key bar were pure no-ops before -
                // sendSequence was "" and nothing ever consumed them. They're
                // one-shot modifiers: tap CTRL/ALT, then type a regular
                // character (from the real keyboard, or another virtual
                // key) and that keystroke gets transformed instead of sent
                // literally.
                var ctrlActive by remember { mutableStateOf(false) }
                var altActive by remember { mutableStateOf(false) }

                // Bell was previously visual-only (bellFlash was set but
                // never read anywhere) - this actually plays the configured
                // sound each time the terminal emits a BEL. Keyed on the
                // tick counter so back-to-back bells each retrigger it, even
                // if playback of the previous one hasn't finished yet.
                LaunchedEffect(state.bellTick) {
                    if (state.bellTick > 0 && bellEnabled) {
                        runCatching {
                            val soundUri = if (useCustomSound && customSoundUri.isNotBlank()) {
                                Uri.parse(customSoundUri)
                            } else {
                                android.media.RingtoneManager.getActualDefaultRingtoneUri(
                                    this@MainActivity,
                                    android.media.RingtoneManager.TYPE_NOTIFICATION
                                )
                            }
                            if (soundUri != null) {
                                android.media.RingtoneManager.getRingtone(this@MainActivity, soundUri)
                                    ?.play()
                            }
                        }
                    }
                }
                // Zero-width placeholder kept permanently in the hidden field.
                // Resetting the field to a truly empty string after every
                // keystroke (the old behavior) meant backspace on an
                // already-empty field never fired onValueChange at all - so
                // deletes were silently dropped. Keeping one invisible
                // placeholder char means there's always something for
                // backspace to remove, so it's reliably detected below.
                val inputPlaceholder = "\u200B"
                var hiddenInput by remember {
                    mutableStateOf(
                        TextFieldValue(inputPlaceholder, selection = TextRange(inputPlaceholder.length))
                    )
                }
                // Tracks whatever text is *currently* sitting in hiddenInput
                // that's already been consumed/forwarded - starts equal to
                // inputPlaceholder and, on normal typing, is advanced to the
                // IME's own newText instead of being collapsed back down to
                // the bare placeholder. That distinction is the actual fix
                // for numeric mode (and other composing-driven IME layouts)
                // reverting after every keystroke: onValueChange used to
                // rebuild hiddenInput as `TextFieldValue(inputPlaceholder,
                // ...)` on every single keystroke, which changes the live
                // *content* every time, not just the composition range -
                // and per Compose's own TextField docs/IME behavior, a
                // content change (as opposed to a pure selection change) is
                // exactly what forces the platform to call
                // InputConnection.restartInput. That's a full IME reset,
                // and it's what a numeric-suggestion strip or number-row
                // popup can't survive. Selection-only changes only need
                // updateSelection(), which doesn't reset. Letting the field
                // grow (newText becomes the new baseline) keeps every
                // "normal typing" edit a pure append, so the IME sees a
                // continuous, growing field instead of a value that snaps
                // back to nothing after each character - the same way any
                // ordinary text field behaves, which is why ordinary text
                // fields don't have this problem. The field still can't be
                // allowed to grow forever, so it's trimmed back down to the
                // bare placeholder on focus loss/regain and once it crosses
                // a generous length threshold - both real, infrequent
                // content changes, not per-keystroke ones, so the odd
                // IME flicker they can cause is a one-off instead of
                // happening on every character.
                var consumedBaseline by remember { mutableStateOf(inputPlaceholder) }

                // Terminal colors now follow Settings > Theme > Terminal color
                // scheme, instead of always rendering flatBlack() regardless
                // of what was picked there.
                val materialColors = MaterialTheme.colorScheme
                val terminalPalette = remember(
                    colorSchemeMode, customFg, customBg, materialColors,
                    materialColorOverride, statusColorsEnabled, statusErrorColor, statusWarningColor,
                    customPaletteColorsJson, customPaletteFg, customPaletteBg
                ) {
                    val basePalette = when (colorSchemeMode) {
                        "Nord" -> TerminalPalette.nord()
                        // "No color": the terminal's own stable built-in
                        // palette, completely untouched by Material/status/
                        // custom overrides - see ThemeSettingsScreen's note
                        // under this radio option.
                        "No color" -> TerminalPalette.flatBlack()
                        // "Custom Palette": all 16 ANSI slots individually
                        // defined (see PaletteThemes.kt) - distinct from
                        // "Custom fg/bg" below, which only varies fg/bg.
                        "Custom Palette" -> TerminalPalette.fromPalette(
                            colors = com.terminator.app.ui.settings.decodePaletteColors(customPaletteColorsJson),
                            foreground = customPaletteFg,
                            background = customPaletteBg
                        )
                        // Imported theme files are parsed straight into CUSTOM_FG/CUSTOM_BG
                        // (see ThemeSettingsScreen), so this now actually reflects what was
                        // imported instead of silently falling back to flatBlack(). "Custom
                        // RGB" is kept as an alias so a value persisted before the mode was
                        // renamed to "Custom fg/bg" still resolves correctly.
                        "Custom fg/bg", "Custom RGB", "Import theme file" ->
                            TerminalPalette.custom(foreground = customFg, background = customBg)
                        // "Material" mode: normally just fills fg/bg from the Material
                        // scheme (custom()), leaving the 16 ANSI accent colors fixed.
                        // With materialColorOverride on, the ANSI slots themselves are
                        // also derived from Material - see materialOverride()'s doc for
                        // why LS_COLORS/program-set colors are only touched in that case.
                        else -> if (materialColorOverride) {
                            TerminalPalette.materialOverride(
                                primary = materialColors.primary.toArgb(),
                                error = materialColors.error.toArgb(),
                                tertiary = materialColors.tertiary.toArgb(),
                                secondary = materialColors.secondary.toArgb(),
                                onBackground = materialColors.onBackground.toArgb(),
                                background = materialColors.background.toArgb(),
                                primaryContainer = materialColors.primaryContainer.toArgb(),
                                errorContainer = materialColors.errorContainer.toArgb(),
                                tertiaryContainer = materialColors.tertiaryContainer.toArgb(),
                                secondaryContainer = materialColors.secondaryContainer.toArgb()
                            )
                        } else {
                            TerminalPalette.custom(
                                foreground = materialColors.onBackground.toArgb(),
                                background = materialColors.background.toArgb()
                            )
                        }
                    }
                    // Layered on top of whichever base palette was just picked - see
                    // Settings > Theme > "Separate error/status colors". Independent
                    // of colorSchemeMode/materialColorOverride by design, EXCEPT for
                    // "No color" - that mode's whole point is untouched stable colors,
                    // so a stale STATUS_COLORS_ENABLED=true from a previously-selected
                    // mode (the UI hides this toggle for "No color", but doesn't clear
                    // it) must not leak through here.
                    if (statusColorsEnabled && colorSchemeMode != "No color") {
                        basePalette.withStatusColors(statusErrorColor, statusWarningColor)
                    } else {
                        basePalette
                    }
                }

                val terminalTypeface = remember(fontFamilySetting) {
                    resolveTerminalTypeface(this@MainActivity, fontFamilySetting)
                }

                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    Scaffold(
                        topBar = {
                            if (showTitlebar) {
                                TerminatorTitleBar(
                                    onMenuClicked = { viewModel.setDrawerOpen(true) },
                                    // Purely cosmetic - see SessionEntry.imageUri's doc.
                                    // activeSessionId here is a runtimeId, so resolve it
                                    // to the RunningSession's entryId first, then look up
                                    // that entry's own picture (if any) from state.sessions.
                                    activeSessionImageUri = state.runningSessions
                                        .firstOrNull { it.runtimeId == state.activeSessionId }
                                        ?.let { running -> state.sessions.firstOrNull { it.id == running.entryId } }
                                        ?.imageUri,
                                    // "+" used to call duplicateActiveSession()
                                    // straight away, silently cloning whatever
                                    // session happened to be active with no
                                    // confirmation - with several sessions
                                    // open, that's often not the one the user
                                    // actually meant to clone. Now it opens
                                    // QuickAddSessionPickerDialog so the user
                                    // picks which running session to clone.
                                    // If nothing is running yet there's
                                    // nothing to choose between, so it falls
                                    // straight through to the old
                                    // spawn-the-default-session behavior
                                    // instead of popping up an empty list.
                                    onQuickAddClicked = {
                                        if (state.runningSessions.isNotEmpty()) {
                                            showQuickAddPicker = true
                                        } else {
                                            viewModel.duplicateActiveSession()
                                        }
                                    }
                                )
                            }
                        }
                    ) { padding ->
                        // Scaffold's `padding` already accounts for system bars (status/nav
                        // bar). While the IME is open, WindowInsets.ime's height also
                        // includes that same nav-bar strip (the keyboard sits above the
                        // nav bar, and the ime inset is measured from the very bottom of
                        // the window) - so a plain .imePadding() here was stacking the nav
                        // bar's height on top of Scaffold's padding a second time, pushing
                        // the virtual key bar noticeably higher than the keyboard's real
                        // top edge. Subtracting the nav bar inset back out of the ime
                        // inset (floored at 0 so nothing breaks while the IME is closed)
                        // adds only the keyboard's own extra height, matching it exactly.
                        val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                        val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                        val extraImePadding = (imeBottom - navBarBottom).coerceAtLeast(0.dp)
                        Box(
                            modifier = Modifier
                                .padding(padding)
                                .fillMaxSize()
                                .padding(bottom = extraImePadding)
                        ) {
                            val buffer = viewModel.activeBuffer()

                            // Pinch-to-zoom text size: per-session and transient, not the
                            // global Settings > Appearance > Text Size. Falls back to that
                            // global value until the user pinches this particular session,
                            // and is dropped from sessionTextSizes (in MainViewModel) the
                            // moment that runtime session ends, so a fresh/other session
                            // always starts back at the global size.
                            val activeSessionId = state.activeSessionId
                            val sessionTextSize = activeSessionId?.let { state.sessionTextSizes[it] }
                            // Pinch gestures fire on essentially every frame while a finger
                            // is moving. Previously each of those frames called
                            // viewModel.setSessionTextSize() directly, which copies the
                            // sessionTextSizes map and pushes a new StateFlow value -
                            // triggering a full recomposition of the whole screen (drawer,
                            // titlebar, virtual key bar, everything) 60+ times a second.
                            // That's what made pinch-zoom feel sluggish/stuttery instead of
                            // smooth. liveZoomSize is purely local Compose state that only
                            // this Box's subtree reads, so during an active pinch only the
                            // terminal itself recomposes; the ViewModel (and therefore the
                            // rest of the screen) is only touched once, when the gesture
                            // ends, via the commit block in the pointerInput below.
                            var liveZoomSize by remember(activeSessionId) { mutableStateOf<Float?>(null) }
                            var zoomCommitJob by remember { mutableStateOf<Job?>(null) }

                            // Long-press-to-select range (row, col screen-space), shown as a
                            // highlight in TerminalView and backing the Copy/Paste toolbar
                            // below. Reset whenever the active session changes so a leftover
                            // selection from a different session's screen can never linger.
                            var selectionStart by remember(activeSessionId) { mutableStateOf<Pair<Int, Int>?>(null) }
                            var selectionEnd by remember(activeSessionId) { mutableStateOf<Pair<Int, Int>?>(null) }
                            // Snapshot of keyboardOpen taken the instant the
                            // long-press selection starts (see that gesture
                            // below), NOT read live inside the toolbar's
                            // Copy/Paste/Cancel callbacks. Those buttons are
                            // themselves focusable/clickable, so tapping any
                            // one of them steals Compose focus away from the
                            // hidden input field first - which flips
                            // hiddenFieldFocused, and therefore keyboardOpen,
                            // to false before (or racing with) the button's
                            // own onClick actually running. Reading the live
                            // keyboardOpen from inside onCopy/onPaste/onCancel
                            // meant "was the keyboard open" was answered with
                            // whatever that race happened to leave behind -
                            // sometimes still true (falsely re-opening a
                            // keyboard the user had left closed), sometimes
                            // already flipped to false (failing to restore a
                            // keyboard the user had open), rather than
                            // reliably reflecting the state the user actually
                            // left things in. This field is set once, before
                            // the toolbar (and thus its focus-stealing
                            // buttons) even exists, so it's immune to that
                            // race.
                            var keyboardWasOpenBeforeSelection by remember(activeSessionId) { mutableStateOf(false) }
                            val clipboardManager = LocalClipboardManager.current

                            // selectionStart/End are absolute (row, col) screen positions, not
                            // tied to any particular piece of text. The moment new output prints
                            // to the live screen (bufferVersion bumps), every row after the first
                            // changed line shifts - whatever the user had highlighted no longer
                            // lines up with the same on-screen rows, which is what made the
                            // highlighted/"about to copy" region appear to silently jump/fall to
                            // a different spot. Clearing the selection as soon as the buffer
                            // changes underneath it means a finished selection stays exactly
                            // where the user left it (nothing more prints once they've stopped
                            // and are just about to tap Copy), while a genuinely live-updating
                            // screen can't leave a stale, now-wrong highlight sitting around.
                            //
                            // Only clears while scrollOffset == 0 (looking at the live screen),
                            // though - the original version cleared on every bufferVersion bump
                            // regardless of scroll position, which meant a selection made in
                            // scrollback (history that has already scrolled off and is frozen -
                            // scrollOffset > 0) got wiped the instant the *live* screen at the
                            // bottom changed at all, e.g. a background process printing a line or
                            // the shell prompt's cursor moving, even though nothing about the
                            // scrolled-back rows the user actually highlighted had moved. That's
                            // what made the highlight disappear the moment the user started
                            // scrolling to look at what they'd selected - ordinary background
                            // terminal activity kept bumping bufferVersion out from under them.
                            // Content changes on the live row range genuinely can still invalidate
                            // a scrollback selection (e.g. the buffer growing enough to push
                            // scrollback content off the top), but that shows up as scrollOffset
                            // itself changing/clamping, which selectionStart/End tracking elsewhere
                            // already accounts for - it doesn't need this effect to also react to
                            // every bufferVersion bump while scrolled back.
                            LaunchedEffect(state.bufferVersion, activeSessionId) {
                                if (state.scrollOffset == 0 && (selectionStart != null || selectionEnd != null)) {
                                    selectionStart = null
                                    selectionEnd = null
                                }
                            }
                            val effectiveTextSize = liveZoomSize ?: sessionTextSize ?: textSize
                            // The pinch gesture's pointerInput below is keyed only on
                            // activeSessionId (not effectiveTextSize) so it doesn't restart
                            // mid-pinch - rememberUpdatedState lets that long-lived gesture
                            // callback still always compute zoom deltas against the latest
                            // effectiveTextSize instead of a stale value captured once.
                            val latestEffectiveTextSize = rememberUpdatedState(effectiveTextSize)

                            // Debounce state for the IME-animation resize fix below.
                            val coroutineScope = rememberCoroutineScope()
                            var resizeDebounceJob by remember { mutableStateOf<Job?>(null) }
                            var latestTerminalSize by remember { mutableStateOf<IntSize?>(null) }
                            // Tracks device orientation so a rotation can skip the IME
                            // debounce below and resize immediately instead. The 120ms
                            // debounce exists to ignore transient mid-animation sizes
                            // during an IME show/hide - but Compose's Canvas repaints
                            // at the new pixel size the instant the container resizes,
                            // which on rotation happens well before that debounce
                            // fires. In that 120ms window the buffer still holds the
                            // OLD row/column count while the canvas is already
                            // painting at the NEW pixel dimensions, so text lands at
                            // the wrong x/y (spaced for the old char grid, drawn into
                            // the new canvas size) - this is what showed up as
                            // corrupted-looking content on rotation, snapping back to
                            // normal only once the debounce finally caught up.
                            val currentOrientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
                            // Guards applyResize() from running before latestTerminalSize has
                            // ever been set (very first composition, before onSizeChanged has
                            // fired even once) - the LaunchedEffect below fires on first
                            // composition too, when there's nothing meaningful to resize yet.
                            var hasSizedOnce by remember { mutableStateOf(false) }

                            // Same char-metric formula drawTerminal() uses. Real Android sp->px
                            // conversion is `px = sp * density * fontScale` (see Android's
                            // Density docs) - this used to multiply by `density` alone and
                            // silently ignore the user's accessibility font-scale setting.
                            // Whenever that scale isn't exactly 1.0 (a very common
                            // accessibility setting), the column/row count computed here
                            // drifted from what was actually drawn onscreen, which is what
                            // made full-screen apps like nano/vim render misaligned or
                            // garbled - the pty thought it had a different number of
                            // columns/rows than the canvas was really drawing.
                            val density = LocalDensity.current.density
                            val fontScale = LocalDensity.current.fontScale
                            val charMetrics = remember(terminalTypeface, effectiveTextSize, density, fontScale) {
                                val metricsPaint = android.graphics.Paint().apply {
                                    typeface = terminalTypeface
                                    this.textSize = effectiveTextSize * density * fontScale
                                }
                                metricsPaint.measureText("M") to metricsPaint.fontSpacing
                            }

                            // Recomputes cols/rows from the current pixel size and char metrics,
                            // then pushes them to the pty (SIGWINCH). Normally cols stays pinned
                            // to Settings > Appearance > "Terminal width" and only rows auto-fit
                            // to the available height (see the comment below). But while this
                            // session has an active pinch-zoom override (liveZoomSize mid-pinch,
                            // or sessionTextSize once committed), cols is derived from the real
                            // pixel width / charWidth too, so full-screen apps (nano, vim, htop)
                            // actually reflow to match what the user is seeing instead of quietly
                            // keeping the old grid. The moment the override is gone - pinch back
                            // to the global size, or the session closes - cols snaps back to the
                            // fixed setting on the next resize.
                            fun applyResize() {
                                val (charWidth, charHeight) = charMetrics
                                val finalSize = latestTerminalSize
                                if (charWidth <= 0f || charHeight <= 0f || finalSize == null) return
                                val zoomActive = liveZoomSize != null || sessionTextSize != null
                                val cols = if (zoomActive) {
                                    (finalSize.width / charWidth).toInt().coerceAtLeast(1)
                                } else {
                                    columnsSetting.toInt().coerceAtLeast(1)
                                }
                                val rws = (finalSize.height / charHeight).toInt().coerceAtLeast(1)
                                Log.d("SelDebug", "applyResize: finalSize=$finalSize charWidth=$charWidth charHeight=$charHeight zoomActive=$zoomActive -> cols=$cols rws=$rws")
                                viewModel.updateTerminalSize(cols, rws)
                            }

                            // Forces an immediate (non-debounced) resize whenever orientation
                            // actually changes. Previously this was attempted inline inside
                            // onSizeChanged by comparing currentOrientation against a
                            // lastOrientation var, but that comparison's reliability depends on
                            // onSizeChanged firing only after LocalConfiguration.current has
                            // already picked up the rotation - Compose gives no ordering
                            // guarantee between a Configuration change propagating and a
                            // layout's onSizeChanged callback for the resulting size change, so
                            // that comparison could run while currentOrientation still read the
                            // PRE-rotation value, silently falling through to the normal 120ms
                            // debounce path instead. That debounce window is exactly where
                            // Canvas repaints at the new (post-rotation) pixel size while
                            // buffer.rows/columns still hold the pre-rotation grid, which is
                            // what showed up as corrupted/reset-looking content after rotating.
                            // A LaunchedEffect keyed on currentOrientation has no such ordering
                            // ambiguity: Compose guarantees it re-runs exactly when the key's
                            // value actually changes between compositions, decoupled entirely
                            // from onSizeChanged's own firing order.
                            LaunchedEffect(currentOrientation) {
                                if (hasSizedOnce) {
                                    applyResize()
                                }
                            }

                            Column(modifier = Modifier.fillMaxSize()) {
                                // Split-screen (see MainUiState.splitRuntimeId's doc). When
                                // splitRuntimeId is null this block is a complete no-op - the
                                // Box right after it keeps exactly its old weight(1f)/
                                // fillMaxWidth(), single-pane rendering is 100% untouched.
                                val splitRuntimeId = state.splitRuntimeId
                                val primaryWeight = if (splitRuntimeId != null) state.splitRatio else 1f
                                Box(
                                    modifier = Modifier
                                        .weight(primaryWeight)
                                        .fillMaxWidth()
                                        .background(Color.Black)
                                        .onSizeChanged { size: IntSize ->
                                            // imePadding() reports a new size on every frame of
                                            // the IME's show/hide animation (~250-300ms of
                                            // continuously changing intermediate heights), not
                                            // just once at the end. Forwarding every one of
                                            // those straight to updateTerminalSize() meant
                                            // full-screen apps like nano/htop got a burst of
                                            // SIGWINCH + redraw cycles against transient
                                            // mid-animation sizes each time the keyboard opened
                                            // or closed, which is what showed up as garbled
                                            // flicker. Debouncing so only the size that's still
                                            // current after the animation settles gets applied
                                            // fixes that without losing responsiveness for
                                            // "real" size changes (split-screen, etc).
                                            latestTerminalSize = size
                                            hasSizedOnce = true
                                            resizeDebounceJob?.cancel()
                                            // Orientation-triggered resizes are handled separately
                                            // by the LaunchedEffect(currentOrientation) above, which
                                            // fires immediately without this debounce - see its
                                            // comment for why that's a more reliable way to detect
                                            // "this size change is a rotation" than comparing
                                            // orientation values inline here. This path now only
                                            // has to handle the IME-animation case.
                                            resizeDebounceJob = coroutineScope.launch {
                                                delay(120)
                                                applyResize()
                                            }
                                        }
                                        .pointerInput(activeSessionId, softKeyboardEnabled) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)

                                                if (viewModel.activeSessionWantsMouseEvents()) {
                                                    // Mouse reporting owns this entire gesture -
                                                    // press/drag/release all become xterm mouse
                                                    // escape sequences for the running ncurses
                                                    // program (mc, vim, htop, ...) instead of tap-
                                                    // to-toggle-keyboard or pinch/pan, which don't
                                                    // apply while a program has grabbed the mouse.
                                                    down.consume()
                                                    val (charWidth, charHeight) = charMetrics
                                                    if (charWidth <= 0f || charHeight <= 0f) return@awaitEachGesture

                                                    fun cellOf(offset: androidx.compose.ui.geometry.Offset) =
                                                        (offset.x / charWidth).toInt() to (offset.y / charHeight).toInt()

                                                    var (col, row) = cellOf(down.position)
                                                    viewModel.sendMouseEvent(TerminalEmulator.MouseEventKind.PRESS, col, row)

                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        val change = event.changes.firstOrNull() ?: break
                                                        change.consume()
                                                        if (!change.pressed) {
                                                            val (rCol, rRow) = cellOf(change.position)
                                                            viewModel.sendMouseEvent(TerminalEmulator.MouseEventKind.RELEASE, rCol, rRow)
                                                            break
                                                        }
                                                        val (dCol, dRow) = cellOf(change.position)
                                                        if (dCol != col || dRow != row) {
                                                            col = dCol; row = dRow
                                                            viewModel.sendMouseEvent(TerminalEmulator.MouseEventKind.DRAG, col, row)
                                                        }
                                                    }
                                                    return@awaitEachGesture
                                                }

                                                // No mouse reporting active: fall back to the
                                                // previous behavior - tap toggles the soft
                                                // keyboard, drag pinches zoom / pans scrollback.
                                                // Tracked by hand here (instead of the separate
                                                // detectTapGestures/detectTransformGestures calls
                                                // this replaced) so this single gesture loop is
                                                // the only thing reading the touch stream.
                                                var moved = false
                                                var lastPos = down.position
                                                var pointerCount = 1
                                                // Text selection: a long-press with the finger
                                                // still down and (near-)stationary starts it,
                                                // exactly like Android's native text selection.
                                                // Raced against the existing tap/scroll/pinch
                                                // handling below via a per-iteration
                                                // withTimeoutOrNull instead of a second, separate
                                                // pointerInput - this loop is deliberately the
                                                // ONLY thing reading the touch stream (see the
                                                // comment above), so a long-press timeout has to
                                                // live inside it rather than compete with it.
                                                var selecting = false
                                                val longPressDeadline = System.currentTimeMillis() + viewConfiguration.longPressTimeoutMillis
                                                while (true) {
                                                    val remainingMillis = longPressDeadline - System.currentTimeMillis()
                                                    val event = if (!moved && !selecting && remainingMillis > 0) {
                                                        withTimeoutOrNull(remainingMillis) { awaitPointerEvent() }
                                                    } else {
                                                        awaitPointerEvent()
                                                    }

                                                    if (event == null) {
                                                        // Timed out with the finger still down and
                                                        // stationary (no event arrived within the
                                                        // deadline) - that's the long-press.
                                                        selecting = true
                                                        moved = true
                                                        // Starting a text selection shouldn't imply
                                                        // "close the keyboard" - without this, focus
                                                        // silently drops off the hidden IME field the
                                                        // moment the long-press gesture takes over
                                                        // (nothing else claims focus during selection),
                                                        // and Android auto-dismisses the keyboard as
                                                        // soon as the focused field loses focus with
                                                        // nothing requesting it elsewhere. Re-requesting
                                                        // keeps typing available immediately after
                                                        // Copy/Paste/Cancel without the user having to
                                                        // tap the terminal again to bring it back.
                                                        //
                                                        // This is also the one reliable place to record
                                                        // whether the keyboard was open BEFORE the
                                                        // selection toolbar appears - see
                                                        // keyboardWasOpenBeforeSelection's own doc for
                                                        // why the toolbar's Copy/Paste/Cancel callbacks
                                                        // can't just read live keyboardOpen themselves.
                                                        // effectiveKeyboardWasOpen() prefers
                                                        // lastKeyboardIntentOpen (this file's own most
                                                        // recent show()/hide() call) and only falls back
                                                        // to live keyboardOpen when nothing has been
                                                        // called yet - see that function's own doc for
                                                        // why settledKeyboardOpen's 120ms debounce isn't
                                                        // actually needed here (the long-press timeout
                                                        // this branch fires from is already well past it).
                                                        keyboardWasOpenBeforeSelection = effectiveKeyboardWasOpen()
                                                        Log.d("KbDebug", "SELECTION START: keyboardOpen=$keyboardOpen settledKeyboardOpen=$settledKeyboardOpen lastKeyboardIntentOpen=$lastKeyboardIntentOpen hiddenFieldFocused=$hiddenFieldFocused -> captured keyboardWasOpenBeforeSelection=$keyboardWasOpenBeforeSelection")
                                                        if (keyboardWasOpenBeforeSelection) {
                                                            focusRequester.requestFocus()
                                                        }
                                                        val (charWidth, charHeight) = charMetrics
                                                        Log.d("SelDebug", "SELECTION START: touch=(${lastPos.x},${lastPos.y}) charWidth=$charWidth charHeight=$charHeight liveZoomSize=$liveZoomSize sessionTextSize=$sessionTextSize effectiveTextSize=$effectiveTextSize bufferCols=${viewModel.activeBuffer()?.columns} bufferRows=${viewModel.activeBuffer()?.rows}")
                                                        if (charWidth > 0f && charHeight > 0f) {
                                                            val touchedRow = (lastPos.y / charHeight).toInt()
                                                            val touchedCol = (lastPos.x / charWidth).toInt()
                                                            // A long-press very commonly lands on blank
                                                            // terminal space below the actual output -
                                                            // with only a couple of lines printed, most
                                                            // of the screen is empty. Starting the
                                                            // selection exactly where the finger is in
                                                            // that case just selects nothing (and Copy
                                                            // silently does nothing). Snap up to the
                                                            // nearest row above the touch that actually
                                                            // has content, landing on its last real
                                                            // character, so a long-press anywhere below
                                                            // the visible output still selects that
                                                            // output instead of empty space.
                                                            val liveBuffer = viewModel.activeBuffer()
                                                            val snapped = if (liveBuffer != null &&
                                                                liveBuffer.lastNonBlankColumn(touchedRow, state.scrollOffset) == null
                                                            ) {
                                                                val contentRow = (touchedRow downTo 0).firstOrNull { r ->
                                                                    liveBuffer.lastNonBlankColumn(r, state.scrollOffset) != null
                                                                }
                                                                if (contentRow != null) {
                                                                    val lastCol = liveBuffer.lastNonBlankColumn(contentRow, state.scrollOffset)!!
                                                                    contentRow to lastCol
                                                                } else {
                                                                    touchedRow to touchedCol
                                                                }
                                                            } else {
                                                                touchedRow to touchedCol
                                                            }
                                                            selectionStart = snapped
                                                            selectionEnd = snapped
                                                        }
                                                        continue
                                                    }

                                                    val changes = event.changes
                                                    pointerCount = changes.count { it.pressed }
                                                    val primary = changes.firstOrNull { it.id == down.id } ?: changes.firstOrNull()
                                                    if (primary == null || !changes.any { it.pressed }) break

                                                    if (selecting) {
                                                        // Dragging while selecting extends the
                                                        // range instead of scrolling/pinching -
                                                        // exactly one finger is expected here since
                                                        // a second finger joining mid-selection just
                                                        // keeps tracking the original one.
                                                        val (charWidth, charHeight) = charMetrics
                                                        if (charWidth > 0f && charHeight > 0f) {
                                                            val rawCol = (primary.position.x / charWidth).toInt()
                                                            val rawRow = (primary.position.y / charHeight).toInt()
                                                            val current = selectionEnd
                                                            if (current == null) {
                                                                selectionEnd = rawRow to rawCol
                                                            } else {
                                                                val (curRow, curCol) = current
                                                                // Hysteresis band around each cell's
                                                                // boundary: a few natural-tremor pixels
                                                                // (well under one whole cell) sitting
                                                                // right at the edge between two rows/
                                                                // cols used to flip selectionEnd back
                                                                // and forth every frame with a plain
                                                                // floor(position/cellSize) - visually
                                                                // that read as the selection endpoint
                                                                // "falling"/jittering even though the
                                                                // finger barely moved. Only actually
                                                                // moves to a new cell once the touch is
                                                                // solidly inside it (past a margin from
                                                                // the boundary), same idea as how native
                                                                // Android text-selection handles keeps
                                                                // handles from chattering at cell edges.
                                                                val margin = 0.25f
                                                                val rowCenterOffset = (primary.position.y / charHeight) - rawRow
                                                                val colCenterOffset = (primary.position.x / charWidth) - rawCol
                                                                val newRow = when {
                                                                    rawRow == curRow -> curRow
                                                                    rawRow > curRow && rowCenterOffset > margin -> rawRow
                                                                    rawRow < curRow && rowCenterOffset < (1f - margin) -> rawRow
                                                                    else -> curRow
                                                                }
                                                                val newCol = when {
                                                                    rawCol == curCol -> curCol
                                                                    rawCol > curCol && colCenterOffset > margin -> rawCol
                                                                    rawCol < curCol && colCenterOffset < (1f - margin) -> rawCol
                                                                    else -> curCol
                                                                }
                                                                selectionEnd = newRow to newCol
                                                            }
                                                        }
                                                        // Edge auto-scroll: holding the finger near the
                                                        // top/bottom of the terminal while selecting
                                                        // scrolls scrollback into view in that direction,
                                                        // same as native Android text selection does at
                                                        // the edge of a scrollable text view. Without
                                                        // this, a selection drag had no way to reach any
                                                        // content above/below what happened to already be
                                                        // on screen when the long-press started - the
                                                        // `if (selecting) { ... continue }` branch here
                                                        // returns before ever reaching the plain-drag
                                                        // scroll logic further down, so selecting and
                                                        // scrolling were mutually exclusive.
                                                        val viewportHeight = latestTerminalSize?.height?.toFloat()
                                                        if (viewportHeight != null && viewportHeight > 0f &&
                                                            !viewModel.activeSessionInAlternateScreen()
                                                        ) {
                                                            val edgeZone = (viewportHeight * 0.15f).coerceAtMost(charHeight * 3f)
                                                            val distanceFromTop = primary.position.y
                                                            val distanceFromBottom = viewportHeight - primary.position.y
                                                            // scrollOffset=0 is the live/newest screen;
                                                            // scrollOffset>0 is N lines back into history
                                                            // (see TerminalBuffer.lineAt's doc). Dragging
                                                            // toward the TOP edge means "let me keep
                                                            // selecting upward, past what's currently on
                                                            // screen" - i.e. reveal OLDER content, which
                                                            // means scrollOffset must INCREASE. Dragging
                                                            // toward the BOTTOM edge is the opposite: reveal
                                                            // content toward the live end, so scrollOffset
                                                            // must DECREASE. This was backwards before (top
                                                            // edge decreased it, bottom edge increased it),
                                                            // which is what made the selection appear to
                                                            // jump backward/upward while dragging down: the
                                                            // view itself was scrolling toward history
                                                            // instead of toward the live screen, opposite to
                                                            // the finger's own direction.
                                                            val scrollLines = when {
                                                                distanceFromTop < edgeZone && charHeight > 0f -> {
                                                                    // Nearer the edge -> faster scroll, same
                                                                    // proportional-speed idea as native
                                                                    // Android edge-scroll-while-selecting.
                                                                    val proximity = 1f - (distanceFromTop / edgeZone).coerceIn(0f, 1f)
                                                                    0.3f + proximity * 0.9f
                                                                }
                                                                distanceFromBottom < edgeZone && charHeight > 0f -> {
                                                                    val proximity = 1f - (distanceFromBottom / edgeZone).coerceIn(0f, 1f)
                                                                    -(0.3f + proximity * 0.9f)
                                                                }
                                                                else -> 0f
                                                            }
                                                            if (scrollLines != 0f) {
                                                                // adjustScrollOffset returns the actual
                                                                // applied delta (clamped to the buffer's
                                                                // bounds, so it can be 0 even when
                                                                // scrollLines isn't, e.g. already at the
                                                                // top/bottom of scrollback).
                                                                //
                                                                // Only selectionStart gets shifted here,
                                                                // not selectionEnd. selectionStart was
                                                                // anchored in an earlier frame (when the
                                                                // long-press first landed) against
                                                                // whatever scrollOffset was active then,
                                                                // so it does need correcting when
                                                                // scrollOffset moves out from under it -
                                                                // same reasoning as the plain-scroll
                                                                // branch below. selectionEnd is different:
                                                                // it's freshly recomputed THIS SAME frame,
                                                                // just above, straight from the finger's
                                                                // raw position divided by the current
                                                                // charHeight - it's already correct for
                                                                // the current scrollOffset. Shifting it
                                                                // again on top of that double-counted the
                                                                // scroll, so every frame the edge-scroll
                                                                // fired while the finger held still near
                                                                // the edge, the highlight's far end kept
                                                                // climbing away on its own - the finger
                                                                // wasn't moving, but the stored row kept
                                                                // incrementing anyway.
                                                                val appliedDelta = viewModel.adjustScrollOffset(scrollLines)
                                                                if (appliedDelta != 0) {
                                                                    selectionStart = selectionStart?.let { (r, c) -> (r + appliedDelta) to c }
                                                                }
                                                            }
                                                        }
                                                        primary.consume()
                                                        lastPos = primary.position
                                                        continue
                                                    }

                                                    if (pointerCount >= 2 && zoomEnabled) {
                                                        // Pinch: two (or more) fingers down -
                                                        // compute zoom from the ratio of current
                                                        // to previous distance between the first
                                                        // two pointers. Gated on Settings >
                                                        // Appearance > "Pinch to zoom" - when off,
                                                        // this whole branch is skipped so a second
                                                        // finger touching down doesn't resize text,
                                                        // it just falls through untouched.
                                                        val p1 = changes.getOrNull(0)
                                                        val p2 = changes.getOrNull(1)
                                                        if (p1 != null && p2 != null) {
                                                            val prevDist = (p1.previousPosition - p2.previousPosition).getDistance()
                                                            val curDist = (p1.position - p2.position).getDistance()
                                                            if (prevDist > 0f) {
                                                                val zoom = curDist / prevDist
                                                                if (zoom != 1f && activeSessionId != null) {
                                                                    val newSize = (latestEffectiveTextSize.value * zoom)
                                                                        .coerceIn(8f, 40f)
                                                                    // Anchor the pinch to the midpoint between
                                                                    // the two fingers, not to row 0 / the top
                                                                    // of the viewport. Without this, resizing
                                                                    // the grid (below) always keeps row 0
                                                                    // pinned and grows/shrinks everything
                                                                    // downward from there - so content under
                                                                    // the fingers visibly drifted upward out
                                                                    // from under them as soon as the finger
                                                                    // midpoint wasn't already at the very top
                                                                    // of the screen (the common case). Convert
                                                                    // the midpoint's CURRENT pixel-y into a
                                                                    // row using the OLD charHeight, so we know
                                                                    // which row the fingers are actually over
                                                                    // before anything changes size.
                                                                    val (_, oldCharHeight) = charMetrics
                                                                    val midY = (p1.position.y + p2.position.y) / 2f
                                                                    val anchorRow = if (oldCharHeight > 0f) (midY / oldCharHeight).toInt() else 0
                                                                    liveZoomSize = newSize
                                                                    zoomCommitJob?.cancel()
                                                                    zoomCommitJob = coroutineScope.launch {
                                                                        delay(150)
                                                                        viewModel.setSessionTextSize(activeSessionId, newSize)
                                                                        liveZoomSize = null
                                                                        // sessionTextSize (the state
                                                                        // read at the top of this
                                                                        // composable) won't reflect
                                                                        // the value just committed
                                                                        // above until the next
                                                                        // recomposition, so
                                                                        // applyResize() here would
                                                                        // still see zoomActive as
                                                                        // false on a *first* pinch.
                                                                        // Recompute charMetrics
                                                                        // against newSize directly
                                                                        // instead of relying on that
                                                                        // stale closure.
                                                                        val metricsPaint = android.graphics.Paint().apply {
                                                                            typeface = terminalTypeface
                                                                            this.textSize = newSize * density * fontScale
                                                                        }
                                                                        val charWidth = metricsPaint.measureText("M")
                                                                        val newCharHeight = metricsPaint.fontSpacing
                                                                        val finalSize = latestTerminalSize
                                                                        if (charWidth > 0f && newCharHeight > 0f && finalSize != null) {
                                                                            val cols = (finalSize.width / charWidth).toInt().coerceAtLeast(1)
                                                                            val rws = (finalSize.height / newCharHeight).toInt().coerceAtLeast(1)
                                                                            Log.d("SelDebug", "zoom-commit resize: finalSize=$finalSize charWidth=$charWidth charHeight=$newCharHeight newSize=$newSize -> cols=$cols rws=$rws")
                                                                            viewModel.updateTerminalSize(cols, rws)
                                                                            // Re-anchor: the row the fingers were
                                                                            // over (anchorRow, in OLD char-height
                                                                            // units) should land at the same pixel
                                                                            // midY again, now measured in the NEW
                                                                            // char-height. The gap between where
                                                                            // that row naturally falls post-resize
                                                                            // and where it needs to be is what
                                                                            // scrollOffset makes up - same unit
                                                                            // (whole lines) adjustScrollOffset
                                                                            // already expects.
                                                                            val desiredRowAtMidY = (midY / newCharHeight)
                                                                            val anchorDelta = (desiredRowAtMidY - anchorRow)
                                                                            if (kotlin.math.abs(anchorDelta) >= 1f) {
                                                                                viewModel.adjustScrollOffset(-anchorDelta)
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        moved = true
                                                        changes.forEach { it.consume() }
                                                    } else {
                                                        // Compares TOTAL displacement from the initial
                                                        // press (down.position), not this frame's delta
                                                        // against lastPos - and against the platform's
                                                        // real touch slop instead of a fixed 2px. A
                                                        // per-frame-delta/2px check treated the very
                                                        // first bit of natural finger tremor during a
                                                        // "hold still for a long-press" gesture as an
                                                        // intentional scroll, setting moved=true and
                                                        // permanently blocking the long-press timeout
                                                        // branch above from ever firing - which is why
                                                        // long-press-to-select never actually started.
                                                        val totalDx = primary.position.x - down.position.x
                                                        val totalDy = primary.position.y - down.position.y
                                                        val totalDistance = kotlin.math.sqrt(totalDx * totalDx + totalDy * totalDy)
                                                        if (totalDistance > viewConfiguration.touchSlop) {
                                                            moved = true
                                                            val dy = primary.position.y - lastPos.y
                                                            if (!viewModel.activeSessionInAlternateScreen()) {
                                                                val (_, charHeight) = charMetrics
                                                                if (charHeight > 0f) {
                                                                    // Same reasoning as the selecting-drag
                                                                    // edge-autoscroll case above: a finished
                                                                    // selection (drag already lifted, still
                                                                    // shown while the user decides whether to
                                                                    // tap Copy) is a plain, unrelated scroll
                                                                    // drag away from having its stored rows
                                                                    // silently point at different content -
                                                                    // nothing about this branch requires the
                                                                    // selection to be gone, only that this
                                                                    // particular drag isn't extending it.
                                                                    val appliedDelta = viewModel.adjustScrollOffset(dy / charHeight)
                                                                    if (appliedDelta != 0) {
                                                                        selectionStart = selectionStart?.let { (r, c) -> (r + appliedDelta) to c }
                                                                        selectionEnd = selectionEnd?.let { (r, c) -> (r + appliedDelta) to c }
                                                                    }
                                                                }
                                                            }
                                                            primary.consume()
                                                        }
                                                        lastPos = primary.position
                                                    }
                                                }

                                                if (!selecting) {
                                                    if (selectionStart != null) {
                                                        selectionStart = null
                                                        selectionEnd = null
                                                    } else if (!moved && softKeyboardEnabled) {
                                                        // Was keyboardController?.hide()/show() (the
                                                        // Compose IME abstraction) - left over from
                                                        // before the toolbar's Copy/Paste/Cancel
                                                        // handlers were switched to
                                                        // WindowInsetsControllerCompat below, for
                                                        // exactly the same reason documented at their
                                                        // call sites: keyboardController's show()/
                                                        // hide() is unreliable here specifically,
                                                        // producing the open-keyboard-closes/closed-
                                                        // keyboard-opens-then-a-moment-later-flips-back
                                                        // behavior. This plain tap-to-toggle path never
                                                        // got migrated when the toolbar paths were, so
                                                        // a tap on the terminal right after a Copy/
                                                        // Paste/Cancel restore could still hit this
                                                        // unreliable API and re-trigger the same bug a
                                                        // moment later. Using insetsController here too
                                                        // makes every keyboard show/hide in this file go
                                                        // through the one API that's actually reliable.
                                                        // Reads keyboardOpen (the live, per-frame inset
                                                        // read) rather than effectiveKeyboardWasOpen()/
                                                        // settledKeyboardOpen deliberately: a plain tap is
                                                        // a direct toggle of whatever's on screen RIGHT
                                                        // NOW, not a "restore what it was before some
                                                        // other action" decision like Copy/Paste/Cancel's
                                                        // handlers make - those need the debounced/intent
                                                        // reads specifically because they're restoring a
                                                        // PRIOR state after an intervening selection, a
                                                        // case that doesn't apply here.
                                                        if (keyboardOpen) {
                                                            focusManager.clearFocus()
                                                            insetsController.hide(WindowInsetsCompat.Type.ime())
                                                            lastKeyboardIntentOpen = false
                                                        } else {
                                                            focusRequester.requestFocus()
                                                            currentView.post { insetsController.show(WindowInsetsCompat.Type.ime()) }
                                                            lastKeyboardIntentOpen = true
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                ) {
                                    // Optional wallpaper behind the terminal - flat, dimmed by
                                    // the blur/alpha slider so text always stays readable.
                                    if (wallpaperUriStr.isNotBlank()) {
                                        val context = LocalContext.current
                                        var bitmap by remember(wallpaperUriStr) {
                                            mutableStateOf<android.graphics.Bitmap?>(null)
                                        }
                                        LaunchedEffect(wallpaperUriStr) {
                                            bitmap = runCatching {
                                                context.contentResolver
                                                    .openInputStream(Uri.parse(wallpaperUriStr))
                                                    ?.use { BitmapFactory.decodeStream(it) }
                                            }.getOrNull()
                                        }
                                        bitmap?.let {
                                            Image(
                                                bitmap = it.asImageBitmap(),
                                                contentDescription = null,
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .alpha(1f - blurAlpha)
                                                    // Real blur (Settings > Appearance >
                                                    // "Background blur"), independent of
                                                    // alpha above - a 0f value is a no-op
                                                    // blur radius rather than skipping the
                                                    // modifier, since RenderEffect.blur
                                                    // requires a positive radius on some
                                                    // API levels.
                                                    .then(
                                                        if (backgroundBlur > 0f) {
                                                            Modifier.blur(radius = (backgroundBlur * 25).dp)
                                                        } else {
                                                            Modifier
                                                        }
                                                    )
                                            )
                                        }
                                    }

                                    if (buffer != null) {
                                        TerminalView(
                                            buffer = buffer,
                                            palette = terminalPalette,
                                            fontFamily = terminalTypeface,
                                            fontSizeSp = effectiveTextSize,
                                            bufferVersion = state.bufferVersion,
                                            // Only let the terminal's own background go
                                            // translucent when there's actually a wallpaper
                                            // behind it - otherwise stay fully opaque as before.
                                            backgroundAlpha = if (wallpaperUriStr.isNotBlank()) blurAlpha else 1f,
                                            scrollOffset = state.scrollOffset,
                                            selectionStart = selectionStart,
                                            selectionEnd = selectionEnd,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    // Subtle flat black scrim so the terminal always reads as
                                    // pure black/flat regardless of wallpaper or buffer content.
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.12f))
                                    )

                                    if (selectionStart != null && selectionEnd != null) {
                                        // Anchored near the selection itself - normally just
                                        // above its topmost row - rather than always pinned to
                                        // the very top of the screen regardless of where the
                                        // selection actually is. A selection made low on screen
                                        // (the common case: selecting recent output, which is
                                        // near the bottom) used to put the toolbar far away from
                                        // what was just selected. Falls back to below the
                                        // selection when it starts too close to the top for the
                                        // toolbar to fit above it.
                                        //
                                        // Neither branch used to be clamped against this Box's
                                        // own bounds, only computed from the selection's row -
                                        // so a selection near row 0 (aboveY negative, falling
                                        // through to the below-selection branch right at the
                                        // top) could still land close enough to 0 to read as
                                        // sitting under the titlebar above this Box, and a
                                        // selection near the bottom of a tall/scrolled buffer
                                        // could compute a y taller than the Box itself, pushing
                                        // the toolbar down into the VirtualKeyBar/soft-keyboard
                                        // area below - there was no barrier keeping either edge
                                        // in bounds. Clamping y into
                                        // [0, boxHeight - toolbarHeight - margin] using the same
                                        // measured size the resize logic above already tracks
                                        // (latestTerminalSize) keeps the toolbar fully inside
                                        // the terminal's own area no matter where the selection
                                        // sits.
                                        val (charWidth, charHeight) = charMetrics
                                        val localDensity = LocalDensity.current
                                        val toolbarOffset = if (charHeight > 0f) {
                                            val (r1, _) = selectionStart!!
                                            val (r2, _) = selectionEnd!!
                                            val topRow = minOf(r1, r2)
                                            val toolbarHeightPx = with(localDensity) { 44.dp.toPx() }
                                            val margin = with(localDensity) { 8.dp.toPx() }
                                            val boxHeightPx = latestTerminalSize?.height?.toFloat()
                                            val aboveY = topRow * charHeight - toolbarHeightPx - margin
                                            // Was: aboveY < 0 (not enough room above the
                                            // selection's top row) always fell through to
                                            // placing the toolbar BELOW the selection instead.
                                            // That's fine while dragging top-to-bottom (topRow
                                            // only grows, so this branch is only ever hit once,
                                            // right at the very start), but dragging
                                            // bottom-to-top makes topRow shrink every frame as
                                            // the selection grows upward - and topRow can't go
                                            // negative, so once it hit the screen's first
                                            // visible row it stayed there while the drag kept
                                            // going, meaning aboveY stayed negative and the
                                            // toolbar kept re-committing to the below-selection
                                            // branch every single frame instead of ever
                                            // switching back above. That's what read as the
                                            // toolbar "refusing" to move up and sitting stuck
                                            // below. Clamping aboveY to 0 (pinning the toolbar
                                            // to the very top of the terminal's own area) rather
                                            // than falling back below fixes that: with less than
                                            // a full toolbar's height of room above the
                                            // selection, sitting flush against the top edge is
                                            // still "above" in the way that matters (out of the
                                            // way of the text being selected), and keeps
                                            // tracking topRow the same way the below-selection
                                            // branch already did before this fix, instead of
                                            // jumping to a different anchor (the selection's
                                            // bottom) that isn't moving the same way the drag is.
                                            val rawY = if (boxHeightPx != null && boxHeightPx > 0f &&
                                                aboveY < 0f && (topRow * charHeight) > boxHeightPx / 2f
                                            ) {
                                                // Selection's top row is in the LOWER half of the
                                                // screen but still too close to some other edge
                                                // for the toolbar to fit above it (e.g. a very
                                                // short selection right under the titlebar isn't
                                                // this case - topRow*charHeight here is checked
                                                // against the box's own vertical center, not 0,
                                                // specifically so a selection near row 0 doesn't
                                                // land here) - genuinely better placed below.
                                                val bottomRow = maxOf(r1, r2)
                                                (bottomRow + 1) * charHeight + margin
                                            } else {
                                                aboveY.coerceAtLeast(0f)
                                            }
                                            val y = if (boxHeightPx != null && boxHeightPx > 0f) {
                                                rawY.coerceIn(0f, (boxHeightPx - toolbarHeightPx - margin).coerceAtLeast(0f))
                                            } else {
                                                rawY.coerceAtLeast(0f)
                                            }
                                            IntOffset(0, y.roundToInt())
                                        } else {
                                            IntOffset.Zero
                                        }
                                        // Animates toward the target position instead of
                                        // snapping there instantly - a selection dragged
                                        // upward (bottom-to-top) moves topRow every frame,
                                        // which used to move the toolbar in an instant jump
                                        // each time rather than a smooth follow. Explicit short
                                        // tween (not the ~300ms spring default) because the
                                        // default duration was itself the problem reported
                                        // after adding this: the toolbar visibly lagged behind
                                        // and sat in its old (lower) position for a beat right
                                        // as an upward drag finished, before catching up to
                                        // where the selection actually ended. 80ms is fast
                                        // enough to read as "keeping up with your finger"
                                        // rather than "animating to a static target" while
                                        // still smoothing out the per-frame jumps that made the
                                        // original instant-snap version feel jerky.
                                        val animatedToolbarOffset by animateIntOffsetAsState(
                                            targetValue = toolbarOffset,
                                            animationSpec = tween(durationMillis = 80),
                                            label = "selectionToolbarOffset"
                                        )
                                        SelectionToolbar(
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .offset { animatedToolbarOffset },
                                            onCopy = {
                                                Log.d("KbDebug", "onCopy fired: keyboardOpen=$keyboardOpen keyboardWasOpenBeforeSelection=$keyboardWasOpenBeforeSelection")
                                                val (r1, c1) = selectionStart!!
                                                val (r2, c2) = selectionEnd!!
                                                Log.d("SelDebug", "onCopy: raw selectionStart=($r1,$c1) selectionEnd=($r2,$c2) scrollOffset=${state.scrollOffset}")
                                                val text = buffer?.selectedText(r1, c1, r2, c2, state.scrollOffset).orEmpty()
                                                Log.d("SelDebug", "onCopy: copied text=[$text] length=${text.length}")
                                                if (text.isNotEmpty()) {
                                                    clipboardManager.setText(AnnotatedString(text))
                                                }
                                                selectionStart = null
                                                selectionEnd = null
                                                // Tapping a toolbar button steals focus away from
                                                // the hidden input field, which drops
                                                // hiddenFieldFocused (and therefore keyboardOpen)
                                                // to false - the soft keyboard would otherwise
                                                // close itself right along with dismissing the
                                                // selection, forcing the user to tap the terminal
                                                // again just to keep typing after a Copy/Paste/
                                                // Cancel. Restoring it here (only when the keyboard
                                                // was actually open BEFORE the toolbar appeared -
                                                // keyboardWasOpenBeforeSelection, not the live
                                                // keyboardOpen this button's own tap just raced
                                                // against and possibly already flipped) brings it
                                                // back immediately instead, and - just as
                                                // importantly - does nothing when the keyboard was
                                                // already closed, so tapping Copy/Paste/Cancel can't
                                                // spuriously pop the keyboard open on its own.
                                                //
                                                // requestFocus() alone is not reliable here: once
                                                // the IME has genuinely finished hiding (which the
                                                // toolbar's own focus-stealing tap can trigger),
                                                // Compose focus moving back to a field does not
                                                // reliably resurface it again - this is a
                                                // well-documented Compose/IME gap, not specific to
                                                // this field. Pairing the focus request with an
                                                // explicit keyboardController.show() call is the
                                                // documented workaround, and pairing hide() with a
                                                // clearFocus() on the "was already closed" branch
                                                // closes the other half of the same gap: without
                                                // it, this field could still end up focused (it's
                                                // the only focusable target once the toolbar's own
                                                // buttons are gone) with nothing having told the
                                                // system to actually show its keyboard - a state
                                                // Android can resolve either way depending on
                                                // what still holds an active input connection,
                                                // which is what made "closed -> tap Copy/Paste/
                                                // Cancel -> opens anyway" intermittent instead of
                                                // consistently one behavior or the other.
                                                //
                                                // requestFocus() itself already fires its own IME
                                                // show request the instant focus lands (visible in
                                                // logcat as onRequestShow ... SHOW_SOFT_INPUT_BY_
                                                // INSETS_API). Calling keyboardController.show()
                                                // synchronously right after, in the same callback,
                                                // fires a SECOND, independent show request
                                                // (SHOW_SOFT_INPUT) before the first one has been
                                                // dispatched - the platform then has two competing
                                                // in-flight IME animation requests and cancels one
                                                // against the other (onCancelled at
                                                // PHASE_CLIENT_APPLY_ANIMATION / PHASE_CLIENT_
                                                // ANIMATION_CANCEL), which is what actually produced
                                                // the "sometimes shows, sometimes doesn't" behavior -
                                                // not stale focus state. Moving the show() into its
                                                // own post-frame callback lets the first (focus-
                                                // driven) request be dispatched and let the
                                                // animation system settle before the explicit show()
                                                // fires, so the second call reinforces the first
                                                // instead of racing it.
                                                if (keyboardWasOpenBeforeSelection) {
                                                    focusRequester.requestFocus()
                                                    // Deferred via view.post: requestFocus() above
                                                    // already fires its own IME show request the
                                                    // instant focus lands. Calling show() here
                                                    // synchronously in the same callback used to fire
                                                    // a second, independent show request before the
                                                    // first was dispatched, and the platform would
                                                    // cancel one against the other - see this
                                                    // function's own doc above for the full story.
                                                    // Posting this call lets the focus-driven request
                                                    // be dispatched and the animation settle first, so
                                                    // this one reinforces it instead of racing it.
                                                    currentView.post { insetsController.show(WindowInsetsCompat.Type.ime()) }
                                                    lastKeyboardIntentOpen = true
                                                } else {
                                                    focusManager.clearFocus()
                                                    insetsController.hide(WindowInsetsCompat.Type.ime())
                                                    lastKeyboardIntentOpen = false
                                                }
                                            },
                                            onPaste = {
                                                Log.d("KbDebug", "onPaste fired: keyboardOpen=$keyboardOpen keyboardWasOpenBeforeSelection=$keyboardWasOpenBeforeSelection")
                                                clipboardManager.getText()?.text?.let { pasted ->
                                                    if (pasted.isNotEmpty()) {
                                                        // Same CR/LF fixup applied to normal IME
                                                        // typing above (see that comment): real
                                                        // terminals send CR (\r, 0x0D) for a line
                                                        // break, not the LF (\n, 0x0A) a copied
                                                        // multi-line selection naturally contains.
                                                        // Forwarding raw \n bytes meant every line
                                                        // of a pasted multi-line selection got
                                                        // interpreted by the shell as if Enter had
                                                        // been pressed after it - i.e. each line
                                                        // immediately executed as its own command
                                                        // instead of the whole paste landing as
                                                        // inert text the user could still edit
                                                        // before running anything. A single-line
                                                        // paste with no trailing newline is
                                                        // unaffected either way.
                                                        viewModel.sendInput(pasted.replace('\n', '\r'))
                                                    }
                                                }
                                                selectionStart = null
                                                selectionEnd = null
                                                // See onCopy's comment above for why this reads
                                                // keyboardWasOpenBeforeSelection rather than the
                                                // live keyboardOpen, and defers show() to the next
                                                // frame rather than calling it synchronously right
                                                // after requestFocus().
                                                if (keyboardWasOpenBeforeSelection) {
                                                    focusRequester.requestFocus()
                                                    // See onCopy's comment above for why this is
                                                    // posted rather than called synchronously here.
                                                    currentView.post { insetsController.show(WindowInsetsCompat.Type.ime()) }
                                                    lastKeyboardIntentOpen = true
                                                } else {
                                                    focusManager.clearFocus()
                                                    insetsController.hide(WindowInsetsCompat.Type.ime())
                                                    lastKeyboardIntentOpen = false
                                                }
                                            },
                                            onCancel = {
                                                Log.d("KbDebug", "onCancel fired: keyboardOpen=$keyboardOpen keyboardWasOpenBeforeSelection=$keyboardWasOpenBeforeSelection")
                                                selectionStart = null
                                                selectionEnd = null
                                                // See onCopy's comment above for why this reads
                                                // keyboardWasOpenBeforeSelection rather than the
                                                // live keyboardOpen, and defers show() to the next
                                                // frame rather than calling it synchronously right
                                                // after requestFocus().
                                                if (keyboardWasOpenBeforeSelection) {
                                                    focusRequester.requestFocus()
                                                    // See onCopy's comment above for why this is
                                                    // posted rather than called synchronously here.
                                                    currentView.post { insetsController.show(WindowInsetsCompat.Type.ime()) }
                                                    lastKeyboardIntentOpen = true
                                                } else {
                                                    focusManager.clearFocus()
                                                    insetsController.hide(WindowInsetsCompat.Type.ime())
                                                    lastKeyboardIntentOpen = false
                                                }
                                            },
                                            // Fast clone: no popup, unlike the titlebar "+"
                                            // (QuickAddSessionPickerDialog) - see
                                            // SelectionToolbar's onCloneClicked doc.
                                            onCloneClicked = { viewModel.duplicateActiveSession() },
                                            // Always available - exports the active session's
                                            // full terminal output (screen + scrollback), not
                                            // gated behind any toggle since there's no persisted
                                            // log to opt in to anymore.
                                            onSaveHistoryClicked = {
                                                terminalExportLauncher.launch("terminal-output.txt")
                                            }
                                        )
                                    }

                                    // Invisible field that actually captures IME input and
                                    // forwards it to the active session, one keystroke at a time.
                                    // Was persisted by KeyboardSettingsScreen but never read, so
                                    // every mode behaved identically to "Default" no matter what
                                    // was picked. Per the descriptions shown in that screen:
                                    //  - Default: full IME compatibility, autocorrect/suggestions
                                    //    and composing (CJK etc.) all left on.
                                    //  - Strict Terminal: "semantically correct but may break some
                                    //    IMEs" - restrict to a raw ASCII keyboard type with
                                    //    autocorrect off, so every keystroke commits immediately
                                    //    as its literal byte instead of going through a composing
                                    //    region (composing IMEs, e.g. CJK, fall back or don't work).
                                    //  - Legacy Workaround: "fixes Samsung keyboard echo" - many
                                    //    Samsung keyboards double-emit characters when autocorrect
                                    //    and predictive text are active alongside this field's
                                    //    per-keystroke reset; turning autocorrect off (while
                                    //    keeping the normal Text keyboard type, unlike Strict) is
                                    //    the actual fix without going as far as blocking CJK.
                                    val fieldKeyboardOptions = remember(inputMode) {
                                        when (inputMode) {
                                            "Strict" -> KeyboardOptions(
                                                keyboardType = KeyboardType.Ascii,
                                                capitalization = KeyboardCapitalization.None,
                                                autoCorrect = false
                                            )
                                            "Legacy" -> KeyboardOptions(
                                                keyboardType = KeyboardType.Text,
                                                capitalization = KeyboardCapitalization.None,
                                                autoCorrect = false
                                            )
                                            // Default used to leave autoCorrect on here, which is
                                            // exactly the ingredient "Legacy Workaround" above
                                            // turns off to stop Samsung keyboards from double-
                                            // emitting characters - the underlying collision
                                            // (autocorrect/predictive-text machinery reacting to
                                            // this field being hard-reset every keystroke) isn't
                                            // Samsung-specific. On Gboard specifically it showed up
                                            // as a different symptom instead of duplication: typing
                                            // digits made Gboard's numeric-suggestion strip silently
                                            // fall back to the normal text layout, since predictive
                                            // text is what drives that strip and this field's resets
                                            // were confusing its state. Turning autoCorrect off by
                                            // default removes the same root cause for every IME
                                            // rather than only being available as an opt-in
                                            // workaround for the one symptom (duplication) that
                                            // prompted "Legacy Workaround" to exist in the first
                                            // place.
                                            else -> KeyboardOptions(
                                                keyboardType = KeyboardType.Text,
                                                autoCorrect = false
                                            )
                                        }
                                    }
                                    BasicTextField(
                                        value = hiddenInput,
                                        keyboardOptions = fieldKeyboardOptions,
                                        onValueChange = { new ->
                                            val newText = new.text
                                            when {
                                                newText.length > consumedBaseline.length &&
                                                    newText.startsWith(consumedBaseline) -> {
                                                    // Normal typing: everything after the already-
                                                    // consumed baseline is what the IME just inserted.
                                                    // Apply any armed CTRL/ALT modifier, then consume it
                                                    // (one-shot).
                                                    val typed = newText.substring(consumedBaseline.length)
                                                    var toSend = typed
                                                    // Real terminals send CR (\r, 0x0D) for the Enter
                                                    // key, not LF (\n, 0x0A) - the pty/shell's own line
                                                    // discipline (icrnl) is what turns that \r into a
                                                    // newline for canonical-mode reads. The IME composes
                                                    // Enter as a literal '\n' in the text field, and that
                                                    // was being forwarded byte-for-byte. Full-screen apps
                                                    // read raw, unprocessed input though, so they see the
                                                    // literal 0x0A - and nano's default keybinding for
                                                    // ^J (0x0A) is "Justify Paragraph", not "insert
                                                    // newline". That's exactly what made Enter in nano
                                                    // justify the paragraph instead of adding a line.
                                                    toSend = toSend.replace('\n', '\r')
                                                    if (ctrlActive) {
                                                        toSend = toSend.map(::applyCtrl).joinToString("")
                                                        ctrlActive = false
                                                    }
                                                    if (altActive) {
                                                        toSend = "\u001B$toSend"
                                                        altActive = false
                                                    }
                                                    // The active session's process may already be dead
                                                    // (typically right after Ctrl+D's hard kill below) -
                                                    // its last screen just sits there frozen since there's
                                                    // no process left to read input. Pressing Enter against
                                                    // that frozen screen dismisses it instead of silently
                                                    // writing into a closed pty: move to the next running
                                                    // session if one exists, or close the app if this was
                                                    // the last one.
                                                    val activeIsExited = state.runningSessions
                                                        .firstOrNull { it.runtimeId == state.activeSessionId }
                                                        ?.exited == true
                                                    if (activeIsExited && toSend.contains('\r')) {
                                                        val shouldExitApp = viewModel.dismissExitedActiveSession()
                                                        if (shouldExitApp) {
                                                            finish()
                                                        }
                                                    } else if (toSend == "\u0004") {
                                                        // Ctrl+D: killActiveSessionHard() only
                                                        // force-kills when the shell itself is in the
                                                        // foreground (no other program running); it
                                                        // sends a plain EOT into the pty instead when
                                                        // something else has the foreground - see
                                                        // TerminalSession.sendCtrlDOrKill.
                                                        viewModel.killActiveSessionHard()
                                                    } else {
                                                        viewModel.sendInput(toSend)
                                                    }
                                                    // Advance the baseline to the full text we just
                                                    // consumed rather than collapsing back down to the
                                                    // bare placeholder - see consumedBaseline's doc for
                                                    // why this (a pure append) is what keeps the IME
                                                    // from seeing a content change and calling
                                                    // restartInput on every keystroke.
                                                    consumedBaseline = newText
                                                    // Growth cap: an unbounded append would still be a
                                                    // real (if rare) memory/perf concern over a very
                                                    // long typing burst. Only trims once genuinely
                                                    // outside any composing region (new.composition ==
                                                    // null) so this can't cut a word being actively
                                                    // composed in half - it'll trim on the next
                                                    // keystroke after composition ends instead, same as
                                                    // it would after any other pure-append edit.
                                                    if (newText.length > 256 && new.composition == null) {
                                                        consumedBaseline = inputPlaceholder
                                                    }
                                                }
                                                newText.length <= consumedBaseline.length -> {
                                                    // The baseline itself got shortened/removed - that's
                                                    // a real backspace, so forward DEL. Handles
                                                    // multi-character deletes (e.g. predictive text
                                                    // clearing a word) by sending one DEL per missing
                                                    // char. This is a genuine content shrink, so letting
                                                    // it fall through to the real reset below (rather
                                                    // than trying to preserve any of newText) is correct
                                                    // here - there's nothing worth keeping past a
                                                    // backspace.
                                                    val removedCount =
                                                        (consumedBaseline.length - newText.length).coerceAtLeast(1)
                                                    viewModel.sendInput("\u007F".repeat(removedCount))
                                                    consumedBaseline = inputPlaceholder
                                                }
                                                else -> {
                                                    // Unexpected replacement (e.g. autocorrect swapped
                                                    // the whole field) - forward it as-is rather than
                                                    // silently dropping it.
                                                    viewModel.sendInput(newText)
                                                    consumedBaseline = inputPlaceholder
                                                }
                                            }
                                            // Only the backspace-to-nothing and unexpected-replacement
                                            // branches above reset consumedBaseline back down to the
                                            // bare placeholder - both are already genuine content
                                            // changes forced by the IME itself, so resetting alongside
                                            // them doesn't cost an *extra* restartInput beyond the one
                                            // that edit was already going to cause. Normal typing
                                            // (the common case, and the one numeric mode/composing
                                            // state actually depends on staying alive) no longer
                                            // resets at all here - see the growth-cap effect below for
                                            // the only other place a reset can happen.
                                            hiddenInput = new.copy(
                                                text = if (consumedBaseline == inputPlaceholder) inputPlaceholder else newText,
                                                selection = if (consumedBaseline == inputPlaceholder)
                                                    TextRange(inputPlaceholder.length) else new.selection
                                            )
                                        },
                                        modifier = Modifier
                                            .size(1.dp)
                                            .alpha(0f)
                                            .focusRequester(focusRequester)
                                            .onFocusChanged {
                                                hiddenFieldFocused = it.isFocused
                                                Log.d("KbDebug", "hiddenField onFocusChanged: isFocused=${it.isFocused}")
                                                // Collapsing back to the bare placeholder on focus
                                                // regain (rather than after every keystroke) is a
                                                // natural, infrequent point for the one content reset
                                                // this field still needs periodically - the field was
                                                // just unfocused, so there's no in-progress composition
                                                // to disrupt.
                                                if (it.isFocused && consumedBaseline != inputPlaceholder) {
                                                    consumedBaseline = inputPlaceholder
                                                    hiddenInput = TextFieldValue(
                                                        inputPlaceholder,
                                                        selection = TextRange(inputPlaceholder.length)
                                                    )
                                                }
                                            }
                                    )
                                }

                                // Split-screen secondary pane + drag handle - only present
                                // when splitRuntimeId is set (see the primaryWeight
                                // computation above this Box). A simpler sibling pane: its
                                // own TerminalView bound to the split session's buffer, basic
                                // tap-to-focus keyboard input via sendInputTo (or the shared
                                // broadcast path via sendInput when broadcastInput is on -
                                // see MainViewModel.sendInput's doc), but deliberately WITHOUT
                                // the primary Box's rich gesture stack (edge-scroll-while-
                                // selecting, long-press selection drag, pinch-zoom) - hoisting
                                // that ~450-line gesture loop out to serve two independent
                                // panes would be a much larger, riskier rewrite of code that
                                // already works correctly for the primary pane today.
                                if (splitRuntimeId != null) {
                                    SplitDragHandle(
                                        onDrag = { deltaPx, containerHeightPx ->
                                            if (containerHeightPx > 0f) {
                                                val deltaRatio = deltaPx / containerHeightPx
                                                viewModel.setSplitRatio(state.splitRatio + deltaRatio)
                                            }
                                        }
                                    )
                                    SplitTerminalPane(
                                        modifier = Modifier
                                            .weight(1f - state.splitRatio)
                                            .fillMaxWidth(),
                                        runtimeId = splitRuntimeId,
                                        buffer = viewModel.bufferFor(splitRuntimeId),
                                        bufferVersion = state.bufferVersion,
                                        palette = terminalPalette,
                                        fontFamily = terminalTypeface,
                                        fontSizeSp = effectiveTextSize,
                                        broadcastInput = state.broadcastInput,
                                        onToggleBroadcast = { viewModel.setBroadcastInput(!state.broadcastInput) },
                                        onInput = { text -> viewModel.sendInputTo(splitRuntimeId, text) },
                                        onClose = { viewModel.setSplitSession(null) }
                                    )
                                }

                                // The bar's enter/exit was previously keyed only on the Settings
                                // toggle, so it sat statically ("donuk") whenever the real soft
                                // keyboard opened or closed underneath it - imePadding() on the
                                // parent just resized things instantly with no motion of its own.
                                // Tying visibility to keyboardOpen as well (when the soft
                                // keyboard is in use) means the bar now slides/fades in step with
                                // the keyboard showing or hiding, not just with the settings
                                // toggle. When the soft keyboard is disabled entirely, the bar
                                // just follows the settings toggle as before.
                androidx.compose.animation.AnimatedVisibility(
                                    visible = virtualKeysEnabled && (!softKeyboardEnabled || keyboardOpen),
                                    // Was 220ms, which read as sluggish given how often this
                                    // bar toggles with the keyboard. 120ms + a snappier
                                    // easing keeps it visible but makes it feel immediate.
                                    enter = androidx.compose.animation.slideInVertically(
                                        animationSpec = androidx.compose.animation.core.tween(
                                            120, easing = androidx.compose.animation.core.FastOutSlowInEasing
                                        )
                                    ) { height -> height } + androidx.compose.animation.fadeIn(
                                        androidx.compose.animation.core.tween(120)
                                    ),
                                    exit = androidx.compose.animation.slideOutVertically(
                                        animationSpec = androidx.compose.animation.core.tween(
                                            120, easing = androidx.compose.animation.core.FastOutSlowInEasing
                                        )
                                    ) { height -> height } + androidx.compose.animation.fadeOut(
                                        androidx.compose.animation.core.tween(120)
                                    )
                                ) {
                                    VirtualKeyBar(
                                        ctrlActive = ctrlActive,
                                        altActive = altActive,
                                        keymaps = keymaps,
                                        onKeymapTriggered = { entry ->
                                            // Each saved shortcut is a short list of VirtualKey
                                            // names (e.g. ["CTRL", "ESC"]) - CTRL/ALT act as
                                            // modifiers on whatever key follows them, same as a
                                            // one-shot tap on the real key bar; every other key in
                                            // the list just sends its own escape sequence in order.
                                            var pendingCtrl = false
                                            var pendingAlt = false
                                            val sequence = StringBuilder()
                                            entry.keys.forEach { keyName ->
                                                val vk = runCatching { VirtualKey.valueOf(keyName) }.getOrNull()
                                                when (vk) {
                                                    VirtualKey.CTRL -> pendingCtrl = true
                                                    VirtualKey.ALT -> pendingAlt = true
                                                    null -> {}
                                                    else -> {
                                                        var seq = vk.sendSequence
                                                        if (pendingCtrl) {
                                                            seq = seq.map(::applyCtrl).joinToString("")
                                                            pendingCtrl = false
                                                        }
                                                        if (pendingAlt) {
                                                            seq = "\u001B$seq"
                                                            pendingAlt = false
                                                        }
                                                        sequence.append(seq)
                                                    }
                                                }
                                            }
                                            if (sequence.isNotEmpty()) {
                                                viewModel.sendInput(sequence.toString())
                                            }
                                        },
                                        onTextSubmitted = { text -> viewModel.sendInput(text) },
                                        onTextFieldFocusChanged = { focused -> textPageFieldFocused = focused },
                                        // See VirtualKeyBar's onTextEntryClosed doc for why this is
                                        // needed - without it, swiping back to the key-rows page
                                        // leaves no field focused and the real IME closes on its
                                        // own, then flickers back open once focus lands here. Only
                                        // restores when the keyboard was actually up before the
                                        // swipe (settledKeyboardOpen, same reasoning as
                                        // keyboardWasOpenBeforeSelection above), and defers the
                                        // explicit show() the same way onCopy/onPaste/onCancel do.
                                        onTextEntryClosed = {
                                            if (settledKeyboardOpen) {
                                                focusRequester.requestFocus()
                                                currentView.post { insetsController.show(WindowInsetsCompat.Type.ime()) }
                                                lastKeyboardIntentOpen = true
                                            }
                                        },
                                        onKeyPressed = { key ->
                                            when (key) {
                                                VirtualKey.CTRL -> ctrlActive = !ctrlActive
                                                VirtualKey.ALT -> altActive = !altActive
                                                else -> if (key.sendSequence.isNotEmpty()) {
                                                    var seq = key.sendSequence
                                                    // Arrow/Home/End keys are encoded differently
                                                    // depending on whether the running program has
                                                    // switched into "application cursor keys" mode
                                                    // (DECCKM) - nano/vim do this via ncurses'
                                                    // keypad(TRUE) at startup. The CSI form below is
                                                    // only correct in normal mode; in application mode
                                                    // the same keys must go out as SS3 (\EO..) instead,
                                                    // or the program won't recognize them as anything.
                                                    if (viewModel.activeSessionApplicationCursorKeys()) {
                                                        seq = when (key) {
                                                            VirtualKey.UP -> "\u001BOA"
                                                            VirtualKey.DOWN -> "\u001BOB"
                                                            VirtualKey.RIGHT -> "\u001BOC"
                                                            VirtualKey.LEFT -> "\u001BOD"
                                                            VirtualKey.HOME -> "\u001BOH"
                                                            VirtualKey.END -> "\u001BOF"
                                                            else -> seq
                                                        }
                                                    }
                                                    if (ctrlActive) {
                                                        seq = seq.map(::applyCtrl).joinToString("")
                                                        ctrlActive = false
                                                    }
                                                    if (altActive) {
                                                        seq = "\u001B$seq"
                                                        altActive = false
                                                    }
                                                    // Same foreground-aware Ctrl+D rule as the real
                                                    // keyboard path, in case a future key row maps to
                                                    // it too.
                                                    if (seq == "\u0004") {
                                                        viewModel.killActiveSessionHard()
                                                    } else {
                                                        viewModel.sendInput(seq)
                                                    }
                                                }
                                            }
                                        },
                                        onMenuClicked = { viewModel.setDrawerOpen(true) }
                                    )
                                }
                            }

                            // Edge-swipe-to-open: a narrow strip along the left edge that
                            // opens the drawer on a right drag, mirroring the hamburger
                            // button. Only active while the drawer is closed so it doesn't
                            // fight with the drawer's own swipe-to-dismiss gesture.
                            if (!state.drawerOpen) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.CenterStart)
                                        .fillMaxHeight()
                                        .width(24.dp)
                                        .pointerInput(Unit) {
                                            var totalDrag = 0f
                                            detectHorizontalDragGestures(
                                                onDragStart = { totalDrag = 0f },
                                                onDragEnd = {
                                                    if (totalDrag > 60f) {
                                                        viewModel.setDrawerOpen(true)
                                                    }
                                                },
                                                onHorizontalDrag = { _, delta ->
                                                    totalDrag += delta
                                                }
                                            )
                                        }
                                )
                            }

                            SessionDrawer(
                                visible = state.drawerOpen,
                                sessions = state.sessions,
                                runningSessions = state.runningSessions,
                                activeSessionId = state.activeSessionId,
                                onSessionSelected = { viewModel.openSession(it) },
                                onRunningSessionSelected = { viewModel.openRunningSession(it) },
                                onKillRunningSession = { viewModel.killSession(it) },
                                onToggleWakeUpRunningSession = { viewModel.toggleWakeUp(it) },
                                onCloneRunningSession = { viewModel.duplicateSession(it) },
                                // Toggling a row that's already the split partner closes
                                // the split; toggling a different row switches the split
                                // partner directly to it (setSplitSession just overwrites
                                // splitRuntimeId, no need to close-then-reopen).
                                splitRuntimeId = state.splitRuntimeId,
                                onToggleSplitSession = { runtimeId ->
                                    viewModel.setSplitSession(
                                        if (state.splitRuntimeId == runtimeId) null else runtimeId
                                    )
                                },
                                onSettingsClicked = {
                                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                                },
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onSetDefault = { viewModel.setDefault(it) },
                                onDismissRequest = { viewModel.setDrawerOpen(false) }
                            )

                            // Titlebar "+" popup - see onQuickAddClicked
                            // above. AlertDialog already renders as a
                            // centered popup with its own scrim, so this
                            // needs no extra positioning/backdrop of its
                            // own, unlike SessionDrawer's slide-in panel.
                            if (showQuickAddPicker) {
                                QuickAddSessionPickerDialog(
                                    runningSessions = state.runningSessions,
                                    onSessionPicked = { runtimeId ->
                                        viewModel.duplicateSession(runtimeId)
                                        showQuickAddPicker = false
                                    },
                                    onDismissRequest = { showQuickAddPicker = false }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // With launchMode="singleTask" (see manifest), tapping the notification
    // while MainActivity is already running delivers here instead of
    // spawning a new instance - this is what makes it possible to route to
    // the specific session the notification named instead of always
    // landing on a fresh default one.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNotificationIntent(intent)
    }

    /** Reads EXTRA_RUNTIME_ID off a notification tap (main content tap or
     *  the single-session Close/body action) and switches to that running
     *  session, or reads EXTRA_OPEN_DRAWER (the "Manage sessions" action
     *  shown once 2+ sessions are running, since the notification can no
     *  longer say which one a tap means) and opens the session drawer
     *  instead so the user can pick. Neither extra present (a plain
     *  launcher-icon launch, or the very first onCreate before any
     *  notification exists) is a no-op - MainViewModel's own init already
     *  opens the default session for that case. */
    private fun handleNotificationIntent(intent: Intent?) {
        val runtimeId = intent?.getStringExtra(SessionForegroundService.EXTRA_RUNTIME_ID)
        if (!runtimeId.isNullOrBlank()) {
            viewModel.openRunningSession(runtimeId)
        }
        if (intent?.getBooleanExtra(SessionForegroundService.EXTRA_OPEN_DRAWER, false) == true) {
            viewModel.setDrawerOpen(true)
        }
    }
}

/**
 * Standard terminal Ctrl+key mapping, e.g. Ctrl+C -> 0x03 (ETX), Ctrl+D ->
 * 0x04 (EOT), Ctrl+L -> 0x0C (form feed/clear). Letters A-Z/a-z map to
 * control codes 1-26; a handful of punctuation keys have their own
 * well-known control codes; anything else without a sensible mapping is
 * passed through unchanged rather than silently dropped.
 */
private fun applyCtrl(c: Char): String {
    val upper = c.uppercaseChar()
    return when {
        upper in 'A'..'Z' -> ((upper.code - 'A'.code + 1)).toChar().toString()
        c == '[' -> "\u001B"
        c == '\\' -> "\u001C"
        c == ']' -> "\u001D"
        c == '^' -> "\u001E"
        c == '_' -> "\u001F"
        c == '?' -> "\u007F"
        else -> c.toString()
    }
}
