## 📝 Changelog

## 🚀 Terminal Rendering, Kitty Graphics & Sixel Improvements

[`4e7c410`](https://github.com/8dmusichannels-star/terminator/commit/4e7c41005a6113345378018b2ff4d4c74a72ebb6)

### ✨ Improvements

- 🖼️ **Kitty Graphics:** Added row tracking to `PlacedImage` and `KittyPlacement`.
- 📜 **Scrollback Handling:** Improved row indexing relative to the cursor and scrollback buffer.
- 🖥️ **TerminalView Rendering:** Added visible-area cropping during image rendering.
- ⌨️ **Cursor & Scrolling:** Improved cursor positioning and screen scrolling behavior.
- 🎨 **Kitty C=1:** Preserved cursor and screen positions.
- 🧹 **Image Clearing:** Updated image clearing behavior for `ESC[2J` and RIS.
- 🖼️ **Sixel Decoder:** Introduced incremental streaming decoding with unlimited input size.
- 📐 **Sixel Limits:** Restricted decoded output to 8192×8192 pixels and 16M total pixels.
- 📡 **DA1 Response:** Updated device attributes response to `?1;2;4c`.
- 📏 **Window Dimensions:** Added CSI 14t and CSI 16t handling through `TerminalSession.resize`.
- ⚡ **Parallel Decoding:** Moved decoder processing into separate background threads.

### 🐛 Bug Fixes

- 🔧 **Sixel Parser:** Fixed handling of DCS sequences exceeding 2 MB without prematurely returning to NORMAL state.
- ⏳ **Sixel Termination:** Fixed decoding synchronization when encountering the Sixel ESC termination sequence.
- 🔍 **Zoom Rendering:** Fixed an issue causing images to be cleared during zoom.
- 🧹 **Image Row Spans:** Removed obsolete `updateImageRowSpans` processing and calls.

---

## 🔒 Terminal State, Synchronization & Rendering Improvements

[`22e54df`](https://github.com/8dmusichannels-star/terminator/commit/22e54dfa2eaaeff372730764869570941c869857)

### ✨ Improvements

- 💾 **Alternate Screen:** Added SGR state preservation when entering modes 1049 and 47.
- 🔄 **Screen Initialization:** Improved alternate-screen initialization and default clearing behavior.
- 🎨 **Cursor State:** Added color preservation and restoration for `ESC 7/8` and `CSI s/u`.
- 🔒 **DEC 2026:** Improved synchronized output handling with `ESC[?2026h` and `ESC[?2026l`.
- ⏱️ **Synchronization Timeout:** Added a 1-second safety timeout for synchronized updates.
- 🕒 **Rendering Timeout:** Improved 150 ms rendering timeout handling.
- 📋 **Cell Copying:** Implemented proper deep copying of terminal cell rows instead of copying row references.
- ⚡ **PTY Performance:** Added an approximately 8 ms paint delay when the PTY read buffer is nearly full.

### 🐛 Bug Fixes

- 🖌️ **Synchronized Painting:** Fixed rendering while DEC 2026 synchronized updates are active.
- 🔄 **Frame Synchronization:** Improved frame flushing after synchronized output completes.
- ⚡ **Large Writes:** Improved rendering stability during large PTY write operations.
