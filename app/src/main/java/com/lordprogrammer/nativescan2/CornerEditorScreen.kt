package com.lordprogrammer.nativescan2

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lordprogrammer.nativescan2.ui.theme.ScanMint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.min

private val Accent = ScanMint
private const val EDIT_MAX_SIDE = 1600
private const val SNAP = 0.012f
private const val LOUPE_ZOOM = 3f

/** Normalizado fuente → normalizado en vista rotada [rot] grados horarios. */
private fun toView(rot: Int, x: Float, y: Float): Offset = when (rot) {
    90 -> Offset(1 - y, x)
    180 -> Offset(1 - x, 1 - y)
    270 -> Offset(y, 1 - x)
    else -> Offset(x, y)
}

/** Inverso de [toView]. */
private fun toSrc(rot: Int, x: Float, y: Float): Offset = when (rot) {
    90 -> Offset(y, 1 - x)
    180 -> Offset(1 - x, 1 - y)
    270 -> Offset(1 - y, x)
    else -> Offset(x, y)
}

/** Ajuste de los 4 vértices con lupa, rejilla, rotación y luz de asistencia. */
@Composable
fun CornerEditorScreen(vm: ScanViewModel) {
    val index = vm.selected
    val page = vm.pages.getOrNull(index) ?: return
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    val src by produceState<Bitmap?>(null, page.id) {
        value = vm.loadSource(page, EDIT_MAX_SIDE)
    }
    var light by remember(page.id) { mutableStateOf(false) }
    var grid by remember(page.id) { mutableStateOf(true) }
    // Rotación solo de vista (0/90/180/270); se suma a page.rotation al aplicar.
    var rot by remember(page.id) { mutableIntStateOf(0) }

    // Luz de asistencia: gamma adaptativo nativo sobre copia; se conserva la imagen previa hasta tener la nueva.
    val image by produceState<ImageBitmap?>(null, src, light) {
        val s = src ?: return@produceState
        value = if (!light) s.asImageBitmap() else withContext(Dispatchers.Default) {
            val c = s.copy(Bitmap.Config.ARGB_8888, true)
            NativeScanner.enhanceLowLight(c)
            c.asImageBitmap()
        }
    }
    // Vértices normalizados en coordenadas fuente, estado local hasta "Aplicar".
    val pts = remember(page.id) {
        mutableStateOf(page.corners.copyOf())
    }
    var active by remember(page.id) { mutableIntStateOf(-1) }
    var finger by remember(page.id) { mutableStateOf(Offset.Zero) }
    var atEdge by remember(page.id) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Ajustar bordes", onBack = { vm.show(Screen.REVIEW) })
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            RoundTool(Icons.Rounded.GridOn, "Alternar cuadrícula", grid) { grid = !grid }
            RoundTool(Icons.Rounded.Rotate90DegreesCw, "Rotar 90 grados", false) { rot = (rot + 90) % 360 }
            RoundTool(
                if (light) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                "Luz de asistencia", light,
            ) { light = !light }
        }
        val img = image
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            if (img != null) {
                val swap = rot == 90 || rot == 270
                val ratio = if (swap) img.height.toFloat() / img.width else img.width.toFloat() / img.height
                Canvas(
                    Modifier
                        .aspectRatio(ratio)
                        .fillMaxWidth()
                        .clipToBounds()
                        .pointerInput(page.id, rot) {
                            val grab = 56.dp.toPx()
                            detectDragGestures(
                                onDragStart = { start ->
                                    val c = pts.value
                                    var best = -1
                                    var bestD = grab
                                    for (i in 0 until 4) {
                                        val v = toView(rot, c[i * 2], c[i * 2 + 1])
                                        val d = hypot(v.x * size.width - start.x, v.y * size.height - start.y)
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
                                val vx = (change.position.x / size.width).coerceIn(0f, 1f)
                                val vy = (change.position.y / size.height).coerceIn(0f, 1f)
                                val s = toSrc(rot, vx, vy)
                                var nx = s.x
                                var ny = s.y
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
                    // Imagen fuente rotada por GPU (sin coste de CPU).
                    val dw = (if (swap) size.height else size.width).toInt()
                    val dh = (if (swap) size.width else size.height).toInt()
                    withTransform({ rotate(rot.toFloat(), Offset(size.width / 2, size.height / 2)) }) {
                        drawImage(
                            img,
                            dstOffset = IntOffset((size.width.toInt() - dw) / 2, (size.height.toInt() - dh) / 2),
                            dstSize = IntSize(dw, dh),
                        )
                    }
                    val v = FloatArray(8)
                    for (i in 0 until 4) {
                        val o = toView(rot, pts.value[i * 2], pts.value[i * 2 + 1])
                        v[i * 2] = o.x; v[i * 2 + 1] = o.y
                    }
                    drawQuad(v, size, grid)
                    if (active >= 0) drawLoupe(img, pts.value, active, finger, size, rot)
                }
            }
        }

        val scheme = MaterialTheme.colorScheme
        ActionDock {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Top,
            ) {
                ToolButton(
                    Icons.Rounded.Replay, "Retomar",
                    onClick = {
                        // Descarta la foto y vuelve a la cámara para tomar otra.
                        vm.delete(index)
                        vm.show(Screen.CAMERA)
                    },
                    container = scheme.errorContainer.copy(alpha = 0.6f),
                    content = scheme.error,
                    labelColor = scheme.error,
                )
                ToolButton(
                    Icons.Rounded.AutoFixHigh, "Auto",
                    onClick = {
                        val s = src ?: return@ToolButton
                        scope.launch {
                            val found = withContext(Dispatchers.Default) {
                                if (light) {
                                    val c = s.copy(Bitmap.Config.ARGB_8888, true)
                                    NativeScanner.enhanceLowLight(c)
                                    NativeScanner.detectQuadBitmap(c).also { c.recycle() }
                                } else NativeScanner.detectQuadBitmap(s)
                            }
                            pts.value = found ?: ScanViewModel.FULL_FRAME.copyOf()
                        }
                    },
                )
                ToolButton(
                    Icons.Rounded.Fullscreen, "Completo",
                    onClick = { pts.value = ScanViewModel.FULL_FRAME.copyOf() },
                )
                ToolButton(
                    Icons.Rounded.Check, "Aplicar",
                    onClick = {
                        vm.update(
                            index,
                            page.copy(corners = pts.value.copyOf(), rotation = (page.rotation + rot) % 360),
                        )
                        vm.show(Screen.REVIEW)
                    },
                    container = scheme.primary,
                    content = scheme.onPrimary,
                    labelColor = scheme.primary,
                )
            }
        }
    }
}

