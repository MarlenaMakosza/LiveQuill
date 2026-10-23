#!/usr/bin/env bash
# Builds everything inside Docker (JDK 21 + Android SDK) and copies the results to dist/.
# Usage: ./build.sh            -> APK, server, desktop app
#        ./build.sh <tasks...> -> any Gradle tasks
set -euo pipefail
cd "$(dirname "$0")"

IMAGE=handnotes-build
docker image inspect "$IMAGE" > /dev/null 2>&1 || docker build -t "$IMAGE" -f docker/build.Dockerfile docker

if [ $# -eq 0 ]; then
    set -- :android:assembleDebug :server:installDist :desktop:createDistributable
    copy=1
fi

# Gradle cache and HOME persist in .cache/. HOME holds the debug signing key,
# so APK updates install over the old version.
mkdir -p .cache/gradle .cache/home
docker run --rm \
    -u "$(id -u):$(id -g)" \
    -e HOME=/cache/home \
    -e GRADLE_USER_HOME=/cache/gradle \
    -v "$PWD":/work \
    -v "$PWD/.cache":/cache \
    -w /work \
    "$IMAGE" gradle --console=plain "$@"

if [ "${copy:-0}" = 1 ]; then
    mkdir -p dist
    cp -f android/build/outputs/apk/debug/android-debug.apk dist/handnotes.apk
    rsync -a --delete server/build/install/handnotes-server/ dist/server/
    rsync -a --delete desktop/build/compose/binaries/main/app/HandNotes/ dist/desktop/
    echo
    echo "dist/handnotes.apk   -> tablet"
    echo "dist/server/         -> ./dist/server/bin/handnotes-server  (or: docker compose up -d)"
    echo "dist/desktop/        -> ./dist/desktop/bin/HandNotes        (or: ./install-desktop.sh)"
fi
