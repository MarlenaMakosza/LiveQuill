package io.github.marlenamakosza.livequill

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import io.github.marlenamakosza.livequill.shared.AddStroke
import io.github.marlenamakosza.livequill.shared.BOARD_DEFAULT_W
import io.github.marlenamakosza.livequill.shared.EraseStroke
import io.github.marlenamakosza.livequill.shared.Ink
import io.github.marlenamakosza.livequill.shared.Msg
import io.github.marlenamakosza.livequill.shared.Stroke
import io.github.marlenamakosza.livequill.shared.Theme
import io.github.marlenamakosza.livequill.shared.newId
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Infinite board. The stylus draws; fingers pan (one or two) and zoom (two).
 *
 * The visible area renders into a bitmap. New pen segments go straight into it. During a pan or
 * zoom the bitmap is drawn transformed, and a full render happens at most every [RERENDER_MS]
 * and at the gesture end.
 */
@SuppressLint("ViewConstructor")
class InkView(ctx: Context, private val repo: Repo) : View(ctx) {
    var pageId: String? = null
        set(v) {
            field = v
            loadViewport()
            render()
            notifyViewport(force = true)
        }
    var dark = false
        set(v) {
            field = v
            render()
        }
    var color = 0xFF111111.toInt()
    var baseWidth = 3f
    var eraserMode = false
    var fingerDraw = false
    var onLive: ((Msg.Live) -> Unit)? = null

    /** A finished pen stroke or eraser pass, for undo. */
    var onAction: ((String, History.Action) -> Unit)? = null

    /** Quick tap with two fingers (undo) or three fingers (redo). */
    var onTap: ((Int) -> Unit)? = null

    /** World rectangle on screen changed: board id, x, y, width, height. */
    var onViewport: ((Msg.Focus) -> Unit)? = null

    private val density = resources.displayMetrics.density

    // Viewport: world point (ox, oy) sits at the screen top-left, z = screen px per world unit.
    private var ox = 0f
    private var oy = 0f
    private var z = 1f
    private var viewportLoaded = false

    // Bitmap and the viewport it was rendered with.
    private var cache: Bitmap? = null
    private var cacheCanvas: Canvas? = null
    private var cox = 0f
    private var coy = 0f
    private var cz = 1f
    private var renderedAt = 0L

