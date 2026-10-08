# Changelog

All notable changes to Terminator are documented here.

## 🚀 [ebdb19c](https://github.com/8dmusichannels-star/terminator/commit/ebdb19c) — Line Wrapping & Pending Column Fixes

### ✨ Improvements

- 📝 **Improved Line Wrapping**
  - Improved terminal line-wrapping behavior.
  - Added handling for the terminal's pending-wrap state.
  - Improved cursor handling when the cursor reaches the end of the terminal line.
  - Added pending-column handling to keep cursor movement and erase operations aligned with xterm behavior.

- ⌨️ **Pending Column Support**
  - Added dedicated pending-wrap column handling.
  - Fixed cursor movement after reaching the last terminal column.
  - Improved `Backspace` behavior when the terminal is in a pending-wrap state.
  - Improved movement and erase operations from the pending-wrap position.

### 🐛 Bug Fixes

- 📋 **Line Dropdown**
  - Fixed an issue where the line dropdown could become stuck.
  - Improved dropdown interaction and state handling.

---

## ✨ [a88f50a](https://github.com/8dmusichannels-star/terminator/commit/a88f50a) — Input, Zoom, Selection & UI Improvements

### 🆕 Added

- 🔗 **Hyperlink Highlighting**
  - Added visual highlighting for terminal hyperlinks.
  - Improved hyperlink visibility and interaction.

- 🔍 **Keyboard Zoom Controls**
  - Added zoom-in support for increasing terminal text size.
  - Added zoom-out support for decreasing terminal text size.
  - Added zoom reset support.
  - Added physical-keyboard routing for zoom actions.

- 🖱️ **Physical Mouse Selection Toolbar**
  - Added a custom selection toolbar for physical mouse text selection.
  - Improved selection actions and toolbar behavior.

### 📐 Resize & Layout

- 🖥️ **Terminal Resize**
  - Fixed terminal resizing issues.
  - Improved split-terminal pane resizing and size handling.
  - Improved behavior when terminal dimensions change.

- 📏 **Terminal Width**
  - Updated terminal width configuration to `1000`.

### 🐛 Bug Fixes

- 🚫 **Cursor Suppression**
  - Fixed issues related to cursor suppression.
  - Improved cursor visibility/state handling.

- 📋 **Copy & Paste / IME**
  - Fixed an issue where the IME could open unexpectedly after using the Copy/Paste `More` menu.
  - Prevented the selection popup from unnecessarily taking window focus.
  - Improved focus handling when dismissing the selection toolbar.
  - Prevented unintended touch/IME activation during clipboard interactions.

- ⌨️ **Physical Keyboard**
  - Improved physical keyboard event routing.
  - Added keyboard-accessible zoom actions.
  - Improved keyboard-driven terminal interaction.

### 🧩 UI & Interaction

- 🔙 Improved Back-button handling for the selection actions popup.
- 🎯 Improved popup positioning and window-boundary handling.
- 🪟 Improved focus behavior for selection and action menus.

---

## 📌 Summary

### Added
- 🔗 Hyperlink highlighting
- 🔍 Keyboard zoom controls
- 🖱️ Physical mouse selection toolbar
- ⌨️ Physical keyboard zoom actions
- 📝 Pending-wrap / pending-column handling

### Fixed
- 📐 Terminal resize issues
- 📋 Stuck line dropdown
- 🚫 Cursor suppression issues
- 📋 Copy/Paste triggering the IME unexpectedly
- 🖱️ Mouse selection toolbar behavior
- ⌨️ Physical keyboard interaction
- 📝 Terminal line-wrapping and end-of-line cursor behavior

### 🔧 Technical

- Improved xterm-compatible pending-wrap cursor handling.
- Improved terminal cursor movement and erase behavior at the last column.
- Improved Compose popup focus handling to prevent unwanted IME activation.
- Improved terminal pane resizing and physical input routing.
