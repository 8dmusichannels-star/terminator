# Changelog

## [0b96846](https://github.com/8dmusichannels-star/terminator/commit/0b96846) - Terminal Resize & Input Improvements 🚀

### ✨ Added
- 🖱️ Added physical mouse scroll wheel support.
- 🔄 Improved hardware mouse wheel event handling.

### 🐛 Fixed
- 📐 Fixed terminal resize pixel calculation bug.
- 🖥️ Fixed incorrect pixel dimension reporting during terminal resize.
- 🧹 Fixed scrollback self-delete issue with `CSI 3J`.

### 🔧 Updated
- 📏 Improved real pixel width/height calculation.
- ⚙️ Updated `ioctl(TIOCSWINSZ)` handling:
  - Added correct `ws_xpixel`
  - Added correct `ws_ypixel`
  - Kernel now receives actual terminal pixel dimensions.

### 📊 Summary
- ✨ Added: 1
- 🐛 Fixed: 3
- 🔧 Updated: 2

Terminal rendering, resizing accuracy and physical input handling are now more reliable. 🎯
