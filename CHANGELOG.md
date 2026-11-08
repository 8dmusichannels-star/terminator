# 📋 Changelog

[`2267429`](https://github.com/8dmusichannels-star/terminator/commit/22674299dc6694cb438662066d912a5fb3ab2984)

### ✨ Added

**🖥️ Terminal Emulation**
- 🧩 `TermProfile` (GENERIC / XTERM / XTERM_COLOR / SCREEN_TMUX / VT220 / VT100 / ANSI): key encodings, DA1/DA2/DECID replies and default conformance level; Terminal Type `NONE` now uses a neutral GENERIC profile
- 🔤 NRCS and 96-char charset support (SCS), DECNRCM, DEC Supplemental, Latin-1
- 📡 DECSCL/DECSTR, S7C1T/S8C1T, 8-bit C1, DECID, DA3, DECXCPR/DECDSR, DECRQM, DECRQSS, XTSAVE/XTRESTORE
- 📊 DECRQPSR (`CSI Ps $ w`): DECCIR cursor info report and DECTABSR tab stop report (verified against real xterm)
- 🧽 Selective erase (DECSCA/DECSED/DECSEL), DECBKM (67), reverse wrap (45), mouse 1005/1015, 1047/1048, alternate scroll (1007)
- 🔑 XTMODKEYS (modifyOtherKeys) set/query/reset
- 💡 Cursor blink tracking (DECSET 12 / DECSCUSR) with new `CursorBlinkPolicy`

**⌨️ Physical Keyboard**
- 🎹 Xterm modified-key forms (`CSI 1;m X` / `CSI n;m ~`), Alt in modifier parameter
- 🐱 Kitty keyboard protocol wired up (flags 1/2/8/16, release events), encoder rewritten per spec
- 🔧 modifyOtherKeys encoding for Enter/Tab/Backspace/Esc and character keys
- 🔢 DECCKM, DECBKM, per-TERM profile and DECKPAM (numpad) support
- 🪟 Key state read from the pane that actually receives input (multi-pane / split / active session)
- 🔍 `Ctrl` + `+` / `-` / `0` text zoom
- ☠️ `Ctrl+D` hard kill; `Enter` dismisses an exited session
- 🖱️ Mouse wheel scrolls alt screen via arrow keys when mode 1007 is set
- 📱 On-screen key bar uses the same profile + DECCKM logic

**⚙️ Settings**
- 🎛️ Settings › Keyboard › **Cursor blink** (Off by default / Follow program / Always)

### 🐛 Fixed

- 🧟 Zombie processes: spawned children are now reaped via `NativePty.waitFor` on a daemon thread
- 🏁 Race in `TerminalSession.markExited()`: "Process completed" now appended under `emulatorAppendLock`
- 🔁 `NativePty.waitFor` (`pty.c`): retries on EINTR, returns `-1` on `waitpid` failure instead of bogus exit code 0
- 🥶 UI freeze when zooming full-screen apps (btop): `emulatorAppendLock` is now a fair `ReentrantLock`; `flushPendingCluster` also runs under it
- 💥 Reader thread no longer crashes the app on `RuntimeException` from `emulator.append()`: chunk dropped, error logged (`TerminatorSession`)
- 🚫 `CSI ?` sequences with non h/l finals acting as mode resets, and `CSI > 4;2 m` applied as SGR
- 🛡️ CSI with unsupported intermediate byte executing as the plain final (DECCARA → DECSTBM, `SP @` / `SP A` → ICH/CUU); only `SP q`, `" q`, `" p`, `! p`, `$ p`, `$ w` are dispatched now

[`d37a0f9`](https://github.com/8dmusichannels-star/terminator/commit/d37a0f9df9e138cfafcbadeb5c83882cda3cae9)

### ✨ Added
- 🖼️ 4 new screenshots: `phoneScreenshots/6.png`, `7.png`, `8.png`, `9.png`

### 🔄 Updated
- 📦 `showcase/showcase1-5.png` moved to `fastlane/metadata/android/en-US/images/phoneScreenshots/1-5.png` (100% rename, content unchanged)
- 🖼️ `phoneScreenshots/10.png` replaced with a new image (55 KB → 1 MB)
- 📝 `README.md` Showcase section: all image links now point to the fastlane path, 5 → 10 images

### 🗑️ Removed
- 🧹 Old screenshots `01.png`–`09.png`, `11.png`, `12.png` (11 files)

