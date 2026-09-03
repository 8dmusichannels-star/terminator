# Changelog

## 🚀 1.1.7

### 📋 Selection & UI
- [`753bf77`](https://github.com/8dmusichannels-star/terminator/commit/753bf77) — Improved selection toolbar window clamping.
- Fixed terminal content escaping the selection toolbar bounds.
- [`c23a813`](https://github.com/8dmusichannels-star/terminator/commit/c23a813) — Improved selection actionbar viewport positioning and automatic bottom-center anchoring.
- Improved temporary selection-popup hiding.

### 📂 Entry Path
- [`7142a9b`](https://github.com/8dmusichannels-star/terminator/commit/7142a9b) — Improved entry-path host-side `chdir` override handling.
- [`0f42735`](https://github.com/8dmusichannels-star/terminator/commit/0f42735) — Added failed-`chdir` reporting while allowing execution to continue.

### 🛡️ Stability
- [`356d843`](https://github.com/8dmusichannels-star/terminator/commit/356d843) — Fixed cloned-app terminal-session crash.

### ⌨️ IME & Keyboard
- [`8b191d2`](https://github.com/8dmusichannels-star/terminator/commit/8b191d2) — Added tap-to-toggle IME support for multipanel containers.
- Fixed unwanted IME opening from taps/presses.
- Fixed IME opening when the panel is unfocused.
- Improved `wantsKeyboard` and multipanel keyboard preferences. 
- The transition touches `wantsKeyboard` never touches.
- Fixed virtual-keybar swipe closing IME in long-text fields.
- Improved floating-screen and virtual-keybar interactions.

### 📊 Summary
- ✨ Native PTY improvements
- 📐 Pixel-aware terminal resizing
- 📋 Selection UI fixes
- 📂 `chdir` / entry-path improvements
- 🛡️ Clone-session crash fix
- ⌨️ IME & virtual-keybar fixes

**7 commits · 26 tracked changes**

We happily announce that **Terminator** is now out of beta and is now a stable release!

## 🔗 Repository

[**8dmusichannels-star/terminator**](https://github.com/8dmusichannels-star/terminator)
