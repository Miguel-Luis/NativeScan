package com.lordprogrammer.nativescan2

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.os.Build
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

enum class ExportFormat(val ext: String, val mime: String) {
    PDF("pdf", "application/pdf"),
    JPEG("jpg", "image/jpeg"),
    WEBP("webp", "image/webp"),
}

object Exporter {

    private const val PDF_PAGE_WIDTH = 595f // A4 @72dpi
    private val EXIF_TAGS = listOf(
        ExifInterface.TAG_DATETIME,
        ExifInterface.TAG_DATETIME_ORIGINAL,
        ExifInterface.TAG_MAKE,
        ExifInterface.TAG_MODEL,
    )

    /** Ensambla todas las páginas en un solo PDF. Mantiene bitmaps vivos hasta writeTo. */
    fun pdf(pages: List<Bitmap>, out: File): File {
        val doc = PdfDocument()
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        try {
            pages.forEachIndexed { i, bmp ->
                val h = PDF_PAGE_WIDTH * bmp.height / bmp.width
                val info = PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH.toInt(), h.toInt(), i + 1).create()
                val page = doc.startPage(info)
                page.canvas.drawBitmap(bmp, null, RectF(0f, 0f, PDF_PAGE_WIDTH, h), paint)
                doc.finishPage(page)
            }
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }
        return out
    }

    /** Comprime una imagen; para JPEG copia metadatos básicos (sin GPS) de [metaSource]. */
    fun image(bmp: Bitmap, format: ExportFormat, out: File, metaSource: File?): File {
        FileOutputStream(out).use {
            when (format) {
                ExportFormat.JPEG -> bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
                ExportFormat.WEBP -> bmp.compress(webpFormat(), 90, it)
                ExportFormat.PDF -> error("usa pdf()")
            }
        }
        if (format == ExportFormat.JPEG && metaSource != null) copyExif(metaSource, out)
        return out
    }

    @Suppress("DEPRECATION")
    private fun webpFormat() =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    private fun copyExif(from: File, to: File) {
        runCatching {
            val src = ExifInterface(from.absolutePath)
            val dst = ExifInterface(to.absolutePath)
            EXIF_TAGS.forEach { tag -> src.getAttribute(tag)?.let { dst.setAttribute(tag, it) } }
            dst.saveAttributes()
        }
    }

    fun share(context: Context, files: List<File>, mime: String) {
        val uris = files.map {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it)
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE)
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = mime
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, null))
    }
}
