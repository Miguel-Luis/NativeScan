package com.lordprogrammer.nativescan2

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PdfCompressorTest {

    private fun page(w: Int, h: Int, kind: Int): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (kind) {
            0 -> { // color
                p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), Color.rgb(250, 240, 220), Color.rgb(200, 220, 250), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.shader = null
                p.color = Color.rgb(180, 30, 30); p.textSize = 90f
                for (i in 1..30) c.drawText("Documento a color línea $i  0123456789", 120f, i * 120f, p)
            }
            1 -> { // gris
                p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.rgb(245, 245, 245), Color.rgb(160, 160, 160), Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
                p.shader = null; p.color = Color.rgb(40, 40, 40); p.textSize = 90f
                for (i in 1..30) c.drawText("Escala de grises línea $i", 120f, i * 120f, p)
            }
            else -> { // B/N puro, sin antialias
                c.drawColor(Color.WHITE)
                p.isAntiAlias = false; p.color = Color.BLACK; p.textSize = 90f
                for (i in 1..30) c.drawText("Blanco y negro puro línea $i  The quick brown fox", 120f, i * 120f, p)
            }
        }
        return bmp
    }

    @Test
    fun generatesAllPageKinds(): Unit = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.cacheDir, "test_compressed.pdf")
        val progress = mutableListOf<Int>()
        val pages = (0..2).map { k -> PdfCompressor.Page(bilevel = k == 2) { page(3000, 4000, k) } }
        val t0 = System.nanoTime()
        val res = PdfCompressor.compress(pages, out, PdfPreset.BALANCED) { d, _ -> progress += d }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertNotNull(res)
        assertEquals(listOf(1, 2, 3), progress)
        assertTrue(out.length() > 1000)
        println("PDFTEST size=${out.length()} ms=$ms")
        android.util.Log.i("PDFTEST", "size=${out.length()} ms=$ms")
    }
}
