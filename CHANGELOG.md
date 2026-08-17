# Terminator — Multi Split Screen & Native Copy/Paste Updates

This release includes major updates to the terminal session interface and Android's native copy/paste system.

## ✨ Highlights

### Experimental Multi Split Screen

Added an experimental **Multi Split Screen** system that allows multiple terminal sessions to be displayed and managed simultaneously.

* Added multi-pane terminal support.
* Added pane focus management.
* Added pane resizing and repositioning.
* Added support for adding and removing terminal sessions from the multi-pane layout.
* Added multi-pane session management.
* Added an option to broadcast keyboard input to all visible panes.
* Added experimental multi-pane UI and controls.
* Added support for switching between different pane modes.
* Added pane-specific terminal sizing.
* Existing single/split terminal behavior remains available when multi-pane mode is inactive.

Commit: [80bb2e7](https://github.com/8dmusichannels-star/terminator/commit/80bb2e7)

---

## 📋 Native Android Copy/Paste API

Terminator has been migrated to the **native Android copy/paste API system**.

### Changes

* Reworked the Android text selection and copy/paste implementation.
* Added native Android selection toolbar integration.
* Removed the previous custom copy/paste handling where no longer required.
* Improved compatibility with Android's native text selection behavior.
* Improved interaction between terminal selection and the Android system.
* Updated terminal selection handling across the application.
* Continued cleanup and transition toward Android's native clipboard APIs.

Commit: [0dc8396](https://github.com/8dmusichannels-star/terminator/commit/0dc8396)

---

## 🔧 Native Copy/Paste API Transition

The initial transition to the native Android copy/paste system has been completed.

* Native Android copy/paste API is now used by the terminal.
* Updated terminal text selection behavior.
* Improved compatibility with Android system selection controls.
* Removed legacy copy/paste behavior where applicable.
* Prepared the terminal UI for the newer native Android selection workflow.

Commit: [3b4bd00](https://github.com/8dmusichannels-star/terminator/commit/3b4bd00)

---

## 🧪 Experimental

The **Multi Split Screen** functionality is currently experimental.

Some UI behavior, pane management, and interaction details may change in future releases as the feature continues to be developed and stabilized.

## 🔗 Commits

* [80bb2e7](https://github.com/8dmusichannels-star/terminator/commit/80bb2e7) — Experimental Multi Split Screen
* [0dc8396](https://github.com/8dmusichannels-star/terminator/commit/0dc8396) — Native Android Copy/Paste API transition and fixes
* [3b4bd00](https://github.com/8dmusichannels-star/terminator/commit/3b4bd00) — Initial native Android Copy/Paste API implementation

## 📱 Project

**Terminator** is an Android terminal emulator focused on a compact, customizable terminal experience with multiple session support.

More information is available in the [repository](https://github.com/8dmusichannels-star/terminator).
