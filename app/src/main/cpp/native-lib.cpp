#include <jni.h>
#include <android/bitmap.h>
#include <opencv2/imgproc.hpp>
#include <vector>
#include <algorithm>
#include <cmath>

namespace {

constexpr int kDetectWidth = 480;
constexpr double kMinAreaRatio = 0.15;

// Ordena 4 puntos: tl, tr, br, bl.
std::vector<cv::Point2f> orderCorners(const std::vector<cv::Point> &pts) {
    std::vector<cv::Point2f> out(4);
    auto sum = [](const cv::Point &p) { return p.x + p.y; };
    auto diff = [](const cv::Point &p) { return p.y - p.x; };
    out[0] = *std::min_element(pts.begin(), pts.end(),
                               [&](auto &a, auto &b) { return sum(a) < sum(b); });
    out[2] = *std::max_element(pts.begin(), pts.end(),
                               [&](auto &a, auto &b) { return sum(a) < sum(b); });
    out[1] = *std::min_element(pts.begin(), pts.end(),
                               [&](auto &a, auto &b) { return diff(a) < diff(b); });
    out[3] = *std::max_element(pts.begin(), pts.end(),
                               [&](auto &a, auto &b) { return diff(a) < diff(b); });
    return out;
}

// Mayor cuadrilátero convexo en una imagen binaria/bordes.
std::vector<cv::Point> bestQuad(const cv::Mat &binary, double imgArea, double &areaOut) {
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(binary.clone(), contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);

    double bestArea = 0;
    std::vector<cv::Point> best;
    for (auto &c : contours) {
        double area = cv::contourArea(c);
        if (area < imgArea * kMinAreaRatio || area <= bestArea) continue;
        // Descarta contornos que ocupan todo el cuadro (borde de imagen).
        if (area > imgArea * 0.98) continue;
        std::vector<cv::Point> approx;
        cv::approxPolyDP(c, approx, 0.02 * cv::arcLength(c, true), true);
        if (approx.size() == 4 && cv::isContourConvex(approx)) {
            bestArea = area;
            best = approx;
        } else if (approx.size() > 4 && area > bestArea) {
            // Esquina ocluida/ruido: aproxima con rectángulo rotado del casco convexo.
            std::vector<cv::Point> hull;
            cv::convexHull(c, hull);
            cv::RotatedRect rr = cv::minAreaRect(hull);
            double ra = (double) rr.size.width * rr.size.height;
            if (ra > 0 && cv::contourArea(hull) / ra > 0.85) {
                cv::Point2f p[4];
                rr.points(p);
                best.assign(4, cv::Point());
                for (int i = 0; i < 4; i++) best[i] = cv::Point((int) p[i].x, (int) p[i].y);
                bestArea = area;
            }
        }
    }
    areaOut = bestArea;
    return best;
}

// gray: 8UC1. Devuelve esquinas normalizadas [0..1] o vacío.
// Etapa 1: Canny. Etapa 2 (fallback luz difícil/fondo similar): máscara Otsu + cierre.
std::vector<float> detectQuad(const cv::Mat &gray) {
    double scale = std::min(1.0, kDetectWidth / (double) gray.cols);
    cv::Mat small;
    if (scale < 1.0) cv::resize(gray, small, cv::Size(), scale, scale, cv::INTER_AREA);
    else small = gray;

    double imgArea = (double) small.cols * small.rows;
    cv::Mat blur;
    cv::GaussianBlur(small, blur, cv::Size(5, 5), 0);

    cv::Mat edges;
    cv::Canny(blur, edges, 50, 150);
    cv::dilate(edges, edges, cv::getStructuringElement(cv::MORPH_RECT, cv::Size(3, 3)));

    double area = 0;
    std::vector<cv::Point> best = bestQuad(edges, imgArea, area);

    if (best.empty()) {
        cv::Mat mask;
        cv::threshold(blur, mask, 0, 255, cv::THRESH_BINARY | cv::THRESH_OTSU);
        cv::morphologyEx(mask, mask, cv::MORPH_CLOSE,
                         cv::getStructuringElement(cv::MORPH_RECT, cv::Size(9, 9)));
        double a1 = 0, a2 = 0;
        auto q1 = bestQuad(mask, imgArea, a1);
        cv::Mat inv;
        cv::bitwise_not(mask, inv);
        auto q2 = bestQuad(inv, imgArea, a2);
        if (!q1.empty() && a1 >= a2) best = q1;
        else if (!q2.empty()) best = q2;
    }
    if (best.empty()) return {};

    auto ordered = orderCorners(best);
    std::vector<float> res;
    res.reserve(8);
    for (auto &p : ordered) {
        res.push_back(std::clamp(p.x / (float) small.cols, 0.f, 1.f));
        res.push_back(std::clamp(p.y / (float) small.rows, 0.f, 1.f));
    }
    return res;
}

// Líneas horizontales: decide si el bloque está invertido (true) por masa de tinta
// sobre la banda x-height (ascendentes/mayúsculas) vs bajo ella (descendentes).
bool upsideDown(const cv::Mat &bin) {
    cv::Mat rowSum;
    cv::reduce(bin, rowSum, 1, cv::REDUCE_SUM, CV_32F);
    cv::GaussianBlur(rowSum, rowSum, cv::Size(1, 5), 0);
    double maxV;
    cv::minMaxLoc(rowSum, nullptr, &maxV);
    if (maxV <= 0) return false;
    const int n = rowSum.rows;
    const float thr = (float) (maxV * 0.12);
    double votes = 0;
    int i = 0;
    while (i < n) {
        if (rowSum.at<float>(i) < thr) { i++; continue; }
        int a = i;
        while (i < n && rowSum.at<float>(i) >= thr) i++;
        int e = i - 1;
        if (e - a < 8) continue;
        float lineMax = 0;
        for (int y = a; y <= e; y++) lineMax = std::max(lineMax, rowSum.at<float>(y));
        int t = a, b = e;
        while (t < e && rowSum.at<float>(t) < lineMax * 0.5f) t++;
        while (b > a && rowSum.at<float>(b) < lineMax * 0.5f) b--;
        double above = 0, below = 0;
        for (int y = a; y < t; y++) above += rowSum.at<float>(y);
        for (int y = b + 1; y <= e; y++) below += rowSum.at<float>(y);
        votes += above - below;  // más masa arriba => derecho
    }
    return votes < 0;
}

// Devuelve grados horarios (0/90/180/270) a rotar para dejar el texto derecho.
int detectOrientation(const cv::Mat &gray) {
    double s = 1000.0 / std::max(gray.cols, gray.rows);
    cv::Mat small;
    if (s < 1.0) cv::resize(gray, small, cv::Size(), s, s, cv::INTER_AREA);
    else small = gray;

    cv::Mat bin;
    cv::adaptiveThreshold(small, bin, 255, cv::ADAPTIVE_THRESH_MEAN_C,
                          cv::THRESH_BINARY_INV, 31, 15);
    cv::morphologyEx(bin, bin, cv::MORPH_OPEN,
                     cv::getStructuringElement(cv::MORPH_RECT, cv::Size(2, 2)));

    auto score = [](const cv::Mat &profile) {
        cv::Scalar m, sd;
        cv::meanStdDev(profile, m, sd);
        return m[0] > 1e-3 ? sd[0] / m[0] : 0.0;
    };
    cv::Mat rows, cols;
    cv::reduce(bin, rows, 1, cv::REDUCE_AVG, CV_32F);
    cv::reduce(bin, cols, 0, cv::REDUCE_AVG, CV_32F);
    bool horizontal = score(rows) >= score(cols);

    cv::Mat b = bin;
    int base = 0;
    if (!horizontal) {
        cv::rotate(bin, b, cv::ROTATE_90_CLOCKWISE);
        base = 90;
    }
    return upsideDown(b) ? (base + 180) % 360 : base;
}

jfloatArray toJava(JNIEnv *env, const std::vector<float> &v) {
    if (v.empty()) return nullptr;
    jfloatArray arr = env->NewFloatArray((jsize) v.size());
    env->SetFloatArrayRegion(arr, 0, (jsize) v.size(), v.data());
    return arr;
}

bool lockRgba(JNIEnv *env, jobject bmp, AndroidBitmapInfo &info, void *&pixels) {
    if (AndroidBitmap_getInfo(env, bmp, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888)
        return false;
    return AndroidBitmap_lockPixels(env, bmp, &pixels) == ANDROID_BITMAP_RESULT_SUCCESS;
}

}  // namespace

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_lordprogrammer_nativescan2_NativeScanner_detectQuadLuma(
        JNIEnv *env, jobject, jobject buffer, jint width, jint height, jint rowStride) {
    void *ptr = env->GetDirectBufferAddress(buffer);
    if (!ptr) return nullptr;
    cv::Mat gray(height, width, CV_8UC1, ptr, (size_t) rowStride);
    return toJava(env, detectQuad(gray));
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_lordprogrammer_nativescan2_NativeScanner_detectQuadBitmap(
        JNIEnv *env, jobject, jobject bitmap) {
    AndroidBitmapInfo info;
    void *pixels = nullptr;
    if (!lockRgba(env, bitmap, info, pixels)) return nullptr;
    cv::Mat rgba(info.height, info.width, CV_8UC4, pixels, info.stride);
    cv::Mat gray;
    cv::cvtColor(rgba, gray, cv::COLOR_RGBA2GRAY);
    AndroidBitmap_unlockPixels(env, bitmap);
    return toJava(env, detectQuad(gray));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lordprogrammer_nativescan2_NativeScanner_detectOrientation(
        JNIEnv *env, jobject, jobject bitmap) {
    AndroidBitmapInfo info;
    void *pixels = nullptr;
    if (!lockRgba(env, bitmap, info, pixels)) return 0;
    cv::Mat rgba(info.height, info.width, CV_8UC4, pixels, info.stride);
    cv::Mat gray;
    cv::cvtColor(rgba, gray, cv::COLOR_RGBA2GRAY);
    AndroidBitmap_unlockPixels(env, bitmap);
    return detectOrientation(gray);
}

// mode: 0 original, 1 blanco y negro, 2 realce de color, 3 color vivo.
// corners: 8 floats normalizados (tl,tr,br,bl). dst ya tiene el tamaño de salida.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_lordprogrammer_nativescan2_NativeScanner_processDocument(
        JNIEnv *env, jobject, jobject src, jfloatArray corners, jint mode, jobject dst) {
    AndroidBitmapInfo si, di;
    void *sp = nullptr, *dp = nullptr;
    if (!lockRgba(env, src, si, sp)) return JNI_FALSE;
    if (!lockRgba(env, dst, di, dp)) {
        AndroidBitmap_unlockPixels(env, src);
        return JNI_FALSE;
    }

    jfloat c[8];
    env->GetFloatArrayRegion(corners, 0, 8, c);

    cv::Mat srcMat(si.height, si.width, CV_8UC4, sp, si.stride);
    cv::Mat dstMat(di.height, di.width, CV_8UC4, dp, di.stride);

    cv::Point2f from[4], to[4];
    for (int i = 0; i < 4; i++)
        from[i] = cv::Point2f(c[i * 2] * si.width, c[i * 2 + 1] * si.height);
    to[0] = {0, 0};
    to[1] = {(float) di.width - 1, 0};
    to[2] = {(float) di.width - 1, (float) di.height - 1};
    to[3] = {0, (float) di.height - 1};

    cv::Mat M = cv::getPerspectiveTransform(from, to);
    if (mode == 0) {
        // Sin filtro: warp directo al bitmap destino (sin copia intermedia).
        cv::warpPerspective(srcMat, dstMat, M, dstMat.size(), cv::INTER_LINEAR);
    } else {
        cv::Mat warped;
        cv::warpPerspective(srcMat, warped, M, dstMat.size(), cv::INTER_LINEAR);
        if (mode == 1) {
            cv::Mat gray;
            cv::cvtColor(warped, gray, cv::COLOR_RGBA2GRAY);
            cv::adaptiveThreshold(gray, gray, 255, cv::ADAPTIVE_THRESH_GAUSSIAN_C,
                                  cv::THRESH_BINARY, 31, 15);
            cv::cvtColor(gray, dstMat, cv::COLOR_GRAY2RGBA);
        } else if (mode == 2) {
            cv::Mat rgb, lab;
            cv::cvtColor(warped, rgb, cv::COLOR_RGBA2RGB);
            cv::cvtColor(rgb, lab, cv::COLOR_RGB2Lab);
            std::vector<cv::Mat> ch;
            cv::split(lab, ch);
            // Eliminación de sombras: dividir L entre estimación de fondo.
            cv::Mat bg;
            cv::dilate(ch[0], bg, cv::getStructuringElement(cv::MORPH_ELLIPSE, cv::Size(15, 15)));
            cv::medianBlur(bg, bg, 21);
            cv::divide(ch[0], bg, ch[0], 255.0);
            auto clahe = cv::createCLAHE(2.0, cv::Size(8, 8));
            clahe->apply(ch[0], ch[0]);
            cv::merge(ch, lab);
            cv::cvtColor(lab, rgb, cv::COLOR_Lab2RGB);
            cv::cvtColor(rgb, dstMat, cv::COLOR_RGB2RGBA);
        } else {
            // 3: color vivo. Saturación x1.5 en HSV + contraste ligero.
            cv::Mat rgb, hsv;
            cv::cvtColor(warped, rgb, cv::COLOR_RGBA2RGB);
            cv::cvtColor(rgb, hsv, cv::COLOR_RGB2HSV);
            std::vector<cv::Mat> ch;
            cv::split(hsv, ch);
            ch[1].convertTo(ch[1], CV_8U, 1.5, 0);
            ch[2].convertTo(ch[2], CV_8U, 1.1, -8);
            cv::merge(ch, hsv);
            cv::cvtColor(hsv, rgb, cv::COLOR_HSV2RGB);
            cv::cvtColor(rgb, dstMat, cv::COLOR_RGB2RGBA);
        }
    }

    AndroidBitmap_unlockPixels(env, dst);
    AndroidBitmap_unlockPixels(env, src);
    return JNI_TRUE;
}
