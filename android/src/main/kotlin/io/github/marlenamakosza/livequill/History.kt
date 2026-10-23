package io.github.marlenamakosza.livequill

import io.github.marlenamakosza.livequill.shared.AddStroke
import io.github.marlenamakosza.livequill.shared.EraseStroke
import io.github.marlenamakosza.livequill.shared.Stroke
import io.github.marlenamakosza.livequill.shared.newId

/**
 * Undo and redo per board, for this session. The op log is append-only, so undo never deletes
 * anything: undoing a stroke erases it, and undoing an erase adds a copy of the stroke with a
 * new id. Every step syncs like any other change.
 */
class History(private val repo: Repo) {
    sealed class Action(var strokes: List<Stroke>) {
        class Add(strokes: List<Stroke>) : Action(strokes)
        class Erase(strokes: List<Stroke>) : Action(strokes)
    }

    private class Stacks {
        val undo = ArrayDeque<Action>()
        val redo = ArrayDeque<Action>()
    }

    private val boards = HashMap<String, Stacks>()

    private fun stacks(pageId: String) = boards.getOrPut(pageId) { Stacks() }

    fun record(pageId: String, a: Action) {
        val s = stacks(pageId)
        s.undo.addLast(a)
        if (s.undo.size > MAX) s.undo.removeFirst()
        s.redo.clear()
    }

    fun undo(pageId: String): Boolean {
        val s = stacks(pageId)
        val a = s.undo.removeLastOrNull() ?: return undoOlder(pageId, s)
        when (a) {
            is Action.Add -> erase(a.strokes)
            is Action.Erase -> a.strokes = restore(a.strokes)
        }
        s.redo.addLast(a)
        return true
    }

    fun redo(pageId: String): Boolean {
        val s = stacks(pageId)
        val a = s.redo.removeLastOrNull() ?: return false
        when (a) {
            is Action.Add -> a.strokes = restore(a.strokes)
            is Action.Erase -> erase(a.strokes)
        }
        s.undo.addLast(a)
        return true
    }

    /** Nothing recorded this session (e.g. after a restart): remove the newest own stroke. */
    private fun undoOlder(pageId: String, s: Stacks): Boolean {
        val last = repo.model.page(pageId)?.strokes?.values?.lastOrNull { it.deviceId == repo.deviceId } ?: return false
        erase(listOf(last))
        s.redo.addLast(Action.Add(listOf(last)))
        return true
    }

    private fun erase(strokes: List<Stroke>) {
        val now = System.currentTimeMillis()
        for (st in strokes) {
            // Another device may have erased it already.
            if (repo.model.page(st.pageId)?.strokes?.containsKey(st.id) != true) continue
            repo.addLocal(EraseStroke(newId(), repo.deviceId, now, st.pageId, st.id))
        }
    }

    /** Erased ids stay erased forever, so a restore is a copy under a new id. */
    private fun restore(strokes: List<Stroke>): List<Stroke> {
        val now = System.currentTimeMillis()
        return strokes.map { st ->
            val copy = Stroke(newId(), st.pageId, repo.deviceId, st.color, st.width, st.points)
            repo.addLocal(AddStroke(newId(), repo.deviceId, now, copy))
            copy
        }
    }

    private companion object {
        const val MAX = 200
    }
}
