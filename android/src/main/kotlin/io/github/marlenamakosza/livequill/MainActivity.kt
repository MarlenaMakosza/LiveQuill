package io.github.marlenamakosza.livequill

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.marlenamakosza.livequill.shared.AddPage
import io.github.marlenamakosza.livequill.shared.AddStroke
import io.github.marlenamakosza.livequill.shared.DeletePage
import io.github.marlenamakosza.livequill.shared.EraseStroke
import io.github.marlenamakosza.livequill.shared.Ink
import io.github.marlenamakosza.livequill.shared.Op
import io.github.marlenamakosza.livequill.shared.Theme
import io.github.marlenamakosza.livequill.shared.newId

class MainActivity : Activity() {
    private val app get() = application as LiveQuillApp
    private val repo get() = app.repo
    private lateinit var ink: InkView
    private lateinit var pageLabel: TextView
    private lateinit var status: TextView
    private lateinit var eraserBtn: Button
    private lateinit var lassoBtn: Button
    private lateinit var inputBtn: Button
    private lateinit var widthBtn: Button
    private val history get() = app.history
    private val renderNow = Runnable { ink.render() }
    private var dark = false
    private val colorViews = ArrayList<View>()

    private val colors = intArrayOf(0xFF111111.toInt(), 0xFF1F4FD8.toInt(), 0xFFD32F2F.toInt(), 0xFF2E7D32.toInt())
    private val widths = floatArrayOf(2f, 3.5f, 6f, 10f)
    private var widthIdx = 1

    private val onOp: (Op, Boolean) -> Unit = { op, local ->
        when (op) {
            // A local stroke is already in the bitmap.
            is AddStroke -> if ((!local || restoring) && op.stroke.pageId == ink.pageId) scheduleRender()
            is EraseStroke -> if (op.pageId == ink.pageId) scheduleRender()
            is AddPage, is DeletePage -> ensurePage()
        }
        updatePageLabel()
    }

