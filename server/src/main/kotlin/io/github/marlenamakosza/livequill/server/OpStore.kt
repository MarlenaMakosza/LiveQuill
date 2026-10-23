package io.github.marlenamakosza.livequill.server

import io.github.marlenamakosza.livequill.shared.Op
import io.github.marlenamakosza.livequill.shared.Proto
import io.github.marlenamakosza.livequill.shared.SeqOp
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream

/** One line of `ops.jsonl`. [clientSeq] lets the server drop duplicates after restart. */
@Serializable
private class Line(val seq: Long, val deviceId: String, val clientSeq: Long, val op: Op)

/**
 * Append-only op log on disk, kept in memory too. Every accepted push is fsynced
 * before the ack goes out, so an acked op never gets lost.
 */
class OpStore(dir: File) {
    private val log = LoggerFactory.getLogger(OpStore::class.java)
    private val file = File(dir, "ops.jsonl")
    private val items = ArrayList<SeqOp>()
    private val opIds = HashSet<String>()
    private val deviceMax = HashMap<String, Long>()
    private val out: FileOutputStream

    init {
        dir.mkdirs()
        if (file.exists()) {
            file.useLines { lines ->
                for (raw in lines) {
                    if (raw.isBlank()) continue
                    // A crash mid-write can leave a partial last line. Skip it; the client re-sends it.
                    val line = runCatching { Proto.json.decodeFromString(Line.serializer(), raw) }.getOrNull() ?: continue
                    items += SeqOp(items.size + 1L, line.op)
                    opIds += line.op.id
                    deviceMax[line.deviceId] = maxOf(deviceMax[line.deviceId] ?: 0, line.clientSeq)
                }
            }
        }
        out = FileOutputStream(file, true)
        log.info("Loaded {} ops from {}", items.size, file)
    }

    val head: Long @Synchronized get() = items.size.toLong()

    @Synchronized
    fun ackFor(deviceId: String): Long = deviceMax[deviceId] ?: 0

    /**
     * Stores the new entries of a client push. Returns the stored entries with server seqs,
     * and the client seq up to which everything is now stored.
     */
    @Synchronized
    fun append(deviceId: String, pushed: List<SeqOp>): Pair<List<SeqOp>, Long> {
        var max = deviceMax[deviceId] ?: 0
        val added = ArrayList<SeqOp>()
        val buf = StringBuilder()
        for (e in pushed.sortedBy { it.seq }) {
            if (e.seq <= max) continue
            max = e.seq
            if (!opIds.add(e.op.id)) continue
            val stored = SeqOp(items.size + 1L, e.op)
            items += stored
            added += stored
            buf.append(Proto.json.encodeToString(Line.serializer(), Line(stored.seq, deviceId, e.seq, e.op))).append('\n')
        }
        if (buf.isNotEmpty()) {
            out.write(buf.toString().toByteArray())
            out.flush()
            out.fd.sync()
        }
        deviceMax[deviceId] = max
        return added to max
    }

    /** Entries with server seq greater than [seq]. */
    @Synchronized
    fun since(seq: Long): List<SeqOp> {
        val from = seq.coerceIn(0, items.size.toLong()).toInt()
        return items.subList(from, items.size).toList()
    }
}
