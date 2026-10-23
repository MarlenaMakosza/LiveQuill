package dev.handnotes.server

import dev.handnotes.shared.Msg
import dev.handnotes.shared.Proto
import dev.handnotes.shared.PushRequest
import dev.handnotes.shared.SeqOp
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

private val log = LoggerFactory.getLogger("handnotes")

fun main() {
    val port = System.getenv("PORT")?.toIntOrNull() ?: 8787
    val token = System.getenv("HANDNOTES_TOKEN").orEmpty()
    val store = OpStore(File(System.getenv("HANDNOTES_DATA") ?: "data"))
    val hub = Hub(store, token)
    if (token.isEmpty()) log.warn("HANDNOTES_TOKEN is empty: any client in the network can connect")

    embeddedServer(CIO, port = port, host = "0.0.0.0") {
        install(WebSockets) {
            pingPeriod = 15.seconds
            timeout = 30.seconds
            maxFrameSize = 16L * 1024 * 1024
        }
        routing {
            get("/health") { call.respondText("ok") }
            post("/api/push") {
                val req = runCatching { Proto.json.decodeFromString(PushRequest.serializer(), call.receiveText()) }
                    .getOrElse { return@post call.respondText("bad request", status = HttpStatusCode.BadRequest) }
                if (!hub.authorized(req.token)) return@post call.respondText("bad token", status = HttpStatusCode.Unauthorized)
                val ack = hub.push(req.deviceId, req.items, from = null)
                call.respondText(Proto.encode(ack), ContentType.Application.Json)
            }
            webSocket("/ws") { hub.handle(this) }
        }
    }.start(wait = true)
}

class Hub(private val store: OpStore, private val token: String) {
    private class Client(val deviceId: String, val name: String, val role: String) {
        val out = Channel<String>(1024)
    }

    private val clients = ConcurrentHashMap.newKeySet<Client>()
    @Volatile private var focus: Msg.Focus? = null

    fun authorized(t: String) = token.isEmpty() || t == token

    suspend fun handle(ws: DefaultWebSocketServerSession) {
        val first = ws.incoming.receiveCatching().getOrNull() as? Frame.Text ?: return
        val hello = runCatching { Proto.decode(first.readText()) }.getOrNull() as? Msg.Hello
        if (hello == null) {
            ws.close(CloseReason(CloseReason.Codes.CANNOT_ACCEPT, "expected hello"))
            return
        }
        if (!authorized(hello.token)) {
            ws.send(Frame.Text(Proto.encode(Msg.Fail("bad token"))))
            ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "bad token"))
            return
        }
        val c = Client(hello.deviceId, hello.name, hello.role)
        val writer = ws.launch {
            for (s in c.out) ws.send(Frame.Text(s))
            // Queue closed by offer(): end the connection so the client reconnects and resyncs.
            ws.close(CloseReason(CloseReason.Codes.TRY_AGAIN_LATER, "slow client"))
        }
        log.info("+ {} ({}, {}) since {}", c.name, c.role, c.deviceId, hello.since)
        try {
            // Same lock as push(): the snapshot goes out complete and in order, before any new broadcast.
            synchronized(this) {
                clients += c
                send(c, Msg.Ack(store.ackFor(c.deviceId)))
                store.since(hello.since).chunked(500).forEach { send(c, Msg.Ops(it, store.head)) }
                focus?.let { send(c, it) }
            }
            for (frame in ws.incoming) {
                if (frame !is Frame.Text) continue
                val msg = runCatching { Proto.decode(frame.readText()) }.getOrNull() ?: continue
                when (msg) {
                    is Msg.Push -> send(c, push(c.deviceId, msg.items, from = c))
                    is Msg.Live -> relay(c, msg)
                    is Msg.Focus -> {
                        focus = msg
                        relay(c, msg)
                    }
                    else -> {}
                }
            }
        } finally {
            clients -= c
            c.out.close()
            writer.cancel()
            log.info("- {} ({})", c.name, c.deviceId)
        }
    }

    @Synchronized
    fun push(deviceId: String, items: List<SeqOp>, from: Any?): Msg.Ack {
        val (added, upTo) = store.append(deviceId, items)
        if (added.isNotEmpty()) {
            val msg = Proto.encode(Msg.Ops(added, store.head))
            for (c in clients) if (c !== from) offer(c, msg)
        }
        return Msg.Ack(upTo)
    }

    private fun send(c: Client, m: Msg) = offer(c, Proto.encode(m))

    /** Reliable message. A client too slow to drain its queue gets dropped; it resyncs on reconnect. */
    private fun offer(c: Client, s: String) {
        if (c.out.trySend(s).isFailure) {
            log.warn("Queue full for {}, dropping connection", c.deviceId)
            c.out.close()
        }
    }

    /** Best-effort message: live strokes may get lost, the final stroke always comes as an op. */
    private fun relay(from: Client, m: Msg) {
        val s = Proto.encode(m)
        for (c in clients) if (c !== from) c.out.trySend(s)
    }
}
