package com.lordprogrammer.nativescan2

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lordprogrammer.nativescan2.data.DocumentEntity
import com.lordprogrammer.nativescan2.data.DocumentRepository
import com.lordprogrammer.nativescan2.data.PageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max

private const val KEY_LIBRARY_GRID = "library_grid"
private const val KEY_PDF_PRESET = "pdf_preset"

enum class Screen { LIBRARY, CAMERA, EDIT, REVIEW }

/** Página: imagen fuente en disco (ya derecha) + parámetros de render no destructivos. */
class ScanPage(
    val id: Long,
    val file: File,
    val corners: FloatArray,
    val mode: Int,
    val rotation: Int,
    val detected: Boolean,
) {
    fun copy(corners: FloatArray = this.corners, mode: Int = this.mode, rotation: Int = this.rotation) =
        ScanPage(id, file, corners, mode, rotation, detected)
}

class ScanViewModel(app: Application) : AndroidViewModel(app) {

    val pages = mutableStateListOf<ScanPage>()
    var screen by mutableStateOf(Screen.LIBRARY); private set
    /** Documento abierto en sesión; null = escaneo nuevo aún sin guardar. */
    var documentId by mutableStateOf<String?>(null); private set
    var selected by mutableIntStateOf(0); private set
    var busy by mutableStateOf(false); private set
    var message by mutableStateOf<String?>(null)

