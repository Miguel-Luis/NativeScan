package com.lordprogrammer.nativescan2

import android.graphics.Bitmap
import java.nio.ByteBuffer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

object NativeScanner {

    init {
        System.loadLibrary("nativescan2")
    }

    const val MODE_ORIGINAL = 0
    const val MODE_BW = 1
    const val MODE_ENHANCED = 2
    const val MODE_VIVID = 3

    /** Plano Y (DirectByteBuffer). Devuelve 8 floats normalizados tl,tr,br,bl o null. */
    external fun detectQuadLuma(luma: ByteBuffer, width: Int, height: Int, rowStride: Int): FloatArray?

    /** Bitmap ARGB_8888. Devuelve 8 floats normalizados tl,tr,br,bl o null. */
    external fun detectQuadBitmap(bitmap: Bitmap): FloatArray?

    /** Grados horarios (0/90/180/270) a rotar el bitmap para dejar el texto derecho. */
    external fun detectOrientation(bitmap: Bitmap): Int

    private external fun processDocument(src: Bitmap, corners: FloatArray, mode: Int, dst: Bitmap): Boolean

    /** Corrige perspectiva y aplica filtro. [corners] normalizado a [src]. */
    fun process(src: Bitmap, corners: FloatArray, mode: Int): Bitmap? {
        fun px(i: Int, j: Int) = hypot(
            (corners[i] - corners[j]) * src.width,
            (corners[i + 1] - corners[j + 1]) * src.height,
        )
        val w = max(px(0, 2), px(4, 6)).roundToInt().coerceAtLeast(1)
        val h = max(px(0, 6), px(2, 4)).roundToInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        if (!processDocument(src, corners, mode, out)) {
            out.recycle()
            return null
        }
        return out
    }
}
