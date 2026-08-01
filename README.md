# TERMINATOR

A lightweight Android terminal UI wrapper. No chroot, no proot, no bundled
Linux distribution — TERMINATOR talks directly to whatever executable you
point it at (`/system/bin/sh`, `/system/bin/su`, or your own script), and
gets out of the way.

## Philosophy

Most "solutions" to terminal access on Android reach for isolation: a
private root filesystem, a bootstrapped package manager, a fork of Debian
running under proot. That solves a different problem (running a foreign
Linux userland on top of Bionic) than the one most people actually have,
which is: *give me a good terminal UI over the shell that's already there.*

TERMINATOR doesn't sandbox you and doesn't sandbox itself. It executes,
renders, and lets you bring your own environment if you want one.

## Status

Early scaffold — core VT100/ANSI emulator, session management, and the
main Compose UI (drawer, titlebar, settings) are in place. Not yet a
finished, tested app. See `/areas/terminator-app.md`-style spec notes in
project history for the full feature list.

## Requirements

- minSdk 33 (Android 13)
- Target: Android 13/14/15/16/17
- ABIs: armeabi-v7a, arm64-v8a, x86, x86_64

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).

## Building

```
./gradlew assembleDebug
```

APKs (per-ABI + universal) land in `app/build/outputs/apk/debug/`.
CI builds run automatically via GitHub Actions on push/PR (see
`.github/workflows/build.yml`).
