#!/bin/sh
# Pure-Java scan policy tests; no Android SDK, device or Bluetooth radio is used.
set -eu
here=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT HUP INT TERM
javac=${JAVA_HOME:+$JAVA_HOME/bin/}javac
java=${JAVA_HOME:+$JAVA_HOME/bin/}java
"$javac" --release 8 -d "$tmp" \
    "$here/app/src/app/techo5/panel/ScanStartBudget.java" \
    "$here/app/test/app/techo5/panel/ScanStartBudgetTest.java"
"$java" -cp "$tmp" app.techo5.panel.ScanStartBudgetTest
