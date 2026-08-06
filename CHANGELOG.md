# Terminator v0.3.0

> Major terminal emulation improvements, notification fixes, PTY stability enhancements, and numerous usability refinements.

---

## 🚀 Highlights

### 🔔 Notification Session Restoration
- Fixed an issue where tapping a notification created a new terminal session instead of restoring the existing one.
- Notifications now correctly return to the active session and preserve its state.
- Improved notification handling and session management.

### 🖥️ Terminal Emulator Improvements

#### ANSI / VT Parser
- Improved OSC escape sequence handling.
- Improved CSI escape sequence parsing.
- Added support for `CSI >` parameter prefixes.
- Added Device Status Report (DSR) reader/writer separation.
- Improved surrogate pair and emoji handling.
- Added support for `CSI 5n` and `CSI 6n`.
- Connected `onRespond()` callback with `MainViewModel.sessionWrite()`.

#### Process Management
- Improved `Ctrl+D` behavior.
- The terminal now only closes the PTY when no foreground process is running.

---

## 📋 Clipboard & Selection

- Improved copy selection behavior.
- Improved clipboard reliability.
- Better paste stability.
- General selection improvements.

---

## 📱 User Interface

- Fixed terminal rendering in landscape orientation.
- Improved notification session restoration.
- General rendering and responsiveness improvements.

---

## 🛠️ Bug Fixes

- Fixed notification session recreation.
- Fixed landscape rendering issues.
- Fixed DSR handling.
- Fixed CSI parser edge cases.
- Fixed OSC parser issues.
- Improved PTY synchronization.
- Improved ANSI escape sequence processing.

---

## ⚡ Performance

- Reduced parser overhead.
- Improved PTY synchronization.
- Better terminal responsiveness.
- More stable escape sequence processing.

---

## 🚧 Known Issues

The following issues are known and planned for future releases:

- `clear` may still leave PTY/scrollback residue.
- Copy/Paste popup menu positioning needs improvement.
- Clipboard paste may occasionally duplicate text.
- Pasting can sometimes close the virtual keyboard.
- Vertical scrolling may feel sluggish on long scrollback.
- Scrollbar may not always reach the earliest or latest scrollback position.
- Text selection cannot always reach the oldest scrollback content.
- Clipboard history (Slideboard) is planned for a future release.

---

## 📜 Included Commits

- [`5de60dc`](../../commit/5de60dc) — Notification session restoration, parser improvements, PTY handling, UI fixes
- [`80c8d5f`](../../commit/80c8d5f) — DSR reader/writer separation
- [`8d46c15`](../../commit/8d46c15) — CSI prefix support and surrogate pair handling
- [`f50adef`](../../commit/f50adef) — CSI `5n` / `6n` handling
- [`07c7e35`](../../commit/07c7e35) — OSC/CSI parser improvements
- [`e054e44`](../../commit/e054e44) — OSC/CSI parser improvements
- [`73fb89a`](../../commit/73fb89a) — fdsan crash and PS1 escape fixes

---

Thank you to everyone testing Terminator and reporting bugs. Your feedback continues to improve the project's stability and feature set.
