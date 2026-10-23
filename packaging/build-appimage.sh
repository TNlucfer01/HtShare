#!/usr/bin/env bash
# Build HtShare-x86_64.AppImage:
#   Maven fat jar  ->  trimmed Java runtime (jlink)  ->  HtShare.AppDir  ->  AppImage
#
# Needs:
#   - JDK 21 with jmods (e.g. the "openjdk-21-jdk" + "openjdk-21-jmods" packages)
#   - JavaFX 21 jmods, unpacked (https://gluonhq.com/products/javafx/ → "jmods")
#   - appimagetool on PATH (https://github.com/AppImage/appimagetool)
#
# Usage:
#   JAVAFX_JMODS=~/javafx-jmods-21.0.1 packaging/build-appimage.sh
set -euo pipefail

: "${JAVAFX_JMODS:?Set JAVAFX_JMODS to your unpacked javafx-jmods-21 folder}"
JDK_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
APPIMAGETOOL="${APPIMAGETOOL:-appimagetool}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# jdk.crypto.ec is required for HTTPS: phones only accept ECDHE key exchange.
# (Leaving it out is why HTTPS failed in earlier AppImages.)
MODULES="java.base,java.datatransfer,java.desktop,java.logging,java.prefs,java.scripting,java.xml,\
jdk.unsupported,jdk.crypto.ec,javafx.base,javafx.graphics,javafx.controls,javafx.fxml"

echo "==> Building jar"
mvn -q -B clean package -DskipTests

echo "==> Linking Java runtime"
rm -rf HtShare.AppDir/usr/lib/jvm
jlink \
  --module-path "$JDK_HOME/jmods:$JAVAFX_JMODS" \
  --add-modules "$MODULES" \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output HtShare.AppDir/usr/lib/jvm

echo "==> Checking the runtime can do HTTPS"
HtShare.AppDir/usr/lib/jvm/bin/java --list-modules | grep -q '^jdk.crypto.ec' \
  || { echo "jdk.crypto.ec missing from runtime"; exit 1; }

cp target/htshare-1.0.0.jar HtShare.AppDir/usr/bin/htshare-1.0.0.jar

echo "==> Packing AppImage"
ARCH=x86_64 "$APPIMAGETOOL" HtShare.AppDir HtShare-x86_64.AppImage
echo "Done: HtShare-x86_64.AppImage"