    private val onStatus: (SyncClient.Status) -> Unit = { s ->
        val (text, color) = when (s.state) {
            SyncClient.State.ONLINE -> getString(R.string.status_online) to 0xFF2E7D32.toInt()
            SyncClient.State.CONNECTING -> getString(R.string.status_connecting) to 0xFFF9A825.toInt()
            SyncClient.State.OFFLINE -> getString(R.string.status_offline) to 0xFF9E9E9E.toInt()
            SyncClient.State.NO_SERVER -> getString(R.string.status_no_server) to 0xFF9E9E9E.toInt()
            SyncClient.State.BAD_TOKEN -> getString(R.string.status_bad_token) to 0xFFD32F2F.toInt()
        }
        status.text = if (s.pending > 0) getString(R.string.status_pending, text, s.pending) else text
        status.setTextColor(color)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        dark = repo.dark ?: ((resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val barColor = Theme.bar(dark)
        val textColor = Theme.text(dark)

        ink = InkView(this, repo).apply {
            this.dark = this@MainActivity.dark
            fingerDraw = repo.fingerDraw
            baseWidth = widths[widthIdx]
            onLive = app.sync::sendLive
            onViewport = app.sync::sendFocus
            onAction = history::record
            onTap = { fingers -> if (fingers == 2) undo() else if (fingers == 3) redo() }
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setBackgroundColor(barColor)
        }
        bar.addView(button("◀") { goPage(-1) })
        pageLabel = TextView(this).apply {
            textSize = 16f
            setTextColor(textColor)
            setPadding(dp(8), 0, dp(8), 0)
        }
        bar.addView(pageLabel)
        bar.addView(button("▶") { goPage(+1) })
        bar.addView(button(getString(R.string.new_board)) { showPage(repo.newPage()) })
        bar.addView(button(getString(R.string.fit_all)) { ink.fitAll() })
        bar.addView(gap())
        colors.forEachIndexed { i, c -> bar.addView(swatch(c, i)) }
        bar.addView(gap())
        widthBtn = button("") { cycleWidth() }
        bar.addView(widthBtn)
        inputBtn = button("") { setFingerMode(!ink.fingerDraw) }
        bar.addView(inputBtn)
        eraserBtn = button(getString(R.string.eraser)) { setTool(eraser = !ink.eraserMode, lasso = false) }
        bar.addView(eraserBtn)
        lassoBtn = button(getString(R.string.lasso)) { setTool(eraser = false, lasso = !ink.lassoMode) }
        bar.addView(lassoBtn)
        bar.addView(button(getString(R.string.undo)) { undo() })
        bar.addView(button(getString(R.string.redo)) { redo() })
        bar.addView(gap())
        status = TextView(this).apply { textSize = 14f }
        bar.addView(status)
        bar.addView(gap())
        bar.addView(button(getString(if (dark) R.string.light_mode else R.string.dark_mode)) {
            repo.dark = !dark
            recreate()
        })
        bar.addView(button(getString(R.string.delete_board)) { confirmDeletePage() })
        bar.addView(button("⚙") { settings() })

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Android 15+ draws apps edge to edge: keep the toolbar out from under the status bar.
            fitsSystemWindows = true
            setBackgroundColor(barColor)
            addView(HorizontalScrollView(this@MainActivity).apply {
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(barColor)
                addView(bar)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(ink, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        selectColor(0)
        setFingerMode(repo.fingerDraw)
        updateWidthLabel()
        ensurePage()
        if (repo.serverUrl.isEmpty()) settings()
    }

    override fun onStart() {
        super.onStart()
        repo.addListener(onOp)
        app.sync.addListener(onStatus)
        app.sync.reconnectNow()
    }

    override fun onStop() {
        repo.removeListener(onOp)
        app.sync.removeListener(onStatus)
        // The process may die in background. WorkManager delivers whatever is still queued.
        if (repo.db.outboxCount() > 0) SyncWorker.enqueue(this)
        super.onStop()
    }

    private fun pages() = repo.model.pages()

    private fun ensurePage() {
        val ps = pages()
        if (ps.isEmpty()) {
            showPage(repo.newPage())
            return
        }
        val cur = repo.currentPage
        if (ink.pageId == null || ps.none { it.id == ink.pageId }) {
            showPage(ps.firstOrNull { it.id == cur }?.id ?: ps.last().id)
        }
    }

    private fun showPage(id: String) {
        repo.currentPage = id
        ink.pageId = id
        updatePageLabel()
    }

    private fun goPage(delta: Int) {
        val ps = pages()
        val i = ps.indexOfFirst { it.id == ink.pageId }
        val j = (i + delta).coerceIn(0, ps.size - 1)
        if (j != i) showPage(ps[j].id)
    }

    private fun updatePageLabel() {
        val ps = pages()
        pageLabel.text = "${ps.indexOfFirst { it.id == ink.pageId } + 1} / ${ps.size}"
    }

    /** True while undo/redo adds strokes back: those are not in the bitmap yet. */
    private var restoring = false

    private fun undo() {
        val id = ink.pageId ?: return
        restoring = true
        val done = history.undo(id)
        restoring = false
        ink.flash(getString(if (done) R.string.undone else R.string.nothing_to_undo))
    }

    private fun redo() {
        val id = ink.pageId ?: return
        restoring = true
        val done = history.redo(id)
        restoring = false
        ink.flash(getString(if (done) R.string.redone else R.string.nothing_to_redo))
    }

    /** Many ops in a row (an undone eraser pass) cost one repaint. */
    private fun scheduleRender() {
        ink.removeCallbacks(renderNow)
        ink.post(renderNow)
    }

    private fun confirmDeletePage() {
        val id = ink.pageId ?: return
        AlertDialog.Builder(this)
            .setMessage(R.string.delete_board_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                val ps = pages()
                val i = ps.indexOfFirst { it.id == id }
                val next = ps.getOrNull(i + 1) ?: ps.getOrNull(i - 1)
                ink.pageId = null
                repo.addLocal(DeletePage(newId(), repo.deviceId, System.currentTimeMillis(), id))
                if (next != null) showPage(next.id) else ensurePage()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Pen mode: only the stylus draws, fingers pan and zoom. Finger mode: one finger draws too,
     * two fingers pan and zoom. The stylus draws in both. The button shows the current mode.
     */
    private fun setFingerMode(on: Boolean) {
        ink.fingerDraw = on
        repo.fingerDraw = on
        inputBtn.text = getString(if (on) R.string.input_finger else R.string.input_pen)
    }

    /** Eraser and lasso exclude each other; with both off, the pen draws. */
    private fun setTool(eraser: Boolean, lasso: Boolean) {
        ink.eraserMode = eraser
        ink.lassoMode = lasso
        if (!lasso) ink.clearSelection()
        eraserBtn.alpha = if (eraser) 1f else 0.55f
        lassoBtn.alpha = if (lasso) 1f else 0.55f
    }

    private fun selectColor(i: Int) {
        ink.color = colors[i]
        setTool(eraser = false, lasso = false)
        colorViews.forEachIndexed { k, v ->
            val ring = if (dark) Color.WHITE else Color.BLACK
            (v.background as GradientDrawable).setStroke(dp(if (k == i) 3 else 1), if (k == i) ring else Color.GRAY)
        }
    }

    private fun cycleWidth() {
        widthIdx = (widthIdx + 1) % widths.size
        ink.baseWidth = widths[widthIdx]
        updateWidthLabel()
    }

    private fun updateWidthLabel() {
        widthBtn.text = getString(R.string.width, widthIdx + 1)
    }

    private fun settings() {
        val url = EditText(this).apply {
            hint = "192.168.1.10:8787"
            setText(repo.serverUrl)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val token = EditText(this).apply {
            hint = "token"
            setText(repo.token)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(label(getString(R.string.server_address)))
            addView(url)
            addView(label(getString(R.string.token)))
            addView(token)
            addView(label(getString(R.string.device_id, repo.deviceId.take(8))))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.settings)
            .setView(form)
            .setPositiveButton(R.string.save) { _, _ ->
                repo.serverUrl = url.text.toString()
                repo.token = token.text.toString()
                app.sync.restart()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), 0, dp(12), 0)
        setOnClickListener { onClick() }
    }

    private fun swatch(c: Int, i: Int) = View(this).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Ink.display(c, dark))
        }
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply { setMargins(dp(4), 0, dp(4), 0) }
        setOnClickListener { selectColor(i) }
        colorViews += this
    }

    private fun gap() = View(this).apply { layoutParams = LinearLayout.LayoutParams(dp(16), 1) }

    private fun label(t: String) = TextView(this).apply {
        text = t
        setPadding(0, dp(10), 0, 0)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
