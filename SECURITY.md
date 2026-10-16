# Security and private data

AgentSpace is an experimental remote terminal viewer with optional input. It is not an isolation boundary between the headset user and the selected terminal session.

## Connection model

The app pins the SSH host key after explicit verification and creates a dedicated key on each headset. The device private key is encrypted using an Android Keystore wrapping key and kept in app-private storage. Android application backup is disabled.

The pairing script installs an OpenSSH `restrict` key with a forced command. The bridge accepts only its catalog, observation and optionally input commands; it validates the server generation and terminal membership. It does not grant a general SSH shell or forwarding access. Keep the bridge file and its parent directories writable only by trusted users. Anyone who can modify the forced-command script can change what the key permits.

Observation is the default. `--allow-input` grants text-plus-Enter access to the sessions exposed by that Herdr server. Input goes to whichever program is running there, including a shell. Treat an input-enabled headset as a trusted input device. Revoke a lost headset's key using `pair-headset.py --remove`.

Live terminal content, titles, local drafts, connection settings and layout metadata can be sensitive. Use demo mode for public demonstrations. Do not commit real session captures, screenshots, connection exports, public-key inventories, logs or signing files. Ignore rules reduce accidental inclusion but are not a substitute for reviewing staged files.

## Reporting

Do not put credentials, terminal transcripts or sensitive reproduction details in public issues. A private reporting contact must be configured by the repository owner before publication. Until then, keep security findings private and share only through an agreed private channel.

The source cleanup is not a comprehensive security audit, dependency vulnerability assessment or approval of a public binary release.
