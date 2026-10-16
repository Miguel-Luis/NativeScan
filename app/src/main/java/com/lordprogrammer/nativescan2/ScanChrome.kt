package com.lordprogrammer.nativescan2

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lordprogrammer.nativescan2.ui.theme.DockShape
import com.lordprogrammer.nativescan2.ui.theme.ScanMint

/** Barra superior común: flecha de regreso + título (mismo estilo que "Editor y filtros"). */
@Composable
fun ScreenTopBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Atrás")
        }
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Contenedor inferior de acciones: panel elevado con esquinas superiores redondeadas. */
@Composable
fun ActionDock(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = DockShape,
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shadowElevation = 12.dp,
    ) {
        Box(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) { content() }
    }
}

/** Botón en cápsula con icono + etiqueta. [primary] = relleno de marca; si no, tonal. */
@Composable
fun ActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = true,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = if (primary) scheme.primary else scheme.secondaryContainer
    val fg = if (primary) scheme.onPrimary else scheme.primary
    Row(
        modifier = modifier
            .then(if (primary) Modifier.shadow(6.dp, CircleShape, ambientColor = scheme.primary, spotColor = scheme.primary) else Modifier)
            .clip(CircleShape)
            .background(if (enabled) bg else bg.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** Disparador: anillo menta + núcleo de marca con icono de cámara. */
@Composable
fun ShutterButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier
            .size(76.dp)
            .shadow(8.dp, CircleShape, ambientColor = scheme.primary, spotColor = scheme.primary)
            .clip(CircleShape)
            .background(scheme.surfaceContainerLowest)
            .border(BorderStroke(3.dp, ScanMint), CircleShape)
            .padding(6.dp)
            .clip(CircleShape)
            .background(if (enabled) scheme.primary else scheme.primary.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.CameraAlt, contentDescription = "Capturar",
            tint = scheme.onPrimary, modifier = Modifier.size(30.dp),
        )
    }
}