    private val prefs = app.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)

    /** Vista de biblioteca: true = cuadrícula, false = lista. Persistida en disco. */
    var libraryGrid by mutableStateOf(prefs.getBoolean(KEY_LIBRARY_GRID, true)); private set

    fun selectLibraryGrid(grid: Boolean) {
        if (grid == libraryGrid) return
        libraryGrid = grid
        prefs.edit().putBoolean(KEY_LIBRARY_GRID, grid).apply()
    }

    /** Compresión del PDF exportado; null = sin comprimir (render a 2000 px, PdfDocument). Persistida. */
    var pdfPreset by mutableStateOf(PdfPreset.entries.getOrNull(prefs.getInt(KEY_PDF_PRESET, PdfPreset.BALANCED.ordinal)))
        private set

    fun selectPdfPreset(preset: PdfPreset?) {
        pdfPreset = preset
        prefs.edit().putInt(KEY_PDF_PRESET, preset?.ordinal ?: -1).apply()
    }

    /** Avance 0..1 de la compresión del PDF en curso; null si no hay. */
    var exportProgress by mutableStateOf<Float?>(null); private set

    private val repo = DocumentRepository(app)
    private val exportDir = File(app.cacheDir, "exports").apply { mkdirs() }
    private val saveJobs = HashMap<Long, Job>()
    private var prefetchJob: Job? = null
    private var orderJob: Job? = null

    private val sourceCache = object : LruCache<String, Bitmap>(SOURCE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val renderCache = object : LruCache<String, Bitmap>(RENDER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private var nextId = System.currentTimeMillis()

    /** null mientras Room carga (evita parpadeo del estado vacío). */
    val documents: StateFlow<List<DocumentEntity>?> =
        repo.documents.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        // Limpia exportaciones previas y el caché de páginas de versiones anteriores.
        File(app.cacheDir, "pages").deleteRecursively()
        exportDir.listFiles()?.forEach { it.delete() }
    }

    /** Escaneo nuevo: sesión vacía y cámara. */
    fun startNewScan() {
        pages.clear()
        documentId = null
        selected = 0
        screen = Screen.CAMERA
    }

    fun openDocument(id: String) {
        viewModelScope.launch {
            val doc = repo.load(id) ?: return@launch
            val loaded = doc.pages
                .filter { File(it.sourcePath).exists() }
                .map { it.toScanPage() }
            if (loaded.isEmpty()) {
                repo.deleteDocument(id)
                return@launch
            }
            pages.clear()
            pages.addAll(loaded)
            documentId = id
            selected = 0
            screen = Screen.REVIEW
        }
    }

    /** Cierra la sesión y vuelve a la biblioteca. */
    fun closeDocument() {
        saveJobs.values.forEach { it.cancel() }
        saveJobs.clear()
        prefetchJob?.cancel()
        sourceCache.evictAll()
        renderCache.evictAll()
        pages.clear()
        documentId = null
        selected = 0
        screen = Screen.LIBRARY
    }

    fun renameDocument(id: String, title: String) {
        viewModelScope.launch { repo.rename(id, title) }
    }

    fun deleteDocument(id: String) {
        viewModelScope.launch { repo.deleteDocument(id) }
    }

    fun show(s: Screen) {
        screen = s
    }

    fun select(i: Int) {
        selected = i.coerceIn(0, max(0, pages.lastIndex))
    }

    fun update(index: Int, page: ScanPage) {
        pages[index] = page
        persist(page)
    }

    /** Mueve una página en memoria (durante el arrastre); la selección sigue a su página. */
    fun movePage(from: Int, to: Int) {
        if (from == to || from !in pages.indices || to !in pages.indices) return
        val selectedId = pages.getOrNull(selected)?.id
        pages.add(to, pages.removeAt(from))
        selectedId?.let { id -> selected = pages.indexOfFirst { it.id == id }.coerceAtLeast(0) }
    }

    /** Guarda el orden actual en Room (al soltar el arrastre). */
    fun commitOrder() {
        val docId = documentId ?: return
        val ids = pages.map { it.id }
        orderJob?.cancel()
        orderJob = viewModelScope.launch { repo.reorder(docId, ids) }
    }

    fun rotate(index: Int) {
        val p = pages[index].let { it.copy(rotation = (it.rotation + 90) % 360) }
        pages[index] = p
        persist(p)
    }

    fun delete(index: Int) {
        val removed = pages.removeAt(index)
        saveJobs.remove(removed.id)?.cancel()
        val docId = documentId
        if (docId != null) {
            viewModelScope.launch { repo.deletePage(removed.toEntity(docId, index)) }
        } else {
            removed.file.delete()
        }
        if (pages.isEmpty()) {
            documentId = null
            screen = Screen.LIBRARY
        } else {
            selected = selected.coerceIn(0, pages.lastIndex)
        }
    }

    /** Regenera miniatura y guarda parámetros; reemplaza guardados pendientes de la misma página. */
    private fun persist(page: ScanPage) {
        val docId = documentId ?: return
        saveJobs[page.id]?.cancel()
        saveJobs[page.id] = viewModelScope.launch {
            val index = pages.indexOfFirst { it.id == page.id }
            if (index < 0) return@launch
            writeThumb(docId, page)
            repo.updatePage(page.toEntity(docId, index))
        }
    }

    private suspend fun writeThumb(docId: String, page: ScanPage) {
        val bmp = render(page, THUMB_SOURCE_SIDE) ?: return
        try {
            repo.saveThumb(bmp, repo.thumbFile(docId, page.id))
        } finally {
            bmp.recycle()
        }
    }

    private fun ScanPage.toEntity(docId: String, index: Int) = PageEntity(
        id = id,
        documentId = docId,
        pageIndex = index,
        sourcePath = file.absolutePath,
        thumbnailPath = repo.thumbFile(docId, id).absolutePath,
        mode = mode,
        rotation = rotation,
        cornersCsv = corners.joinToString(","),
        detected = detected,
    )

    private fun PageEntity.toScanPage() = ScanPage(
        id = id,
        file = File(sourcePath),
        corners = cornersCsv.split(",").mapNotNull { it.toFloatOrNull() }.toFloatArray()
            .takeIf { it.size == 8 } ?: FULL_FRAME.copyOf(),
        mode = mode,
        rotation = rotation,
        detected = detected,
    )

    /** Foto JPEG de CameraX → archivo fuente derecho + detección de bordes y orientación. */
    suspend fun addCapture(jpeg: ByteArray, rotation: Int, openEditor: Boolean = true) {
        busy = true
        try {
            val docId = documentId ?: UUID.randomUUID().toString()
            val page = withContext(Dispatchers.Default) {
                val bmp = decode(jpeg, CAPTURE_MAX_SIDE, rotation)
                val id = nextId++
                val file = repo.sourceFile(docId, id)
                FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                copyExif(jpeg, file)

                val found = NativeScanner.detectQuadBitmap(bmp)
                val corners = found ?: FULL_FRAME.copyOf()
                val orientation = NativeScanner.process(bmp, corners, NativeScanner.MODE_ORIGINAL)
                    ?.let { p -> NativeScanner.detectOrientation(p).also { p.recycle() } } ?: 0
                bmp.recycle()
                ScanPage(id, file, corners, NativeScanner.MODE_ENHANCED, orientation, found != null)
            }
            writeThumb(docId, page)
            repo.addPage(docId, defaultTitle(), page.toEntity(docId, pages.size))
            documentId = docId
            pages.add(page)
            selected = pages.lastIndex
            if (openEditor) screen = Screen.EDIT
        } finally {
            busy = false
        }
    }

    /** Imágenes de la galería → mismo flujo que una captura; el usuario se queda en la revisión. */
    suspend fun addFromGallery(resolver: ContentResolver, uris: List<Uri>) {
        var firstNew = -1
        for (uri in uris) {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { resolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                message = "No se pudo abrir la imagen"
                continue
            }
            val rotation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).rotationDegrees }
                .getOrDefault(0)
            val before = pages.size
            runCatching { addCapture(bytes, rotation, openEditor = false) }
                .onFailure { message = "Formato de imagen no compatible" }
            if (firstNew < 0 && pages.size > before) firstNew = before
        }
        if (firstNew >= 0) select(firstNew)
    }

    /** Fuente decodificada; [maxSide] > 0 reduce con inSampleSize para ahorrar memoria. */
    suspend fun loadSource(page: ScanPage, maxSide: Int = 0): Bitmap? = withContext(Dispatchers.IO) {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        if (maxSide > 0) {
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeFile(page.file.absolutePath, opts)
            opts.inSampleSize = sampleFor(max(opts.outWidth, opts.outHeight), maxSide)
            opts.inJustDecodeBounds = false
        }
        BitmapFactory.decodeFile(page.file.absolutePath, opts)
    }

    /** Fuente reducida compartida: se decodifica una vez por página y se reutiliza en cada filtro. */
    private suspend fun cachedSource(page: ScanPage, maxSide: Int): Bitmap? {
        val key = "${page.id}:$maxSide"
        sourceCache.get(key)?.let { return it }
        val bmp = loadSource(page, maxSide) ?: return null
        sourceCache.put(key, bmp)
        return bmp
    }

    /** Página final: warp + filtro + rotación. El bitmap devuelto es del llamador (puede reciclarlo). */
    suspend fun render(page: ScanPage, maxSide: Int = 0): Bitmap? = withContext(Dispatchers.Default) {
        val cached = maxSide in 1..PREVIEW_SIDE
        val src = (if (cached) cachedSource(page, maxSide) else loadSource(page, maxSide))
            ?: return@withContext null
        val out = NativeScanner.process(src, page.corners, page.mode)
        if (!cached) src.recycle()
        if (out == null || page.rotation == 0) return@withContext out
        val rotated = Bitmap.createBitmap(
            out, 0, 0, out.width, out.height,
            Matrix().apply { postRotate(page.rotation.toFloat()) }, true,
        )
        out.recycle()
        rotated
    }

    private fun renderKey(page: ScanPage, maxSide: Int) =
        "${page.id}:${page.mode}:${page.rotation}:${page.corners.contentHashCode()}:$maxSide"

    /**
     * Render para pantalla: memoriza el resultado por (página, filtro, rotación, vértices, tamaño).
     * El bitmap devuelto pertenece al caché: no reciclar.
     */
    suspend fun renderCached(page: ScanPage, maxSide: Int): Bitmap? {
        val key = renderKey(page, maxSide)
        renderCache.get(key)?.let { return it }
        val bmp = render(page, maxSide) ?: return null
        renderCache.put(key, bmp)
        return bmp
    }

    /** Acierto síncrono del caché de render (evita parpadeo al volver a un filtro ya calculado). */
    fun peekRendered(page: ScanPage, maxSide: Int): Bitmap? = renderCache.get(renderKey(page, maxSide))

    /** Calcula en segundo plano los otros filtros de la página para que el cambio sea instantáneo. */
    fun prefetchFilters(page: ScanPage, maxSide: Int = PREVIEW_SIDE) {
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.Default) {
            for (mode in FILTER_MODES) {
                if (mode == page.mode) continue
                ensureActive()
                renderCached(page.copy(mode = mode), maxSide)
            }
        }
    }

    /** Genera archivos de exportación en caché y los devuelve para compartir. */
    suspend fun export(format: ExportFormat): List<File> = exportPages(pages.toList(), format)

    /** Exporta un documento guardado sin tocar la sesión abierta. */
    suspend fun exportDocument(id: String, format: ExportFormat): List<File> {
        val doc = repo.load(id) ?: return emptyList()
        val list = doc.pages.filter { File(it.sourcePath).exists() }.map { it.toScanPage() }
        return exportPages(list, format)
    }

    private suspend fun exportPages(list: List<ScanPage>, format: ExportFormat): List<File> =
        withContext(Dispatchers.Default) {
            busy = true
            try {
                val stamp = System.currentTimeMillis()
                when (format) {
                    ExportFormat.PDF -> {
                        val out = File(exportDir, "scan_$stamp.pdf")
                        val preset = pdfPreset
                        if (preset == null) {
                            val bitmaps = list.mapNotNull { render(it, PDF_MAX_SIDE) }
                            try {
                                listOf(Exporter.pdf(bitmaps, out))
                            } finally {
                                bitmaps.forEach { it.recycle() }
                            }
                        } else {
                            val sources = list.map { p ->
                                PdfCompressor.Page(bilevel = p.mode == NativeScanner.MODE_BW) { render(p) }
                            }
                            exportProgress = 0f
                            try {
                                listOfNotNull(
                                    PdfCompressor.compress(sources, out, preset) { done, total ->
                                        exportProgress = done / total.toFloat()
                                    },
                                )
                            } finally {
                                exportProgress = null
                            }
                        }
                    }
                    else -> list.mapIndexedNotNull { i, p ->
                        val bmp = render(p) ?: return@mapIndexedNotNull null
                        try {
                            Exporter.image(bmp, format, File(exportDir, "scan_${stamp}_${i + 1}.${format.ext}"), p.file)
                        } finally {
                            bmp.recycle()
                        }
                    }
                }
            } finally {
                busy = false
            }
        }

    private fun decode(jpeg: ByteArray, maxSide: Int, rotation: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = sampleFor(max(bounds.outWidth, bounds.outHeight), maxSide)
        }
        var bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)
        if (rotation != 0) {
            val rotated = Bitmap.createBitmap(
                bmp, 0, 0, bmp.width, bmp.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true,
            )
            bmp.recycle()
            bmp = rotated
        }
        return bmp
    }

    private fun defaultTitle() =
        "Escaneo " + SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date())

    private fun sampleFor(side: Int, maxSide: Int): Int {
        var s = 1
        while (side / (s * 2) >= maxSide) s *= 2
        return s
    }

    private fun copyExif(jpeg: ByteArray, to: File) {
        runCatching {
            val src = ExifInterface(ByteArrayInputStream(jpeg))
            val dst = ExifInterface(to.absolutePath)
            listOf(
                ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
            ).forEach { tag -> src.getAttribute(tag)?.let { dst.setAttribute(tag, it) } }
            dst.saveAttributes()
        }
    }

    companion object {
        private const val CAPTURE_MAX_SIDE = 4000
        private const val PDF_MAX_SIDE = 2000
        private const val THUMB_SOURCE_SIDE = 720
        /** Lado máximo de la vista previa en pantalla; el warp+filtro nativo corre a este tamaño. */
        const val PREVIEW_SIDE = 1280
        private const val SOURCE_CACHE_BYTES = 40 * 1024 * 1024
        private const val RENDER_CACHE_BYTES = 48 * 1024 * 1024
        private val FILTER_MODES = intArrayOf(
            NativeScanner.MODE_ORIGINAL, NativeScanner.MODE_ENHANCED,
            NativeScanner.MODE_BW, NativeScanner.MODE_VIVID,
        )
        val FULL_FRAME = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
    }
}
