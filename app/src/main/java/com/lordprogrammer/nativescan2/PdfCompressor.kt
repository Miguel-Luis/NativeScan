package com.lordprogrammer.nativescan2

import android.graphics.Bitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Nivel de compresión del PDF. [dpi] = resolución objetivo (nunca se amplía); [jpegQuality] solo color/gris. */
enum class PdfPreset(val label: String, val dpi: Int, val jpegQuality: Int) {
    SMALL("Mínimo", 150, 75),
    BALANCED("Equilibrado", 200, 80),
    HIGH("Alta calidad", 300, 82),
}

/**
 * PDF comprimido generado en C++ (pdf_compress.cpp).
 *
 * - Color / gris: remuestreo a [PdfPreset.dpi], JPEG 4:2:0 con tablas optimizadas (libjpeg-turbo vía OpenCV).
 *   Una página sin cromaticidad se guarda como DeviceGray.
 * - Blanco y negro: 1 bpp con CCITT Group 4 (a 300 dpi como mínimo).
 * - Los flujos ya comprimidos se escriben directo al archivo: en RAM solo vive la página en curso.
 *
 * Java solo entrega el Bitmap (los píxeles se leen en sitio vía AndroidBitmap_lockPixels) o una ruta.
 */
object PdfCompressor {

    init {
        System.loadLibrary("nativescan2")
    }

    private external fun nativeOpen(path: String): Long
    private external fun nativeAddBitmap(handle: Long, bitmap: Bitmap, bilevel: Boolean, dpi: Int, quality: Int): Boolean
    private external fun nativeAddFile(handle: Long, path: String, bilevel: Boolean, dpi: Int, quality: Int): Boolean
    private external fun nativeFinish(handle: Long): Boolean
    private external fun nativeAbort(handle: Long)

    /** Origen de una página: [render] produce el Bitmap RGBA_8888 a resolución completa (se recicla tras usarlo). */
    class Page(val bilevel: Boolean, val render: suspend () -> Bitmap?)

    /**
     * Genera el PDF en [out] página a página. Bloqueante respecto a la corrutina: llamar desde un
     * dispatcher de fondo. [onProgress] recibe (páginas terminadas, total) tras cada página.
     * Devuelve [out], o null si falló (no deja archivo parcial). Respeta la cancelación.
     */
    suspend fun compress(
        pages: List<Page>,
        out: File,
        preset: PdfPreset = PdfPreset.BALANCED,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): File? {
        val handle = nativeOpen(out.absolutePath)
        if (handle == 0L) return null
        var finished = false
        try {
            pages.forEachIndexed { i, page ->
                currentCoroutineContext().ensureActive()
                val bmp = page.render() ?: return null
                try {
                    if (!nativeAddBitmap(handle, bmp, page.bilevel, preset.dpi, preset.jpegQuality)) return null
                } finally {
                    bmp.recycle()
                }
                onProgress(i + 1, pages.size)
            }
            finished = true
            return if (nativeFinish(handle)) out else null
        } finally {
            if (!finished) nativeAbort(handle)
        }
    }

    /** Igual que [compress] pero con imágenes ya guardadas en disco (decodificadas en C++). */
    suspend fun compressFiles(
        files: List<File>,
        out: File,
        bilevel: Boolean = false,
        preset: PdfPreset = PdfPreset.BALANCED,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): File? {
        val handle = nativeOpen(out.absolutePath)
        if (handle == 0L) return null
        var finished = false
        try {
            files.forEachIndexed { i, f ->
                currentCoroutineContext().ensureActive()
                if (!nativeAddFile(handle, f.absolutePath, bilevel, preset.dpi, preset.jpegQuality)) return null
                onProgress(i + 1, files.size)
            }
            finished = true
            return if (nativeFinish(handle)) out else null
        } finally {
            if (!finished) nativeAbort(handle)
        }
    }
}
