# TERMINATOR

[![GitHub Downloads](https://img.shields.io/github/downloads/8dmusichannels-star/terminator/total)](https://github.com/8dmusichannels-star/terminator/releases)

Terminator is a terminal emulator. It offers comprehensive terminal emulator support with multi-session support that you can directly customize and control. Its main purpose is a terminal emulation that can only be read by the user and is suitable for daily use. Its main difference from other terminal emulators is mostly in the interface area — it comes with embedded terminal colors and a completely compact interface. It does not offer a ready-made chroot/proot environment; setting up the terminal tool infrastructure and other work is left to the user. Its terminal environment and terminal user interface are modern. Supports Android.

## Features

It comes with a Material user interface, supports all color mappings, and implements the terminal in its own color interface.

AMOLED black support and dynamic Material color support are available in the terminal. If you want to keep it simple or plain, you can just enter an RGB color code — custom CSS terminal color support is also offered. It comes with support for color palettes, including the following themes: default, Solarized Dark, Gruvbox Dark, Dracula, Nord. You can also configure the palettes yourself. Termcolor and CSS color support is offered as well.
[Terminator-themes](https://github.com/8dmusichannels-star/terminator-themes) is a repo containing examples of custom themes and palettes used for the terminal emulator.

There is multi-session support: you can create multiple sessions and choose a default or favorite one. After adding a session, there is support for file-based sessions or command-argument sessions. In a command-arg session, you directly specify the executable that should be run. If you are using root, you can directly activate a root session without using /system/bin/su — specify the login path and the directory path that will act as the startup directory, then start the terminal through the session you've set as default. The file-based method works the same way: by specifying the shell script's name and path, you can specify the directory to be created and run it with the login path, while also activating the root session. However, if you specify a chroot- or proot-like environment as an argument, the starting directory may not work — this is only valid for arguments that support an entry path. The validity of chroot in root/proot-like environments is variable. There is a purge setting under clear, related to terminal behavior — with this, you can determine whether old data remaining in the terminal's cleared PTY buffer is actually wiped or not. Hyperlink support is also offered: only the formats we allow can be opened, via a whitelist. If you want to bypass the whitelist restrictions, you can enable "Allow custom app schemes" in the session settings, but you'll be responsible for any resulting security or other issues. OSC 32 clipboard read support is included, so reading and copying is possible on a clipboard/terminal basis. SRM and local echo are available, but if you use an IME, double characters may appear — there is a typing risk here, the same as the risk you'd have on a physical keyboard.

Resizing the terminal is supported based on text size, terminal width, or temporary session zoom. Alpha (transparency) is supported, along with terminal background support and background blur. It has font support — monospace, sans, and serif embedded fonts are available, along with custom font support so you can add TTF/OTF fonts. You can also control whether terminal screen zoom support (pinch-to-zoom) is on or off; it is turned on by default.

Ringtone support is included — you can choose a custom or default notification sound from the system.

OSC 777 and OSC 9 notification support is offered. If you enable the notification feature on your phone, notification support will now work in the local terminal. This way, a notification-based app that can send you notifications in the terminal — whether built by someone else or developed by you — can now reach you.

You can show or hide the status bar and title bar on screen. Horizontal orientation mode is supported, and you can use the terminal this way as well. You can export the terminal session as a .txt file, and you can determine whether the save button appears in the bar. Using the split-screen visibility toggle, you can turn the buttons that enable split-screen and floating-screen switching on or off. If you turn on the "broadcast to all panes" toggle, you can type text or commands into more than one terminal session at the same time, in split-screen and floating-screen mode. You can also manage all sessions with the "clear all sessions" toggle: when it's off, the clear button is hidden when a session is closed; when it's on, it clears all sessions. Physical keyboard and mouse support is also offered. Full Kitty protocol support and Kitty terminal emulation support are offered. Sixel image rendering support is offered, along with iTerm2 inline image support.

As for keyboards, both a soft keyboard (tap to open/close the terminal) and a virtual keybar are supported. As for login mode:

- **default** — compatible with ALL IMEs, including a solid CJK terminal button
- **semantically correct** — but may break some IMEs
- **Old workaround** — fixes Samsung keyboard echo; could break Gboard
- **CJK input** — supported, but default is recommended

3 types of terminal types are supported:

- **NONE** — don't set `$TERM` at all; whatever the shell/exec environment already provides is left as-is
- **xterm** — base xterm entry, no 256-color extension
- **xterm-color** — xterm with basic 16-color support
- **xterm-256color** — recommended; full color and feature support, most full-screen apps (vim/htop/nano) expect this
- **screen** — for running inside GNU screen, no 256-color extension
- **screen-256color** — GNU screen with 256-color support
- **tmux** — for running inside tmux, no 256-color extension
- **tmux-256color** — tmux with 256-color support
- **xterm-kitty** — for kitty-protocol-aware programs (kitty graphics/keyboard protocol); not in the standard ncurses db, needs the separately-bundled entry
- **vt220** — older DEC VT220 compatibility
- **vt100** — minimal, near-universal; color support is limited, but will work even without a terminfo entry (ncurses has it hardcoded)
- **ANSI** — basic ANSI/DOS-style color support, no 256-color extension

You can control whether the soft keyboard is on or off with a toggle. With the virtual keybar toggle, you can control whether the virtual keybar appears or not. With the keyboard shortcuts/keymapper toggle, you can control whether the keymapper buttons appear or not.
Keyboard shortcuts and keymapper support are also provided — you can assign or map physical keyboard input directly to the virtual keyboard. Additionally, seccomp support is available to resolve operation-disallowed errors. And there are more features.

## Build

### Debug setup

```bash
gradle assembleDebug --stacktrace
```

### Release (signed) setup

Keystore environment must be specified:

| Variable | Value |
|---|---|
| `KEYSTORE_PATH` | `${{ runner.temp }}/release.keystore` |
| `KEYSTORE_PASSWORD` | `${{ secrets.KEYSTORE_PASSWORD }}` |
| `KEY_ALIAS` | `${{ secrets.KEY_ALIAS }}` |
| `KEY_PASSWORD` | `${{ secrets.KEY_PASSWORD }}` |
| `KEYSTORE_BASE64` | `${{ secrets.KEYSTORE_BASE64 }}` |

Build command:

```bash
gradle assembleRelease --stacktrace
```

# Install

<a href="https://www.openapk.net/terminator/com.terminator.app/"><img src="https://www.openapk.net/images/openapk-192.png" width="100" alt="OpenAPK"></a>
<a href="https://f-droid.org/en/packages/com.terminator.app/"><img src="https://f-droid.org/badge/get-it-on.png" width="192" alt="F-Droid"></a>
<a href="https://github.com/8dmusichannels-star/terminator/releases"><img src="https://cdn.simpleicons.org/github/black" width="100" alt="GitHub Releases"></a>


## Showcase

[<img src="showcase/showcase1.png" width=19% alt="Showcase1">](showcase/showcase1.png)
[<img src="showcase/showcase2.png" width=19% alt="Showcase2">](showcase/showcase2.png)
[<img src="showcase/showcase3.png" width=19% alt="Showcase3">](showcase/showcase3.png)
[<img src="showcase/showcase4.png" width=19% alt="Showcase4">](showcase/showcase4.png)
[<img src="showcase/showcase5.png" width=19% alt="Showcase5">](showcase/showcase5.png)

# Support and Donate

If you want to contribute to development:

[Support and Donate](https://www.patreon.com/Azccriminal)

[Contributed](https://github.com/8dmusichannels-star/terminator/pulls)
