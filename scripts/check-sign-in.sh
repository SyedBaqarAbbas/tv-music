#!/usr/bin/env bash
# Sign-in navigation fixtures; no SDK, live account, browser, or test library.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
if [[ -z "${JAVA_HOME:-}" && -x "$project_dir/.tools/jdk-17/bin/javac" ]]; then
    export JAVA_HOME="$project_dir/.tools/jdk-17"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
for tool in javac java python3; do
    command -v "$tool" >/dev/null 2>&1 || {
        printf 'Required command missing: %s. Run ./scripts/bootstrap-android.sh --tools-only for JDK 17.\n' "$tool" >&2
        exit 1
    }
done
framework_jar="$project_dir/.tools/java-check/android-all-11-robolectric-6757853.jar"
if [[ ! -f "$framework_jar" ]]; then
    printf '%s\n' 'Run ./scripts/check-java.sh once to obtain the verified API30 framework jar.' >&2
    exit 1
fi
classes_dir="$(mktemp -d "${TMPDIR:-/tmp}/ytmusic-sign-in.XXXXXX")"
cleanup() { python3 -c 'import shutil, sys; shutil.rmtree(sys.argv[1])' "$classes_dir"; }
trap cleanup EXIT
javac -J-Xmx256m --release 17 -classpath "$framework_jar" -d "$classes_dir" \
    app/src/main/java/dev/ytmusictv/CursorOverlayView.java \
    app/src/main/java/dev/ytmusictv/SignInActivity.java \
    tests/java/dev/ytmusictv/SignInNavigationTest.java
java -Xmx64m -classpath "$classes_dir:$framework_jar" dev.ytmusictv.SignInNavigationTest
