package com.lordprogrammer.nativescan2

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lordprogrammer.nativescan2.data.DocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val CardShape = RoundedCornerShape(16.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(vm: ScanViewModel) {
    val documents by vm.documents.collectAsState()
    var toRename by remember { mutableStateOf<DocumentEntity?>(null) }
    var toDelete by remember { mutableStateOf<DocumentEntity?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun shareDoc(doc: DocumentEntity) {
        scope.launch {
            val files = vm.exportDocument(doc.id, ExportFormat.PDF, doc.title)
            if (files.isNotEmpty()) Exporter.share(context, files, ExportFormat.PDF.mime)
            else vm.message = "No se pudo exportar el documento"
        }
    }
    var query by rememberSaveable { mutableStateOf("") }
    val gridView = vm.libraryGrid
    val haptic = LocalHapticFeedback.current

    // Selección múltiple: se activa con pulsación larga y termina al quedar vacía.
    var selection by remember { mutableStateOf<Set<String>>(emptySet()) }
    var confirmBulkDelete by remember { mutableStateOf(false) }
    val chosen = documents.orEmpty().filter { it.id in selection }
    val selecting = chosen.isNotEmpty()
    BackHandler(enabled = selecting) { selection = emptySet() }
    fun toggle(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }
    fun longPress(id: String) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        toggle(id)
    }
    fun shareChosen() {
        scope.launch {
            val files = vm.exportDocuments(chosen)
            if (files.isNotEmpty()) Exporter.share(context, files, ExportFormat.PDF.mime)
            else vm.message = "No se pudo exportar los documentos"
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            if (selecting) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { selection = emptySet() }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Cancelar selección")
                        }
                    },
                    title = {
                        Text(
                            if (chosen.size == 1) "1 seleccionado" else "${chosen.size} seleccionados",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    },
                    actions = {
                        IconButton(enabled = !vm.busy, onClick = ::shareChosen) {
                            Icon(
                                Icons.Rounded.Share, contentDescription = "Enviar seleccionados",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(enabled = !vm.busy, onClick = { confirmBulkDelete = true }) {
                            Icon(
                                Icons.Rounded.DeleteOutline, contentDescription = "Eliminar seleccionados",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    },
                )
            } else {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                "NativeScan",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                "DOCUMENTOS",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (!selecting) ScanBottomBar(showDocs = false, showScan = true, onDocs = {}, onScan = vm::startNewScan)
        },
    ) { padding ->
        val docs = documents
        when {
            docs == null -> Box(Modifier.fillMaxSize().padding(padding), Alignment.Center) {
                CircularProgressIndicator()
            }
            docs.isEmpty() -> EmptyLibrary(Modifier.fillMaxSize().padding(padding))
            else -> {
                // Filtro barato en Kotlin: título + fecha. remember evita refiltrar en cada recomposición.
                val q = query.trim()
                val visible = remember(docs, q) {
                    if (q.isEmpty()) docs
                    else {
                        val fmt = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                        docs.filter {
                            it.title.contains(q, ignoreCase = true) ||
                                fmt.format(Date(it.updatedAt)).contains(q, ignoreCase = true)
                        }
                    }
                }
                Column(Modifier.fillMaxSize().padding(padding)) {
                    if (vm.busy && selecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (selecting) {
                        val allSelected = visible.isNotEmpty() && visible.all { it.id in selection }
                        SelectAllRow(
                            allSelected = allSelected,
                            onToggle = {
                                selection = if (allSelected) selection - visible.map { it.id }.toSet()
                                else selection + visible.map { it.id }
                            },
                        )
                    } else {
                        SearchBar(
                            query = query,
                            onQuery = { query = it },
                            gridView = gridView,
                            onGrid = vm::selectLibraryGrid,
                        )
                    }
                    if (visible.isEmpty()) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Text(
                                "Sin resultados",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (gridView) {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(160.dp),
                            contentPadding = PaddingValues(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(visible, key = { it.id }) { doc ->
                                DocumentCard(
                                    doc,
                                    selecting = selecting,
                                    selected = doc.id in selection,
                                    onClick = { if (selecting) toggle(doc.id) else vm.openDocument(doc.id) },
                                    onLongClick = { longPress(doc.id) },
                                    onShare = { shareDoc(doc) },
                                    onRename = { toRename = doc },
                                    onDelete = { toDelete = doc },
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(visible, key = { it.id }) { doc ->
                                DocumentRow(
                                    doc,
                                    selecting = selecting,
                                    selected = doc.id in selection,
                                    onClick = { if (selecting) toggle(doc.id) else vm.openDocument(doc.id) },
                                    onLongClick = { longPress(doc.id) },
                                    onShare = { shareDoc(doc) },
                                    onRename = { toRename = doc },
                                    onDelete = { toDelete = doc },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    toRename?.let { doc ->
        var text by remember(doc.id) { mutableStateOf(doc.title) }
        AlertDialog(
            onDismissRequest = { toRename = null },
            title = { Text("Renombrar documento") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    singleLine = true, label = { Text("Nombre") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.renameDocument(doc.id, text)
                    toRename = null
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { toRename = null }) { Text("Cancelar") } },
        )
    }

    if (confirmBulkDelete && chosen.isNotEmpty()) {
        val pages = chosen.sumOf { it.pageCount }
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text(if (chosen.size == 1) "Eliminar documento" else "Eliminar ${chosen.size} documentos") },
            text = {
                Text(
                    (if (chosen.size == 1) "«${chosen[0].title}»" else "Los ${chosen.size} documentos seleccionados") +
                        " y sus $pages páginas se borrarán del dispositivo. Esta acción no se puede deshacer.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteDocuments(chosen.map { it.id })
                    selection = emptySet()
                    confirmBulkDelete = false
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancelar") } },
        )
    }

    toDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Eliminar documento") },
            text = { Text("«${doc.title}» y sus ${doc.pageCount} páginas se borrarán del dispositivo.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteDocument(doc.id)
                    toDelete = null
                }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }
}

/** Barra inferior compartida: biblioteca muestra solo "Escanear"; cámara solo "Docs". */
@Composable
fun ScanBottomBar(showDocs: Boolean, showScan: Boolean, onDocs: () -> Unit, onScan: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Row(
            Modifier.fillMaxWidth().height(80.dp).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showDocs) BottomItem(Icons.Rounded.FolderOpen, "Docs", filled = false, onClick = onDocs)
            if (showScan) BottomItem(Icons.Rounded.DocumentScanner, "Escanear", filled = true, onClick = onScan)
        }
    }
}

@Composable
private fun BottomItem(icon: ImageVector, label: String, filled: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    Column(
        Modifier.clip(CardShape).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (filled) primary else MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, contentDescription = null,
                tint = if (filled) MaterialTheme.colorScheme.onPrimary else primary,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = if (filled) MaterialTheme.colorScheme.onSurfaceVariant else primary,
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    gridView: Boolean,
    onGrid: (Boolean) -> Unit,
) {
    val low = MaterialTheme.colorScheme.surfaceContainerLow
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .weight(1f)
                .clip(CircleShape)
                .background(low)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Search, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        "Buscar por título o fecha...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.merge(
                        TextStyle(color = MaterialTheme.colorScheme.onSurface),
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Row(
            Modifier.clip(CircleShape).background(low).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ViewToggle(Icons.Rounded.GridView, "Vista cuadrícula", gridView, low) { onGrid(true) }
            ViewToggle(Icons.Rounded.ViewAgenda, "Vista lista", !gridView, low) { onGrid(false) }
        }
    }
}

@Composable
private fun ViewToggle(
    icon: ImageVector,
    description: String,
    selected: Boolean,
    idle: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.surfaceContainerLowest else idle)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon, description,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun DocumentMenu(title: String, onShare: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { menu = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "Opciones")
        }
        DropdownMenu(
            expanded = menu,
            onDismissRequest = { menu = false },
            shape = RoundedCornerShape(24.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            modifier = Modifier.width(280.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "Acciones rápidas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable { menu = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Close, "Cerrar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            MenuAction(Icons.Rounded.Share, "Compartir o Enviar", "Vía WhatsApp, correo o nube", false) {
                menu = false; onShare()
            }
            MenuAction(Icons.Rounded.DriveFileRenameOutline, "Renombrar archivo", "Cambiar título del documento", false) {
                menu = false; onRename()
            }
            MenuAction(Icons.Rounded.DeleteOutline, "Eliminar documento", "Se borra del dispositivo", true) {
                menu = false; onDelete()
            }
        }
    }
}

@Composable
private fun MenuAction(icon: ImageVector, title: String, subtitle: String, danger: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .clip(CardShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (danger) scheme.errorContainer else scheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon, null,
                tint = if (danger) scheme.onErrorContainer else scheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (danger) scheme.error else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (danger) scheme.error.copy(alpha = 0.8f) else scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun rememberDate(updatedAt: Long): String = remember(updatedAt) {
    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date(updatedAt))
}

@Composable
private fun DocumentCard(
    doc: DocumentEntity,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val date = rememberDate(doc.updatedAt)
    val cover by rememberThumbnail(doc.coverThumbnailPath)

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .selectionBorder(selected)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = CardShape,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .padding(8.dp),
        ) {
            cover?.let {
                Image(it, doc.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            PageBadge(doc.pageCount, Modifier.align(Alignment.BottomEnd))
            if (selecting) SelectCheck(selected, Modifier.align(Alignment.TopStart))
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!selecting) DocumentMenu(doc.title, onShare, onRename, onDelete)
            else Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun DocumentRow(
    doc: DocumentEntity,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val date = rememberDate(doc.updatedAt)
    val cover by rememberThumbnail(doc.coverThumbnailPath)

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .selectionBorder(selected)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = CardShape,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (selecting) SelectCheck(selected)
            Box(
                Modifier
                    .size(width = 64.dp, height = 80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                cover?.let {
                    Image(it, doc.title, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    doc.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    date,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                PageBadge(doc.pageCount)
            }
            if (!selecting) DocumentMenu(doc.title, onShare, onRename, onDelete)
        }
    }
}

/** Marco de acento sobre la tarjeta seleccionada. */
@Composable
private fun Modifier.selectionBorder(selected: Boolean): Modifier =
    if (selected) border(2.dp, MaterialTheme.colorScheme.primary, CardShape) else this

/** Checkbox de solo lectura: el toque lo recibe la tarjeta. */
@Composable
private fun SelectCheck(checked: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest.copy(alpha = 0.9f))
            .padding(4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

/** Fila superior del modo selección: marca o desmarca todos los documentos visibles. */
@Composable
private fun SelectAllRow(allSelected: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 4.dp)
            .fillMaxWidth()
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = allSelected, onCheckedChange = null)
        Text(
            if (allSelected) "Deseleccionar todos" else "Seleccionar todos",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PageBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(8.dp),
        modifier = modifier,
    ) {
        Text(
            if (count == 1) "1 pág." else "$count págs.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun EmptyLibrary(modifier: Modifier = Modifier) {
    Column(
        modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(112.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(width = 48.dp, height = 56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Description, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            "Sin documentos guardados",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Tus escaneos de una o varias páginas aparecerán aquí para editarlos o exportarlos cuando quieras.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Capability(Icons.Rounded.CropFree, "Detección automática de bordes", "Encuadre asistido en tiempo real")
        Spacer(Modifier.height(8.dp))
        Capability(Icons.Rounded.Description, "Filtros de texto claros y nítidos", "Modos blanco/negro, color y realce")
        Spacer(Modifier.height(8.dp))
        Capability(Icons.Rounded.DocumentScanner, "Exportación directa a PDF y JPEG", "Comparte con un solo toque")
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                Icons.Rounded.Lock, null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(15.dp),
            )
            Text(
                "Procesamiento 100% privado en tu dispositivo",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Capability(icon: ImageVector, title: String, subtitle: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Decodifica la miniatura WebP en IO; null si no existe. */
@Composable
private fun rememberThumbnail(path: String?) = produceState<ImageBitmap?>(null, path) {
    value = path?.let { p ->
        withContext(Dispatchers.IO) { BitmapFactory.decodeFile(p)?.asImageBitmap() }
    }
}
