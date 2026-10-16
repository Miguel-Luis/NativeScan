// Puente JNI de PdfCompressor. El "handle" es un puntero a pdfc::Writer.
#include <jni.h>
#include <android/bitmap.h>
#include <opencv2/imgcodecs.hpp>

#include <string>

#include "pdf_compress.h"

namespace {

pdfc::Writer *W(jlong h) { return reinterpret_cast<pdfc::Writer *>(h); }

pdfc::PageParams params(jboolean bilevel, jint dpi, jint quality) {
    pdfc::PageParams p;
    p.bilevel = bilevel == JNI_TRUE;
    p.dpi = dpi;
    p.jpegQuality = quality;
    return p;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_lordprogrammer_nativescan2_PdfCompressor_nativeOpen(JNIEnv *env, jobject, jstring path) {
    const char *c = env->GetStringUTFChars(path, nullptr);
    auto *w = new pdfc::Writer();
    const bool ok = w->open(c);
    env->ReleaseStringUTFChars(path, c);
    if (!ok) {
        delete w;
        return 0;
    }
    return reinterpret_cast<jlong>(w);
}

/** Lee los píxeles del Bitmap RGBA_8888 en sitio (sin copiarlos al heap de Java). */
JNIEXPORT jboolean JNICALL
Java_com_lordprogrammer_nativescan2_PdfCompressor_nativeAddBitmap(
    JNIEnv *env, jobject, jlong handle, jobject bitmap, jboolean bilevel, jint dpi, jint quality) {
    AndroidBitmapInfo info;
    if (!handle || AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return JNI_FALSE;
    void *px = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &px) != ANDROID_BITMAP_RESULT_SUCCESS) return JNI_FALSE;
    bool ok = false;
    try {
        cv::Mat src((int) info.height, (int) info.width, CV_8UC4, px, info.stride);
        ok = W(handle)->addPage(src, params(bilevel, dpi, quality));
    } catch (...) {
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** Variante por ruta: la imagen (JPEG/PNG/WebP) se decodifica nativamente, sin pasar por Java. */
JNIEXPORT jboolean JNICALL
Java_com_lordprogrammer_nativescan2_PdfCompressor_nativeAddFile(
    JNIEnv *env, jobject, jlong handle, jstring path, jboolean bilevel, jint dpi, jint quality) {
    if (!handle) return JNI_FALSE;
    const char *c = env->GetStringUTFChars(path, nullptr);
    std::string file(c);
    env->ReleaseStringUTFChars(path, c);
    bool ok = false;
    try {
        cv::Mat src = cv::imread(file, cv::IMREAD_COLOR);  // BGR
        ok = !src.empty() && W(handle)->addPage(src, params(bilevel, dpi, quality));
    } catch (...) {
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** Cierra el PDF y libera el handle. */
JNIEXPORT jboolean JNICALL
Java_com_lordprogrammer_nativescan2_PdfCompressor_nativeFinish(JNIEnv *, jobject, jlong handle) {
    if (!handle) return JNI_FALSE;
    auto *w = W(handle);
    bool ok = false;
    try {
        ok = w->finish();
    } catch (...) {
    }
    delete w;
    return ok ? JNI_TRUE : JNI_FALSE;
}

/** Descarta el PDF parcial y libera el handle. */
JNIEXPORT void JNICALL
Java_com_lordprogrammer_nativescan2_PdfCompressor_nativeAbort(JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    auto *w = W(handle);
    w->abort();
    delete w;
}

}  // extern "C"
