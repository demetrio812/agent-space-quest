#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
adb_bin="${ADB:-adb}"
"$adb_bin" install -r "$project_dir/app/build/outputs/apk/debug/app-debug.apk"
"$adb_bin" shell am start -n io.agentspace.quest/.HomeActivity
