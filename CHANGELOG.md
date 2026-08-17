# 🚧 Terminator Pre-Release

## ✨ What's New

### (unreleased) Native text selection

* 🔄 **Selection/Copy now uses Compose's native `SelectionContainer`** (requires compose-bom `2026.08.00` / Compose 1.12) instead of the hand-rolled long-press/drag/edge-scroll pointerInput logic - this is the fix for the recurring "kesik kusuk" / partial-copy-with-blank-spaces bug class. `TerminalView` now overlays an invisible, real-text row stack on top of its Canvas; Android owns long-press, drag handles, edge auto-scroll, and the Copy toolbar. **⚠️ Not yet build-verified on a real toolchain** - `compileSdk`/AGP were bumped to match, but the Kotlin/Compose-compiler plugin setup may also need updating for Kotlin 2.x before this actually compiles. See `TerminalView.kt` and `MainActivity.kt`'s gesture loop for details.

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

> ⚠️ **Pre-release:** Some split-screen and toolbar features are still experimental and may receive further changes.
