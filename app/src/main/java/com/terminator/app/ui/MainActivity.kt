package com.terminator.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntSize
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
                MainViewModel(app.sessionRepository, filesDir, app.settingsRepository, app.terminfoDir)
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

        // Always launch with the default session, per spec - handled in
        // MainViewModel.init by opening the flagged-default entry.
        SessionForegroundService.start(this)

        setContent {
            val app = application as TerminatorApp
            val repo = app.settingsRepository

            val amoledBlack by repo.flow(SettingsKeys.AMOLED_BLACK, false).collectAsState(initial = false)
            val wallpaperUriStr by repo.flow(SettingsKeys.WALLPAPER_URI, "").collectAsState(initial = "")
            val blurAlpha by repo.flow(SettingsKeys.BLUR_ALPHA, 0.3f).collectAsState(initial = 0.3f)
            val showTitlebar by repo.flow(SettingsKeys.SHOW_TITLEBAR, true).collectAsState(initial = true)
            val virtualKeysEnabled by repo.flow(SettingsKeys.VIRTUAL_KEYS, true).collectAsState(initial = true)
            val softKeyboardEnabled by repo.flow(SettingsKeys.SOFT_KEYBOARD, true).collectAsState(initial = true)
            val textSize by repo.flow(SettingsKeys.TEXT_SIZE, 14f).collectAsState(initial = 14f)
            val colorSchemeMode by repo.flow(SettingsKeys.COLOR_SCHEME_MODE, "Material")
                .collectAsState(initial = "Material")
            val customFg by repo.flow(SettingsKeys.CUSTOM_FG, DEFAULT_CUSTOM_FG).collectAsState(initial = DEFAULT_CUSTOM_FG)
            val customBg by repo.flow(SettingsKeys.CUSTOM_BG, DEFAULT_CUSTOM_BG).collectAsState(initial = DEFAULT_CUSTOM_BG)
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
                val keyboardController = LocalSoftwareKeyboardController.current
                val focusManager = LocalFocusManager.current
                val focusRequester = remember { FocusRequester() }
                var keyboardOpen by remember { mutableStateOf(false) }
                // Two independent focus sources feed keyboardOpen: the
                // always-present hidden terminal input, and (only while the
                // virtual key bar's text-entry page is open) its own
                // OutlinedTextField. Either one having focus counts as "the
                // keyboard is open" - see the VirtualKeyBar param doc for
                // why this can't just be the hidden field's focus alone.
                var hiddenFieldFocused by remember { mutableStateOf(false) }
                var textPageFieldFocused by remember { mutableStateOf(false) }
                keyboardOpen = hiddenFieldFocused || textPageFieldFocused
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

                // Terminal colors now follow Settings > Theme > Terminal color
                // scheme, instead of always rendering flatBlack() regardless
                // of what was picked there.
                val materialColors = MaterialTheme.colorScheme
                val terminalPalette = remember(colorSchemeMode, customFg, customBg, materialColors) {
                    when (colorSchemeMode) {
                        "Nord" -> TerminalPalette.nord()
                        // Imported theme files are parsed straight into CUSTOM_FG/CUSTOM_BG
                        // (see ThemeSettingsScreen), so this now actually reflects what was
                        // imported instead of silently falling back to flatBlack().
                        "Custom RGB", "Import theme file" ->
                            TerminalPalette.custom(foreground = customFg, background = customBg)
                        else -> TerminalPalette.custom(
                            foreground = materialColors.onBackground.toArgb(),
                            background = materialColors.background.toArgb()
                        )
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
                                    onQuickAddClicked = { viewModel.duplicateActiveSession() }
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
                                viewModel.updateTerminalSize(cols, rws)
                            }

                            Column(modifier = Modifier.fillMaxSize()) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
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
                                            // "real" size changes (rotation, split-screen, etc).
                                            latestTerminalSize = size
                                            resizeDebounceJob?.cancel()
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
                                                while (true) {
                                                    val event = awaitPointerEvent()
                                                    val changes = event.changes
                                                    pointerCount = changes.count { it.pressed }
                                                    val primary = changes.firstOrNull { it.id == down.id } ?: changes.firstOrNull()
                                                    if (primary == null || !changes.any { it.pressed }) break

                                                    if (pointerCount >= 2) {
                                                        // Pinch: two (or more) fingers down -
                                                        // compute zoom from the ratio of current
                                                        // to previous distance between the first
                                                        // two pointers.
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
                                                                        val charHeight = metricsPaint.fontSpacing
                                                                        val finalSize = latestTerminalSize
                                                                        if (charWidth > 0f && charHeight > 0f && finalSize != null) {
                                                                            val cols = (finalSize.width / charWidth).toInt().coerceAtLeast(1)
                                                                            val rws = (finalSize.height / charHeight).toInt().coerceAtLeast(1)
                                                                            viewModel.updateTerminalSize(cols, rws)
                                                                        }
                                                                    }
                                                                }
                                                            }
                                                        }
                                                        moved = true
                                                        changes.forEach { it.consume() }
                                                    } else {
                                                        val dy = primary.position.y - lastPos.y
                                                        if (kotlin.math.abs(dy) > 2f) {
                                                            moved = true
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
                                                    if (keyboardOpen) {
                                                        keyboardController?.hide()
                                                        focusManager.clearFocus()
                                                    } else {
                                                        focusRequester.requestFocus()
                                                        keyboardController?.show()
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
                                                modifier = Modifier.fillMaxSize().alpha(1f - blurAlpha)
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
                                            else -> KeyboardOptions(keyboardType = KeyboardType.Text)
                                        }
                                    }
                                    BasicTextField(
                                        value = hiddenInput,
                                        keyboardOptions = fieldKeyboardOptions,
                                        onValueChange = { new ->
                                            val newText = new.text
                                            when {
                                                newText.length > inputPlaceholder.length &&
                                                    newText.startsWith(inputPlaceholder) -> {
                                                    // Normal typing: everything after the placeholder
                                                    // is what the IME just inserted. Apply any armed
                                                    // CTRL/ALT modifier, then consume it (one-shot).
                                                    val typed = newText.substring(inputPlaceholder.length)
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
                                                        // Ctrl+D is a hard kill in this app, not a normal
                                                        // EOF write to the pty - per spec it always sends
                                                        // SIGKILL to the active session's process instead.
                                                        viewModel.killActiveSessionHard()
                                                    } else {
                                                        viewModel.sendInput(toSend)
                                                    }
                                                }
                                                newText.length <= inputPlaceholder.length -> {
                                                    // The placeholder itself got shortened/removed -
                                                    // that's a real backspace, so forward DEL. Handles
                                                    // multi-character deletes (e.g. predictive text
                                                    // clearing a word) by sending one DEL per missing char.
                                                    val removedCount =
                                                        (inputPlaceholder.length - newText.length).coerceAtLeast(1)
                                                    viewModel.sendInput("\u007F".repeat(removedCount))
                                                }
                                                else -> {
                                                    // Unexpected replacement (e.g. autocorrect swapped
                                                    // the whole field) - forward it as-is rather than
                                                    // silently dropping it.
                                                    viewModel.sendInput(newText)
                                                }
                                            }
                                            hiddenInput = TextFieldValue(
                                                inputPlaceholder,
                                                selection = TextRange(inputPlaceholder.length)
                                            )
                                        },
                                        modifier = Modifier
                                            .size(1.dp)
                                            .alpha(0f)
                                            .focusRequester(focusRequester)
                                            .onFocusChanged { hiddenFieldFocused = it.isFocused }
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
                                                    // Same Ctrl+D-is-a-kill rule as the real keyboard
                                                    // path, in case a future key row maps to it too.
                                                    if (seq == "\u0004") {
                                                        viewModel.killActiveSessionHard()
                                                    } else {
                                                        viewModel.sendInput(seq)
                                                    }
                                                }
                                            }
                                        }
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
                                onSettingsClicked = {
                                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                                },
                                onToggleFavorite = { viewModel.toggleFavorite(it) },
                                onSetDefault = { viewModel.setDefault(it) },
                                onDismissRequest = { viewModel.setDrawerOpen(false) }
                            )
                        }
                    }
                }
            }
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
