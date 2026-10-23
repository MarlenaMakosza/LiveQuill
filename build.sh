#!/usr/bin/env bash
# Builds everything inside Docker (JDK 21 + Android SDK) and copies the results to dist/.
# Usage: ./build.sh            -> APK, server, desktop app
#        ./build.sh <tasks...> -> any Gradle tasks
#
# APK signing: with a release key in ~/.config/livequill-signing/signing.env (see README),
# dist/livequill.apk is the release APK signed with it, the same key as GitHub Releases.
# Without it, dist/livequill.apk is a debug APK; its key lives in .cache/android/.
set -euo pipefail
cd "$(dirname "$0")"

IMAGE=livequill-build
docker image inspect "$IMAGE" > /dev/null 2>&1 || docker build -t "$IMAGE" -f docker/build.Dockerfile docker

SIGNING_DIR="$HOME/.config/livequill-signing"
signing=()
if [ -f "$SIGNING_DIR/signing.env" ]; then
    # shellcheck disable=SC1091
    . "$SIGNING_DIR/signing.env"
    signing=(
        -v "$SIGNING_DIR":/signing:ro
        -e LIVEQUILL_KEYSTORE="/signing/$(basename "$KEYSTORE")"
        -e LIVEQUILL_STORE_PASSWORD="$STORE_PASSWORD"
        -e LIVEQUILL_KEY_ALIAS="$KEY_ALIAS"
        -e LIVEQUILL_KEY_PASSWORD="$KEY_PASSWORD"
    )
fi

if [ $# -eq 0 ]; then
    apk_task=:android:assembleDebug
    [ ${#signing[@]} -gt 0 ] && apk_task=:android:assembleRelease
    set -- "$apk_task" :server:installDist :desktop:createDistributable
    copy=1
fi

# Gradle cache, HOME and the Android user dir persist in .cache/. ANDROID_USER_HOME must be set:
# without it the debug key lands in the container's own home and changes on every build.
mkdir -p .cache/gradle .cache/home .cache/android
docker run --rm \
    -u "$(id -u):$(id -g)" \
    -e HOME=/cache/home \
    -e GRADLE_USER_HOME=/cache/gradle \
    -e ANDROID_USER_HOME=/cache/android \
    "${signing[@]}" \
    -v "$PWD":/work \
    -v "$PWD/.cache":/cache \
    -w /work \
    "$IMAGE" ./gradlew --console=plain "$@"

if [ "${copy:-0}" = 1 ]; then
    mkdir -p dist
    if [ "$apk_task" = :android:assembleRelease ]; then
        cp -f android/build/outputs/apk/release/android-release.apk dist/livequill.apk
    else
        cp -f android/build/outputs/apk/debug/android-debug.apk dist/livequill.apk
    fi
    rsync -a --delete server/build/install/livequill-server/ dist/server/
    rsync -a --delete desktop/build/compose/binaries/main/app/LiveQuill/ dist/desktop/
    echo
    echo "dist/livequill.apk   -> tablet (${apk_task#:android:assemble} build)"
    echo "dist/server/         -> ./dist/server/bin/livequill-server  (or: docker compose up -d)"
    echo "dist/desktop/        -> ./dist/desktop/bin/LiveQuill        (or: ./install-desktop.sh)"
fi
