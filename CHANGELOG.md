# 🚀 Terminator v2.3.2

> A major terminal emulation, graphics, protocol, rendering and interaction update.

## ✨ Highlights

- 🖥️ **Advanced Terminal Protocol Support**
  - Added and updated Sixel image decoder support.
  - Added and updated Kitty Graphics, Image, Terminal and Keyboard protocol support.
  - Added XTVERSION support.
  - Added OSC 8 hyperlink support.
  - Added OSC 52 clipboard support.
  - Added OSC 133 shell integration support.
  - Added OSC 1337 iTerm2 inline image support.
  - Added OSC 4/10/11/12 color support.
  - Added OSC 7 current working directory support.
  - Added DECCOLM support.
  - Added DECSLRM support.
  - Added DECOM support.
  - Added IRM insert/replace mode.
  - Added DECSCNM reverse-screen mode.
  - Added LNM / New Line Mode.
  - Added SRM / Send-Receive Mode.
  - Added DECARM / Auto Repeat Mode.
  - Added G2/G3 character sets.
  - Added LS2/LS3/SS2/SS3 support.

- 🎨 **Rendering & SGR**
  - Added truecolor support.
  - Added real xterm-256color support.
  - Added SGR 8 conceal.
  - Added SGR 2/22 dim/faint.
  - Added SGR 5/25 blink.
  - Added SGR 9/29 strikethrough.
  - Added SGR 53/55 overline.
  - Added SGR 4:3 undercurl.
  - Added SGR 58/59 underline color.
  - Improved terminal palette resolution.
  - Improved RGB and truecolor handling.
  - Added X11 colors and presets.

- 🖼️ **Graphics & Images**
  - Added Sixel image decoder support.
  - Added Kitty Graphics protocol support.
  - Added iTerm2 OSC 1337 inline image support.
  - Fixed Sixel/Kitty images disappearing when IME is opened or closed.
  - Improved image rendering and persistence during resize operations.

- ⌨️ **Keyboard & Input**
  - Added physical keyboard support improvements.
  - Added DECKPAM / DECKPNM (`ESC =` / `ESC >`).
  - Added DECARM keyboard repeat support.
  - Added physical keyboard repeat toggle.
  - Added SRM force-local-echo option.
  - Added bracketed paste mode 2004.
  - Improved IME and keyboard behavior during pinch zoom.
  - Improved multi-touch and gesture event handling.

- 🔗 **Hyperlinks & Clipboard**
  - Added OSC 8 hyperlinks.
  - Added custom hyperlink scheme toggle.
  - Added hyperlink whitelist support.
  - Added OSC 52 clipboard support.
  - Added OSC 52 `onclipboardget` toggle.
  - Improved hyperlink and clipboard handling.

- 🐚 **Shell Integration**
  - Added OSC 133 shell integration.
  - Added OSC 7 current working directory support.
  - Improved terminal state handling for shell integration.

## 🧩 Terminal Compatibility

- Added REP — `CSI Pn b`.
- Added CHT — `CSI Pn I`.
- Added CBT — `CSI Pn Z`.
- Added XTWINOPS — `CSI Ps t`.
- Added DA1 — `CSI c`.
- Added DA2 — `CSI > c`.
- Added DECALN — `ESC # 8`.
- Added DECKPAM — `ESC =`.
- Added DECKPNM — `ESC >`.
- Added VT `\v` / FF `\f` → LF behavior.
- Added TBC `Ps 1/2` line tab stops.
- Added improved terminal state handling.
- Added and updated bundled `terminfo` entries.
- Improved xterm, screen, tmux, vt220 and xterm-kitty compatibility.

## 🪟 Split Screen & UI

- Added vertical scrolling to Keyboard Settings.
- Added vertical scrolling to Session Settings.
- Improved split-screen broadcasting.
- Improved split-pane resize handling.
- Improved floating panel resizing.
- Improved GridView/FloatingPanel/MultiPanelContainer zoom behavior.
- Improved terminal type selection UI.
- Added new terminal type popup options.
- Added `NONE` terminal type mode.
- Fixed split-screen broadcast input disorder.
- Fixed split-screen broadcast issues.
- Fixed floating-mode resize delay and opacity problems.

