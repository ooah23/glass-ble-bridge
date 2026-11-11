#!/usr/bin/env bash
# Compile and run the pure-Java core tests (framing, auth, scan record, backoff). No Android SDK needed.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac_run() { if command -v javac >/dev/null; then javac "$@"; else java -m jdk.compiler/com.sun.tools.javac.Main "$@"; fi; }
javac_run -d "$out" "$here"/glass/app/src/main/java/net/n/glassbridge/core/*.java "$here"/glass/app/src/test/java/net/n/glassbridge/core/CoreTest.java
java -cp "$out" net.n.glassbridge.core.CoreTest
