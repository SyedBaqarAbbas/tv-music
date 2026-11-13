#!/usr/bin/env bash
# Small deterministic tests: no SDK, network access, or test dependencies.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
if [[ -z "${JAVA_HOME:-}" && -x "$project_dir/.tools/jdk-17/bin/javac" ]]; then
    export JAVA_HOME="$project_dir/.tools/jdk-17"
fi
if [[ -n "${JAVA_HOME:-}" ]]; then
    export PATH="$JAVA_HOME/bin:$PATH"
fi
if ! command -v javac >/dev/null 2>&1 || ! command -v java >/dev/null 2>&1; then
    printf '%s\n' 'JDK 17 is required. Run ./scripts/bootstrap-android.sh --tools-only or set JAVA_HOME.' >&2
    exit 1
fi
command -v python3 >/dev/null 2>&1 || { printf '%s\n' 'Python 3 is required for temporary-file cleanup.' >&2; exit 1; }
classes_dir="$(mktemp -d "${TMPDIR:-/tmp}/ytmusic-matcher.XXXXXX")"
cleanup() { python3 -c 'import shutil, sys; shutil.rmtree(sys.argv[1])' "$classes_dir"; }
trap cleanup EXIT
javac -J-Xmx256m --release 17 -d "$classes_dir" \
    app/src/main/java/dev/ytmusictv/LyricsMatcher.java \
    tests/java/dev/ytmusictv/LyricsMatcherTest.java
java -Xmx64m -cp "$classes_dir" dev.ytmusictv.LyricsMatcherTest
