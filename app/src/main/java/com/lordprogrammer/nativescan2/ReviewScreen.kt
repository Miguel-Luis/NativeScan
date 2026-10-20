package com.lordprogrammer.nativescan2

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.zIndex
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lordprogrammer.nativescan2.ui.theme.CardShape
import com.lordprogrammer.nativescan2.ui.theme.DockShape
import com.lordprogrammer.nativescan2.ui.theme.ScanMint
import kotlinx.coroutines.launch

@Composable
fun ReviewScreen(vm: ScanViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val index = vm.selected
    val page = vm.pages.getOrNull(index) ?: return
    var format by rememberSaveable { mutableStateOf(ExportFormat.PDF) }
    val scheme = MaterialTheme.colorScheme
    val filter = DocumentFilter.fromMode(page.mode)
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) scope.launch { vm.addFromGallery(context.contentResolver, uris) }
    }
    var showZoom by rememberSaveable { mutableStateOf(false) }
    if (showZoom) PageZoomViewer(vm, index) { showZoom = false }

    val preview by rememberRendered(vm, page, ScanViewModel.PREVIEW_SIDE)
    // Con la página ya en pantalla, precalcula en nativo los demás filtros: el cambio es instantáneo.
    LaunchedEffect(page.id, page.corners.contentHashCode(), page.rotation, preview != null) {
        if (preview != null) vm.prefetchFilters(page)
    }

    Column(Modifier.fillMaxSize().background(scheme.surface)) {
        // Barra superior
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.closeDocument() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Atrás")
            }
            Text(
                "Editor y filtros",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) else Box(Modifier.height(4.dp))

        // Contexto: página actual + Guardar
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(scheme.surfaceContainer)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.AutoStories, contentDescription = null,
                    tint = scheme.primary, modifier = Modifier.size(18.dp),
                )
                Text(
                    "Página ${index + 1} de ${vm.pages.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                )
            }
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (vm.busy) scheme.primary.copy(alpha = 0.5f) else scheme.primary)
                    .clickable(enabled = !vm.busy) {
                        scope.launch {
                            val files = vm.export(format)
                            if (files.isNotEmpty()) Exporter.share(context, files, format.mime)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.DownloadDone, contentDescription = null,
                    tint = scheme.onPrimary, modifier = Modifier.size(18.dp),
                )
                Text(
                    vm.exportProgress?.let { "Comprimiendo ${(it * 100).toInt()} %" } ?: "Guardar ${format.name}",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onPrimary,
                    maxLines = 1,
                )
            }
        }

        // Visor del documento
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .shadow(8.dp, CardShape, ambientColor = scheme.primary, spotColor = scheme.primary)
                    .clip(CardShape)
                    .background(scheme.surfaceContainerLowest)
                    .clickable(enabled = preview != null) { showZoom = true }
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(
                    targetState = preview,
                    animationSpec = tween(140),
                    modifier = Modifier.fillMaxSize(),
                    label = "preview",
                ) { bmp ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (bmp != null) {
                            Image(
                                bmp, contentDescription = "Documento",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Fit,
                            )
                        } else {
                            CircularProgressIndicator()
                        }
                    }
                }
                Row(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(CircleShape)
                        .background(scheme.primary)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(ScanMint))
                    Text(
                        "${vm.pages.size} pág.",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onPrimary,
                        maxLines = 1,
                    )
                }
            }
        }

        PageCarousel(vm, index)

        // Panel de controles
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DockShape,
            color = scheme.surfaceContainerLowest,
            shadowElevation = 12.dp,
        ) {
            Column(
                Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "FILTRO DE MEJORA",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                    Text(
                        filter.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = scheme.primary,
                    )
                }
                FilterSelectionRow(
                    selectedFilter = filter,
                    onFilterSelected = { vm.update(index, page.copy(mode = it.mode)) },
                )
                DocumentQuickActionsBar(
                    onRotate = { vm.rotate(index) },
                    onAdjustBorders = { vm.show(Screen.EDIT) },
                    onDelete = { vm.delete(index) },
                    onAddPage = { vm.show(Screen.CAMERA) },
                    onAddFromGallery = {
                        galleryPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
                ExportSegmentedControl(
                    selectedFormat = format,
                    onFormatSelected = { format = it },
                )
                if (format == ExportFormat.PDF) {
                    PdfCompressionRow(selected = vm.pdfPreset, onSelected = vm::selectPdfPreset)
                }
            }
        }
    }
}

@Composable
private fun PageCarousel(vm: ScanViewModel, selected: Int) {
    val scheme = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    LazyRow(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(vm.pages, key = { _, p -> p.id }) { i, p ->
            val thumb by rememberRendered(vm, p, 256)
            val isSelected = i == selected
            val isDragging = draggingId == p.id
            Box(
                Modifier
                    .then(if (isDragging) Modifier else Modifier.animateItem())
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        if (isDragging) {
                            translationX = dragOffset
                            scaleX = 1.12f
                            scaleY = 1.12f
                            shadowElevation = 12.dp.toPx()
                        }
                    }
                    .pointerInput(p.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                draggingId = p.id
                                dragOffset = 0f
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                dragOffset += amount.x
                                val items = listState.layoutInfo.visibleItemsInfo
                                val cur = items.firstOrNull { it.key == p.id } ?: return@detectDragGesturesAfterLongPress
                                val center = cur.offset + cur.size / 2f + dragOffset
                                val target = items.firstOrNull {
                                    it.key is Long && it.key != p.id &&
                                        center >= it.offset && center <= it.offset + it.size
                                } ?: return@detectDragGesturesAfterLongPress
                                vm.movePage(cur.index, target.index)
                                dragOffset -= (target.offset - cur.offset)
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                            onDragEnd = {
                                draggingId = null
                                dragOffset = 0f
                                vm.commitOrder()
                            },
                            onDragCancel = {
                                draggingId = null
                                dragOffset = 0f
                                vm.commitOrder()
                            },
                        )
                    }
                    .width(48.dp).height(60.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) scheme.primaryContainer else scheme.surfaceContainerLowest)
                    .border(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) scheme.primary else scheme.outlineVariant,
                        RoundedCornerShape(8.dp),
                    )
                    .clickable { vm.select(i) }
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                thumb?.let { Image(it, null, contentScale = ContentScale.Fit) }
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(if (isSelected) scheme.primary else scheme.onSurfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${i + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.surface,
                    )
                }
            }
        }
        item(key = "add") {
            Row(
                Modifier
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(scheme.surfaceContainerLow)
                    .clickable { vm.show(Screen.CAMERA) }
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.AddAPhoto, contentDescription = null,
                    tint = scheme.primary, modifier = Modifier.size(20.dp),
                )
                Text(
                    "+ Escanear",
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Render nativo en segundo plano con caché: un acierto del caché se entrega en la misma composición
 * (sin parpadeo); si no, conserva la imagen anterior hasta que llegue la nueva.
 */
@Composable
private fun rememberRendered(vm: ScanViewModel, page: ScanPage, maxSide: Int): State<ImageBitmap?> {
    val key = listOf(page.id, page.mode, page.rotation, page.corners.contentHashCode(), maxSide)
    val hit = remember(key) { vm.peekRendered(page, maxSide)?.asImageBitmap() }
    val async = produceState<ImageBitmap?>(null, key) {
        if (hit == null) value = vm.renderCached(page, maxSide)?.asImageBitmap()
    }
    return remember(hit, async.value) { mutableStateOf(hit ?: async.value) }
}
