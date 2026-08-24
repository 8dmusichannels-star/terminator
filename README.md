# TERMINATOR

Terminator is Terminal emulator offers comprehensive terminal emulator support with multiple session support that you can directly customize and control. Its main purpose is a terminal emulation that can only be read by the user and is suitable for daily use. The main difference of Terminator is only in the Interface area. It comes with embedded terminal colors and a fully compact interface. It does not offer a ready-made chroot proot envormient support, terminal tool infrastructure and other things are left to the users of the Terminator application. Other terminal envormient and terminal UI are modern. It supports on Android.

## Features

It comes with Material UI, supports all color matches and the terminal applies it to its own color interface.

It has Amoled black support, but there is no dynamic color in the terminal, it only comes with blue and black terminal colors. If you want to keep it simple or plain, just enter the rgb color code and custom css terminal color support is also offered. For example, it comes with the nord theme.

It has multiple session support, you can create more than one session, you can choose default or favorite. After adding the session, there is file base session or command arg session support. command arg session you specify the executable file that should be spawned directly. If you are using root, you can activate the root session directly without using `/system/bin/su` and specify the entry path, the directory path that will spawn the starting directory, and the terminal can be started via the session you set as default. The file-based method is the same. You can directly specify the directory to spawn by specifying the shell script file name and path as follows, you can run it with the entry path and by activating the root session.

Resizing the terminal size by text size, terminal width or temporary session zoom is supported. Alpha is supported with terminal background support and background blur. There is font support, monospace sans mono serif, mono embedded fonts are available and there is special font support, you can add ttf otf supported fonts.

Bell sound is supported, you can choose it as custom or default notification sound in the system.

You can hide or show the statusbar and titlebar on the screen. Horizontal landspace mode is supported. You can use the terminal that way as well.

As keyboard, soft keyboard (tap to terminal open/close) and virtual key are supported (as key bar). As input mode:

- **default** — compatible with ALL IMEs including cjk button strict terminal
- **semantically correct** but may break some IMEs
- **Legacy workaround** — fixes samsung keyboard echo: may break Gboard
- **cjk input** is supported but default is recommended.

3 types of terminal types are supported:

- **xterm256-color** — recommended
- **VT100** — color support is limited and it does not work even without a terminfo entry
- **ANSI** — color support, but it is compatible with DOS.


Keyboard shortcuts and keymapper support are also provided. You can assign or support virtual keyboards directly from the physical keyboard. And there is also seccomp support to solve operation not permitted errors. and there are more features

## Development status

It is currently under development, more advanced features or improved features will be developed soon, but there may be minor bugs as it is in beta stage and not fully stable.

# Support and Donate

If you want to contribute to the development:

[Support and Donate](https://www.patreon.com/Azccriminal)

[Contributor](https://github.com/8dmusichannels-star/terminator/pulls)
