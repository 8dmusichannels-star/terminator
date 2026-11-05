## 📝 Changelog
 
## 🎨 Material Theme, Floating Panes & X11 Palette Picker
 
[`b26de05`](https://github.com/8dmusichannels-star/terminator/commit/b26de05)
 
### ✨ Improvements
 
- ⚡ **Zoom:** Latency reduced from 150 ms to 90 ms.
- 🖼️ **Floating Pane Images:** Image lines are archived into scrollback; images past the visible area are clipped, not deleted, and return when the pane grows.
- 📐 **Image Scaling:** Width now scales from the cell aspect ratio, like height.
- 🎨 **Pane Controls:** Material theme applied to Clone, Wake lock, Split, Save output, Move, Close and Resize.
- 🗂️ **Drawer:** Material colors for surface, scrim, headings, dividers, session names, icons, stars and the trash/error states.
- 🟦 **Active Row:** Highlighted with `secondaryContainer` instead of translucent white.
- 📋 **Session Rows:** Two-tier layout with the name on top and right-aligned actions below; buttons shrunk from 48 dp to 40 dp.
- 🪟 **Window Background:** Follows the active theme; status/navigation bar icons switch light/dark.
- 📱 **Themed Icons:** Android 13+ monochrome icons follow the wallpaper color.
- 🏷️ **X11 Names:** Each palette slot shows its current X11 color name (`X11Colors.byArgb`), or "Custom".
- ✅ **X11 Picker:** Current color is marked with ✓ and bold text.
- 👀 **Palette Preview:** Live `ls --color` sample and 16-color strip in the palette editor.
- 💬 **Palette Hint:** Notes that Black/Bright White set background/text and that Red/Yellow are pinned by status colors.
### 🐛 Bug Fixes
 
- 🔧 **Pane Photo Slip:** Fixed images slipping when a floating pane is resized.
- 🔒 **Resize Lock:** `buffer.resize()` and `emulator.onBufferResized()` now run inside the same lock as `append()`, fixing frames drawn from the wrong row.
- 🎨 **Palette Slots:** Changing Black (0) or Bright White (15) now updates the terminal background and default text color.
