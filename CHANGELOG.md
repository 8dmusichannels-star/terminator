# Changelog

## 🚀 Latest Updates

### [`5cb3c35`](https://github.com/8dmusichannels-star/terminator/commit/5cb3c35) — Environment, Keymapper & Split-Screen Improvements

- 🛠️ **Environment override** support improved.
  - Added and updated handling for `PATH`, `HOME`, `TERM`, `TMPDIR`, and `TERMINFO`.
- ⌨️ **Keymapper** improvements.
  - Added hold/repeat support for `ALL_KEY_OPTIONS`.
  - Improved literal string processing.
- 🖥️ **Split-screen** handling improved.
  - Updated `CTRL+D` behavior.
  - Added special handling for `SIGKILL`.
  - Added `sendtoscreenkill` with **SIGKILL-aware path handling**.
  - Fixed bugs related to process termination and split-screen handling.
- 🐛 Improved terminal process and session stability.

### [`15fa409`](https://github.com/8dmusichannels-star/terminator/commit/15fa409) — XTerm Mouse & Touch Improvements

- 🖱️ Improved **XTerm mouse reporting**.
- 👆 Improved touch and mouse gesture handling.
- 🖱️ Fixed and improved **physical mouse** input behavior.
- 🔄 Improved mouse event processing and reporting consistency.
- 🐛 Fixed several mouse interaction and gesture-related bugs.

---

## 📋 Summary

This update focuses on improving:

- 🛠️ Terminal environment and process handling
- ⌨️ Keymapper, hold/repeat, and literal string processing
- 🖥️ Split-screen and `SIGKILL` handling
- 🖱️ XTerm mouse, touch gestures, and physical mouse support
- 🐛 Stability, reliability, and bug fixes

---

## 🔗 Repository

[**8dmusichannels-star/terminator**](https://github.com/8dmusichannels-star/terminator)
