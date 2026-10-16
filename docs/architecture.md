# Architecture

The Kotlin/Compose app has two presentations: `HomeActivity` / `HomeWindowActivity` for Quest-managed 2D windows, and `AgentSpaceActivity` for immersive spatial panels. Both share session, connection and terminal models.

- `WorkspaceState` tracks catalog views, groups, selections and demo content.
- `CatalogController` polls the Herdr catalog; `sessions/` parses bounded responses and models session identities.
- `connection/` implements SSH host pinning, encrypted per-device keys and saved connection profiles.
- `terminal/` shares observation streams and native terminal buffers across views. It also handles bounded framing, retry state, rendering and explicit input submission.
- Dashboard, workspace and detail UI components adapt to available space.
- `LayoutPersistence` and Home state helpers keep local state separated by mode and source.
- `scripts/catalog-gate.py` is the Mac-side forced command. `scripts/pair-headset.py` installs or removes a restricted headset key.

The native terminal emulator and renderer are vendored separately under `app/src/main/third_party/termux/`. AgentSpace feeds observed ANSI output to the renderer; it does not start a local terminal process.

Fixtures are synthetic. Device captures, operational logs, private configuration, historical development evidence and the exported diagnostic preview activity are intentionally absent from this source tree.
