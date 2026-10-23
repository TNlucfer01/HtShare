#!/usr/bin/env bash
# Build HtShare installers for the platform you run this on.
#
#   Windows (Git Bash):  HtShare-<ver>.msi          installer (Start menu + desktop shortcut)
#                        HtShare-<ver>-windows-x64.zip  portable, no install needed
#   Linux:               htshare_<ver>_amd64.deb    Debian / Ubuntu / Mint / Pop!_OS
#                        htshare-<ver>-1.x86_64.rpm Fedora / openSUSE (if rpmbuild is installed)
#                        HtShare-<ver>-x86_64.AppImage  any distro (if appimagetool is on PATH)
#
# Needs:
#   - JDK 21 with jmods (Temurin from adoptium.net includes them;
#     on Ubuntu: apt install openjdk-21-jdk openjdk-21-jmods)
#   - JavaFX 21 jmods for this OS, unpacked: https://gluonhq.com/products/javafx/ → "jmods"
#   - Windows .msi only: WiX Toolset 3.x on PATH (candle.exe, light.exe)
#
# Usage:
#   JAVAFX_JMODS=/path/to/javafx-jmods-21.0.1 packaging/package.sh
#   SKIP_BUILD=1 ...   reuse target/*.jar instead of running Maven
#   TYPES="deb"  ...   only build some package types (msi zip deb rpm appimage)
#
# Output goes to build/dist/.
set -euo pipefail

: "${JAVAFX_JMODS:?Set JAVAFX_JMODS to your unpacked javafx-jmods-21 folder}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*) OS=windows ;;
  Linux) OS=linux ;;
  *) echo "Unsupported OS: $(uname -s). Run this on Windows (Git Bash) or Linux." >&2; exit 1 ;;
esac

# Windows tools want Windows paths and ';' between module path entries
if [ "$OS" = windows ]; then
  SEP=';'
  native() { cygpath -w "$1"; }
  EXE=.exe
else
  SEP=':'
  native() { printf '%s' "$1"; }
  EXE=
fi

JDK_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
[ "$OS" = windows ] && JDK_HOME="$(cygpath -u "$JDK_HOME")"
JLINK="$JDK_HOME/bin/jlink$EXE"
JPACKAGE="$JDK_HOME/bin/jpackage$EXE"
for tool in "$JLINK" "$JPACKAGE"; do
  [ -x "$tool" ] || { echo "Missing $tool (need a full JDK 21)" >&2; exit 1; }
done
[ -d "$JDK_HOME/jmods" ] || { echo "No jmods in $JDK_HOME (install the JDK's jmods)" >&2; exit 1; }
[ -f "$JAVAFX_JMODS/javafx.graphics.jmod" ] || { echo "No JavaFX jmods in $JAVAFX_JMODS" >&2; exit 1; }

# the first <version> in pom.xml is the project's own
VERSION="$(grep -m1 -o '<version>[^<]*' pom.xml | sed 's/<version>//')"
APP=HtShare
BUILD="$ROOT/build"
DIST="$BUILD/dist"
TYPES="${TYPES:-$([ "$OS" = windows ] && echo "msi zip" || echo "deb rpm appimage")}"
want() { [[ " $TYPES " == *" $1 "* ]]; }

echo "==> HtShare $VERSION for $OS ($TYPES)"
rm -rf "$BUILD"
mkdir -p "$BUILD/input" "$DIST"

# ---------------------------------------------------------------- 1. jar
if [ -z "${SKIP_BUILD:-}" ]; then
  echo "==> Building jar"
  mvn -q -B clean package -DskipTests
fi
JAR="target/htshare-$VERSION.jar"
[ -f "$JAR" ] || { echo "Missing $JAR" >&2; exit 1; }
cp "$JAR" "$BUILD/input/"

# ---------------------------------------------------------------- 2. Java runtime
# jdk.crypto.ec: phones only accept ECDHE key exchange, so HTTPS needs it.
# java.prefs: remembered folder/options. No java.naming: so no logback.xml.
MODULES="java.base,java.datatransfer,java.desktop,java.logging,java.prefs,java.scripting,java.xml,\
jdk.unsupported,jdk.crypto.ec,javafx.base,javafx.graphics,javafx.controls,javafx.fxml"

echo "==> Linking Java runtime"
"$JLINK" \
  --module-path "$(native "$JDK_HOME/jmods")$SEP$(native "$JAVAFX_JMODS")" \
  --add-modules "$MODULES" \
  --strip-debug --no-header-files --no-man-pages --compress=zip-6 \
  --output "$(native "$BUILD/runtime")"
