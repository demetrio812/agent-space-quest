# Before publication or distribution

## Source publication

- Review the exact staged files, including new screenshots and fixtures, for personal or session data.
- Scan credentials and inspect all commits that will be published. Ignore rules do not remove already tracked data.
- Keep signing files, connection exports, local settings, build outputs and diagnostics out of Git.
- Confirm ownership and retain the MIT license and third-party notices.
- Set a private vulnerability reporting channel in SECURITY.md.
- Run the bridge tests and the Android build, JVM tests and lint.

## Binary distribution

Source publication and a public app release are separate steps. Before distributing an APK:

- Review dependency licenses, transitive notices and Meta SDK distribution terms.
- Build a non-debuggable release without diagnostic entry points, signed using a separately managed release key.
- Test installation, updates, host verification, read-only pairing, input-enabled pairing, revocation and reconnect behaviour on a headset.
- Test both Home and immersive modes, hand/controller input, resizing and saved layouts.
- Confirm store/device requirements and document the tested Herdr compatibility.
- Review privacy handling and security independently; a source secret scan does not establish runtime security.

Never use live user sessions as public screenshots or test fixtures. Demonstrations should use demo mode or deliberately synthetic sessions.
