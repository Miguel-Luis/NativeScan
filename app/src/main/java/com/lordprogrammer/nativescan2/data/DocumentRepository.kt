package com.lordprogrammer.nativescan2.data

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Room (metadatos) + `filesDir/scans/{docId}/` (fuente JPEG y miniatura WebP). */
class DocumentRepository(context: Context) {

    private val dao = NativeScanDatabase.get(context).documentDao()
    private val root = File(context.filesDir, "scans")

    val documents: Flow<List<DocumentEntity>> = dao.observeDocuments()

    private fun docDir(documentId: String) = File(root, documentId).apply { mkdirs() }
    fun sourceFile(documentId: String, pageId: Long) = File(docDir(documentId), "$pageId.jpg")
    fun thumbFile(documentId: String, pageId: Long) = File(docDir(documentId), "${pageId}_thumb.webp")

    suspend fun load(documentId: String): DocumentWithPages? = withContext(Dispatchers.IO) {
        dao.getWithPages(documentId)?.let { it.copy(pages = it.pages.sortedBy { p -> p.pageIndex }) }
    }

    /** Inserta la página; crea el documento si no existe. */
    suspend fun addPage(documentId: String, title: String, page: PageEntity) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (dao.getDocument(documentId) == null) {
            dao.insertDocument(DocumentEntity(documentId, title, now, now, 0, null))
        }
        dao.insertPage(page)
        sync(documentId, now)
    }

    suspend fun updatePage(page: PageEntity) = withContext(Dispatchers.IO) {
        dao.updatePage(page)
        sync(page.documentId, System.currentTimeMillis())
    }

    /** Devuelve true si el documento quedó vacío y fue eliminado. */
    suspend fun deletePage(page: PageEntity): Boolean = withContext(Dispatchers.IO) {
        File(page.sourcePath).delete()
        File(page.thumbnailPath).delete()
        dao.deletePage(page.id)
        val rest = dao.getPages(page.documentId)
        if (rest.isEmpty()) {
            deleteDocument(page.documentId)
            true
        } else {
            rest.forEachIndexed { i, p -> if (p.pageIndex != i) dao.updatePage(p.copy(pageIndex = i)) }
            sync(page.documentId, System.currentTimeMillis())
            false
        }
    }

    /** Persiste el nuevo orden: [orderedIds] son los ids de página en su posición final. */
    suspend fun reorder(documentId: String, orderedIds: List<Long>) = withContext(Dispatchers.IO) {
        val byId = dao.getPages(documentId).associateBy { it.id }
        orderedIds.forEachIndexed { i, id ->
            val p = byId[id] ?: return@forEachIndexed
            if (p.pageIndex != i) dao.updatePage(p.copy(pageIndex = i))
        }
        sync(documentId, System.currentTimeMillis())
    }

    suspend fun rename(documentId: String, title: String) = withContext(Dispatchers.IO) {
        val doc = dao.getDocument(documentId) ?: return@withContext
        val t = title.trim()
        if (t.isNotEmpty()) dao.updateDocument(doc.copy(title = t, updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteDocument(documentId: String) = withContext(Dispatchers.IO) {
        File(root, documentId).deleteRecursively()
        dao.deleteDocument(documentId)
    }

    /** Escribe [bmp] como miniatura WebP de alto [THUMB_HEIGHT]; el reciclado de [bmp] es del llamador. */
    suspend fun saveThumb(bmp: Bitmap, file: File) = withContext(Dispatchers.IO) {
        val h = minOf(THUMB_HEIGHT, bmp.height)
        val w = (bmp.width * h / bmp.height.toFloat()).roundToInt().coerceAtLeast(1)
        val scaled = if (h == bmp.height) bmp else Bitmap.createScaledBitmap(bmp, w, h, true)
        try {
            FileOutputStream(file).use { scaled.compress(webp(), 80, it) }
        } finally {
            if (scaled !== bmp) scaled.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun webp() =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    private suspend fun sync(documentId: String, now: Long) {
        val doc = dao.getDocument(documentId) ?: return
        val pages = dao.getPages(documentId)
        dao.updateDocument(
            doc.copy(updatedAt = now, pageCount = pages.size, coverThumbnailPath = pages.firstOrNull()?.thumbnailPath),
        )
    }

    companion object {
        const val THUMB_HEIGHT = 360
    }
}
