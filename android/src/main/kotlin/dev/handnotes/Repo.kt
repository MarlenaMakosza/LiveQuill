package dev.handnotes

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.handnotes.shared.AddPage
import dev.handnotes.shared.NoteModel
import dev.handnotes.shared.Op
import dev.handnotes.shared.SeqOp
import dev.handnotes.shared.newId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Notebook state for the app. [model] lives on the main thread only;
 * remote ops reach it through a main-thread post.
 */
class Repo(ctx: Context) {
    val db = NoteDb(ctx)
    val model = NoteModel()
    val deviceId: String = db.kv("deviceId") ?: newId().also { db.setKv("deviceId", it) }
    val deviceName: String = Build.MODEL ?: "tablet"

    /** Called with the op and true if it came from this tablet. */
    private val listeners = CopyOnWriteArrayList<(Op, Boolean) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    /** Wakes the sync client after a local change. */
    var onLocalOp: (() -> Unit)? = null

    var serverUrl: String
        get() = db.kv("serverUrl").orEmpty()
        set(v) = db.setKv("serverUrl", v.trim())
    var token: String
        get() = db.kv("token").orEmpty()
        set(v) = db.setKv("token", v.trim())
    var fingerDraw: Boolean
        get() = db.kv("fingerDraw") == "1"
        set(v) = db.setKv("fingerDraw", if (v) "1" else "0")
    var currentPage: String?
        get() = db.kv("page")
        set(v) = db.setKv("page", v)

    /** Dark mode: true/false, or null to follow the system setting. */
    var dark: Boolean?
        get() = db.kv("dark")?.let { it == "1" }
        set(v) = db.setKv("dark", v?.let { if (it) "1" else "0" })

    /** Last view of a board on this tablet: ox, oy, zoom. */
    fun viewport(pageId: String): FloatArray? =
        db.kv("vp:$pageId")?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 3 }?.toFloatArray()

    fun setViewport(pageId: String, v: FloatArray) = db.setKv("vp:$pageId", v.joinToString(","))

    init {
        db.allOps().forEach(model::apply)
        if (model.pages().isEmpty()) newPage()
    }

    fun addListener(l: (Op, Boolean) -> Unit) = listeners.add(l)
    fun removeListener(l: (Op, Boolean) -> Unit) = listeners.remove(l)

    /** Main thread only. */
    fun addLocal(op: Op) {
        db.insertLocal(op)
        model.apply(op)
        listeners.forEach { it(op, true) }
        onLocalOp?.invoke()
    }

    fun newPage(): String {
        val pageId = newId()
        addLocal(AddPage(newId(), deviceId, System.currentTimeMillis(), pageId))
        return pageId
    }

    /** Any thread. */
    fun applyRemote(items: List<SeqOp>) {
        val fresh = db.insertRemote(items)
        if (fresh.isEmpty()) return
        main.post {
            for (op in fresh) {
                model.apply(op)
                listeners.forEach { it(op, false) }
            }
        }
    }
}
