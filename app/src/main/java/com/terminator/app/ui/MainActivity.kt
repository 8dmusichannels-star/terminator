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
import androidx.compose.animation.core.tween
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.viewModels
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.offset
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

    // SelectionOverrideToolbar.kt's own doc says this is required
    // alongside the Compose-level NoOpTextToolbar block ("some
    // compose-foundation versions still go through real ActionMode
    // during an active drag, and that path needs the Activity-level
    // block too") - but it was never actually written, which is exactly
    // why the native bubble kept winning the race: LocalTextToolbar only
    // intercepts Compose's *own* fallback path, it has no say over a
    // real android.view.ActionMode started straight off the underlying
    // View, and that's a second, independent path into the same native
    // bubble. Returning null from both overloads refuses to let the
    // window start ANY ActionMode (primary or floating) for as long as
    // this Activity is up, so there's nothing left to race
    // ActionModeController/SelectionActionBar - ours is the only
    // Copy/Paste UI that can ever show, full stop.
    override fun onWindowStartingActionMode(callback: android.view.ActionMode.Callback?): android.view.ActionMode? {
        android.util.Log.d("ToolbarDebug", "onWindowStartingActionMode(callback) blocked - refusing native ActionMode")
        return null
    }

    override fun onWindowStartingActionMode(
        callback: android.view.ActionMode.Callback?,
        type: Int
    ): android.view.ActionMode? {
        android.util.Log.d("ToolbarDebug", "onWindowStartingActionMode(callback, type=$type) blocked - refusing native ActionMode")
        return null
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

        val gatingComposeView = ComposeView(this)
        // Same lifecycle-driven disposal ComponentActivity.setContent(...)
        // would normally set up automatically - required here since this
        // ComposeView is being created and attached manually instead.
        gatingComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        gatingComposeView.setContent {
            val app = application as TerminatorApp
            val repo = app.settingsRepository

            val amoledBlack by repo.flow(SettingsKeys.AMOLED_BLACK, false).collectAsState(initial = false)
            val wallpaperUriStr by repo.flow(SettingsKeys.WALLPAPER_URI, "").collectAsState(initial = "")
            // Runner toolbar's save icon - exports the active (or split
            // secondary) pane's full terminal output. Always available, no
            // toggle: unlike the old clipboard-history log this replaced,
            // there's no persisted state to gate - it just reads whatever
            // the session's buffer currently holds at export time.
            //
            // pendingSaveRuntimeId: which running session's output the NEXT
            // launcher result should export. CreateDocument's callback only
            // gets the destination Uri back, not anything about which
            // button triggered it - so when the drawer's per-row Save icon
            // (SessionDrawer's onSaveRunningSession) is tapped for a
            // session that ISN'T the active one, the target runtimeId is
            // stashed here right before launching, then consumed (and
            // cleared) in the callback below. Left null for the runner
            // toolbar's own Save icon and the long-press selection
            // toolbar's save icon, both of which always mean "the active
            // session" - exportSessionOutput's own runtimeId = null default
            // already covers that case without this.
            var pendingSaveRuntimeId by remember { mutableStateOf<String?>(null) }
            val terminalExportLauncher = rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain")
            ) { uri ->
                uri?.let { viewModel.exportSessionOutput(it, pendingSaveRuntimeId) }
                pendingSaveRuntimeId = null
            }
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
            // Settings > Display > "Show runner toolbar save button" - now
            // only gates the per-session Save icon in SessionDrawer's
            // Running rows (see SessionDrawer.kt's onSaveRunningSession
            // doc). The persistent RunnerToolbar bar above the terminal
            // that this setting used to also gate has been removed.
            val showRunnerToolbarSave by repo.flow(SettingsKeys.SHOW_RUNNER_TOOLBAR_SAVE, true).collectAsState(initial = true)
            // Settings > Display > "Split screen visibility" - gates whether
            // a dedicated split-screen button/option is offered anywhere
            // (SessionDrawer's per-row split icon, the selection bar's More
            // popup). Doesn't affect an already-open split - see
            // SettingsKeys.SPLIT_SCREEN_VISIBLE's doc.
            val splitScreenVisible by repo.flow(SettingsKeys.SPLIT_SCREEN_VISIBLE, true).collectAsState(initial = true)
            // Settings > Display > "Broadcast to all panes" - see
            // MainViewModel.sendPaneInput's doc. Only consulted while
            // multi-pane mode is on (state.panes non-empty); has no effect
            // on the classic single/split-pane path's own separate
            // broadcastInput flag.
            val broadcastAllPanes by repo.flow(SettingsKeys.BROADCAST_ALL_PANES, false).collectAsState(initial = false)
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
                // NOTE (reverted): a set of derivedStateOf wrappers used to sit here
                // (sessionMetaSessions, sessionMetaRunningSessions, etc.) meant to stop
                // SessionDrawer/TerminatorTitleBar from recomposing on every
                // bufferVersion bump. That change is what actually introduced a
                // constant stutter/freeze across the WHOLE app, not just screens with a
                // session photo - reported immediately after that commit landed.
                // Reverted back to reading state.* directly, which is exactly what this
                // file did before that commit, when there was no such freeze. Root-
                // caused later if the recomposition-skipping optimization is
                // revisited, but it should not go back in without being verified
                // against a real build/profiler first - the freeze this caused was
                // worse than the jank it was trying to fix.
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
                // MultiPaneContainer's toolbar "+" button state: reuses the
                // same running-session picker dialog as the titlebar "+",
                // but wired to viewModel.addPaneSession instead of
                // duplicateSession - see the dialog's call site near the
                // bottom of this Scaffold for both usages side by side.
                var showAddPanePicker by remember { mutableStateOf(false) }
                // CTRL/ALT on the virtual key bar were pure no-ops before -
                // sendSequence was "" and nothing ever consumed them. They're
                // one-shot modifiers: tap CTRL/ALT, then type a regular
                // character (from the real keyboard, or another virtual
                // key) and that keystroke gets transformed instead of sent
                // literally.
                var ctrlActive by remember { mutableStateOf(false) }
                var altActive by remember { mutableStateOf(false) }
                // Bumped (never 0 after the first bump) to tell SplitTerminalPane
                // "reclaim your own IME focus" - see its focusRequestSignal doc.
                // Needed because the shared VirtualKeyBar's onTextEntryClosed
                // below previously only knew how to focus the PRIMARY pane's own
                // field, even while the split pane was the one actually focused.
                var splitFocusRequestSignal by remember { mutableStateOf(0) }
                // Same purpose as splitFocusRequestSignal above, but for
                // multi-pane grid mode's own focused tile - see
                // MultiPaneContainer/PaneContent's focusRequestSignal doc.
                // Bumped from multi-pane's onTextEntryClosed below instead
                // of that branch's old bare insetsController.show() call,
                // which had no focused field left to actually attach the
                // IME to once the long-text page's own field left
                // composition on swipe-back.
                var multiPaneFocusRequestSignal by remember { mutableStateOf(0) }

                // Which pane VirtualKeyBar's key presses / keymaps / long-text
                // page should actually send to. Previously the bar was wired
                // unconditionally to viewModel.sendInput (the primary pane's
                // session), so tapping into the split partner and using the
                // key bar (CTRL/ALT/arrows/keymaps) silently kept typing into
                // the primary pane instead - the split pane's own touch/mouse
                // gesture handling worked (SplitTerminalPane has its own
                // pointerInput loop), but the shared key bar never followed
                // focus onto it. Defaults to false (primary) and flips to
                // true only while the split pane reports itself focused (see
                // SplitTerminalPane's isFocused, wired below) - tapping back
                // into the primary pane's own hidden input field flips it
                // back via the existing hiddenFieldFocused callback.
                var splitPaneFocused by remember { mutableStateOf(false) }

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

                            val effectiveTextSize = liveZoomSize ?: sessionTextSize ?: textSize
                            // Hoisted here (rather than created inside TerminalView) so the
                            // toolbar below can read selectionState.selectedTexts to decide
                            // whether it's visible and what Copy actually copies - passed down
                            // into TerminalView's SelectionContainer, which is the only thing
                            // that actually mutates it (via long-press/drag). Reset whenever the
                            // active session changes so a leftover selection from a different
                            // session's screen can never linger.
                            val selectionState = rememberSelectionState()
                            LaunchedEffect(activeSessionId) { selectionState.clear() }
                            // Clears any active selection the instant state.scrollOffset
                            // changes (dragging into scrollback, or back toward the live
                            // screen) - see TerminalView's row loop doc: each row is a
                            // fixed Composable slot that gets DIFFERENT text handed to it
                            // as scrollOffset changes, which SelectionContainer has no
                            // reliable way to track (native selection is only well-defined
                            // when the text under a given node stays the same - see
                            // Compose's own "undefined behavior" warning on lazy layouts
                            // for the same underlying reason, just triggered here without
                            // a lazy layout at all). Previously the highlight would freeze
                            // on whatever was selected before the scroll, but silently stop
                            // tracking real text underneath it and the Copy/Paste toolbar
                            // would go stale or never reappear - explicitly clearing here
                            // makes the cutoff clean and immediate instead of leaving a
                            // highlight on screen that no longer corresponds to anything
                            // selectable. Keyed on the value itself (not Unit/a boolean) so
                            // every distinct offset - including scrolling back to exactly
                            // where a selection started - reruns this.
                            // isEdgeAutoScroll guard: edge-auto-scroll-while-selecting
                            // (the pointerInput block below, PointerEventPass.Initial)
                            // calls adjustScrollOffset() specifically so the user can
                            // extend a selection into scrollback by dragging a handle
                            // to the top/bottom edge. Clearing the selection on every
                            // scrollOffset change unconditionally - as this used to do -
                            // deleted the selection on the very first auto-scroll tick,
                            // defeating the feature it was meant to support (see
                            // MainViewModel.lastScrollWasEdgeAutoScroll's doc). Free
                            // drag-to-pan / pinch-zoom still clear as before: those
                            // aren't driven by an active selection, so there's nothing
                            // worth preserving and the row-slot-reuse problem this
                            // effect exists for (see below) still applies.
                            LaunchedEffect(state.scrollOffset) {
                                if (!viewModel.lastScrollWasEdgeAutoScroll) {
                                    selectionState.clear()
                                }
                            }
                            val clipboardManager = LocalClipboardManager.current
                            // Copy/Paste/More bar - a plain Compose popup
                            // (SelectionActionBar), not a native android.view.ActionMode or a
                            // TextToolbar override, since this app's Compose Foundation
                            // version doesn't reliably route SelectionContainer's own menu
                            // through either of those - see SelectionOverrideToolbar.kt's doc
                            // for the full story. actionModeController.show()/hide() are
                            // called from the selectedTexts LaunchedEffect further down;
                            // moreVisible is flipped on by SelectionActionBar's own "More"
                            // button click, via the onMore lambda passed to show().
                            val actionModeController = rememberActionModeController()
                            var moreVisible by remember(activeSessionId) { mutableStateOf(false) }
                            // Snapshot of keyboardOpen taken the instant a selection starts -
                            // see the doc further down (onCopy) for why the toolbar's Copy/
                            // Paste/Cancel callbacks read this instead of live keyboardOpen.
                            // Previously captured inline in the gesture loop at the moment a
                            // long-press started a selection; now that long-press-to-select is
                            // entirely native (TerminalView's SelectionContainer), there's no
                            // hook left in this file to snapshot from directly - so this reacts
                            // to selectionState.selectedTexts transitioning from empty to
                            // non-empty instead, which happens on the same frame the toolbar
                            // below becomes visible, before any of its buttons exist to steal
                            // focus.
                            var keyboardWasOpenBeforeSelection by remember(activeSessionId) { mutableStateOf(false) }
                            LaunchedEffect(selectionState.selectedTexts.isNotEmpty()) {
                                if (selectionState.selectedTexts.isNotEmpty()) {
                                    keyboardWasOpenBeforeSelection = effectiveKeyboardWasOpen()
                                }
                            }
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
                            val latestCharMetrics = rememberUpdatedState(charMetrics)

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
                              // Multi-pane mode (see MainUiState.panes's doc) takes over this
                              // entire Column's content whenever any panes are open, instead of
                              // the classic single/split-pane rendering below. Kept as a single
                              // top-level branch here (rather than threading a check through
                              // every inner block) so the classic path underneath - including
                              // its primary-pane gesture stack, virtual key bar and IME wiring -
                              // stays completely untouched and byte-for-byte identical to before
                              // whenever panes is empty (the common case).
                              if (state.panes.isNotEmpty()) {
                                // Multi-pane mode wraps MultiPaneContainer + VirtualKeyBar in
                                // its own Column so the bar is always visible here, independent
                                // of the else-branch bar below (which is split/single-pane only).
                                // Previously the bar lived entirely inside the else branch and
                                // was never rendered when panes.isNotEmpty() — the bar simply
                                // didn't exist in multi-pane mode. Routes key presses to the
                                // focused pane via viewModel.sendPaneInput (same path as typing).
                                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                MultiPaneContainer(
                                    panes = state.panes,
                                    mode = state.paneMode,
                                    focusedRuntimeId = state.focusedPaneRuntimeId,
                                    bufferVersion = state.bufferVersion,
                                    bufferFor = { runtimeId -> viewModel.bufferFor(runtimeId) },
                                    labelFor = { runtimeId ->
                                        state.runningSessions.firstOrNull { it.runtimeId == runtimeId }?.label ?: "Terminal"
                                    },
                                    palette = terminalPalette,
                                    fontFamily = terminalTypeface,
                                    fontSizeSp = textSize,
                                    onInput = { runtimeId, text ->
                                        // Same missing-transform bug as the split pane's onInput
                                        // (see its own doc): this tile's HiddenPaneInputField
                                        // forwards raw IME text, and ctrlActive/altActive are
                                        // shared with THIS bar (see its ctrlActive/altActive
                                        // props above) - toggling CTRL here armed the flag but
                                        // nothing on this path ever consumed it for a real
                                        // keystroke, only for the bar's own key buttons.
                                        var toSend = text.replace('\n', '\r')
                                        if (ctrlActive) {
                                            toSend = toSend.map(::applyCtrl).joinToString("")
                                            ctrlActive = false
                                        }
                                        if (altActive) {
                                            toSend = "\u001B$toSend"
                                            altActive = false
                                        }
                                        viewModel.sendPaneInput(toSend, broadcastAllPanes)
                                        // sendPaneInput() only targets the focused pane (or every
                                        // pane, if broadcasting) - it deliberately ignores which
                                        // pane's OWN hidden field produced this call, matching
                                        // "basarak focus etmek gerekir": typing into a pane that
                                        // ISN'T focused shouldn't silently type there anyway; the
                                        // tap that focused it already happened before the IME
                                        // field could produce any text. runtimeId is accepted for
                                        // signature symmetry with onFocusPane/onClosePane below
                                        // but intentionally unused here.
                                    },
                                    onFocusPane = { runtimeId -> viewModel.bringPaneToFront(runtimeId) },
                                    onClosePane = { runtimeId -> viewModel.removePane(runtimeId) },
                                    onMovePane = { runtimeId, offset -> viewModel.movePane(runtimeId, offset) },
                                    onResizePane = { runtimeId, size -> viewModel.resizePane(runtimeId, size) },
                                    onResizeSessionPty = { runtimeId, cols, rws -> viewModel.updateTerminalSizeFor(runtimeId, cols, rws) },
                                    onSetMode = { mode -> viewModel.setPaneMode(mode) },
                                    onAddPaneRequested = {
                                        // Same "don't pop up an empty list"
                                        // fallback as the titlebar "+" - see
                                        // spawnAndAddPane's doc. Only offer
                                        // the picker when there's actually a
                                        // running-but-not-yet-paned session
                                        // to choose from.
                                        val pickableSessions = state.runningSessions.filterNot { r ->
                                            state.panes.any { it.runtimeId == r.runtimeId }
                                        }
                                        if (pickableSessions.isNotEmpty()) {
                                            showAddPanePicker = true
                                        } else {
                                            viewModel.spawnAndAddPane()
                                        }
                                    },
                                    onExitMultiPane = { viewModel.exitMultiPaneMode() },
                                    onWantsMouseEvents = { runtimeId -> viewModel.sessionWantsMouseEvents(runtimeId) },
                                    onMouseEvent = { runtimeId, kind, col, row -> viewModel.sendMouseEventTo(runtimeId, kind, col, row) },
                                    onCloneSession = { runtimeId -> viewModel.duplicateSession(runtimeId) },
                                    onToggleWakeUp = { runtimeId -> viewModel.toggleWakeUp(runtimeId) },
                                    wakeUpActiveFor = { runtimeId ->
                                        state.runningSessions.firstOrNull { it.runtimeId == runtimeId }?.wakeUp == true
                                    },
                                    onSaveSession = { runtimeId ->
                                        pendingSaveRuntimeId = runtimeId
                                        terminalExportLauncher.launch("terminator-session.txt")
                                    },
                                    focusRequestSignal = multiPaneFocusRequestSignal,
                                    modifier = Modifier.weight(1f)
                                )
                                // VirtualKeyBar for multi-pane mode. Shows whenever any pane is
                                // focused (focusedPaneRuntimeId != null) or the IME is visible,
                                // same reasoning as splitPaneFocused in the else branch below.
                                // Routes to the focused pane via sendPaneInput — no CTRL/ALT
                                // state here, key presses go straight as sequences.
                                if (virtualKeysEnabled && (!softKeyboardEnabled || keyboardOpen || state.focusedPaneRuntimeId != null)) {
                                    VirtualKeyBar(
                                        ctrlActive = ctrlActive,
                                        altActive = altActive,
                                        keymaps = keymaps,
                                        onKeymapTriggered = { entry ->
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
                                                        if (pendingCtrl) { seq = seq.map(::applyCtrl).joinToString(""); pendingCtrl = false }
                                                        if (pendingAlt) { seq = "\u001B$seq"; pendingAlt = false }
                                                        sequence.append(seq)
                                                    }
                                                }
                                            }
                                            if (sequence.isNotEmpty()) viewModel.sendPaneInput(sequence.toString(), broadcastAllPanes)
                                        },
                                        onTextSubmitted = { text -> viewModel.sendPaneInput(text, broadcastAllPanes) },
                                        onTextFieldFocusChanged = { focused -> textPageFieldFocused = focused },
                                        onTextEntryClosed = {
                                            // See VirtualKeyBar's onTextEntryClosed doc, and
                                            // PaneContent's focusRequestSignal doc, for why a bare
                                            // insetsController.show() here isn't enough: the tile
                                            // that's actually focused needs its own hidden field to
                                            // reclaim focus first, or there's no input connection
                                            // for the IME to attach to and it just closes again on
                                            // its own right after swiping back from the long-text
                                            // page - "SANAL KLAVYE SWİPE ... İMEİ KENDİ KENDİNE
                                            // KAPANİYOR", multi-pane-only for the same reason
                                            // SplitTerminalPane's own splitFocusRequestSignal exists.
                                            if (settledKeyboardOpen) {
                                                multiPaneFocusRequestSignal++
                                                lastKeyboardIntentOpen = true
                                            }
                                        },
                                        onKeyPressed = { key ->
                                            when (key) {
                                                VirtualKey.CTRL -> ctrlActive = !ctrlActive
                                                VirtualKey.ALT -> altActive = !altActive
                                                else -> if (key.sendSequence.isNotEmpty()) {
                                                    var seq = key.sendSequence
                                                    if (ctrlActive) { seq = seq.map(::applyCtrl).joinToString(""); ctrlActive = false }
                                                    if (altActive) { seq = "\u001B$seq"; altActive = false }
                                                    viewModel.sendPaneInput(seq, broadcastAllPanes)
                                                }
                                            }
                                        },
                                        onMenuClicked = { viewModel.setDrawerOpen(true) }
                                    )
                                }
                                } // end multi-pane Column
                              } else {
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
                                        // Edge-scroll while selecting: when the user drags a
                                        // selection handle to the top or bottom 15% of the
                                        // terminal area, automatically scroll the scrollback
                                        // so they can extend the selection into history.
                                        // Uses PointerEventPass.Initial so we read the pointer
                                        // position BEFORE SelectionContainer gets the event and
                                        // consumes it — without Initial pass the handle drag
                                        // events are invisible to this block entirely.
                                        // Never consumes anything itself: it only observes
                                        // position and drives adjustScrollOffset as a side
                                        // effect, leaving SelectionContainer fully in charge
                                        // of the actual drag/handle/highlight mechanics.
                                        .pointerInput(activeSessionId) {
                                            val edgeFraction = 0.15f
                                            // Scroll speed: lines per frame at full edge
                                            val maxLinesPerFrame = 1.5f
                                            awaitPointerEventScope {
                                                while (true) {
                                                    // Observe every pointer event at Initial pass
                                                    // (before children consume) without consuming.
                                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                                    // Only act when a selection is active and the
                                                    // session is not in alternate screen (where
                                                    // scrollback doesn't apply).
                                                    if (selectionState.selectedTexts.isEmpty()) continue
                                                    if (viewModel.activeSessionInAlternateScreen()) continue
                                                    val pointer = event.changes.firstOrNull() ?: continue
                                                    if (!pointer.pressed) continue
                                                    val h = size.height.toFloat()
                                                    if (h <= 0f) continue
                                                    val y = pointer.position.y
                                                    val edgePx = h * edgeFraction
                                                    val (_, charHeight) = latestCharMetrics.value
                                                    if (charHeight <= 0f) continue
                                                    when {
                                                        // Top edge — scroll up into scrollback
                                                        y < edgePx -> {
                                                            val strength = ((edgePx - y) / edgePx).coerceIn(0f, 1f)
                                                            viewModel.adjustScrollOffset(strength * maxLinesPerFrame, isEdgeAutoScroll = true)
                                                        }
                                                        // Bottom edge — scroll back toward live output
                                                        y > h - edgePx -> {
                                                            val strength = ((y - (h - edgePx)) / edgePx).coerceIn(0f, 1f)
                                                            viewModel.adjustScrollOffset(-strength * maxLinesPerFrame, isEdgeAutoScroll = true)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        .pointerInput(activeSessionId, softKeyboardEnabled) {
                                            awaitEachGesture {
                                                val down = awaitFirstDown(requireUnconsumed = false)
                                                android.util.Log.d("SelDebug", "outer loop: down received, isConsumed=${down.isConsumed}")

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
                                                var moved = false
                                                var lastPos = down.position
                                                var pointerCount = 1

                                                // Long-press-to-select is handled natively by the
                                                // SelectionContainer overlay wrapping the terminal
                                                // (see TerminalView.kt), which runs its own
                                                // long-press detector as a coroutine on this same
                                                // pointerInput subtree. In practice that detector
                                                // never got an uncontested window to actually
                                                // reach its timeout: as long as this loop kept
                                                // calling awaitPointerEvent() itself on every
                                                // frame, it kept "winning" that shared input
                                                // queue first, so the child's timer effectively
                                                // never elapsed (confirmed via logging - the
                                                // child's selectedTexts never changed and this
                                                // loop never saw a consumed change either).
                                                //
                                                // The fix: give the child first, uncontested
                                                // crack at every down. Wait here - without
                                                // calling awaitPointerEvent() in a competing loop,
                                                // just watching for movement - for up to the
                                                // system's own long-press timeout. If the finger
                                                // hasn't moved past touch slop by then, treat this
                                                // as a long-press: stop reading the pointer stream
                                                // entirely and return, handing the rest of the
                                                // gesture to SelectionContainer completely.
                                                val longPressDeadline = System.nanoTime() + viewConfiguration.longPressTimeoutMillis * 1_000_000L
                                                var longPressCandidate = true
                                                var fingerLifted = false
                                                while (longPressCandidate) {
                                                    val remainingMillis = (longPressDeadline - System.nanoTime()) / 1_000_000L
                                                    if (remainingMillis <= 0L) break
                                                    val event = withTimeoutOrNull(remainingMillis) { awaitPointerEvent() } ?: break
                                                    val changes = event.changes
                                                    val primary = changes.firstOrNull { it.id == down.id } ?: changes.firstOrNull()
                                                    if (primary == null || !changes.any { it.pressed }) {
                                                        // Finger lifted before the long-press
                                                        // timeout - this was a plain tap, not a
                                                        // long-press. Skip the normal loop below
                                                        // entirely (there's no more pointer to
                                                        // read) and fall straight through to the
                                                        // tap handling (moved stays false).
                                                        longPressCandidate = false
                                                        fingerLifted = true
                                                        break
                                                    }
                                                    pointerCount = changes.count { it.pressed }
                                                    if (pointerCount >= 2) {
                                                        // A second finger landed - this is a
                                                        // pinch, not a long-press. Hand off to the
                                                        // normal loop below immediately.
                                                        longPressCandidate = false
                                                        break
                                                    }
                                                    val totalDx = primary.position.x - down.position.x
                                                    val totalDy = primary.position.y - down.position.y
                                                    if (kotlin.math.sqrt(totalDx * totalDx + totalDy * totalDy) > viewConfiguration.touchSlop) {
                                                        // Real movement - this is a scroll, not a
                                                        // long-press. Apply THIS event's motion
                                                        // right now instead of discarding it -
                                                        // otherwise the very first bit of scroll
                                                        // motion (the event that crossed touch
                                                        // slop) was silently dropped, since the
                                                        // normal loop below only starts reading
                                                        // from the NEXT event onward. That's what
                                                        // made short/slow drags fail to scroll at
                                                        // all, or feel like they needed an extra
                                                        // nudge before anything moved.
                                                        moved = true
                                                        val dy = primary.position.y - lastPos.y
                                                        if (!viewModel.activeSessionInAlternateScreen()) {
                                                            val (_, charHeight) = charMetrics
                                                            if (charHeight > 0f) {
                                                                viewModel.adjustScrollOffset(dy / charHeight)
                                                            }
                                                        }
                                                        primary.consume()
                                                        lastPos = primary.position
                                                        longPressCandidate = false
                                                        break
                                                    }
                                                    // Still down, still stationary, timeout not
                                                    // yet reached - keep waiting without
                                                    // consuming anything.
                                                }
                                                if (longPressCandidate) {
                                                    // Timeout reached with the finger still down
                                                    // and stationary: this is a long-press.
                                                    // SelectionContainer's own detector has had
                                                    // this exact same window, uncontested, to
                                                    // reach its own timeout and claim the
                                                    // gesture - don't read the pointer stream
                                                    // again for the rest of this gesture.
                                                    android.util.Log.d("SelDebug", "outer loop: long-press window elapsed, backing off entirely")
                                                    return@awaitEachGesture
                                                }

                                                while (!fingerLifted) {
                                                    val event = awaitPointerEvent()

                                                    val changes = event.changes
                                                    pointerCount = changes.count { it.pressed }
                                                    val primary = changes.firstOrNull { it.id == down.id } ?: changes.firstOrNull()
                                                    if (primary == null || !changes.any { it.pressed }) break

                                                    if (changes.any { it.isConsumed }) {
                                                        android.util.Log.d("SelDebug", "outer loop: backing off, child consumed a change")
                                                        break
                                                    }

                                                    if (pointerCount >= 2 && zoomEnabled) {
                                                        // Two fingers: when a selection is active,
                                                        // treat as a scroll so the user can extend
                                                        // a selection into scrollback — place one
                                                        // finger on the selection handle to hold it
                                                        // and use a second finger to scroll up/down
                                                        // into history, then drag the handle further.
                                                        // When no selection is active, this is the
                                                        // usual pinch-to-zoom. Gated on zoomEnabled
                                                        // (Settings > Appearance > Pinch to zoom) —
                                                        // when off the branch is skipped entirely so
                                                        // two fingers don't resize text; the
                                                        // selection-scroll sub-branch is unaffected
                                                        // because it doesn't resize anything.
                                                        val selectionActive = selectionState.selectedTexts.isNotEmpty()
                                                        val p1 = changes.getOrNull(0)
                                                        val p2 = changes.getOrNull(1)
                                                        if (selectionActive && p1 != null && p2 != null) {
                                                            // Average vertical delta of both fingers
                                                            // → scroll scrollback, same unit as the
                                                            // single-finger scroll path above.
                                                            if (!viewModel.activeSessionInAlternateScreen()) {
                                                                val (_, charHeight) = charMetrics
                                                                if (charHeight > 0f) {
                                                                    val avgDy = ((p1.position.y - p1.previousPosition.y) +
                                                                        (p2.position.y - p2.previousPosition.y)) / 2f
                                                                    if (kotlin.math.abs(avgDy) > 0f) {
                                                                        viewModel.adjustScrollOffset(avgDy / charHeight)
                                                                    }
                                                                }
                                                            }
                                                        } else if (!selectionActive && p1 != null && p2 != null) {
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
                                                                    viewModel.adjustScrollOffset(dy / charHeight)
                                                                }
                                                            }
                                                            primary.consume()
                                                        }
                                                        lastPos = primary.position
                                                    }
                                                }

                                                if (!moved && softKeyboardEnabled) {
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
                                        // TerminalView provides its own LocalTextToolbar
                                        // override internally (NoOpTextToolbar, wrapping its
                                        // SelectionContainer directly) - wrapping it again out
                                        // here would just be a second, outer CompositionLocalProvider
                                        // that TerminalView's own inner one immediately
                                        // overrides anyway, so it's not done here to avoid two
                                        // toolbar instances existing for the same subtree.
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
                                            selectionState = selectionState,
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

                                    // Native Copy/Paste/More bar (ActionModeController, see
                                    // SelectionOverrideToolbar.kt) - fires the instant
                                    // selectedTexts goes non-empty. No rect/anchor math needed
                                    // here anymore: an android.view.ActionMode with
                                    // TYPE_FLOATING positions itself against the real selection
                                    // the same way the platform's own default bubble always did
                                    // - that's the actual fix for the toolbar "jumping too high"
                                    // (a Compose-side Rect approximation is no longer in the
                                    // picture at all) and for it feeling delayed (no more
                                    // waiting on a LocalTextToolbar round-trip that wasn't being
                                    // honored).
                                    // Keyed on the full selectedTexts list (not just
                                    // isNotEmpty()) - see SplitTerminalPane's identical
                                    // LaunchedEffect for why: isNotEmpty() is a Boolean, so
                                    // it only re-fires on a false<->true edge. Scrolling far
                                    // in either direction while a selection is active feeds
                                    // TerminalView's per-row BasicText composables new text
                                    // at the same slot (see TerminalView's row loop), which
                                    // can re-anchor or briefly empty selectedTexts and
                                    // repopulate it within the same recomposition - isNotEmpty()
                                    // never actually toggled, so this effect never re-ran and
                                    // the toolbar was left stale (or never shown at all) even
                                    // though the mavi highlight itself was still visible.
                                    LaunchedEffect(selectionState.selectedTexts.toList()) {
                                        android.util.Log.d(
                                            "ToolbarDebug",
                                            "primary LaunchedEffect fired, isEmpty=${selectionState.selectedTexts.isEmpty()} splitRuntimeId=${state.splitRuntimeId} count=${selectionState.selectedTexts.size}"
                                        )
                                        if (selectionState.selectedTexts.isEmpty()) {
                                            actionModeController.hide()
                                            return@LaunchedEffect
                                        }
                                        actionModeController.show(
                                            onCopy = {
                                                Log.d("KbDebug", "onCopy fired: keyboardOpen=$keyboardOpen keyboardWasOpenBeforeSelection=$keyboardWasOpenBeforeSelection")
                                                // selectedTexts is one AnnotatedString per Text
                                                // composable the selection spans (i.e. one per
                                                // terminal row in TerminalView's overlay) -
                                                // Compose Foundation joins these with "\n" when
                                                // multiple Text composables are involved (see the
                                                // Compose 1.12 changelog), so this doesn't need to
                                                // add its own line separators.
                                                val text = selectionState.selectedTexts.joinToString("\n") { it.text }
                                                Log.d("SelDebug", "onCopy: copied text=[$text] length=${text.length}")
                                                if (text.isNotEmpty()) {
                                                    clipboardManager.setText(AnnotatedString(text))
                                                }
                                                selectionState.clear()
                                                actionModeController.hide()
                                                // Tapping a toolbar button steals focus away from
                                                // the hidden input field, which drops
                                                // hiddenFieldFocused (and therefore keyboardOpen)
                                                // to false - the soft keyboard would otherwise
                                                // close itself right along with dismissing the
                                                // selection, forcing the user to tap the terminal
                                                // again just to keep typing after a Copy/Paste.
                                                // Restoring it here (only when the keyboard was
                                                // actually open BEFORE the toolbar appeared -
                                                // keyboardWasOpenBeforeSelection, not the live
                                                // keyboardOpen this button's own tap just raced
                                                // against and possibly already flipped) brings it
                                                // back immediately instead, and - just as
                                                // importantly - does nothing when the keyboard was
                                                // already closed, so tapping Copy/Paste can't
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
                                                // which is what made "closed -> tap Copy/Paste ->
                                                // opens anyway" intermittent instead of
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
                                                selectionState.clear()
                                                actionModeController.hide()
                                                // See onCopy's comment above for why this reads
                                                // keyboardWasOpenBeforeSelection rather than the
                                                // live keyboardOpen, and defers show() to the next
                                                // frame rather than calling it synchronously right
                                                // after requestFocus().
                                                if (keyboardWasOpenBeforeSelection) {
                                                    focusRequester.requestFocus()
                                                    currentView.post { insetsController.show(WindowInsetsCompat.Type.ime()) }
                                                    lastKeyboardIntentOpen = true
                                                } else {
                                                    focusManager.clearFocus()
                                                    insetsController.hide(WindowInsetsCompat.Type.ime())
                                                    lastKeyboardIntentOpen = false
                                                }
                                            },
                                            onMore = if (activeSessionId != null) {
                                                {
                                                    // Opens MoreActionsPopup (rendered further
                                                    // down). This deliberately does NOT call
                                                    // actionModeController.hide() here, so the
                                                    // selection + SelectionActionBar both stay
                                                    // alive underneath the popup.
                                                    moreVisible = true
                                                }
                                            } else {
                                                null
                                            }
                                        )
                                    }

                                    // The Copy/Paste/More bar itself - see
                                    // SelectionOverrideToolbar.kt's top doc for why this is a
                                    // Compose popup driven off actionModeController's state
                                    // rather than a native ActionMode/TextToolbar bubble.
                                    SelectionActionBar(actionModeController)

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
                                                // Primary pane's own hidden input field just
                                                // claimed focus (tap back into the primary
                                                // terminal) - hand VirtualKeyBar's routing back
                                                // to it. See splitPaneFocused's doc above.
                                                if (it.isFocused) splitPaneFocused = false
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

                                // Popup opened by the native ActionMode's "More..." item
                                // (moreVisible flips true from onMore in the LaunchedEffect
                                // above) - clone, wake-up lock, split screen (open/close with
                                // the active session as the primary side), and Save (identical
                                // to SessionDrawer's per-row Save icon).
                                activeSessionId?.let { runtimeId ->
                                    MoreActionsPopup(
                                        visible = moreVisible,
                                        actions = MoreMenuActions(
                                            onCloneSession = { viewModel.duplicateSession(runtimeId) },
                                            onToggleWakeUp = { viewModel.toggleWakeUp(runtimeId) },
                                            wakeUpActive = state.runningSessions
                                                .firstOrNull { it.runtimeId == runtimeId }?.wakeUp == true,
                                            onToggleSplitScreen = if (splitScreenVisible) {
                                                {
                                                    if (state.splitRuntimeId != null) {
                                                        // Split is already open - this closes it,
                                                        // same as before.
                                                        viewModel.setSplitSession(null)
                                                    } else {
                                                        // Split is currently OFF and we're turning
                                                        // it on. setSplitSession refuses to split a
                                                        // session against itself (see its own doc),
                                                        // so passing `runtimeId` (== activeSessionId)
                                                        // here was always a silent no-op - this is
                                                        // the actual bug: the "+"/split toggle in
                                                        // the More menu never opened a split when
                                                        // split screen wasn't already on, because it
                                                        // never had a second, DIFFERENT session to
                                                        // pass. Now it picks the most relevant other
                                                        // running session as the partner (falling
                                                        // back to cloning the active session itself
                                                        // if nothing else is running yet) - same
                                                        // "always do something useful" contract the
                                                        // titlebar "+" already has via
                                                        // duplicateActiveSession's fallback.
                                                        val otherRunning = state.runningSessions
                                                            .filterNot { it.runtimeId == runtimeId }
                                                            .lastOrNull()
                                                        if (otherRunning != null) {
                                                            viewModel.setSplitSession(otherRunning.runtimeId)
                                                        } else {
                                                            viewModel.duplicateActiveSessionIntoSplit()
                                                        }
                                                    }
                                                }
                                            } else {
                                                null
                                            },
                                            splitScreenActive = state.splitRuntimeId != null,
                                            onSave = {
                                                pendingSaveRuntimeId = runtimeId
                                                terminalExportLauncher.launch("terminator-session.txt")
                                            }
                                        ),
                                        onDismiss = {
                                            moreVisible = false
                                            selectionState.clear()
                                            actionModeController.hide()
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
                                        onInput = { text ->
                                            // Split pane's own HiddenPaneInputField forwards raw IME
                                            // text with no processing at all - unlike the primary
                                            // pane's inline BasicTextField (see its onValueChange),
                                            // it never applied ctrlActive/altActive or the \n->\r
                                            // fix. That's what left CTRL a visible no-op here: the
                                            // VirtualKeyBar's CTRL toggle correctly flips ctrlActive
                                            // (button animates, onKeyPressed's own log confirms it),
                                            // but a regular letter typed via the real keyboard while
                                            // the split pane is focused went straight through as a
                                            // literal character - the flag was armed but nothing ever
                                            // consumed it on this path.
                                            var toSend = text.replace('\n', '\r')
                                            if (ctrlActive) {
                                                toSend = toSend.map(::applyCtrl).joinToString("")
                                                ctrlActive = false
                                            }
                                            if (altActive) {
                                                toSend = "\u001B$toSend"
                                                altActive = false
                                            }
                                            // Same frozen-screen problem the primary pane's own
                                            // onValueChange already guards against (see its
                                            // activeIsExited doc), just never ported here: Ctrl+D
                                            // (sent as a literal EOT byte below, since the split
                                            // pane has no killActiveSessionHard equivalent - see
                                            // onKeyPressed's own doc on that) makes the shell exit
                                            // naturally via EOF, but nothing then cleared the dead
                                            // runtime - so a following Enter just wrote \r into a
                                            // pty with no process left to read it. Enter on an
                                            // already-exited split session now tears the runtime
                                            // down and closes the split (killSession already clears
                                            // splitRuntimeId when the runtime being killed is the
                                            // split partner - see its own doc) instead of silently
                                            // doing nothing.
                                            // state.runningSessions is a Compose snapshot captured
                                            // at the last recomposition - it hasn't refreshed yet
                                            // when this lambda fires (killSessionHard → exited=true
                                            // → _uiState.value update all happen synchronously
                                            // before the next recomposition frame). Reading
                                            // viewModel.uiState.value directly gives the atomic
                                            // current value of the StateFlow, same frame, no stale
                                            // snapshot. Primary pane's BasicTextField lives inside
                                            // the same Composable scope as state so its snapshot is
                                            // always current; split's onInput lambda is a prop
                                            // passed into SplitTerminalPane so it closes over an
                                            // older snapshot - that's the only reason this fails
                                            // here and not on the primary pane.
                                            val splitExited = viewModel.uiState.value.runningSessions
                                                .firstOrNull { it.runtimeId == splitRuntimeId }
                                                ?.exited == true
                                            android.util.Log.d("SplitEnterDebug", "onInput text=${text.map{it.code}} toSend=${toSend.map{it.code}} splitExited=$splitExited splitRuntimeId=$splitRuntimeId")
                                            if (splitExited && toSend.contains('\r')) {
                                                viewModel.killSession(splitRuntimeId)
                                            } else if (toSend == "\u0004") {
                                                // Mirror the primary pane's Ctrl+D path: killSessionHard
                                                // uses the same foreground-aware sendCtrlDOrKill logic,
                                                // SIGKILLing synchronously when the shell is in the
                                                // foreground so exited=true propagates to UI state
                                                // before the user's next keypress. Raw sendInputTo
                                                // was async (pty write → shell reads → shell exits),
                                                // leaving a window where the following Enter saw
                                                // splitExited=false and wrote \r into a dead pty.
                                                // When a foreground program other than the shell has
                                                // the pty, sendCtrlDOrKill sends plain EOT instead.
                                                viewModel.killSessionHard(splitRuntimeId)
                                            } else {
                                                viewModel.sendInputTo(splitRuntimeId, toSend)
                                            }
                                        },
                                        onClose = { viewModel.setSplitSession(null) },
                                        onFocusChanged = { focused -> splitPaneFocused = focused },
                                        focusRequestSignal = splitFocusRequestSignal,
                                        wantsMouseEvents = { viewModel.sessionWantsMouseEvents(splitRuntimeId) },
                                        onMouseEvent = { kind, col, row ->
                                            viewModel.sendMouseEventTo(splitRuntimeId, kind, col, row)
                                        },
                                        scrollOffset = state.splitScrollOffset,
                                        onScroll = { deltaLines -> viewModel.adjustSplitScrollOffset(deltaLines) },
                                        onEdgeAutoScroll = { deltaLines ->
                                            viewModel.adjustSplitScrollOffset(deltaLines, isEdgeAutoScroll = true)
                                        },
                                        wasLastScrollEdgeAutoScroll = { viewModel.lastSplitScrollWasEdgeAutoScroll },
                                        // Supply More actions for the split pane's own
                                        // selection toolbar — clone opens a NEW split
                                        // with a copy of this split session (primary
                                        // pane stays untouched - see
                                        // duplicateSplitSessionIntoNewSplit's doc),
                                        // wake-lock/save target the split session
                                        // directly. Split-screen toggle closes the
                                        // split (already active by definition here).
                                        // Null hides the More button entirely when
                                        // splitScreenVisible is off (same hidden-not-
                                        // disabled treatment as the primary pane's More).
                                        moreMenuActions = MoreMenuActions(
                                            onCloneSession = { viewModel.duplicateSplitSessionIntoNewSplit(splitRuntimeId) },
                                            onToggleWakeUp = { viewModel.toggleWakeUp(splitRuntimeId) },
                                            wakeUpActive = state.runningSessions
                                                .firstOrNull { it.runtimeId == splitRuntimeId }?.wakeUp == true,
                                            onToggleSplitScreen = if (splitScreenVisible) {
                                                { viewModel.setSplitSession(null) }
                                            } else {
                                                null
                                            },
                                            splitScreenActive = true,
                                            onSave = {
                                                pendingSaveRuntimeId = splitRuntimeId
                                                terminalExportLauncher.launch("terminator-session.txt")
                                            }
                                        )
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
                                    // While the split pane is focused, show the bar
                                    // unconditionally rather than gating on keyboardOpen.
                                    // keyboardOpen tracks WindowInsets.ime, which is a
                                    // real system report - but it's driven by whichever
                                    // field actually holds IME focus at the OS level, and
                                    // the split pane's own HiddenPaneInputField (a second,
                                    // independent focus target - see MultiPaneContainer's
                                    // doc) requesting the IME while the primary pane's
                                    // hidden field is mid-transition (or vice versa) is
                                    // exactly the kind of focus race that doc already
                                    // warns can silently lose. Concretely: the bar
                                    // (self-vanishing right as the split pane opens, or
                                    // never appearing when tapping into it) was keyboardOpen
                                    // reading stale/false because the split pane's IME
                                    // show request lost that race, not because the bar's
                                    // own visibility logic was wrong for the primary pane.
                                    // Once splitPaneFocused is true the user is actively in
                                    // the split pane and needs the key bar regardless of
                                    // whatever keyboardOpen currently reads.
                                    visible = virtualKeysEnabled && (splitPaneFocused || !softKeyboardEnabled || keyboardOpen),
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
                                                // Route to whichever pane last reported focus -
                                                // see splitPaneFocused's doc above. Without this,
                                                // a keymap tapped while the split pane is focused
                                                // silently landed in the primary session instead.
                                                val targetSplitId = splitRuntimeId
                                                if (splitPaneFocused && targetSplitId != null) {
                                                    viewModel.sendInputTo(targetSplitId, sequence.toString())
                                                } else {
                                                    viewModel.sendInput(sequence.toString())
                                                }
                                            }
                                        },
                                        onTextSubmitted = { text ->
                                            val targetSplitId = splitRuntimeId
                                            if (splitPaneFocused && targetSplitId != null) {
                                                viewModel.sendInputTo(targetSplitId, text)
                                            } else {
                                                viewModel.sendInput(text)
                                            }
                                        },
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
                                            // Route to whichever pane is actually focused - see
                                            // splitFocusRequestSignal's doc above. Previously this
                                            // always called the PRIMARY pane's focusRequester even
                                            // while the split pane held focus, leaving the split
                                            // pane's own hidden field with no input connection to
                                            // reopen the IME for - that's what made the keyboard
                                            // vanish on its own after swiping back from the
                                            // long-text page while typing into the split pane.
                                            val targetSplitId = splitRuntimeId
                                            if (splitPaneFocused && targetSplitId != null) {
                                                if (settledKeyboardOpen) {
                                                    // Signal the split pane to reclaim its own
                                                    // HiddenPaneInputField focus - this is the ONLY
                                                    // show() call for this path. It used to be paired
                                                    // with a direct
                                                    // currentView.post { insetsController.show(...) }
                                                    // fired right here (reasoned as "reinforcing" the
                                                    // signal path in case it lost the race) - but that
                                                    // direct call uses MainActivity's own
                                                    // insetsController/view, completely independent of
                                                    // HiddenPaneInputField's own focusRequester. At the
                                                    // moment this fires there is no focused field yet
                                                    // for the system to attach the keyboard to - the
                                                    // signal chain (splitFocusRequestSignal++ ->
                                                    // SplitTerminalPane's LaunchedEffect -> focusToken++
                                                    // -> HiddenPaneInputField's own
                                                    // LaunchedEffect(activationKey) -> requestFocus() +
                                                    // show() together) hasn't run yet. A show() request
                                                    // with nothing focused is a well-known Android/
                                                    // Compose race that fails SILENTLY, and once real
                                                    // focus lands a frame later nothing re-fires show()
                                                    // for it - the IME never (re)appears. That silent
                                                    // failure is exactly the "swipe-right in split
                                                    // screen, IME closes itself" bug: this path only
                                                    // runs in split screen (the primary branch below
                                                    // never had this second call), which is why the
                                                    // same swipe worked fine there.
                                                    splitFocusRequestSignal++
                                                    lastKeyboardIntentOpen = true
                                                }
                                            } else if (settledKeyboardOpen) {
                                                focusRequester.requestFocus()
                                                currentView.post { insetsController.show(WindowInsetsCompat.Type.ime()) }
                                                lastKeyboardIntentOpen = true
                                            }
                                        },
                                        onKeyPressed = { key ->
                                            android.util.Log.d("KbDebug", "onKeyPressed: key=$key splitPaneFocused=$splitPaneFocused splitRuntimeId=$splitRuntimeId ctrlActive=$ctrlActive altActive=$altActive")
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
                                                    // Route to whichever pane last reported focus -
                                                    // see splitPaneFocused's doc above. Previously
                                                    // every key-bar press (arrows, CTRL/ALT combos,
                                                    // ESC, etc.) went to the primary session even
                                                    // while the split pane was the one focused/typed
                                                    // into, so the key bar effectively didn't work
                                                    // for the split pane at all.
                                                    val targetSplitId = splitRuntimeId
                                                    // Foreground-aware Ctrl+D, same rule for both
                                                    // panes now. This used to route split-focused
                                                    // Ctrl+D straight to sendInputTo(targetSplitId,
                                                    // "\u0004") below - a raw EOT byte with NO exit
                                                    // tracking at all, completely bypassing
                                                    // SplitTerminalPane's own onInput handler (the one
                                                    // with the splitExited/killSessionHard logic - see
                                                    // its doc in the SplitTerminalPane composable call
                                                    // below). That handler only ever runs for input
                                                    // that goes through the split pane's own
                                                    // HiddenPaneInputField; the shared virtual key
                                                    // bar's dedicated Ctrl+D key is a separate input
                                                    // path that skipped it entirely. When the shell was
                                                    // in the foreground, that raw EOT made the shell
                                                    // exit asynchronously (pty write -> shell reads ->
                                                    // shell calls exit()) with nothing observing it -
                                                    // no kill(), no synchronous markExited() - so
                                                    // "exited" never got set before the next keystroke,
                                                    // and pressing Enter right after wrote \r into a
                                                    // pty with no process left to read it: looked
                                                    // exactly like Enter "doing nothing" and the
                                                    // session never actually got torn down. This is
                                                    // exactly why the bug only showed up in split
                                                    // screen and never in primary - primary's
                                                    // killActiveSessionHard() (below) already went
                                                    // through sendCtrlDOrKill()'s synchronous SIGKILL
                                                    // path for this exact key. killSessionHard is that
                                                    // same synchronous, foreground-aware kill, just
                                                    // parameterized by runtimeId instead of always
                                                    // targeting the active session.
                                                    if (seq == "\u0004" && splitPaneFocused && targetSplitId != null) {
                                                        viewModel.killSessionHard(targetSplitId)
                                                    } else if (seq == "\u0004") {
                                                        viewModel.killActiveSessionHard()
                                                    } else if (splitPaneFocused && targetSplitId != null) {
                                                        viewModel.sendInputTo(targetSplitId, seq)
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
                                // Exports this specific row's session output, not
                                // necessarily the active one - stash the target runtimeId
                                // for the launcher callback to pick up (see
                                // pendingSaveRuntimeId's doc above), then launch the same
                                // CreateDocument picker the runner toolbar/selection
                                // toolbar's Save icons already use.
                                onSaveRunningSession = { runtimeId ->
                                    pendingSaveRuntimeId = runtimeId
                                    terminalExportLauncher.launch("terminal-output.txt")
                                },
                                showRunnerSaveButtons = showRunnerToolbarSave,
                                // Settings > Display > "Split screen visibility" - see
                                // SettingsKeys.SPLIT_SCREEN_VISIBLE's doc. Hides the
                                // per-row split icon entirely when off, same "hidden not
                                // disabled" treatment showRunnerSaveButtons already gets.
                                showSplitButtons = splitScreenVisible,
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
                                // Multi-pane mode: which rows already show as
                                // panes, and adding a row calls addPaneSession
                                // directly - it already handles "not in
                                // multi-pane mode yet" (auto-enters it seeded
                                // with the active session + this one) and
                                // "already a pane" (bring-to-front) itself,
                                // see its doc.
                                paneRuntimeIds = state.panes.map { it.runtimeId }.toSet(),
                                onAddPaneSession = { runtimeId -> viewModel.addPaneSession(runtimeId) },
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

                            // MultiPaneContainer's toolbar "+" popup - same
                            // dialog, but picking a session here adds it as a
                            // pane (viewModel.addPaneSession) instead of
                            // cloning it. Only running sessions not already
                            // shown as a pane are worth offering here, so
                            // they're filtered out rather than letting the
                            // user pick a session that's already visible.
                            if (showAddPanePicker) {
                                QuickAddSessionPickerDialog(
                                    runningSessions = state.runningSessions.filterNot { r ->
                                        state.panes.any { it.runtimeId == r.runtimeId }
                                    },
                                    onSessionPicked = { runtimeId ->
                                        viewModel.addPaneSession(runtimeId)
                                        showAddPanePicker = false
                                    },
                                    onDismissRequest = { showAddPanePicker = false }
                                )
                            }
                        }
                    }
                }
            }
        }
        setContentView(gatingComposeView)
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
