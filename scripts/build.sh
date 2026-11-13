#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"

if [[ -z "${JAVA_HOME:-}" && -x "$project_dir/.tools/jdk-17/bin/java" ]]; then
    export JAVA_HOME="$project_dir/.tools/jdk-17"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
if ! command -v java >/dev/null 2>&1 || ! command -v javac >/dev/null 2>&1; then
    printf '%s\n' 'JDK 17 is required. Run ./scripts/bootstrap-android.sh first.' >&2
    exit 1
fi
java_version="$(java -version 2>&1 | head -n 1)"
if [[ ! "$java_version" =~ \"17[\.\"] ]]; then
    printf 'Use JDK 17 (JAVA_HOME). Detected: %s\n' "$java_version" >&2
    exit 1
fi

if [[ -z "${ANDROID_HOME:-}" ]]; then
    if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
        export ANDROID_HOME="$ANDROID_SDK_ROOT"
    elif [[ -d "$project_dir/.tools/android-sdk" ]]; then
        export ANDROID_HOME="$project_dir/.tools/android-sdk"
    fi
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
    printf '%s\n' 'Android SDK is required. Run ./scripts/bootstrap-android.sh or set ANDROID_HOME.' >&2
    exit 1
fi
for sdk_file in platforms/android-35/android.jar build-tools/34.0.0/aapt2; do
    if [[ ! -f "$ANDROID_HOME/$sdk_file" ]]; then
        printf 'Missing SDK component: %s\nRun ./scripts/bootstrap-android.sh to review licenses and install it.\n' "$sdk_file" >&2
        exit 1
    fi
done

if (( $# == 0 )); then
    set -- assembleDebug
fi
exec ./gradlew --no-daemon --max-workers=1 --console=plain "$@"