"$BUILD/runtime/bin/java$EXE" --list-modules | grep -q '^jdk.crypto.ec' \
  || { echo "jdk.crypto.ec missing from runtime" >&2; exit 1; }

# ---------------------------------------------------------------- 3. packages
COMMON=(
  --name "$APP"
  --app-version "$VERSION"
  --vendor "Aathi Senthil"
  --copyright "Copyright (c) 2025-2026 Aathi Senthil. MIT License."
  --description "Share a folder to any phone or laptop on your network"
  --runtime-image "$(native "$BUILD/runtime")"
  --input "$(native "$BUILD/input")"
  --main-jar "htshare-$VERSION.jar"
  --main-class com.htshare.MainApp
  --java-options "-Dfile.encoding=UTF-8"
  --dest "$(native "$DIST")"
)

if [ "$OS" = windows ]; then
  ICON="$(native "$ROOT/packaging/icons/htshare.ico")"

  if want zip; then
    echo "==> Portable zip"
    "$JPACKAGE" --type app-image "${COMMON[@]}" --icon "$ICON"
    (cd "$DIST" && powershell -NoProfile -Command \
      "Compress-Archive -Path '$APP' -DestinationPath '$APP-$VERSION-windows-x64.zip' -Force")
    rm -rf "${DIST:?}/$APP"
  fi

  if want msi; then
    if command -v candle.exe >/dev/null && command -v light.exe >/dev/null; then
      echo "==> MSI installer"
      # Per-user install: no admin rights needed. The upgrade UUID must never change,
      # it's how Windows knows a newer HtShare replaces the old one.
      "$JPACKAGE" --type msi "${COMMON[@]}" --icon "$ICON" \
        --win-per-user-install --win-dir-chooser \
        --win-menu --win-menu-group "$APP" --win-shortcut \
        --win-upgrade-uuid "6f1c1a52-7d0e-4c55-9a2b-2f6a4e6b9c41"
    else
      echo "!! WiX Toolset 3 not on PATH: skipping .msi (portable zip still built)" >&2
    fi
  fi

else
  ICON="$ROOT/packaging/icons/htshare.png"
  # packaging/linux/jpackage overrides jpackage's install scripts and menu entry
  LINUX=(
    --resource-dir "$ROOT/packaging/linux/jpackage"
    --icon "$ICON"
    --linux-package-name htshare
    --linux-app-category net
    --linux-menu-group "Network;FileTransfer;Utility;"
    --linux-shortcut
  )

  if want deb; then
    echo "==> .deb"
    "$JPACKAGE" --type deb "${COMMON[@]}" "${LINUX[@]}"
  fi

  if want rpm; then
    if command -v rpmbuild >/dev/null; then
      echo "==> .rpm"
      "$JPACKAGE" --type rpm "${COMMON[@]}" "${LINUX[@]}" --linux-rpm-license-type MIT
    else
      echo "!! rpmbuild not installed: skipping .rpm" >&2
    fi
  fi

  if want appimage; then
    echo "==> AppImage folder"
    "$JPACKAGE" --type app-image "${COMMON[@]}" --icon "$ICON"
    APPDIR="$BUILD/$APP.AppDir"
    mkdir -p "$APPDIR/usr/lib" "$APPDIR/usr/share/applications" "$APPDIR/usr/share/metainfo" \
      "$APPDIR/usr/share/icons/hicolor/512x512/apps"
    mv "$DIST/$APP" "$APPDIR/usr/lib/htshare"
    cat > "$APPDIR/AppRun" <<'APPRUN'
#!/bin/sh
HERE="$(dirname "$(readlink -f "$0")")"
exec "$HERE/usr/lib/htshare/bin/HtShare" "$@"
APPRUN
    chmod +x "$APPDIR/AppRun"
    cp packaging/linux/com.htshare.HtShare.desktop "$APPDIR/"
    cp packaging/linux/com.htshare.HtShare.desktop "$APPDIR/usr/share/applications/"
    cp packaging/linux/com.htshare.HtShare.metainfo.xml "$APPDIR/usr/share/metainfo/"
    cp "$ICON" "$APPDIR/htshare.png"
    cp "$ICON" "$APPDIR/usr/share/icons/hicolor/512x512/apps/htshare.png"

    if command -v "${APPIMAGETOOL:-appimagetool}" >/dev/null; then
      echo "==> AppImage"
      ARCH=x86_64 "${APPIMAGETOOL:-appimagetool}" --no-appstream "$APPDIR" \
        "$DIST/$APP-$VERSION-x86_64.AppImage"
    else
      echo "!! appimagetool not found: AppDir left at $APPDIR" >&2
    fi
  fi
fi

echo "==> Done:"
ls -1 "$DIST"
