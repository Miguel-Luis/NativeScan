package com.lordprogrammer.nativescan2

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 2.5f

/** Visor a pantalla completa: desliza entre páginas, pellizca o doble toque para ampliar. */
@Composable
fun PageZoomViewer(vm: ScanViewModel, startIndex: Int, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val pager = rememberPagerState(initialPage = startIndex.coerceIn(0, vm.pages.lastIndex)) { vm.pages.size }
    var zoomed by remember { mutableStateOf(false) }

    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { vm.select(it) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(Modifier.fillMaxSize().background(scheme.scrim)) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.fillMaxSize(),
                userScrollEnabled = !zoomed,
                key = { vm.pages.getOrNull(it)?.id ?: it },
            ) { i ->
                val page = vm.pages.getOrNull(i)
                if (page != null) {
                    ZoomablePage(vm, page, active = i == pager.currentPage, onZoomChanged = { zoomed = it })
                }
            }
            Row(
                Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(scheme.surface.copy(alpha = 0.85f))
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cerrar", tint = scheme.onSurface)
                }
                Box(Modifier.weight(1f))
                Text(
                    "${pager.currentPage + 1} / ${vm.pages.size}",
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(scheme.surface.copy(alpha = 0.85f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun ZoomablePage(
    vm: ScanViewModel,
    page: ScanPage,
    active: Boolean,
    onZoomChanged: (Boolean) -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = (s - 1f) * box.width / 2f
        val maxY = (s - 1f) * box.height / 2f
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    // La vista previa llega del caché; la versión grande la reemplaza al terminar el render.
    val image by produceState<ImageBitmap?>(
        vm.peekRendered(page, ScanViewModel.PREVIEW_SIDE)?.asImageBitmap(),
        page.id, page.mode, page.rotation, page.corners.contentHashCode(),
    ) {
        if (value == null) value = vm.renderCached(page, ScanViewModel.PREVIEW_SIDE)?.asImageBitmap()
        vm.render(page, ZOOM_SIDE)?.let { value = it.asImageBitmap() }
    }

    LaunchedEffect(active) {
        if (!active) {
            scale = 1f
            offset = Offset.Zero
        }
    }
    LaunchedEffect(scale) { if (active) onZoomChanged(scale > 1.01f) }

    val transform = rememberTransformableState { zoomChange, pan, _ ->
        val s = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
        scale = s
        offset = if (s <= 1f) Offset.Zero else clamp(offset + pan, s)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .transformable(transform, canPan = { scale > 1.01f })
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { tap ->
                    if (scale > 1.01f) {
                        scale = 1f
                        offset = Offset.Zero
                    } else {
                        scale = DOUBLE_TAP_ZOOM
                        val centre = Offset(size.width / 2f, size.height / 2f)
                        offset = clamp((centre - tap) * (DOUBLE_TAP_ZOOM - 1f), DOUBLE_TAP_ZOOM)
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = image
        if (bmp != null) {
            Image(
                bmp, contentDescription = "Página ampliada",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
                contentScale = ContentScale.Fit,
            )
        } else {
            CircularProgressIndicator()
        }
    }
}

private const val ZOOM_SIDE = 2800
