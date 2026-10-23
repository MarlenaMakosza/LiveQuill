package dev.handnotes.shared

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.UUID

/**
 * Board coordinates are unbounded world units. A new board view shows [BOARD_DEFAULT_W] units
 * across the screen, so pen widths look the same on every device at default zoom.
 */
const val BOARD_DEFAULT_W = 1000f

fun newId(): String = UUID.randomUUID().toString()

/**
 * A finished stroke. [points] is flattened as x, y, pressure triples in page units,
 * pressure in 0..1.
 */
@Serializable
class Stroke(
    val id: String,
    val pageId: String,
    val deviceId: String,
    val color: Int,
    val width: Float,
    val points: FloatArray,
)

/** Notebook changes. Ops are append-only, so merging logs from many devices never conflicts. */
@Serializable
sealed class Op {
    abstract val id: String
    abstract val deviceId: String
    abstract val time: Long
}

@Serializable
@SerialName("page")
class AddPage(
    override val id: String,
    override val deviceId: String,
    override val time: Long,
    val pageId: String,
) : Op()

@Serializable
@SerialName("stroke")
class AddStroke(
    override val id: String,
    override val deviceId: String,
    override val time: Long,
    val stroke: Stroke,
) : Op()

@Serializable
@SerialName("erase")
class EraseStroke(
    override val id: String,
    override val deviceId: String,
    override val time: Long,
    val pageId: String,
    val strokeId: String,
) : Op()

@Serializable
@SerialName("delpage")
class DeletePage(
    override val id: String,
    override val deviceId: String,
    override val time: Long,
    val pageId: String,
) : Op()

/** An op with a sequence number: the client outbox number on push, the server log number on delivery. */
@Serializable
class SeqOp(val seq: Long, val op: Op)

/** WebSocket messages, both directions. */
@Serializable
sealed class Msg {
    /** First client message. [since] is the last server seq the client already has. */
    @Serializable
    @SerialName("hello")
    class Hello(
        val deviceId: String,
        val name: String,
        val role: String,
        val token: String,
        val since: Long,
    ) : Msg()

    /** Client to server: outbox entries, ascending by seq. */
    @Serializable
    @SerialName("push")
    class Push(val items: List<SeqOp>) : Msg()

    /** Server to client: every outbox entry up to and including [upTo] is stored. */
    @Serializable
    @SerialName("ack")
    class Ack(val upTo: Long) : Msg()

    /** Server to client: log entries. [head] is the newest server seq. */
    @Serializable
    @SerialName("ops")
    class Ops(val items: List<SeqOp>, val head: Long) : Msg()

    /** Stroke in progress, relayed to viewers and never stored. [points] holds only the new points. */
    @Serializable
    @SerialName("live")
    class Live(
        val deviceId: String,
        val pageId: String,
        val strokeId: String,
        val color: Int,
        val width: Float,
        val points: FloatArray,
        val done: Boolean,
    ) : Msg()

    /**
     * The board and the world rectangle the tablet shows now. Viewers in follow mode show the same area.
     * The rectangle is null when the tablet did not lay out its view yet.
     */
    @Serializable
    @SerialName("focus")
    class Focus(
        val deviceId: String,
        val pageId: String,
        val x: Float? = null,
        val y: Float? = null,
        val w: Float? = null,
        val h: Float? = null,
    ) : Msg()

    @Serializable
    @SerialName("fail")
    class Fail(val message: String) : Msg()
}

/** Body of `POST /api/push`, used by the Android background worker. The reply is [Msg.Ack]. */
@Serializable
class PushRequest(val token: String, val deviceId: String, val items: List<SeqOp>)

object Proto {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "t"
    }

    fun encode(m: Msg): String = json.encodeToString(Msg.serializer(), m)
    fun decode(s: String): Msg = json.decodeFromString(Msg.serializer(), s)
    fun encodeOp(op: Op): String = json.encodeToString(Op.serializer(), op)
    fun decodeOp(s: String): Op = json.decodeFromString(Op.serializer(), s)
}

/** Shared pen look, so both apps draw a stroke the same way. */
object Ink {
    fun width(base: Float, pressure: Float): Float = base * (0.15f + 0.85f * pressure.coerceIn(0f, 1f))

    /**
     * Color to draw with. Strokes keep the color they were written with. In dark mode, dark ink
     * turns light and other colors get lighter, so a note stays readable in both modes.
     */
    fun display(argb: Int, dark: Boolean): Int {
        if (!dark) return argb
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val luma = (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        val chroma = maxOf(r, g, b) - minOf(r, g, b)
        // Only dark, grayish ink (black, graphite) turns light; a dark red stays red.
        if (luma < 0.2f && chroma < 60) return (argb and 0xFF000000.toInt()) or 0xE8E8E8
        fun lift(c: Int) = c + ((255 - c) * 0.3f).toInt()
        return (argb and 0xFF000000.toInt()) or (lift(r) shl 16) or (lift(g) shl 8) or lift(b)
    }

    /** Bounding box of a stroke in world units: left, top, right, bottom. */
    fun bounds(s: Stroke): FloatArray {
        val p = s.points
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        var i = 0
        while (i < p.size) {
            l = minOf(l, p[i]); r = maxOf(r, p[i])
            t = minOf(t, p[i + 1]); b = maxOf(b, p[i + 1])
            i += 3
        }
        val pad = s.width
        return floatArrayOf(l - pad, t - pad, r + pad, b + pad)
    }
}

/** Board colors, shared by both apps. */
object Theme {
    fun background(dark: Boolean): Int = if (dark) 0xFF1C1C1E.toInt() else 0xFFFFFFFF.toInt()
    fun dots(dark: Boolean): Int = if (dark) 0xFF3A3A3E.toInt() else 0xFFDCDCDC.toInt()
    fun bar(dark: Boolean): Int = if (dark) 0xFF2A2A2D.toInt() else 0xFFF3F1EC.toInt()
    fun text(dark: Boolean): Int = if (dark) 0xFFE6E6E6.toInt() else 0xFF222222.toInt()

    /** Dot grid step in world units, doubled until the dots are at least [minPx] apart on screen. */
    fun dotStep(zoom: Float, minPx: Float): Float {
        var step = 50f
        while (step * zoom < minPx) step *= 2
        while (step * zoom > minPx * 4 && step > 12.5f) step /= 2
        return step
    }
}

/** Normalizes what the user types ("192.168.1.5:8787", "http://host", "wss://host") to base URLs. */
object ServerAddress {
    fun ws(input: String): String {
        val (secure, rest) = split(input)
        return (if (secure) "wss://" else "ws://") + rest + "/ws"
    }

    fun http(input: String): String {
        val (secure, rest) = split(input)
        return (if (secure) "https://" else "http://") + rest
    }

    /** Returns (secure, host[:port][/path]). Plain addresses without a port get the default 8787. */
    private fun split(input: String): Pair<Boolean, String> {
        val s = input.trim().trimEnd('/').removeSuffix("/ws")
        val secure = s.startsWith("https://") || s.startsWith("wss://")
        val rest = s.substringAfter("://")
        val host = rest.substringBefore('/')
        if (secure || ':' in host) return secure to rest
        return false to host + ":8787" + rest.removePrefix(host)
    }
}
