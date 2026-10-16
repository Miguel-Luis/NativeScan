package com.lordprogrammer.nativescan2

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class DocumentFilter(val mode: Int, val title: String, val icon: ImageVector) {
    ORIGINAL(NativeScanner.MODE_ORIGINAL, "Original", Icons.Rounded.Image),
    BLACK_AND_WHITE(NativeScanner.MODE_BW, "B/N", Icons.Rounded.Contrast),
    ENHANCE(NativeScanner.MODE_ENHANCED, "Realce", Icons.Rounded.AutoFixHigh),
    VIVID(NativeScanner.MODE_VIVID, "Color vivo", Icons.Rounded.Palette);

    companion object {
        fun fromMode(mode: Int) = entries.firstOrNull { it.mode == mode } ?: ORIGINAL
    }
}

/** Fila de herramientas: botones circulares de 48dp con etiqueta debajo. */
@Composable
fun DocumentQuickActionsBar(
    onRotate: () -> Unit,
    onAdjustBorders: () -> Unit,
    onDelete: () -> Unit,
    onAddPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Top,
    ) {
        val scheme = MaterialTheme.colorScheme
        ToolButton(Icons.AutoMirrored.Rounded.RotateRight, "Rotar", onRotate)
        ToolButton(Icons.Rounded.Crop, "Bordes", onAdjustBorders)
        ToolButton(
            Icons.Rounded.DeleteOutline, "Borrar", onDelete,
            container = scheme.errorContainer.copy(alpha = 0.6f),
            content = scheme.error,
            labelColor = scheme.error,
        )
        ToolButton(
            Icons.Rounded.AddPhotoAlternate, "Agregar", onAddPage,
            container = scheme.primaryContainer,
            content = scheme.primary,
        )
    }
}

@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: Color = MaterialTheme.colorScheme.onSurface,
    labelColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = content, modifier = Modifier.size(22.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor, maxLines = 1)
    }
}

/** Chips de filtro en una fila desplazable; cada toque aplica el filtro de inmediato. */
@Composable
fun FilterSelectionRow(
    selectedFilter: DocumentFilter,
    onFilterSelected: (DocumentFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items(DocumentFilter.entries, key = { it.mode }) { filter ->
            val selected = filter == selectedFilter
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (selected) scheme.secondaryContainer else scheme.surfaceContainer)
                    .clickable {
                        if (!selected) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onFilterSelected(filter)
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    filter.icon, contentDescription = null,
                    tint = if (selected) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    filter.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) scheme.primary else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
            }
        }
    }
}

/** Selector de formato en cápsula: segmento activo relleno con check. */
@Composable
fun ExportSegmentedControl(
    selectedFormat: ExportFormat,
    onFormatSelected: (ExportFormat) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = CircleShape,
        color = scheme.surfaceContainerLow,
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            ExportFormat.entries.forEach { format ->
                val selected = format == selectedFormat
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(CircleShape)
                        .background(if (selected) scheme.primary else Color.Transparent)
                        .clickable { onFormatSelected(format) }
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selected) {
                        Icon(
                            Icons.Rounded.Check, contentDescription = null,
                            tint = scheme.onPrimary, modifier = Modifier.size(16.dp),
                        )
                        Box(Modifier.size(4.dp))
                    }
                    Text(
                        format.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
