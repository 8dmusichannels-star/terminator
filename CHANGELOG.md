# 🚧 Terminator Pre-Release

## ✨ What's New

### [`80bb2e7`](https://github.com/8dmusichannels-star/terminator/commit/80bb2e7)

* 🧱 **Multi-pane mode (experimental)** added - an arbitrary number of independent panes, each bound to its own running session, shown at once instead of the classic single/split-pane view. Opt-in per session via a new grid icon on each running-session row in the drawer; the classic single-pane and 2-pane split views are untouched and remain the default.
* 🪟🧊 **Tiling and Floating layouts**, switchable at any time from the pane toolbar. Tiling auto-arranges panes into a grid with draggable dividers between them. Floating lets every pane be dragged and resized freely and panes may overlap, brought to front with a tap - same feel as a desktop window manager.
* 📌 **Floating pane position/size is remembered per session**, persisted across app restarts, so a session's floating window reopens exactly where it was last left.
* ⌨️ **Direct tap-to-type in every pane** - tapping a pane's terminal focuses it and brings up the keyboard directly, no separate "Type here…" input box. This also replaces the old separate input field on the split-screen secondary pane with the same direct-tap-to-type behavior.
* 📡 **New Settings > Display > "Broadcast to all panes" toggle.** Off (default): typed input goes only to whichever pane was last tapped to focus. On: every keystroke is mirrored to every visible pane at once.
* ➕ Each running-session row in the drawer now has an **"Add to panes"** icon, alongside the existing Split icon - tapping it starts multi-pane mode (seeded with the current session) or adds that session to an already-open pane group.
* ✂️ Every pane, including the split-screen secondary pane, now gets its **own independent native text selection** (see the native-selection entry below) - long-press/drag-to-select and the OS's own selection toolbar work per-pane.
* ⚠️ **Experimental / not yet build-verified on a real toolchain.** Multi-pane is a large addition (new gesture handling for drag/resize, tiling grid math, floating-window geometry) - expect rough edges, especially around drag/resize gestures and pty resizing on real devices.

### [`0dc8396`](https://github.com/8dmusichannels-star/terminator/commit/0dc8396) · [`3b4bd00`](https://github.com/8dmusichannels-star/terminator/commit/3b4bd00) — Native text selection

* 🔄 **Selection/Copy now uses Compose's native `SelectionContainer`** (requires compose-bom `2026.08.00` / Compose 1.12) instead of the hand-rolled long-press/drag/edge-scroll pointerInput logic - this is the fix for the recurring "kesik kusuk" / partial-copy-with-blank-spaces bug class. `TerminalView` overlays an invisible, real-text row stack on top of its Canvas; Android owns long-press, drag handles, and edge auto-scroll. The **Copy/Paste toolbar is now Android's own native floating toolbar** (`LocalTextToolbar.showMenu`) - not a custom-styled composable anymore. `SelectionToolbar.kt` is gone; Copy pulls straight from the new `SelectionState` API (`rememberSelectionState()`/`selectedTexts`) instead of hand-tracked (row, col) pairs, so there's no more separate highlight-draw vs. copied-text logic to go out of sync. The old toolbar's Clone-session/Save-history quick actions are dropped (the native toolbar can't show custom actions) - both already have other entry points (titlebar "+" and the export menu), so nothing is actually lost. **⚠️ Not yet build-verified on a real toolchain** - `compileSdk`/AGP were bumped to match, but the Kotlin/Compose-compiler plugin setup may also need updating for Kotlin 2.x before this actually compiles. See `TerminalView.kt` and `MainActivity.kt`'s toolbar wiring for details.

### [`60c380b`](https://github.com/8dmusichannels-star/terminator/commit/60c380b)

* 🧩 **ZWJ Emoji Support** added.
* 🖼️ **Session picture icons** now support `.svg` format.
* 📋 Fixed a bug where **copying content resulted in blank content**.
* 🪟 Improved split panel by removing the unwanted **black block**.
* ➖ Added a **straight divider line** to the split panel.
* 💾 Added **Runner Session Save** button.
* 🔘 Improved **Toggle Save Button** management.

### [`103152f`](https://github.com/8dmusichannels-star/terminator/commit/103152f)

* 🪟 Added **Split Screen** support.
* 🛠️ Added an **experimental toolbar** for split-screen usage.
* 📋 Added support for saving **all Clipboard session history**.
* 🤏 Fixed the **pinch gesture slide-up** issue.
* ⌨️ Fixed keyboard behavior when using **Copy / Paste / Close** toolbar actions.

## 📦 Repository

[**8dmusichannels-star/terminator**](https://github.com/8dmusichannels-star/terminator)

> ⚠️ **Pre-release:** Some split-screen, multi-pane, and toolbar features are still experimental and may receive further changes.
