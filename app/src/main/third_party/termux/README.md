# Vendored terminal components

Source: https://github.com/termux/termux-app/tree/5b657c6adf4304e5198951ce815fe0205dcac29c
Version: v0.118.3 (commit 5b657c6adf4304e5198951ce815fe0205dcac29c).

Included unchanged: Java sources from terminal-emulator, and terminal-view's TerminalRenderer.java. The upstream LICENSE.md identifies the terminal-emulator and terminal-view code as the Apache License 2.0 exception to the app repository's GPLv3 license. The upstream notice and Apache license are bundled in the APK assets/licenses directory. No Termux app UI, packages, native libraries, shell installation or process creation is used by AgentSpace.

AgentSpace instantiates TerminalEmulator with a no-op TerminalOutput and TerminalSessionClient, feeds observed ANSI bytes, and uses TerminalRenderer to draw a canvas. Input replies, bell and clipboard callbacks are deliberately discarded. The main project source set explicitly includes this directory; no JitPack or unpinned master dependency is needed.

Reason for selection: native Android canvas rendering, stateful incremental UTF-8/ANSI parsing, alternate screen, color/style/cursor support, and bounded buffers. A WebView-based renderer was considered but would introduce another rendering runtime inside the spatial panels. The complete Termux application was not incorporated.
