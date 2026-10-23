package dev.handnotes.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.handnotes.shared.AddPage
import dev.handnotes.shared.AddStroke
import dev.handnotes.shared.BOARD_DEFAULT_W
import dev.handnotes.shared.Ink
import dev.handnotes.shared.DeletePage
import dev.handnotes.shared.Msg
import dev.handnotes.shared.NoteModel
import dev.handnotes.shared.Proto
import dev.handnotes.shared.ServerAddress
import dev.handnotes.shared.newId
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.util.Properties
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

class Config {
    private val file = File(System.getProperty("user.home"), ".config/handnotes/desktop.properties")
    private val p = Properties().apply { if (file.exists()) file.inputStream().use(::load) }

    var serverUrl: String
        get() = p.getProperty("serverUrl", "localhost:8787")
        set(v) { p.setProperty("serverUrl", v.trim()) }
    var token: String
        get() = p.getProperty("token", "")
        set(v) { p.setProperty("token", v.trim()) }
    var pinned: Boolean
        get() = p.getProperty("pinned") == "true"
        set(v) { p.setProperty("pinned", v.toString()) }
    var follow: Boolean
        get() = p.getProperty("follow", "true") == "true"
        set(v) { p.setProperty("follow", v.toString()) }
    var dark: Boolean
        get() = p.getProperty("dark", "true") == "true"
        set(v) { p.setProperty("dark", v.toString()) }
    val deviceId: String
        get() = p.getProperty("deviceId") ?: newId().also { p.setProperty("deviceId", it); save() }

    fun save() {
        file.parentFile.mkdirs()
        file.outputStream().use { p.store(it, "HandNotes desktop") }
    }
}

/** Stroke in progress on the tablet. */
class Partial(val pageId: String, val color: Int, val width: Float) {
    var points = FloatArray(0)
    var touched = System.currentTimeMillis()
}

/**
 * Viewer state. All fields change on the Swing thread only, which is also the Compose UI thread.
 * [version] bumps on every change, and the canvas reads it to know when to repaint.
 */
class Viewer(val config: Config) {
    enum class Status { CONNECTING, ONLINE, OFFLINE, BAD_TOKEN, BAD_ADDRESS }

    val model = NoteModel()
    val partials = HashMap<String, Partial>()
    var version by mutableIntStateOf(0)
        private set
    var pageId by mutableStateOf<String?>(null)
    var follow by mutableStateOf(config.follow)
    var dark by mutableStateOf(config.dark)

    /** Own view, used when not following: world point at the canvas top-left, and px per world unit. */
    var ox by mutableStateOf(0f)
    var oy by mutableStateOf(0f)
    var zoom by mutableStateOf(0f) // 0 = not set yet, the canvas picks a default
    /** Last world rectangle the tablet showed: x, y, w, h. */
    var tabletView by mutableStateOf<FloatArray?>(null)
        private set
    var status by mutableStateOf(Status.CONNECTING)
        private set

