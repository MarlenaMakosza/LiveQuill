#!/usr/bin/env bash
# Installs the desktop viewer for the current user and adds it to the KDE app menu.
set -euo pipefail
cd "$(dirname "$0")"

# Works from the repo (after ./build.sh) and from the release archive (desktop/ next to this script).
if [ -x desktop/bin/HandNotes ]; then
    src=desktop
elif [ -x dist/desktop/bin/HandNotes ]; then
    src=dist/desktop
else
    echo "Run ./build.sh first"
    exit 1
fi

target="$HOME/.local/opt/handnotes"
mkdir -p "$target" "$HOME/.local/share/applications"
rsync -a --delete "$src"/ "$target"/

cat > "$HOME/.local/share/applications/handnotes.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=LiveQuill
Comment=Live view of handwritten notes from the tablet
Exec=$target/bin/HandNotes
Icon=$target/lib/HandNotes.png
Categories=Office;
StartupWMClass=dev-handnotes-desktop-MainKt
EOF

echo "Installed to $target. Find HandNotes in the app menu."
