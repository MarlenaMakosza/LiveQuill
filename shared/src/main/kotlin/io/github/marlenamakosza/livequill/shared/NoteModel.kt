package io.github.marlenamakosza.livequill.shared

class Page(val id: String, val created: Long) {
    /** Insertion order is drawing order. */
    val strokes = LinkedHashMap<String, Stroke>()
}

/**
 * Notebook state rebuilt from the op log. Not thread-safe: each app touches it from its UI thread only.
 * Applying the same op twice is harmless.
 */
class NoteModel {
    private val pageMap = HashMap<String, Page>()
    private val erased = HashSet<String>()
    private val deletedPages = HashSet<String>()

    fun apply(op: Op) {
        when (op) {
            is AddPage -> if (op.pageId !in deletedPages) pageMap.getOrPut(op.pageId) { Page(op.pageId, op.time) }
            is AddStroke -> {
                val s = op.stroke
                if (s.id in erased || s.pageId in deletedPages) return
                pageMap.getOrPut(s.pageId) { Page(s.pageId, op.time) }.strokes[s.id] = s
            }
            is EraseStroke -> {
                erased += op.strokeId
                pageMap[op.pageId]?.strokes?.remove(op.strokeId)
            }
            is DeletePage -> {
                deletedPages += op.pageId
                pageMap.remove(op.pageId)
            }
        }
    }

    fun page(id: String?): Page? = id?.let { pageMap[it] }

    fun pages(): List<Page> = pageMap.values.sortedWith(compareBy({ it.created }, { it.id }))
}