## 🔍 Zoom & Resize

- Added real buffer resize during gestures.
- Added 150 ms real-commit throttling for gesture-triggered resizing.
- Improved pinch-to-zoom handling.
- Improved font-size synchronization.
- Fixed zoom black-screen issues.
- Fixed zoom/resize black-space issues.
- Fixed zoom/resize split cracks.
- Fixed involuntary keyboard open/close after zoom.
- Fixed missing final pinch-zoom resize commit.
- Fixed deferred IOCTL handling during pinch gestures.
- Fixed pointer-count handling during multi-touch gestures.
- Fixed font-size synchronization across screens.

## 🖱️ Selection & Text

- Added double-click word selection.
- Improved area-based selection.
- Improved line selection behavior.
- Fixed selection of non-selected areas.
- Fixed selection handle flickering.
- Fixed incorrect row-wide selection behavior.

## 🐛 Bug Fixes

- Fixed emoji/ZWJ rendering issues.
- Fixed screen tearing in Btop-like terminal applications.
- Fixed copy/paste data occasionally being lost.
- Fixed ANSI escape latency.
- Fixed terminal palette RGB resolution behavior.
- Fixed truecolor marker packing/restoration.
- Fixed floating resize scrollback deletion.
- Fixed status-bar display remaining enabled unexpectedly.
- Fixed zoom-related keyboard state changes.
- Fixed split-screen input ordering.
- Fixed image disappearance during IME transitions.
- Fixed multiple multi-touch gesture and IOCTL timing issues.

## 🎨 Text Attributes

- Added **Dim/Faint** — SGR `2/22`.
- Added **Blink** — SGR `5/25`.
- Added **Strikethrough** — SGR `9/29`.
- Added **Conceal** — SGR `8`.
- Added **Overline** — SGR `53/55`.
- Added **Undercurl** — SGR `4:3`.
- Added **Underline Color** — SGR `58/59`.

## 📌 Commits

### [`83c5940`](https://github.com/8dmusichannels-star/terminator/commit/83c5940)

🛠️ **Updated and fixing**

Major terminal protocol, graphics, rendering, keyboard, hyperlink, clipboard, shell integration, zoom, resize and UI improvements.

- 🖼️ Sixel / Kitty Graphics
- 🔗 OSC 8 / OSC 52
- 🐚 OSC 7 / OSC 133 / OSC 1337
- 🎨 OSC colors / truecolor
- ⌨️ Keyboard and DEC modes
- 🪟 Split-screen and floating UI
- 🔍 Zoom and resize improvements
- 🐛 Extensive rendering and interaction fixes

### [`e6881ed`](https://github.com/8dmusichannels-star/terminator/commit/e6881ed)

🛠️ **Updated and fixing**

Major rendering, selection, truecolor, resize, zoom, keyboard, paste and terminal compatibility improvements.

- 🎨 Truecolor / xterm-256color
- 🖱️ Selection improvements
- 🔍 Zoom and resize
- ⌨️ Keyboard and paste handling
- 🎨 Dim / blink / strikethrough
- 🧩 Terminal compatibility
- 🐛 Multiple rendering and UI fixes

## 📊 Release Summary

| Category | Changes |
|---|---|
| 🚀 Version | `2.3.2` |
| 🧩 Commits | `2` |
| 🖥️ Protocols | Sixel, Kitty, OSC, DEC, XTerm and more |
| 🎨 Rendering | Truecolor, 256-color, dim, blink, conceal, strike-through, overline, undercurl |
| 🖼️ Graphics | Sixel, Kitty Graphics, iTerm2 inline images |
| ⌨️ Input | Keyboard, IME, physical keyboard, bracketed paste |
| 🔗 Hyperlinks | OSC 8 + custom schemes + whitelist |
| 📋 Clipboard | OSC 52 |
| 🐚 Shell | OSC 7 + OSC 133 |
| 🪟 UI | Split-screen, floating panels, resize and zoom |
| 🐛 Fixes | Rendering, input, selection, resize, image and UI fixes |

---

## 🚀 Terminator v2.3.2

**More protocols. 🎨 Better rendering. ⌨️ Better input. 🖼️ Better graphics. 🐛 Fewer bugs.**

