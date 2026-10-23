package dev.handnotes.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import dev.handnotes.shared.Theme
import kotlin.math.floor
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.window.rememberWindowState
import dev.handnotes.shared.Ink
import kotlinx.coroutines.delay

fun main() = application {
    val config = remember { Config() }
    val viewer = remember { Viewer(config) }
    var pinned by remember { mutableStateOf(config.pinned) }
    DisposableEffect(Unit) {
        viewer.start()
        onDispose {}
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "LiveQuill",
        state = rememberWindowState(width = 560.dp, height = 820.dp),
    ) {
        // Compose Desktop runs through XWayland, and KWin honors keep-above there. The flag must be
        // set after the window is shown: set before, AWT ends up with _NET_WM_STATE_BELOW on KWin.
        LaunchedEffect(pinned) {
            while (!window.isShowing) delay(50)
            window.isAlwaysOnTop = pinned
        }
        MaterialTheme(colorScheme = if (viewer.dark) darkColorScheme() else lightColorScheme()) {
            App(viewer, pinned) {
                pinned = it
                config.pinned = it
                config.save()
            }
        }
    }
}

@Composable
private fun App(viewer: Viewer, pinned: Boolean, onPin: (Boolean) -> Unit) {
    var showSettings by remember { mutableStateOf(false) }
    val dark = viewer.dark
    Column(Modifier.fillMaxSize().background(Color(Theme.background(dark)))) {
        Row(
            Modifier.fillMaxWidth().background(Color(Theme.bar(dark))).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            viewer.version // Recompose the board counter on model changes.
            val pages = viewer.pages()
            val text = Color(Theme.text(dark))
            TextButton(onClick = { viewer.go(-1) }) { Text("<") }
            Text("${pages.indexOfFirst { it.id == viewer.pageId } + 1} / ${pages.size}", fontSize = 14.sp, color = text)
            TextButton(onClick = { viewer.go(+1) }) { Text(">") }
            Checkbox(checked = viewer.follow, onCheckedChange = { viewer.changeFollow(it) })
            Text(Strings.follow, fontSize = 13.sp, color = text)
            TextButton(onClick = { viewer.fitAll() }) { Text(Strings.fitAll) }
            Spacer(Modifier.weight(1f))
            Text(Strings.status(viewer.status), fontSize = 13.sp, color = if (viewer.status == Viewer.Status.ONLINE) Color(0xFF4CAF50) else Color.Gray)
            TextButton(onClick = { viewer.setDarkMode(!dark) }) { Text(if (dark) Strings.light else Strings.dark) }
            if (pinned) {
                Button(onClick = { onPin(false) }) { Text(Strings.pinned) }
            } else {
                OutlinedButton(onClick = { onPin(true) }) { Text(Strings.pin) }
            }
            TextButton(onClick = { showSettings = true }) { Text(Strings.options) }
        }
        BoardCanvas(viewer, Modifier.weight(1f).fillMaxWidth())
    }
    if (showSettings) Settings(viewer) { showSettings = false }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BoardCanvas(viewer: Viewer, modifier: Modifier) {
    val m = modifier
        .onSizeChanged { viewer.canvasW = it.width.toFloat(); viewer.canvasH = it.height.toFloat() }
        .pointerInput(Unit) {
            detectDragGestures(
                onDragStart = { viewer.takeOverView() },
                onDrag = { change, d ->
                    change.consume()
                    viewer.pan(d.x, d.y)
                },
            )
        }
        .onPointerEvent(PointerEventType.Scroll) { e ->
            val c = e.changes.first()
            viewer.zoomAt(c.position.x, c.position.y, if (c.scrollDelta.y > 0) 1 / 1.15f else 1.15f)
        }
    Canvas(m) {
        viewer.version // Read in draw scope: repaint on every model change.
        val dark = viewer.dark
        val (ox, oy, z) = viewer.view()
        if (z <= 0f) return@Canvas
        fun sx(x: Float) = (x - ox) * z
        fun sy(y: Float) = (y - oy) * z

        val step = Theme.dotStep(z, 28.dp.toPx())
        val dots = ArrayList<Offset>()
        var gx = floor(ox / step) * step
        while (gx < ox + size.width / z) {
            var gy = floor(oy / step) * step
            while (gy < oy + size.height / z) {
                dots += Offset(sx(gx), sy(gy))
                gy += step
            }
            gx += step
        }
        drawPoints(dots, PointMode.Points, Color(Theme.dots(dark)), strokeWidth = 2.5.dp.toPx(), cap = StrokeCap.Round)

        val pageId = viewer.pageId ?: return@Canvas
        val l = ox
        val t = oy
        val r = ox + size.width / z
        val b = oy + size.height / z
        viewer.model.page(pageId)?.strokes?.values?.forEach {
            val bb = viewer.bounds(it)
            if (bb[2] >= l && bb[0] <= r && bb[3] >= t && bb[1] <= b) drawInk(it.points, it.color, it.width, ox, oy, z, dark)
        }
        viewer.partials.values.forEach {
            if (it.pageId == pageId) drawInk(it.points, it.color, it.width, ox, oy, z, dark)
        }
        // Not following: outline what the tablet shows.
        val tv = viewer.tabletView
        if (!viewer.follow && tv != null) {
            drawRect(
                Color(0xFF4C8BF5).copy(alpha = 0.6f),
                Offset(sx(tv[0]), sy(tv[1])),
                Size(tv[2] * z, tv[3] * z),
                style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
        }
    }
}

private fun DrawScope.drawInk(p: FloatArray, color: Int, width: Float, ox: Float, oy: Float, z: Float, dark: Boolean) {
    val c = Color(Ink.display(color, dark))
    val count = p.size / 3
    fun at(i: Int) = Offset((p[i * 3] - ox) * z, (p[i * 3 + 1] - oy) * z)
    if (count == 1) {
        drawCircle(c, maxOf(0.5f, Ink.width(width, p[2]) * z / 2), at(0))
        return
    }
    for (i in 1 until count) {
        val w = Ink.width(width, (p[i * 3 - 1] + p[i * 3 + 2]) / 2) * z
        drawLine(c, at(i - 1), at(i), strokeWidth = maxOf(1f, w), cap = StrokeCap.Round)
    }
}

@Composable
private fun Settings(viewer: Viewer, onClose: () -> Unit) {
    var url by remember { mutableStateOf(viewer.config.serverUrl) }
    var token by remember { mutableStateOf(viewer.config.token) }
    DialogWindow(onCloseRequest = onClose, title = Strings.settings, state = rememberDialogState(width = 420.dp, height = 300.dp)) {
        MaterialTheme(colorScheme = if (viewer.dark) darkColorScheme() else lightColorScheme()) {
            Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text(Strings.serverAddress) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text(Strings.token) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = onClose) { Text(Strings.cancel) }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        viewer.config.serverUrl = url
                        viewer.config.token = token
                        viewer.config.save()
                        viewer.restart()
                        onClose()
                    }) { Text(Strings.save) }
                }
            }
        }
    }
}
