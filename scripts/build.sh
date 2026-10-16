#!/usr/bin/env bash
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
build_state="${AGENTSPACE_BUILD_STATE:-$project_dir/.local-build}"
mkdir -p "$build_state"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$build_state/gradle}"
export ANDROID_USER_HOME="${ANDROID_USER_HOME:-$build_state/android}"
key_path="$build_state/agentspace-debug.jks"
if [[ ! -f "$key_path" ]]; then
  keytool -genkeypair -keystore "$key_path" -storepass android -keypass android \
    -alias androiddebugkey -dname 'CN=AgentSpace Demo,O=Local Development,C=GB' \
    -keyalg RSA -keysize 2048 -validity 10000
fi
"${GRADLE_BIN:-$project_dir/gradlew}" -p "$project_dir" \
  "-PagentspaceDebugKeystore=$key_path" \
  :app:assembleDebug :app:testDebugUnitTest :app:lintDebug --console=plain