    private val bounds = HashMap<String, FloatArray>()

    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.5f * density
    }
    private val cursor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF888888.toInt()
        strokeWidth = 1.5f * density
    }

    // Stroke in progress, world units.
    private var activePointer = -1
    private var strokeTool = 0
    private var erasing = false
    private var strokeId = ""
    private var pts = FloatArray(3 * 256)
    private var n = 0
    private var liveSent = 0
    private var liveAt = 0L
    private var eraserX = -1f
    private var eraserY = -1f
    private val erased = ArrayList<Stroke>()

    // Finger gesture.
    private val fingers = LinkedHashMap<Int, FloatArray>()
    private var gestureStart: FloatArray? = null // viewport at gesture start, to undo a palm pan
    private var gestureMoved = false
    private var downX = 0f
    private var downY = 0f
    private var prevCx = 0f
    private var prevCy = 0f
    private var prevSpan = 0f
    /** Pointers recognized as a palm: ignored until they lift. */
    private val palms = HashSet<Int>()

    // Multi-finger tap, tracked per finger and apart from pan/zoom, so a resting palm does not count.
    private class TapFinger(val t0: Long, val x0: Float, val y0: Float) {
        var moved = false
    }
    private val tapFingers = HashMap<Int, TapFinger>()
    private var tapCount = 0
    private var tapGroupStart = 0L
    private var tapViewport: FloatArray? = null

    // Short message over the board ("Undone"), so a gesture shows it worked.
    private var flashText: String? = null
    private var flashUntil = 0L
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 18 * resources.displayMetrics.density
    }
    private val flashBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private var viewportSentAt = 0L

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        cache?.recycle()
        cache = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        cacheCanvas = Canvas(cache!!)
        if (!viewportLoaded) loadViewport()
        render()
        notifyViewport(force = true)
    }

    private fun loadViewport() {
        if (width == 0) return
        val saved = pageId?.let(repo::viewport)
        if (saved != null) {
            ox = saved[0]; oy = saved[1]; z = saved[2]
        } else {
            ox = 0f; oy = 0f; z = width / BOARD_DEFAULT_W
        }
        viewportLoaded = true
    }

    private fun saveViewport() {
        pageId?.let { repo.setViewport(it, floatArrayOf(ox, oy, z)) }
    }

    /** Full repaint of the visible area. Call after any change to the board. */
    fun render() {
        val c = cacheCanvas ?: return
        c.drawColor(Theme.background(dark))
        drawDots(c)
        val page = repo.model.page(pageId)
        if (page != null) {
            val l = ox
            val t = oy
            val r = ox + width / z
            val b = oy + height / z
            for (s in page.strokes.values) {
                val bb = bounds.getOrPut(s.id) { Ink.bounds(s) }
                if (bb[2] < l || bb[0] > r || bb[3] < t || bb[1] > b) continue
                drawStroke(c, s)
            }
        }
        cox = ox; coy = oy; cz = z
        renderedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    private fun drawDots(c: Canvas) {
        val step = Theme.dotStep(z, 28 * density)
        dot.color = Theme.dots(dark)
        val x0 = floor(ox / step) * step
        val y0 = floor(oy / step) * step
        val cols = (width / z / step).toInt() + 2
        val rows = (height / z / step).toInt() + 2
        val arr = FloatArray(cols * rows * 2)
        var k = 0
        for (i in 0 until cols) for (j in 0 until rows) {
            arr[k++] = sx(x0 + i * step)
            arr[k++] = sy(y0 + j * step)
        }
        c.drawPoints(arr, dot)
    }

    private fun drawStroke(c: Canvas, s: Stroke) {
        val p = s.points
        val count = p.size / 3
        val col = Ink.display(s.color, dark)
        if (count == 1) {
            fill.color = col
            c.drawCircle(sx(p[0]), sy(p[1]), maxOf(0.5f, Ink.width(s.width, p[2]) * z / 2), fill)
            return
        }
        for (i in 1 until count) segment(c, p, i - 1, i, col, s.width)
    }

    private fun segment(c: Canvas, p: FloatArray, a: Int, b: Int, displayColor: Int, width: Float) {
        ink.color = displayColor
        ink.strokeWidth = maxOf(1f, Ink.width(width, (p[a * 3 + 2] + p[b * 3 + 2]) / 2) * z)
        c.drawLine(sx(p[a * 3]), sy(p[a * 3 + 1]), sx(p[b * 3]), sy(p[b * 3 + 1]), ink)
    }

    private fun sx(x: Float) = (x - ox) * z
    private fun sy(y: Float) = (y - oy) * z
    private fun wx(sx: Float) = ox + sx / z
    private fun wy(sy: Float) = oy + sy / z

    override fun onDraw(canvas: Canvas) {
        val bmp = cache ?: return
        if (cox == ox && coy == oy && cz == z) {
            canvas.drawBitmap(bmp, 0f, 0f, null)
        } else {
            // Gesture in progress: move the last render instead of drawing every stroke again.
            canvas.drawColor(Theme.background(dark))
            canvas.save()
            canvas.translate((cox - ox) * z, (coy - oy) * z)
            canvas.scale(z / cz, z / cz)
            canvas.drawBitmap(bmp, 0f, 0f, null)
            canvas.restore()
        }
        if (erasing && eraserX >= 0) canvas.drawCircle(eraserX, eraserY, ERASER_DP * density, cursor)
        val text = flashText
        if (text != null && SystemClock.uptimeMillis() < flashUntil) {
            val w = flashPaint.measureText(text) + 32 * density
            val h = 40 * density
            val cx = width / 2f
            val top = 24 * density
            flashBg.color = if (dark) 0xE0333336.toInt() else 0xE0F0F0F0.toInt()
            flashPaint.color = Theme.text(dark)
            canvas.drawRoundRect(cx - w / 2, top, cx + w / 2, top + h, h / 2, h / 2, flashBg)
            canvas.drawText(text, cx, top + h / 2 - (flashPaint.descent() + flashPaint.ascent()) / 2, flashPaint)
        }
    }

    /** Shows all strokes on the board, or the default view if it is empty. */
    fun fitAll() {
        val page = repo.model.page(pageId)
        val all = page?.strokes?.values?.map { bounds.getOrPut(it.id) { Ink.bounds(it) } }.orEmpty()
        if (all.isEmpty() || width == 0) {
            ox = 0f; oy = 0f; z = width / BOARD_DEFAULT_W
        } else {
            val l = all.minOf { it[0] }
            val t = all.minOf { it[1] }
            val r = all.maxOf { it[2] }
            val b = all.maxOf { it[3] }
            z = (minOf(width / (r - l), height / (b - t)) * 0.9f).coerceIn(MIN_ZOOM, width / BOARD_DEFAULT_W * 2)
            ox = (l + r) / 2 - width / z / 2
            oy = (t + b) / 2 - height / z / 2
        }
        saveViewport()
        render()
        notifyViewport(force = true)
    }

    private fun notifyViewport(force: Boolean) {
        val pid = pageId ?: return
        if (width == 0) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - viewportSentAt < 80) return
        viewportSentAt = now
        onViewport?.invoke(Msg.Focus(repo.deviceId, pid, ox, oy, width / z, height / z))
    }

    private fun isPen(tool: Int) = tool == MotionEvent.TOOL_TYPE_STYLUS ||
        tool == MotionEvent.TOOL_TYPE_ERASER || tool == MotionEvent.TOOL_TYPE_MOUSE

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> pointerDown(e, e.actionIndex)
            MotionEvent.ACTION_MOVE -> move(e)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> pointerUp(e, e.actionIndex)
            MotionEvent.ACTION_CANCEL -> {
                // Many tablets claim three fingers for a system gesture (screenshot) and cancel the
                // app's touch. A fresh, still tap of 3+ fingers still counts as a tap then.
                val cancelledTap = cancelledTapCount(e)
                if (activePointer != -1 && !erasing) n = 0
                endStroke()
                if (cancelledTap >= 3) tapViewport?.let { ox = it[0]; oy = it[1]; z = it[2] }
                if (fingers.isNotEmpty()) endGesture()
                palms.clear()
                tapFingers.clear()
                tapCount = 0
                render()
                if (cancelledTap >= 3) onTap?.invoke(cancelledTap)
            }
        }
        return true
    }

    private fun pointerDown(e: MotionEvent, i: Int) {
        val id = e.getPointerId(i)
        val tool = e.getToolType(i)
        val x = e.getX(i)
        val y = e.getY(i)
        if (isPen(tool)) {
            if (activePointer != -1 || pageId == null) return
            if (fingers.isNotEmpty()) {
                // A finger touched first: that was the palm. Undo its pan and ignore it until it lifts.
                gestureStart?.let { ox = it[0]; oy = it[1]; z = it[2] }
                palms += fingers.keys
                fingers.clear()
                gestureStart = null
                gestureMoved = false
            }
            palms += tapFingers.keys
            tapFingers.clear()
            tapCount = 0
            startStroke(e, i, id, tool)
            return
        }
        if (tool != MotionEvent.TOOL_TYPE_FINGER) return
        if (activePointer != -1 && strokeTool != MotionEvent.TOOL_TYPE_FINGER) return // Palm while the stylus writes.
        trackTapDown(e, id, x, y)
        if (activePointer != -1) {
            // Second finger: the first one was not drawing, it was the start of a pan or zoom.
            val j = e.findPointerIndex(activePointer)
            val first = activePointer
            render() // Drops the half-drawn stroke from the bitmap.
            endStroke()
            gestureStart = floatArrayOf(ox, oy, z)
            gestureMoved = false
            if (j >= 0) fingers[first] = floatArrayOf(e.getX(j), e.getY(j))
        } else if (id in palms) {
            return
        } else if (fingerDraw && fingers.isEmpty()) {
            startStroke(e, i, id, tool)
            return
        }
        if (fingers.isEmpty()) {
            gestureStart = floatArrayOf(ox, oy, z)
            gestureMoved = false
            downX = x
            downY = y
        }
        fingers[id] = floatArrayOf(x, y)
        resetGestureBaseline()
    }

    private fun startStroke(e: MotionEvent, i: Int, id: Int, tool: Int) {
        if (cox != ox || coy != oy || cz != z) render()
        activePointer = id
        strokeTool = tool
        if (Build.VERSION.SDK_INT >= 30) requestUnbufferedDispatch(e)
        erasing = eraserMode || tool == MotionEvent.TOOL_TYPE_ERASER ||
            (e.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0
        if (erasing) eraseAt(e.getX(i), e.getY(i)) else begin(e.getX(i), e.getY(i), pressure(e, i, -1))
    }

    private fun move(e: MotionEvent) {
        for ((id, t) in tapFingers) {
            val i = e.findPointerIndex(id)
            if (i >= 0 && hypot(e.getX(i) - t.x0, e.getY(i) - t.y0) > TAP_SLOP_DP * density) t.moved = true
        }
        if (activePointer != -1) {
            val i = e.findPointerIndex(activePointer)
            if (i < 0) return
            for (h in 0 until e.historySize) {
                val x = e.getHistoricalX(i, h)
                val y = e.getHistoricalY(i, h)
                if (erasing) eraseAt(x, y) else add(x, y, pressure(e, i, h))
            }
            if (erasing) eraseAt(e.getX(i), e.getY(i)) else add(e.getX(i), e.getY(i), pressure(e, i, -1))
            if (!erasing) sendLive(false)
            return
        }
        if (fingers.isEmpty()) return
        for ((id, pos) in fingers) {
            val i = e.findPointerIndex(id)
            if (i >= 0) { pos[0] = e.getX(i); pos[1] = e.getY(i) }
        }
        val (cx, cy, span) = centroid()
        if (!gestureMoved) {
            // One finger must travel past the slop first, so a resting palm does not pan.
            if (fingers.size == 1 && hypot(cx - downX, cy - downY) < 10 * density) return
            gestureMoved = true
            resetGestureBaseline()
            return
        }
        val f = if (fingers.size >= 2 && prevSpan > 0f) span / prevSpan else 1f
        val wx = wx(prevCx)
        val wy = wy(prevCy)
        z = (z * f).coerceIn(MIN_ZOOM, MAX_ZOOM)
        ox = wx - cx / z
        oy = wy - cy / z
        prevCx = cx; prevCy = cy; prevSpan = span
        if (SystemClock.uptimeMillis() - renderedAt > RERENDER_MS) render() else invalidate()
        notifyViewport(force = false)
    }

    private fun pointerUp(e: MotionEvent, i: Int) {
        val id = e.getPointerId(i)
        palms -= id
        val tap = trackTapUp(e, id)
        if (tap > 0) {
            // A tap, not a pan or a finger stroke: drop what the fingers did, then report it.
            if (activePointer != -1 && strokeTool == MotionEvent.TOOL_TYPE_FINGER) {
                n = 0
                endStroke()
            }
            tapViewport?.let { ox = it[0]; oy = it[1]; z = it[2] }
            fingers.clear()
            gestureMoved = false
            endGesture()
            render()
            onTap?.invoke(tap)
            return
        }
        if (id == activePointer) {
            if (!erasing) {
                add(e.getX(i), e.getY(i), pressure(e, i, -1))
                commit()
            }
            endStroke()
        } else if (fingers.remove(id) != null) {
            if (fingers.isEmpty()) endGesture() else resetGestureBaseline()
        }
    }

    private fun trackTapDown(e: MotionEvent, id: Int, x: Float, y: Float) {
        val now = e.eventTime
        // Fingers of one tap land close together; a late one starts a new group.
        if (tapFingers.isEmpty() || now - tapGroupStart > TAP_MS) {
            tapFingers.clear()
            tapCount = 0
            tapGroupStart = now
            tapViewport = floatArrayOf(ox, oy, z)
        }
        tapFingers[id] = TapFinger(now, x, y)
    }

    /** Returns the finger count when the last finger of a tap lifts, else 0. */
    private fun trackTapUp(e: MotionEvent, id: Int): Int {
        val t = tapFingers.remove(id) ?: return 0
        if (t.moved || e.eventTime - t.t0 > TAP_MS) {
            tapFingers.clear()
            tapCount = -100 // This group is a pan or a hold, not a tap.
            return 0
        }
        tapCount++
        if (tapFingers.isNotEmpty()) return 0
        val count = tapCount
        tapCount = 0
        return if (count >= 2) count else 0
    }

    /** Fingers of a tap cut short by ACTION_CANCEL, or 0 if it was not a tap. */
    private fun cancelledTapCount(e: MotionEvent): Int {
        if (tapCount < 0 || tapFingers.isEmpty()) return 0
        if (tapFingers.values.any { it.moved || e.eventTime - it.t0 > TAP_MS }) return 0
        return tapCount + tapFingers.size
    }

    /** Shows [text] over the board for a moment. */
    fun flash(text: String) {
        flashText = text
        flashUntil = SystemClock.uptimeMillis() + 800
        invalidate()
        postDelayed({ invalidate() }, 850)
    }

    private fun centroid(): Triple<Float, Float, Float> {
        var cx = 0f
        var cy = 0f
        for (p in fingers.values) { cx += p[0]; cy += p[1] }
        cx /= fingers.size; cy /= fingers.size
        var span = 0f
        for (p in fingers.values) span += hypot(p[0] - cx, p[1] - cy)
        return Triple(cx, cy, span / fingers.size)
    }

    private fun resetGestureBaseline() {
        if (fingers.isEmpty()) return
        val (cx, cy, span) = centroid()
        prevCx = cx; prevCy = cy; prevSpan = span
    }

    private fun endGesture() {
        fingers.clear()
        gestureStart = null
        if (gestureMoved) {
            saveViewport()
            render()
            notifyViewport(force = true)
        }
        gestureMoved = false
    }

    private fun pressure(e: MotionEvent, i: Int, h: Int): Float {
        val p = if (h < 0) e.getPressure(i) else e.getHistoricalPressure(i, h)
        return p.coerceIn(0f, 1f)
    }

    private fun begin(x: Float, y: Float, p: Float) {
        strokeId = newId()
        n = 0
        liveSent = 0
        liveAt = 0
        add(x, y, p)
    }

    private fun add(sxv: Float, syv: Float, p: Float) {
        val x = wx(sxv)
        val y = wy(syv)
        if (n > 0) {
            val dx = (x - pts[(n - 1) * 3]) * z
            val dy = (y - pts[(n - 1) * 3 + 1]) * z
            if (dx * dx + dy * dy < 0.5f) return
        }
        if (n * 3 + 3 > pts.size) pts = pts.copyOf(pts.size * 2)
        pts[n * 3] = x
        pts[n * 3 + 1] = y
        pts[n * 3 + 2] = p
        n++
        if (n >= 2) {
            cacheCanvas?.let { segment(it, pts, n - 2, n - 1, Ink.display(color, dark), baseWidth) }
            invalidate()
        }
    }

    private fun commit() {
        val pid = pageId ?: return
        if (n == 0) return
        val points = pts.copyOf(n * 3)
        if (n == 1) {
            cacheCanvas?.let {
                fill.color = Ink.display(color, dark)
                it.drawCircle(sx(points[0]), sy(points[1]), maxOf(0.5f, Ink.width(baseWidth, points[2]) * z / 2), fill)
            }
            invalidate()
        }
        sendLive(true)
        val stroke = Stroke(strokeId, pid, repo.deviceId, color, baseWidth, points)
        repo.addLocal(AddStroke(newId(), repo.deviceId, System.currentTimeMillis(), stroke))
        onAction?.invoke(pid, History.Action.Add(listOf(stroke)))
    }

    private fun endStroke() {
        val pid = pageId
        if (erasing && erased.isNotEmpty() && pid != null) onAction?.invoke(pid, History.Action.Erase(erased.toList()))
        erased.clear()
        activePointer = -1
        erasing = false
        eraserX = -1f
        n = 0
        invalidate()
    }

    /** Sends new points about 60 times per second, plus the tail at pen up. */
    private fun sendLive(done: Boolean) {
        val pid = pageId ?: return
        val now = SystemClock.uptimeMillis()
        if (!done && now - liveAt < 16) return
        // Overlap by one point so the viewer can join the chunks.
        val from = maxOf(0, liveSent - 1)
        if (from >= n && !done) return
        liveAt = now
        val chunk = pts.copyOfRange(from * 3, n * 3)
        liveSent = n
        onLive?.invoke(Msg.Live(repo.deviceId, pid, strokeId, color, baseWidth, chunk, done))
    }

    private fun eraseAt(sxv: Float, syv: Float) {
        eraserX = sxv
        eraserY = syv
        invalidate()
        val pg = repo.model.page(pageId) ?: return
        val x = wx(sxv)
        val y = wy(syv)
        val r = ERASER_DP * density / z
        val hits = pg.strokes.values.filter { hit(it, x, y, r) }
        for (s in hits) {
            repo.addLocal(EraseStroke(newId(), repo.deviceId, System.currentTimeMillis(), s.pageId, s.id))
            erased += s
        }
    }

    private fun hit(s: Stroke, x: Float, y: Float, radius: Float): Boolean {
        val bb = bounds.getOrPut(s.id) { Ink.bounds(s) }
        if (x < bb[0] - radius || x > bb[2] + radius || y < bb[1] - radius || y > bb[3] + radius) return false
        val r = radius + s.width / 2
        val r2 = r * r
        val p = s.points
        var i = 0
        while (i < p.size) {
            val dx = p[i] - x
            val dy = p[i + 1] - y
            if (dx * dx + dy * dy < r2) return true
            i += 3
        }
        return false
    }

    private companion object {
        const val ERASER_DP = 14f
        const val MIN_ZOOM = 0.05f
        const val MAX_ZOOM = 30f
        const val RERENDER_MS = 120L
        const val TAP_MS = 350L
        const val TAP_SLOP_DP = 20f
    }
}
