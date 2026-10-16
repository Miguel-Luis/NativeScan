package com.lordprogrammer.nativescan2

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min

private val Accent = Color(0xFF00E5A0)
private const val EDIT_MAX_SIDE = 1600
private const val SNAP = 0.012f
private const val LOUPE_ZOOM = 3f

/** Ajuste de los 4 vértices con lupa que sigue el dedo y vibración al tocar bordes. */
@Composable
fun CornerEditorScreen(vm: ScanViewModel) {
    val index = vm.selected
    val page = vm.pages.getOrNull(index) ?: return
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val image by produceState<ImageBitmap?>(null, page.id) {
        value = vm.loadSource(page, EDIT_MAX_SIDE)?.asImageBitmap()
    }
    // Vértices normalizados, estado local hasta "Aplicar".
    val pts = remember(page.id) {
        mutableStateOf(page.corners.copyOf())
    }
    var active by remember(page.id) { mutableIntStateOf(-1) }
    var finger by remember(page.id) { mutableStateOf(Offset.Zero) }
    var atEdge by remember(page.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        val img = image
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (img != null) {
                Canvas(
                    Modifier
                        .aspectRatio(img.width.toFloat() / img.height)
                        .fillMaxWidth()
                        .clipToBounds()
                        .pointerInput(page.id) {
                            val grab = 56.dp.toPx()
                            detectDragGestures(
                                onDragStart = { start ->
                                    val c = pts.value
                                    var best = -1
                                    var bestD = grab
                                    for (i in 0 until 4) {
                                        val d = hypot(
                                            c[i * 2] * size.width - start.x,
                                            c[i * 2 + 1] * size.height - start.y,
                                        )
                                        if (d < bestD) { bestD = d; best = i }
                                    }
                                    active = best
                                    finger = start
                                },
                                onDragEnd = { active = -1; atEdge = false },
                                onDragCancel = { active = -1; atEdge = false },
                            ) { change, _ ->
                                change.consume()
                                val i = active
                                if (i < 0) return@detectDragGestures
                                finger = change.position
                                var nx = (change.position.x / size.width).coerceIn(0f, 1f)
                                var ny = (change.position.y / size.height).coerceIn(0f, 1f)
                                var edge = false
                                if (nx < SNAP) { nx = 0f; edge = true }
                                if (nx > 1 - SNAP) { nx = 1f; edge = true }
                                if (ny < SNAP) { ny = 0f; edge = true }
                                if (ny > 1 - SNAP) { ny = 1f; edge = true }
                                if (edge && !atEdge) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                atEdge = edge
                                val c = pts.value.copyOf()
                                c[i * 2] = nx
                                c[i * 2 + 1] = ny
                                pts.value = c
                            }
                        },
                ) {
                    drawImage(img, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
                    drawQuad(pts.value, size)
                    if (active >= 0) drawLoupe(img, pts.value, active, finger, size)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val src = vm.loadSource(page, EDIT_MAX_SIDE) ?: return@launch
                        val found = withContext(Dispatchers.Default) { NativeScanner.detectQuadBitmap(src) }
                        src.recycle()
                        pts.value = found ?: ScanViewModel.FULL_FRAME.copyOf()
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Auto") }
            OutlinedButton(
                onClick = { pts.value = ScanViewModel.FULL_FRAME.copyOf() },
                modifier = Modifier.weight(1f),
            ) { Text("Completo") }
            Button(
                onClick = {
                    vm.update(index, page.copy(corners = pts.value.copyOf()))
                    vm.show(Screen.REVIEW)
                },
                modifier = Modifier.weight(1f),
            ) { Text("Aplicar") }
        }
    }
}

private fun DrawScope.drawQuad(c: FloatArray, size: Size) {
    val path = Path().apply {
        for (i in 0 until 4) {
            val x = c[i * 2] * size.width
            val y = c[i * 2 + 1] * size.height
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    drawPath(path, Accent.copy(alpha = 0.18f), style = Fill)
    drawPath(path, Accent, style = Stroke(width = 3.dp.toPx()))
    for (i in 0 until 4) {
        val o = Offset(c[i * 2] * size.width, c[i * 2 + 1] * size.height)
        drawCircle(Color.White, 12.dp.toPx(), o)
        drawCircle(Accent, 9.dp.toPx(), o)
    }
}

/** Lupa circular x3 centrada en el vértice activo, ubicada en el lado opuesto al dedo. */
private fun DrawScope.drawLoupe(
    img: ImageBitmap, c: FloatArray, active: Int, finger: Offset, size: Size,
) {
    val radius = 56.dp.toPx()
    val corner = Offset(c[active * 2], c[active * 2 + 1])
    val center = Offset(
        x = if (finger.x < size.width / 2) size.width - radius * 1.4f else radius * 1.4f,
        y = radius * 1.4f,
    )

    // Región fuente: círculo de radio/ZOOM en coords de la imagen original.
    val scale = img.width / size.width
    val srcR = (radius / LOUPE_ZOOM) * scale
    val sx = corner.x * img.width
    val sy = corner.y * img.height
    val side = min(2 * srcR, min(img.width, img.height).toFloat())
    val left = (sx - side / 2).coerceIn(0f, img.width - side)
    val top = (sy - side / 2).coerceIn(0f, img.height - side)

    val circle = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(center, radius))
    }
    clipPath(circle) {
        drawRect(Color.Black, center - Offset(radius, radius), Size(radius * 2, radius * 2))
        drawImage(
            img,
            srcOffset = IntOffset(left.toInt(), top.toInt()),
            srcSize = IntSize(side.toInt().coerceAtLeast(1), side.toInt().coerceAtLeast(1)),
            dstOffset = IntOffset((center.x - radius).toInt(), (center.y - radius).toInt()),
            dstSize = IntSize((radius * 2).toInt(), (radius * 2).toInt()),
        )
    }
    drawCircle(Color.White, radius, center, style = Stroke(width = 3.dp.toPx()))
    // Retícula en el vértice.
    val cross = 8.dp.toPx()
    val tx = center.x + ((sx - left) / side - 0.5f) * radius * 2
    val ty = center.y + ((sy - top) / side - 0.5f) * radius * 2
    drawLine(Accent, Offset(tx - cross, ty), Offset(tx + cross, ty), strokeWidth = 2.dp.toPx())
    drawLine(Accent, Offset(tx, ty - cross), Offset(tx, ty + cross), strokeWidth = 2.dp.toPx())
}
