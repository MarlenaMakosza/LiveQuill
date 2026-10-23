import QtQuick
import QtQuick.Layouts
import QtWebSockets
import org.kde.plasma.plasmoid
import org.kde.plasma.core as PlasmaCore
import org.kde.kirigami as Kirigami

// LiveQuill desktop widget: a read-only viewer. It connects to the server like the desktop app,
// keeps the board state in memory and repaints when strokes arrive.
PlasmoidItem {
    id: root

    preferredRepresentation: fullRepresentation
    Plasmoid.backgroundHints: PlasmaCore.Types.NoBackground

    readonly property var cfg: Plasmoid.configuration
    readonly property bool pl: Qt.locale().name.startsWith("pl")

    function t(en, pl) {
        return root.pl ? pl : en
    }

    // ---- Board state (same rules as NoteModel in shared/) ----
    property var pages: ({})          // pageId -> { created, strokes: { id -> stroke }, order: [ids] }
    property var erased: ({})
    property var deletedPages: ({})
    property var partials: ({})       // strokeId -> { pageId, color, width, points, touched }
    property var bounds: ({})         // strokeId -> [l, t, r, b]
    property var tabletView: null     // [x, y, w, h]
    property string focusPage: ""
    property real lastSeq: 0
    property string status: "connecting"
    property int attempt: 0
    property bool dirty: false

    /** The canvas lives inside fullRepresentation, out of reach by id: it listens to this instead. */
    signal repaint()

    function page(id) {
        if (!pages[id]) pages[id] = { created: Date.now(), strokes: {}, order: [] }
        return pages[id]
    }

    function applyOp(op) {
        if (op.t === "page") {
            if (!deletedPages[op.pageId] && !pages[op.pageId]) pages[op.pageId] = { created: op.time, strokes: {}, order: [] }
        } else if (op.t === "stroke") {
            const s = op.stroke
            if (erased[s.id] || deletedPages[s.pageId]) return
            const p = pages[s.pageId] || (pages[s.pageId] = { created: op.time, strokes: {}, order: [] })
            if (!p.strokes[s.id]) p.order.push(s.id)
            p.strokes[s.id] = s
            delete partials[s.id]
        } else if (op.t === "erase") {
            erased[op.strokeId] = true
            const p = pages[op.pageId]
            if (p && p.strokes[op.strokeId]) {
                delete p.strokes[op.strokeId]
                p.order = p.order.filter(id => id !== op.strokeId)
            }
        } else if (op.t === "delpage") {
            deletedPages[op.pageId] = true
            delete pages[op.pageId]
        }
    }

    function handle(msg) {
        if (msg.t === "ops") {
            for (const item of msg.items) {
                applyOp(item.op)
                if (item.seq > lastSeq) lastSeq = item.seq
            }
        } else if (msg.t === "live") {
            const old = partials[msg.strokeId]
            if (old) {
                // Chunks overlap by one point.
                old.points = old.points.concat(msg.points.slice(3))
                old.touched = Date.now()
            } else {
                partials[msg.strokeId] = { pageId: msg.pageId, color: msg.color, width: msg.width, points: msg.points, touched: Date.now() }
            }
            focusPage = msg.pageId
        } else if (msg.t === "focus") {
            focusPage = msg.pageId
            if (msg.w && msg.h) tabletView = [msg.x, msg.y, msg.w, msg.h]
        } else if (msg.t === "fail") {
            status = "badToken"
        }
        dirty = true
    }

    /** The board to show: the one the tablet shows, else the newest one. */
    function currentPageId() {
        if (focusPage && pages[focusPage]) return focusPage
        let best = ""
        let bestTime = -1
        for (const id in pages) {
            if (pages[id].created > bestTime) { bestTime = pages[id].created; best = id }
        }
        return best
    }

    function strokeBounds(s) {
        let b = bounds[s.id]
        if (b) return b
        const p = s.points
        let l = Infinity, tp = Infinity, r = -Infinity, bt = -Infinity
        for (let i = 0; i < p.length; i += 3) {
            l = Math.min(l, p[i]); r = Math.max(r, p[i])
            tp = Math.min(tp, p[i + 1]); bt = Math.max(bt, p[i + 1])
        }
        b = [l - s.width, tp - s.width, r + s.width, bt + s.width]
        bounds[s.id] = b
        return b
    }

    // ---- Colors (same as Ink and Theme in shared/) ----
    function displayColor(argb) {
        const u = argb >>> 0
        const a = ((u >>> 24) & 255) / 255
        let r = (u >>> 16) & 255, g = (u >>> 8) & 255, b = u & 255
        if (cfg.dark) {
            const luma = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
            const chroma = Math.max(r, g, b) - Math.min(r, g, b)
            if (luma < 0.2 && chroma < 60) { r = 0xE8; g = 0xE8; b = 0xE8 }
            else { r += Math.floor((255 - r) * 0.3); g += Math.floor((255 - g) * 0.3); b += Math.floor((255 - b) * 0.3) }
        }
        return "rgba(" + r + "," + g + "," + b + "," + a + ")"
    }

    function inkWidth(base, pressure) {
        return base * (0.15 + 0.85 * Math.max(0, Math.min(1, pressure)))
    }

    // ---- Connection ----
    function serverWsUrl() {
        let s = (cfg.serverUrl || "localhost:8787").trim().replace(/\/+$/, "").replace(/\/ws$/, "")
        const secure = s.startsWith("https://") || s.startsWith("wss://")
        let rest = s.indexOf("://") >= 0 ? s.substring(s.indexOf("://") + 3) : s
        const host = rest.split("/")[0]
        if (!secure && host.indexOf(":") < 0) rest = host + ":8787" + rest.substring(host.length)
        return (secure ? "wss://" : "ws://") + rest + "/ws"
    }

    function reconnect() {
        socket.active = false
        lastSeq = 0
        attempt = 0
        status = "connecting"
        socket.url = serverWsUrl()
        socket.active = true
    }

    WebSocket {
        id: socket
        url: root.serverWsUrl()
        active: true
        onTextMessageReceived: message => {
            try { root.handle(JSON.parse(message)) } catch (e) { console.warn("LiveQuill: bad message", e) }
        }
        onStatusChanged: st => {
            if (st === WebSocket.Open) {
                root.attempt = 0
                root.status = "online"
                sendTextMessage(JSON.stringify({
                    t: "hello", deviceId: "plasma-widget", name: "Plasma widget",
                    role: "viewer", token: root.cfg.token || "", since: root.lastSeq
                }))
            } else if (st === WebSocket.Closed || st === WebSocket.Error) {
                if (root.status !== "badToken") root.status = "offline"
                active = false
                // 1, 2, 4 ... 30 s. The server may start after the desktop session.
                retry.interval = Math.min(30000, 1000 * Math.pow(2, Math.min(root.attempt, 5)))
                root.attempt++
                retry.restart()
            }
        }
    }

    Timer {
        id: retry
        onTriggered: socket.active = true
    }

    Connections {
        target: root.cfg
        function onServerUrlChanged() { root.reconnect() }
        function onTokenChanged() { root.reconnect() }
        function onDarkChanged() { root.dirty = true }
        function onViewModeChanged() { root.dirty = true }
    }

    // Repaint at most 30 times per second, and drop tablet strokes that never finished.
    Timer {
        interval: 33
        running: true
        repeat: true
        onTriggered: {
            if (root.dirty) {
                root.dirty = false
                root.repaint()
            }
        }
    }
    Timer {
        interval: 5000
        running: true
        repeat: true
        onTriggered: {
            const now = Date.now()
            for (const id in root.partials) {
                if (now - root.partials[id].touched > 10000) { delete root.partials[id]; root.dirty = true }
            }
        }
    }

    fullRepresentation: Item {
        implicitWidth: Kirigami.Units.gridUnit * 26
        implicitHeight: Kirigami.Units.gridUnit * 18
        Layout.minimumWidth: Kirigami.Units.gridUnit * 8
        Layout.minimumHeight: Kirigami.Units.gridUnit * 6

        Rectangle {
            anchors.fill: parent
            radius: Kirigami.Units.cornerRadius * 2
            color: root.cfg.dark ? "#1c1c1e" : "#ffffff"
            opacity: root.cfg.backgroundOpacity / 100
        }

        Canvas {
            id: canvas
            anchors.fill: parent
            anchors.margins: Kirigami.Units.smallSpacing * 2
            onWidthChanged: root.dirty = true
            onHeightChanged: root.dirty = true

            Connections {
                target: root
                function onRepaint() { canvas.requestPaint() }
            }

            onPaint: {
                const ctx = getContext("2d")
                ctx.reset()
                const pid = root.currentPageId()
                const pg = root.pages[pid]
                if (!pg) return
                const strokes = pg.order.map(id => pg.strokes[id])
                const live = []
                for (const id in root.partials) if (root.partials[id].pageId === pid) live.push(root.partials[id])

                // View: the whole board, or the tablet's rectangle.
                let l, tp, r, b
                const tv = root.tabletView
                if (root.cfg.viewMode === "tablet" && tv && pid === root.focusPage) {
                    l = tv[0]; tp = tv[1]; r = tv[0] + tv[2]; b = tv[1] + tv[3]
                } else {
                    l = Infinity; tp = Infinity; r = -Infinity; b = -Infinity
                    for (const s of strokes) {
                        const bb = root.strokeBounds(s)
                        l = Math.min(l, bb[0]); tp = Math.min(tp, bb[1]); r = Math.max(r, bb[2]); b = Math.max(b, bb[3])
                    }
                    for (const s of live) {
                        for (let i = 0; i < s.points.length; i += 3) {
                            l = Math.min(l, s.points[i]); r = Math.max(r, s.points[i])
                            tp = Math.min(tp, s.points[i + 1]); b = Math.max(b, s.points[i + 1])
                        }
                    }
                    if (!isFinite(l)) return
                    const pad = Math.max(r - l, b - tp) * 0.05 + 10
                    l -= pad; tp -= pad; r += pad; b += pad
                }
                const z = Math.min(width / (r - l), height / (b - tp))
                const ox = (l + r) / 2 - width / z / 2
                const oy = (tp + b) / 2 - height / z / 2

                ctx.lineCap = "round"
                ctx.lineJoin = "round"
                const draw = (p, color, w) => {
                    ctx.strokeStyle = root.displayColor(color)
                    ctx.fillStyle = ctx.strokeStyle
                    const n = p.length / 3
                    if (n === 1) {
                        ctx.beginPath()
                        ctx.arc((p[0] - ox) * z, (p[1] - oy) * z, Math.max(0.5, root.inkWidth(w, p[2]) * z / 2), 0, 2 * Math.PI)
                        ctx.fill()
                        return
                    }
                    // Segments with about the same width share one path: far fewer stroke() calls.
                    let bucket = -1
                    for (let i = 1; i < n; i++) {
                        const lw = Math.max(1, root.inkWidth(w, (p[i * 3 - 1] + p[i * 3 + 2]) / 2) * z)
                        const q = Math.round(lw * 2)
                        if (q !== bucket) {
                            if (bucket >= 0) ctx.stroke()
                            bucket = q
                            ctx.lineWidth = q / 2
                            ctx.beginPath()
                            ctx.moveTo((p[i * 3 - 3] - ox) * z, (p[i * 3 - 2] - oy) * z)
                        }
                        ctx.lineTo((p[i * 3] - ox) * z, (p[i * 3 + 1] - oy) * z)
                    }
                    ctx.stroke()
                }
                for (const s of strokes) draw(s.points, s.color, s.width)
                for (const s of live) draw(s.points, s.color, s.width)
            }
        }

        // Empty board or no connection.
        Text {
            anchors.centerIn: parent
            visible: text !== ""
            color: root.cfg.dark ? "#9a9a9e" : "#6a6a6e"
            font.pixelSize: Kirigami.Units.gridUnit
            text: {
                root.dirty // re-evaluate on changes
                const pg = root.pages[root.currentPageId()]
                if (pg && pg.order.length > 0) return ""
                if (root.status === "online") return root.t("No notes yet", "Brak notatek")
                return ""
            }
        }

        Text {
            anchors.right: parent.right
            anchors.bottom: parent.bottom
            anchors.margins: Kirigami.Units.smallSpacing * 2
            visible: root.status !== "online"
            color: root.status === "badToken" ? "#e57373" : (root.cfg.dark ? "#9a9a9e" : "#6a6a6e")
            font.pixelSize: Kirigami.Units.gridUnit * 0.7
            text: root.status === "badToken" ? root.t("bad token", "zły token")
                : root.status === "connecting" ? root.t("connecting…", "łączę…")
                : "offline"
        }
    }
}
