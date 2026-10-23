# LiveQuill

**English** · [Polski](README.pl.md)

Handwritten notes on an Android tablet, mirrored live to a Linux desktop. You write with a pressure-sensitive stylus on an infinite board. A small self-hosted server stores every stroke. A desktop window shows the board as you write, and it can stay pinned above other windows.

```
[Android tablet] --WebSocket--> [server :8787] --WebSocket--> [desktop viewer]
  SQLite + outbox                data/ops.jsonl                 always on top
```

The tablet works offline: it stores everything locally and syncs when the server is back.

## Features

- Pressure-sensitive stylus input with palm rejection. Fingers pan and zoom.
- Infinite boards. Each board remembers its last view.
- Live mirroring: strokes appear on the desktop while you write.
- Offline-first sync. Changes queue on the tablet and go out on reconnect, also from the background via WorkManager.
- Undo and redo (two-finger tap and three-finger tap), eraser, lasso select and move, 4 colors, 4 widths.
- Dark mode on both apps.
- The desktop follows the tablet view, or you pan and zoom on your own.
- KDE Plasma widget: the current board on the desktop, live, right after login.
- UI in English, and in Polish when the system language is Polish.

## Download

Ready builds are on the [Releases](https://github.com/MarlenaMakosza/LiveQuill/releases) page:

| File | What to do |
|---|---|
| `livequill-<version>.apk` | install on the tablet (see [Tablet](#tablet)) |
| `livequill-desktop-<version>-linux-x64.tar.gz` | extract, then run `./install-desktop.sh` inside (bundled JRE, no Java needed) |
| `livequill-widget-<version>.tar.gz` | KDE Plasma widget: extract, then run `./install-widget.sh --add` |
| `livequill-<version>.plasmoid` | the same widget for Plasma's "Install Widget From Local File" |
| `livequill-server-<version>.tar.gz` | server without Docker: `LIVEQUILL_TOKEN=... ./bin/livequill-server` (Java 17+) |
| `SHA256SUMS` | checksums: `sha256sum -c SHA256SUMS` |

The server also comes as a Docker image, so you need no build at all:

```bash
docker run -d --name livequill --restart unless-stopped -p 8787:8787 \
  -e LIVEQUILL_TOKEN=your-secret -v "$PWD/data:/data" \
  ghcr.io/marlenamakosza/livequill-server:latest
```

To build from source instead, read on.

## Requirements

- Docker with the compose plugin, and rsync. JDK, Gradle and the Android SDK run inside a build image; you do not install them.
- An Android 8.0 (API 26) or newer tablet **with an active stylus** (S Pen, USI, Wacom EMR and similar). In 1.0 you write with the stylus only; fingers pan, zoom and tap for undo/redo. Drawing with a finger is planned for 1.1 ([#4](https://github.com/MarlenaMakosza/LiveQuill/issues/4)).
- Linux desktop for the viewer. Pinning was tested on KDE Plasma (Wayland via XWayland).
- About 3 GB of disk for the build image, the Gradle cache and the output.

Arch/Manjaro example:

```bash
sudo pacman -S docker docker-compose rsync
sudo systemctl enable --now docker
sudo usermod -aG docker $USER     # log out and back in afterwards
docker run --rm hello-world       # must work without sudo
```

## Build

```bash
./build.sh
```

The first build downloads about 1 GB and takes several minutes. Later builds take about 20–30 s. Results land in `dist/`:

| Path | What |
|---|---|
| `dist/livequill.apk` | Android app |
| `dist/server/` | server (the Docker image uses it) |
| `dist/desktop/` | desktop app with a bundled JRE |

To build a single part, run `./build.sh :android:assembleDebug`, `:server:installDist` or `:desktop:createDistributable`. In this mode the results are not copied to `dist/`.

## Server

Run `./build.sh` first. The server image copies the build from `server/build/install/`.

```bash
echo "LIVEQUILL_TOKEN=$(openssl rand -hex 4)" > .env   # once; the shared secret for all devices
docker compose up -d --build
curl http://localhost:8787/health                      # prints: ok
```

The container restarts on its own after a reboot. All notes live in one file, `data/ops.jsonl`.

| Command | What it does |
|---|---|
| `docker compose logs -f` | live log; every client shows as `+ name (tablet/viewer)` |
| `docker compose restart` | restart |
| `docker compose down` | stop |
| `docker compose up -d --build` | start, or update after a new `./build.sh` |

| Variable | Default | Meaning |
|---|---|---|
| `LIVEQUILL_TOKEN` | `change-me` in compose, empty (no auth) when run directly | shared secret |
| `LIVEQUILL_DATA` | `data` | directory for `ops.jsonl` |
| `PORT` | `8787` | listen port |

To run without Docker: `LIVEQUILL_TOKEN=... ./dist/server/bin/livequill-server` (needs Java 17+).

The tablet needs the LAN IP of the server machine. Find it with `ip -4 -o addr show scope global`. A DHCP reservation in the router keeps the address from changing. If a firewall is active, open the port: `sudo ufw allow 8787/tcp`.

## Tablet

**Install.** Messengers often break APK files. The most reliable way is to serve the file from the PC:

```bash
cd dist && python3 -m http.server 8000
```

Open `http://<pc-ip>:8000/livequill.apk` in the tablet browser, allow installs from that browser, then stop the server with `Ctrl+C`. With USB debugging, `adb install -r dist/livequill.apk` works too.

A new APK installs over the old one and keeps notes and settings, as long as both are signed with the same key:

- If `~/.config/livequill-signing/signing.env` exists (see [Releasing](#releasing)), `./build.sh` signs `dist/livequill.apk` with that release key, the same key as GitHub Releases. Local builds and release downloads then update each other.
- Otherwise it is a debug APK, signed with a debug key kept in `.cache/android/`.

Android refuses an update signed with another key ("conflicts with an existing package"). Then uninstall the old app first. Before you do, check that the status shows `● online` without queued changes: notes already on the server sync back after you connect, queued ones would be lost.

**Connect.** The settings dialog opens on first start (later: ⚙). Enter the server IP (port 8787 is the default) and the token from `.env`. The toolbar status turns green: `● online`.

| Action | How |
|---|---|
| Write | stylus (fingers and palm are ignored) |
| Pan | one finger |
| Pan and zoom | two fingers |
| Undo | two-finger tap, or "↶ Undo" |
| Redo | three-finger tap, or "↷ Redo" |
| Erase | "Eraser" button, the stylus eraser end, or the stylus side button while writing |
| Select and move | "Lasso", circle the strokes with the pen, then drag inside the dashed frame |
| Show the whole board | "⌖ Fit all" |
| Dark / light mode | "☾ Dark" / "☀ Light" (default: the system setting) |
| Boards | "+ board", ◀ ▶, "Delete board" |

- Lasso picks the strokes with most of their points inside the loop. Touching outside the frame drops the selection; in lasso mode it also starts a new loop. A move is one undo step.
- Undo is per board. It covers strokes, eraser passes and moves, up to 200 steps in the current session. After a restart, undo removes your newest stroke.
- ⚙ has an experimental "Draw with finger too" switch (one finger draws, two pan). It is not supported in 1.0; proper finger input is planned for 1.1 ([#4](https://github.com/MarlenaMakosza/LiveQuill/issues/4)).
- If a three-finger tap takes a screenshot instead, turn off the three-finger screenshot gesture in the Android settings.

| Status | Meaning |
|---|---|
| `● online` | connected |
| `● offline · N queued` | no server; N changes wait and go out on their own |
| `● bad token` | the token does not match `.env` |
| `● no server` | no address set |

## Desktop

```bash
./install-desktop.sh
```

The script installs to `~/.local/opt/livequill` and adds LiveQuill to the app menu. In **Options**, enter `localhost` (or the server IP) and the token. The settings are stored in `~/.config/livequill/desktop.properties`.

| Action | How |
|---|---|
| Keep above other windows | "Pin" |
| Show what the tablet shows | "follow" |
| Pan | drag with the mouse (turns off "follow"; the tablet view then shows as a dashed frame) |
| Zoom | mouse wheel |
| Whole board | "Fit all" |
| Dark / light | "Dark" / "Light" |

## KDE Plasma widget

The widget shows the board the tablet is on, right on the desktop, and updates live while you write. Plasma loads it at every login, so the note greets you as soon as the PC starts. It is read-only.

Requirements: Plasma 6 and Qt WebSockets. On Arch/Manjaro: `sudo pacman -S --needed qt6-websockets`.

From the repository, or from the extracted `livequill-widget-<version>.tar.gz`:

```bash
./install-widget.sh --add                    # install and put it on the desktop
./install-widget.sh --add 192.168.1.10 TOKEN # server elsewhere, explicit token
./install-widget.sh                          # install only; add it via right click -> Add Widgets
```

With `--add`, the script configures the server (`localhost:8787` by default) and the token from `.env`. The widget appears in the top-left corner of the desktop.

To add it by hand (after `./install-widget.sh` without `--add`, or from the `.plasmoid` file):
1. Right click the desktop → **Add or Manage Widgets**. For a `.plasmoid` file, use **Get New Widgets → Install Widget From Local File** in that panel.
2. Search for **LiveQuill** and drag it onto the desktop.
3. Right click the widget → **Configure LiveQuill**, then enter the server address and the token.

**Move and resize:** right click the desktop → **Enter Edit Mode**. Drag the widget; the corner handles resize it. Click **Done** at the top to leave.

Right click the widget → Configure for:

| Setting | Meaning |
|---|---|
| Server address, Token | as in the desktop app |
| Show | the whole board (default), or the same area as the tablet |
| Dark board | dark or light board |
| Background opacity | 0 = see-through, 100 = solid |

What it shows:
- The board the tablet is on now; before the tablet connects, the newest board.
- "No notes yet" when connected and the board is empty.
- Bottom-right, when something is wrong: `connecting…` / `offline` (no server; it retries every 1–30 s), or `bad token` (fix it in Configure; the value is in `.env`).
- Without a connection it stays empty: it keeps nothing locally.

To upgrade, run `./install-widget.sh` again, without `--add` so you do not get a second copy. If the widget does not pick up the change, log out and back in. To remove it from the desktop: right click → **Remove**. To uninstall: `kpackagetool6 -t Plasma/Applet -r io.github.marlenamakosza.livequill`.

## Updating

```bash
./build.sh
docker compose up -d --build
./install-desktop.sh      # close the running desktop app first
./install-widget.sh       # upgrade the Plasma widget
# then install dist/livequill.apk on the tablet
```

If `shared/` (the protocol) changed, update all three parts.

## Backup

```bash
cp data/ops.jsonl ~/livequill-backup-$(date +%F).jsonl
```

To restore, stop the server with `docker compose down`, replace the file, and start the server again.

## Troubleshooting

- **The tablet shows offline.** On the PC, `curl http://localhost:8787/health` must print `ok`; if not, check `docker compose ps` and `docker compose logs`. Next, open `http://<pc-ip>:8787/health` in the tablet browser. If that fails, the tablet is on another network, the IP changed, or a firewall blocks the port.
- **Bad token.** The token in the tablet settings and the desktop Options must match `.env` exactly. After you edit `.env`, run `docker compose up -d`.
- **The desktop shows no strokes.** Check the status in the toolbar. Click "Fit all": the strokes may be out of view. Check that you look at the same board as the tablet (`<` `>`), or turn on "follow".
- **`./build.sh` gets permission denied from Docker.** Your user is not in the `docker` group, or you did not log in again after `usermod`.

## Outside the home network

Install Tailscale on the PC and the tablet, then use the Tailscale address of the PC (`100.x.y.z`). For a public server, put it behind a reverse proxy with TLS and enter `https://host`; the tablet then switches to `wss://`.

## How sync works

- Every change is an op: add board, add stroke, erase stroke, delete board. Ops are append-only, so merging never conflicts.
- The tablet writes each op to SQLite first: the `ops` table plus an `outbox` queue. Drawing never waits for the network.
- While the app is open, `SyncClient` keeps a WebSocket open. It reconnects with backoff (1–30 s) and right away on every network change, then sends the whole outbox.
- The server appends the ops to `ops.jsonl`, fsyncs, and only then acks the highest stored sequence number. The tablet drops only acked entries. The server ignores duplicates, so a lost ack does no harm.
- When the app goes to the background with a non-empty outbox, WorkManager takes over. It sends the queue through `POST /api/push` once a network is up.
- Strokes in progress (`live`, about 60 messages/s) go straight to viewers and are not stored. The finished stroke always arrives as an op.
- Undo of a stroke is an erase. Redo adds a copy with a new id, because erased ids stay erased.

A board is called a *page* in the code and in the protocol (`AddPage`, `pageId`).

## Project layout

```
shared/   protocol (Op, Msg), NoteModel, pen width and colors: shared by all parts
server/   Ktor (CIO); OpStore = append-only log, Hub = WebSocket fan-out
android/  InkView (drawing, gestures), History (undo/redo), NoteDb (SQLite),
          SyncClient (WebSocket), SyncWorker (WorkManager)
desktop/  Compose Desktop viewer
plasmoid/ KDE Plasma 6 widget (QML, read-only viewer)
docker/   build image (JDK 21 + Android SDK + Gradle)
```

## Releasing

Push a version tag. The [Release workflow](.github/workflows/release.yml) builds and signs the APK, packs the desktop app, the widget and the server, pushes the server image to GHCR, and publishes a GitHub Release:

```bash
# set appVersion in gradle.properties first, commit
git tag v1.1.0
git push origin v1.1.0
```

Versions starting with `0.` are marked as pre-releases. Running the workflow by hand (Actions → Release → Run workflow) only builds; the files land in the run artifacts.

The APK signing key comes from repository secrets: `LIVEQUILL_KEYSTORE_BASE64` (the `.jks` file in base64), `LIVEQUILL_STORE_PASSWORD`, `LIVEQUILL_KEY_ALIAS`, `LIVEQUILL_KEY_PASSWORD`. Keep a backup of the keystore: an APK signed with another key does not install over the old one.

To build a signed APK locally, export the same values as `LIVEQUILL_KEYSTORE` (a file path), `LIVEQUILL_STORE_PASSWORD`, `LIVEQUILL_KEY_ALIAS` and `LIVEQUILL_KEY_PASSWORD`, then run `./gradlew :android:assembleRelease`.

## Roadmap

**1.1**
- Drawing without a stylus: finger input as a real mode, with working pan/zoom and undo/redo taps ([#4](https://github.com/MarlenaMakosza/LiveQuill/issues/4)).

**Later ideas**

- Lower pen latency with front-buffered rendering (`androidx.graphics`) and `MotionEventPredictor`.
- Export a board to PDF/SVG, or to `.xopp` for Xournal++.

## Upgrading from HandNotes

The project was called HandNotes before 1.0.0. The new Android package name (`io.github.marlenamakosza.livequill`) makes it a new app: install LiveQuill, then uninstall HandNotes once it shows `online` with no queued changes. Your notes come back from the server.

On the PC, `HANDNOTES_TOKEN` and `HANDNOTES_DATA` still work, and the desktop app reads its old settings from `~/.config/handnotes/` until it saves new ones. The old desktop install in `~/.local/opt/handnotes` can be deleted once LiveQuill runs. With Docker, run `docker compose up -d --build --remove-orphans` once to replace the old `handnotes` container.

## License

[MIT](LICENSE)
