#!/usr/bin/env bash
# Installs (or upgrades) the LiveQuill widget for KDE Plasma 6.
# Usage: ./install-widget.sh                      install only; add it via right click on the desktop -> Add Widgets
#        ./install-widget.sh --add [server] [token]
#                                                  also put it on the desktop, configured. Defaults:
#                                                  server localhost:8787, token from .env next to this script
set -euo pipefail
cd "$(dirname "$0")"

ID=io.github.marlenamakosza.livequill
# Works from the repo (plasmoid/) and from the release archive (widget/).
src=plasmoid
[ -f widget/metadata.json ] && src=widget

if kpackagetool6 -t Plasma/Applet -l | grep -qx "$ID"; then
    kpackagetool6 -t Plasma/Applet -u "$src"
else
    kpackagetool6 -t Plasma/Applet -i "$src"
fi

[ "${1:-}" = "--add" ] || {
    echo "Installed. Right click on the desktop -> Add or Manage Widgets -> LiveQuill."
    exit 0
}

server="${2:-localhost:8787}"
token="${3:-}"
if [ -z "$token" ] && [ -f .env ]; then
    token="$(grep -m1 '^HANDNOTES_TOKEN=' .env | cut -d= -f2-)"
fi

qdbus="$(command -v qdbus6 || command -v qdbus)"
# Plasma desktop scripting: add the widget to the first desktop and write its settings.
script="
var d = desktops()[0];
var w = d.addWidget('$ID', 40, 40, 520, 360);
w.currentConfigGroup = ['General'];
w.writeConfig('serverUrl', '$server');
w.writeConfig('token', '$token');
w.reloadConfig();
"
"$qdbus" org.kde.plasmashell /PlasmaShell org.kde.PlasmaShell.evaluateScript "$script"
echo "Added to the desktop (server $server). Drag its edge to move or resize it."