    private var lastSeq = 0L
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "viewer").apply { isDaemon = true } }
    private var ws: WebSocket? = null
    private var retry: ScheduledFuture<*>? = null
    private var attempt = 0

    fun start() {
        connect()
        // Drop live strokes the tablet never finished (lost connection mid-stroke).
        timer.scheduleAtFixedRate({
            ui {
                val now = System.currentTimeMillis()
                if (partials.values.removeIf { now - it.touched > 10_000 }) version++
            }
        }, 5, 5, TimeUnit.SECONDS)
    }

    fun restart() {
        ws?.cancel()
        ws = null
        lastSeq = 0
        attempt = 0
        connect()
    }

    fun pages() = model.pages()

    var canvasW = 0f
    var canvasH = 0f
    private val boundsCache = HashMap<String, FloatArray>()

    fun bounds(s: dev.handnotes.shared.Stroke): FloatArray = boundsCache.getOrPut(s.id) { Ink.bounds(s) }

    /** View in use now: ox, oy, zoom. Follow mode fits the tablet view into the canvas. */
    fun view(): Triple<Float, Float, Float> {
        val tv = tabletView
        if (canvasW <= 0f || canvasH <= 0f) return Triple(0f, 0f, 0f)
        if (follow && tv != null && tv[2] > 0f && tv[3] > 0f) {
            val z = minOf(canvasW / tv[2], canvasH / tv[3])
            return Triple(tv[0] + tv[2] / 2 - canvasW / z / 2, tv[1] + tv[3] / 2 - canvasH / z / 2, z)
        }
        if (zoom <= 0f) zoom = canvasW / BOARD_DEFAULT_W
        return Triple(ox, oy, zoom)
    }

    /** The user moves the view: stop following and continue from what is on screen. */
    fun takeOverView() {
        val (x, y, z) = view()
        ox = x; oy = y; zoom = z
        if (follow) changeFollow(false)
    }

    fun pan(dx: Float, dy: Float) {
        ox -= dx / zoom
        oy -= dy / zoom
    }

    fun zoomAt(px: Float, py: Float, factor: Float) {
        takeOverView()
        val wx = ox + px / zoom
        val wy = oy + py / zoom
        zoom = (zoom * factor).coerceIn(0.02f, 40f)
        ox = wx - px / zoom
        oy = wy - py / zoom
    }

    fun fitAll() {
        val strokes = model.page(pageId)?.strokes?.values ?: return
        if (strokes.isEmpty() || canvasW <= 0f) return
        val all = strokes.map(::bounds)
        val l = all.minOf { it[0] }
        val t = all.minOf { it[1] }
        val r = all.maxOf { it[2] }
        val b = all.maxOf { it[3] }
        takeOverView()
        zoom = minOf(canvasW / (r - l), canvasH / (b - t)) * 0.9f
        ox = (l + r) / 2 - canvasW / zoom / 2
        oy = (t + b) / 2 - canvasH / zoom / 2
    }

    fun changeFollow(v: Boolean) {
        follow = v
        config.follow = v
        config.save()
    }

    fun setDarkMode(v: Boolean) {
        dark = v
        config.dark = v
        config.save()
    }

    fun go(delta: Int) {
        val ps = pages()
        val i = ps.indexOfFirst { it.id == pageId }
        val j = (i + delta).coerceIn(0, ps.size - 1)
        if (j >= 0) pageId = ps[j].id
    }

    private fun connect() {
        retry?.cancel(false)
        val req = runCatching { Request.Builder().url(ServerAddress.ws(config.serverUrl)).build() }.getOrElse {
            status = Status.BAD_ADDRESS
            return
        }
        status = Status.CONNECTING
        val socket = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(Proto.encode(Msg.Hello(config.deviceId, hostName(), "viewer", config.token, lastSeq)))
                ui {
                    if (webSocket !== ws) return@ui
                    attempt = 0
                    status = Status.ONLINE
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val msg = runCatching { Proto.decode(text) }.getOrNull() ?: return
                ui { if (webSocket === ws) handle(msg) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)
        })
        ws = socket
    }

    private fun lost(webSocket: WebSocket) = ui {
        if (webSocket !== ws) return@ui
        ws = null
        if (status != Status.BAD_TOKEN) status = Status.OFFLINE
        val delay = minOf(30_000L, 1000L shl minOf(attempt, 5))
        attempt++
        retry = timer.schedule({ ui { if (ws == null) connect() } }, delay, TimeUnit.MILLISECONDS)
    }

    private fun handle(msg: Msg) {
        when (msg) {
            is Msg.Ops -> {
                for (item in msg.items) {
                    val op = item.op
                    model.apply(op)
                    when (op) {
                        is AddStroke -> partials.remove(op.stroke.id)
                        is AddPage -> if (follow && item.seq > lastSeq && msg.items.size == 1) pageId = op.pageId
                        is DeletePage -> if (pageId == op.pageId) pageId = null
                        else -> {}
                    }
                    lastSeq = maxOf(lastSeq, item.seq)
                }
                if (pageId == null || model.page(pageId) == null) pageId = pages().lastOrNull()?.id
            }
            is Msg.Live -> {
                val p = partials.getOrPut(msg.strokeId) { Partial(msg.pageId, msg.color, msg.width) }
                // Chunks overlap by one point; skip it.
                val add = if (p.points.isEmpty()) msg.points else msg.points.copyOfRange(minOf(3, msg.points.size), msg.points.size)
                p.points += add
                p.touched = System.currentTimeMillis()
                if (follow) pageId = msg.pageId
            }
            is Msg.Focus -> {
                if (follow) pageId = msg.pageId
                if (msg.x != null && msg.y != null && msg.w != null && msg.h != null) {
                    tabletView = floatArrayOf(msg.x!!, msg.y!!, msg.w!!, msg.h!!)
                }
            }
            is Msg.Fail -> status = Status.BAD_TOKEN
            else -> {}
        }
        version++
    }

    private fun ui(block: () -> Unit) = SwingUtilities.invokeLater(block)

    private fun hostName() = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("desktop")
}