@Composable
private fun RoundTool(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(
                if (on) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLowest,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, label,
            tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** [c]: vértices normalizados ya en espacio de vista. */
private fun DrawScope.drawQuad(c: FloatArray, size: Size, grid: Boolean) {
    val path = Path().apply {
        for (i in 0 until 4) {
            val x = c[i * 2] * size.width
            val y = c[i * 2 + 1] * size.height
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    drawPath(path, Accent.copy(alpha = 0.18f), style = Fill)
    if (grid) {
        // Malla interior 3x3: interpolación bilinear entre las 4 esquinas (tl,tr,br,bl).
        fun p(u: Float, v: Float): Offset {
            val tx = c[0] + (c[2] - c[0]) * u
            val ty = c[1] + (c[3] - c[1]) * u
            val bx = c[6] + (c[4] - c[6]) * u
            val by = c[7] + (c[5] - c[7]) * u
            return Offset((tx + (bx - tx) * v) * size.width, (ty + (by - ty) * v) * size.height)
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx()))
        val col = Accent.copy(alpha = 0.7f)
        val w = 1.5.dp.toPx()
        for (t in floatArrayOf(1f / 3f, 2f / 3f)) {
            drawLine(col, p(t, 0f), p(t, 1f), strokeWidth = w, pathEffect = dash)
            drawLine(col, p(0f, t), p(1f, t), strokeWidth = w, pathEffect = dash)
        }
    }
    drawPath(path, Accent, style = Stroke(width = 3.dp.toPx()))
    for (i in 0 until 4) {
        val o = Offset(c[i * 2] * size.width, c[i * 2 + 1] * size.height)
        drawCircle(Color.White, 12.dp.toPx(), o)
        drawCircle(Accent, 9.dp.toPx(), o)
    }
}

/** Lupa circular x3 centrada en el vértice activo, ubicada en el lado opuesto al dedo. */
private fun DrawScope.drawLoupe(
    img: ImageBitmap, c: FloatArray, active: Int, finger: Offset, size: Size, rot: Int,
) {
    val radius = 56.dp.toPx()
    val corner = Offset(c[active * 2], c[active * 2 + 1])
    val center = Offset(
        x = if (finger.x < size.width / 2) size.width - radius * 1.4f else radius * 1.4f,
        y = radius * 1.4f,
    )

    // Región fuente: círculo de radio/ZOOM en coords de la imagen original.
    val viewW = if (rot == 90 || rot == 270) img.height else img.width
    val scale = viewW / size.width
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
        withTransform({ rotate(rot.toFloat(), center) }) {
            drawImage(
                img,
                srcOffset = IntOffset(left.toInt(), top.toInt()),
                srcSize = IntSize(side.toInt().coerceAtLeast(1), side.toInt().coerceAtLeast(1)),
                dstOffset = IntOffset((center.x - radius).toInt(), (center.y - radius).toInt()),
                dstSize = IntSize((radius * 2).toInt(), (radius * 2).toInt()),
            )
        }
    }
    drawCircle(Color.White, radius, center, style = Stroke(width = 3.dp.toPx()))
    // Retícula en el vértice (offset dentro del recorte, rotado como la imagen).
    val cross = 8.dp.toPx()
    val o = toView(rot, (sx - left) / side, (sy - top) / side)
    val tx = center.x + (o.x - 0.5f) * radius * 2
    val ty = center.y + (o.y - 0.5f) * radius * 2
    drawLine(Accent, Offset(tx - cross, ty), Offset(tx + cross, ty), strokeWidth = 2.dp.toPx())
    drawLine(Accent, Offset(tx, ty - cross), Offset(tx, ty + cross), strokeWidth = 2.dp.toPx())
}
