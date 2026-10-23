#!/usr/bin/env bash
# Installs the desktop viewer for the current user and adds it to the KDE app menu.
set -euo pipefail
cd "$(dirname "$0")"

# Works from the repo (after ./build.sh) and from the release archive (desktop/ next to this script).
if [ -x desktop/bin/LiveQuill ]; then
    src=desktop
elif [ -x dist/desktop/bin/LiveQuill ]; then
    src=dist/desktop
else
    echo "Run ./build.sh first"
    exit 1
fi

target="$HOME/.local/opt/livequill"
mkdir -p "$target" "$HOME/.local/share/applications"
rsync -a --delete "$src"/ "$target"/

cat > "$HOME/.local/share/applications/livequill.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=LiveQuill
Comment=Live view of handwritten notes from the tablet
Exec=$target/bin/LiveQuill
Icon=$target/lib/LiveQuill.png
Categories=Office;
StartupWMClass=io-github-marlenamakosza-livequill-desktop-MainKt
EOF

# Menu entry of the old name (HandNotes), so the menu shows one app.
old_entry="$HOME/.local/share/applications/handnotes.desktop"
[ -f "$old_entry" ] && rm -f "$old_entry"

echo "Installed to $target. Find LiveQuill in the app menu."
