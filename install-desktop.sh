#!/usr/bin/env bash
# Installs the desktop viewer for the current user and adds it to the KDE app menu.
set -euo pipefail
cd "$(dirname "$0")"

[ -x dist/desktop/bin/HandNotes ] || { echo "Run ./build.sh first"; exit 1; }

target="$HOME/.local/opt/handnotes"
mkdir -p "$target" "$HOME/.local/share/applications"
rsync -a --delete dist/desktop/ "$target"/

cat > "$HOME/.local/share/applications/handnotes.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=HandNotes
Comment=Live view of handwritten notes from the tablet
Exec=$target/bin/HandNotes
Icon=$target/lib/HandNotes.png
Categories=Office;
StartupWMClass=dev-handnotes-desktop-MainKt
EOF

echo "Installed to $target. Find HandNotes in the app menu."
