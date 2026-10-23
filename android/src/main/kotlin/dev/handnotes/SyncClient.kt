package dev.handnotes

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import dev.handnotes.shared.Msg
import dev.handnotes.shared.Proto
import dev.handnotes.shared.ServerAddress
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Keeps one WebSocket to the server while the process lives. Everything except [sendLive]
 * runs on a private thread, so the state needs no locks.
 *
 * Offline is the normal case: local ops wait in the outbox, and the client retries with
 * backoff and on every network change.
 */
class SyncClient(ctx: Context, private val repo: Repo) {
    enum class State { NO_SERVER, OFFLINE, CONNECTING, ONLINE, BAD_TOKEN }

    class Status(val state: State, val pending: Long)

    private val thread = HandlerThread("sync").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()
    private val connectivity = ctx.getSystemService(ConnectivityManager::class.java)
    private val listeners = CopyOnWriteArrayList<(Status) -> Unit>()

    private var ws: WebSocket? = null
    @Volatile private var open: WebSocket? = null
    private var sentUpTo = 0L
    private var attempt = 0
    private var state = State.OFFLINE
    @Volatile var status = Status(State.OFFLINE, 0)
        private set

    private val retry = Runnable { connect() }

    fun start() {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                reconnectNow()
            }
        })
        handler.post { connect() }
    }

    fun addListener(l: (Status) -> Unit) {
        listeners += l
        l(status)
    }

    fun removeListener(l: (Status) -> Unit) = listeners.remove(l)

    /** Settings changed: drop the current connection and start again. */
    fun restart() = handler.post {
        ws?.cancel()
        ws = null
        open = null
        attempt = 0
        connect()
    }

    fun reconnectNow() = handler.post {
        attempt = 0
        if (ws == null) connect()
    }

    fun onLocalOp() = handler.post {
        flush()
        publish()
    }

    @Volatile private var lastFocus: Msg.Focus? = null

    /** Main thread. Remembered and re-sent on every connect, so viewers always know the current view. */
    fun sendFocus(m: Msg.Focus) {
        lastFocus = m
        sendLive(m)
    }

    /** Any thread. Dropped while offline: live strokes are a preview only. */
    fun sendLive(m: Msg) {
        open?.send(Proto.encode(m))
    }

    private fun connect() {
        handler.removeCallbacks(retry)
        if (ws != null) return
        val url = repo.serverUrl
        if (url.isEmpty()) return setState(State.NO_SERVER)
        val req = runCatching { Request.Builder().url(ServerAddress.ws(url)).build() }.getOrElse {
            return setState(State.NO_SERVER)
        }
        setState(State.CONNECTING)
        val socket = http.newWebSocket(req, Listener())
        ws = socket
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = handler.post {
            if (webSocket !== ws) return@post
            attempt = 0
            sentUpTo = 0
            webSocket.send(
                Proto.encode(
                    Msg.Hello(repo.deviceId, repo.deviceName, "tablet", repo.token, repo.db.kvLong("lastServerSeq")),
                ),
            )
            open = webSocket
            (lastFocus ?: repo.currentPage?.let { Msg.Focus(repo.deviceId, it) })
                ?.let { webSocket.send(Proto.encode(it)) }
            flush()
        }.let {}

        override fun onMessage(webSocket: WebSocket, text: String) = handler.post {
            if (webSocket !== ws) return@post
            val msg = runCatching { Proto.decode(text) }.getOrNull() ?: return@post
            when (msg) {
                is Msg.Ack -> {
                    repo.db.ackOutbox(msg.upTo)
                    if (sentUpTo < msg.upTo) sentUpTo = msg.upTo
                    setState(State.ONLINE)
                }
                is Msg.Ops -> repo.applyRemote(msg.items)
                is Msg.Fail -> {
                    setState(State.BAD_TOKEN)
                    webSocket.close(1000, null)
                }
                else -> {}
            }
        }.let {}

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket)

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = lost(webSocket)

        private fun lost(webSocket: WebSocket) {
            handler.post {
                if (webSocket !== ws) return@post
                ws = null
                open = null
                if (state != State.BAD_TOKEN) setState(State.OFFLINE)
                // 1, 2, 4 ... 30 s. Network changes reset it through reconnectNow().
                val delay = minOf(30_000L, 1000L shl minOf(attempt, 5))
                attempt++
                handler.postDelayed(retry, delay)
            }
        }
    }

    /** Sends outbox entries not yet sent on this connection. The server ignores duplicates. */
    private fun flush() {
        val socket = open ?: return
        while (true) {
            val batch = repo.db.outbox(sentUpTo, 200)
            if (batch.isEmpty()) return
            if (!socket.send(Proto.encode(Msg.Push(batch)))) return
            sentUpTo = batch.last().seq
        }
    }

    private fun setState(s: State) {
        state = s
        publish()
    }

    private fun publish() {
        val st = Status(state, repo.db.outboxCount())
        status = st
        main.post { listeners.forEach { it(st) } }
    }
}
