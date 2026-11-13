#!/usr/bin/env bash
# Portable Linux x86-64 tools only: no root, Android Studio, or emulator required.
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
tools_dir="$project_dir/.tools"
downloads_dir="$tools_dir/downloads"
tools_only=false
case "${1:-}" in
    --tools-only) tools_only=true ;;
    '') ;;
    *) printf 'Usage: %s [--tools-only]\n' "$0" >&2; exit 1 ;;
esac
if [[ "$(uname -s)" != Linux || "$(uname -m)" != x86_64 ]]; then
    printf '%s\n' 'This bootstrap supports Linux x86-64. Elsewhere install JDK 17 and Android SDK, then set JAVA_HOME and ANDROID_HOME.' >&2
    exit 1
fi
for tool in curl python3 tar sha256sum sha1sum; do
    command -v "$tool" >/dev/null 2>&1 || { printf 'Required command missing: %s\n' "$tool" >&2; exit 1; }
done
mkdir -p "$downloads_dir"

download() {
    local url="$1" destination="$2" checksum="$3" algorithm="${4:-sha256sum}"
    if [[ -f "$destination" ]] && printf '%s  %s\n' "$checksum" "$destination" | "$algorithm" --status -c -; then
        return
    fi
    curl --fail --location --retry 3 --proto '=https' --tlsv1.2 "$url" --output "$destination.part"
    printf '%s  %s\n' "$checksum" "$destination.part" | "$algorithm" -c -
    mv -- "$destination.part" "$destination"
}

if [[ ! -x "$tools_dir/jdk-17/bin/javac" ]]; then
    # Published by Eclipse Adoptium's release API and checksum sidecar.
    jdk_archive="$downloads_dir/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz"
    download 'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.21%2B9/OpenJDK17U-jdk_x64_linux_hotspot_17.0.21_9.tar.gz' \
        "$jdk_archive" '8b9b611d917e3dfd8419d3566b5949226f69b052fed7e109c1368add1dfe8711'
    mkdir -p "$tools_dir/jdk-17"
    tar -xzf "$jdk_archive" --strip-components=1 -C "$tools_dir/jdk-17"
fi
export JAVA_HOME="$tools_dir/jdk-17"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$tools_dir/android-sdk"

if [[ ! -x "$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager" ]]; then
    # Version 19 runs with JDK 17. Upstream checksum is published in:
    # https://dl.google.com/android/repository/repository2-1.xml (cmdline-tools;19.0).
    # Verify Google's published SHA-1 as well as the pinned SHA-256.
    sdk_archive="$downloads_dir/commandlinetools-linux-13114758_latest.zip"
    download 'https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip' \
        "$sdk_archive" '5fdcc763663eefb86a5b8879697aa6088b041e70' sha1sum
    printf '%s  %s\n' '7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee' "$sdk_archive" | sha256sum -c -
    extraction_dir="$(mktemp -d "$tools_dir/sdk-extract.XXXXXX")"
    python3 - "$sdk_archive" "$extraction_dir" <<'PY'
import os
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1]) as archive:
    archive.extractall(sys.argv[2])
    for entry in archive.infolist():
        permissions = (entry.external_attr >> 16) & 0o777
        if permissions:
            os.chmod(os.path.join(sys.argv[2], entry.filename), permissions)
PY
    mkdir -p "$ANDROID_HOME/cmdline-tools"
    mv -- "$extraction_dir/cmdline-tools" "$ANDROID_HOME/cmdline-tools/19.0"
    rmdir -- "$extraction_dir"
fi

printf '\nPortable JDK and Android command-line tools are ready in %s\n' "$tools_dir"
if [[ "$tools_only" == true ]]; then
    printf '%s\n' 'Run ./scripts/bootstrap-android.sh in a terminal to review SDK licenses and install the build packages.'
    exit 0
fi
if [[ ! -t 0 ]]; then
    printf '%s\n' 'SDK licenses require your review. Run ./scripts/bootstrap-android.sh interactively in a terminal.' >&2
    exit 2
fi

# sdkmanager displays the licenses, asks the user, and records acceptance in
# .tools/android-sdk/licenses. Never pipe automatic yes.
sdkmanager="$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager"
JAVA_OPTS='-Xmx512m -XX:MaxMetaspaceSize=192m' "$sdkmanager" --sdk_root="$ANDROID_HOME" 'platforms;android-35' 'build-tools;34.0.0' 'platform-tools'
printf '\nSetup complete. Build the APK with ./scripts/build.sh\n'
