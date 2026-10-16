#include "pdf_compress.h"

#include <opencv2/imgcodecs.hpp>
#include <opencv2/imgproc.hpp>
#include <zlib.h>

#include <algorithm>
#include <cmath>

#include "g4_encoder.h"

namespace pdfc {
namespace {

constexpr double kPageWidthPt = 595.0;  // A4 @72 dpi, igual que el exportador anterior
constexpr int kBilevelMinDpi = 300;     // 1 bpp es barato: texto nítido a 300 dpi como mínimo

std::vector<uint8_t> deflateBytes(const uint8_t *data, size_t len) {
    uLongf cap = compressBound((uLong) len);
    std::vector<uint8_t> out(cap);
    if (compress2(out.data(), &cap, data, (uLong) len, 9) != Z_OK) return {};
    out.resize(cap);
    return out;
}

// Color o gris: muestrea una rejilla y acepta hasta 0,5 % de píxeles con cromaticidad apreciable.
bool isGrayscale(const cv::Mat &m, bool rgba) {
    const int ch = rgba ? 4 : 3;
    const int stepY = std::max(1, m.rows / 160), stepX = std::max(1, m.cols / 160);
    long total = 0, colored = 0;
    for (int y = 0; y < m.rows; y += stepY) {
        const uint8_t *r = m.ptr<uint8_t>(y);
        for (int x = 0; x < m.cols; x += stepX) {
            const uint8_t *p = r + x * ch;
            int hi = std::max(p[0], std::max(p[1], p[2]));
            int lo = std::min(p[0], std::min(p[1], p[2]));
            total++;
            if (hi - lo > 14) colored++;
        }
    }
    return colored * 200 <= total;
}

}  // namespace

Writer::~Writer() {
    if (f_) abort();
}

bool Writer::writeRaw(const void *data, size_t len) {
    if (!ok_ || !f_) return false;
    if (len && fwrite(data, 1, len, f_) != len) ok_ = false;
    pos_ += len;
    return ok_;
}

bool Writer::open(const std::string &path) {
    f_ = fopen(path.c_str(), "wb");
    if (!f_) return false;
    path_ = path;
    setvbuf(f_, nullptr, _IOFBF, 256 * 1024);
    static const char header[] = "%PDF-1.5\n%\xE2\xE3\xCF\xD3\n";
    return writeRaw(header, sizeof(header) - 1);
}

bool Writer::writeObject(int num, const std::string &dict, const uint8_t *data, size_t len) {
    if ((int) offsets_.size() <= num) offsets_.resize(num + 1, 0);
    offsets_[num] = pos_;
    std::string head = std::to_string(num) + " 0 obj\n<< " + dict + " /Length " + std::to_string(len) +
                       " >>\nstream\n";
    writeRaw(head.data(), head.size());
    writeRaw(data, len);
    static const char tail[] = "\nendstream\nendobj\n";
    return writeRaw(tail, sizeof(tail) - 1);
}

bool Writer::addPage(const cv::Mat &src, const PageParams &p) {
    if (!ok_ || !f_ || src.empty() || src.depth() != CV_8U) return false;
    const bool rgba = src.channels() == 4;
    if (!rgba && src.channels() != 3) return false;

    // 1) Normaliza la resolución: nunca se amplía. INTER_AREA es el filtro correcto al reducir
    //    (Lanczos produce aliasing con factores grandes).
    const int dpi = p.bilevel ? std::max(p.dpi, kBilevelMinDpi) : p.dpi;
    const int targetW = std::max(1, (int) std::lround(kPageWidthPt / 72.0 * dpi));
    cv::Mat work = src;
    if (src.cols > targetW) {
        const int nh = std::max(1, (int) std::lround((double) src.rows * targetW / src.cols));
        cv::resize(src, work, cv::Size(targetW, nh), 0, 0, cv::INTER_AREA);
    }
    const int w = work.cols, h = work.rows;

    // 2) Codifica según el tipo de página.
    std::vector<uint8_t> stream;
    std::string dict = "/Type /XObject /Subtype /Image /Width " + std::to_string(w) + " /Height " +
                       std::to_string(h) + " /BitsPerComponent ";
    if (p.bilevel) {
        cv::Mat gray, bin;
        cv::cvtColor(work, gray, rgba ? cv::COLOR_RGBA2GRAY : cv::COLOR_BGR2GRAY);
        cv::threshold(gray, bin, 128, 255, cv::THRESH_BINARY_INV);  // !=0 = tinta
        stream = g4::encode(bin.data, w, h, (int) bin.step);
        dict += "1 /ColorSpace /DeviceGray /Filter /CCITTFaxDecode /DecodeParms << /K -1 /Columns " +
                std::to_string(w) + " /Rows " + std::to_string(h) + " /BlackIs1 false >>";
    } else {
        const bool gray = isGrayscale(work, rgba);
        cv::Mat enc;
        if (gray) cv::cvtColor(work, enc, rgba ? cv::COLOR_RGBA2GRAY : cv::COLOR_BGR2GRAY);
        else if (rgba) cv::cvtColor(work, enc, cv::COLOR_RGBA2BGR);
        else enc = work;
        const std::vector<int> params = {
            cv::IMWRITE_JPEG_QUALITY, std::clamp(p.jpegQuality, 1, 100),
            cv::IMWRITE_JPEG_OPTIMIZE, 1,
            cv::IMWRITE_JPEG_PROGRESSIVE, 0,
            cv::IMWRITE_JPEG_SAMPLING_FACTOR, cv::IMWRITE_JPEG_SAMPLING_FACTOR_420,
        };
        if (!cv::imencode(".jpg", enc, stream, params)) return false;
        dict += std::string("8 /ColorSpace ") + (gray ? "/DeviceGray" : "/DeviceRGB") +
                " /Filter /DCTDecode";
    }
    if (stream.empty()) return false;

    // 3) Emite imagen y contenido directamente al archivo.
    const int i = (int) pages_.size();
    const int pageObj = 3 + 3 * i, contentObj = pageObj + 1, imageObj = pageObj + 2;
    const float pw = (float) kPageWidthPt, ph = (float) (kPageWidthPt * src.rows / src.cols);

    char cs[96];
    int n = snprintf(cs, sizeof(cs), "q %.2f 0 0 %.2f 0 0 cm /Im0 Do Q\n", pw, ph);
    std::vector<uint8_t> content = deflateBytes((const uint8_t *) cs, (size_t) n);
    if (content.empty()) return false;

    writeObject(imageObj, dict, stream.data(), stream.size());
    writeObject(contentObj, "/Filter /FlateDecode", content.data(), content.size());
    if (!ok_) return false;
    pages_.push_back({pw, ph});
    return true;
}

bool Writer::finish() {
    const int n = (int) pages_.size();
    if (!f_ || !ok_ || n == 0) {
        abort();
        return false;
    }
    char buf[256];

    // Object stream: catálogo, árbol de páginas y cada página.
    std::vector<std::string> objs;
    objs.push_back("<< /Type /Catalog /Pages 2 0 R >>");
    std::string kids;
    for (int i = 0; i < n; i++) kids += std::to_string(3 + 3 * i) + " 0 R ";
    objs.push_back("<< /Type /Pages /Kids [" + kids + "] /Count " + std::to_string(n) + " >>");
    for (int i = 0; i < n; i++) {
        snprintf(buf, sizeof(buf),
                 "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 %.2f %.2f] /Contents %d 0 R "
                 "/Resources << /XObject << /Im0 %d 0 R >> >> >>",
                 pages_[i].width, pages_[i].height, 4 + 3 * i, 5 + 3 * i);
        objs.push_back(buf);
    }
    std::string header, body;
    for (size_t k = 0; k < objs.size(); k++) {
        const int num = k < 2 ? (int) k + 1 : 3 + 3 * ((int) k - 2);
        header += std::to_string(num) + " " + std::to_string(body.size()) + " ";
        body += objs[k] + "\n";
    }
    std::string all = header + "\n" + body;
    std::vector<uint8_t> z = deflateBytes((const uint8_t *) all.data(), all.size());
    const int objStmNum = 3 + 3 * n, xrefNum = objStmNum + 1, size = xrefNum + 1;
    writeObject(objStmNum,
                "/Type /ObjStm /N " + std::to_string(objs.size()) + " /First " +
                    std::to_string(header.size() + 1) + " /Filter /FlateDecode",
                z.data(), z.size());

    // Xref stream, W = [1 4 2].
    offsets_.resize(size, 0);
    const uint64_t xrefPos = pos_;
    offsets_[xrefNum] = xrefPos;
    std::vector<uint8_t> rows((size_t) size * 7, 0);
    auto put = [&](int num, uint8_t type, uint32_t a, uint16_t b) {
        uint8_t *r = &rows[(size_t) num * 7];
        r[0] = type;
        r[1] = (uint8_t) (a >> 24); r[2] = (uint8_t) (a >> 16); r[3] = (uint8_t) (a >> 8); r[4] = (uint8_t) a;
        r[5] = (uint8_t) (b >> 8); r[6] = (uint8_t) b;
    };
    put(0, 0, 0, 0xFFFF);
    put(1, 2, (uint32_t) objStmNum, 0);
    put(2, 2, (uint32_t) objStmNum, 1);
    for (int i = 0; i < n; i++) {
        put(3 + 3 * i, 2, (uint32_t) objStmNum, (uint16_t) (2 + i));
        put(4 + 3 * i, 1, (uint32_t) offsets_[4 + 3 * i], 0);
        put(5 + 3 * i, 1, (uint32_t) offsets_[5 + 3 * i], 0);
    }
    put(objStmNum, 1, (uint32_t) offsets_[objStmNum], 0);
    put(xrefNum, 1, (uint32_t) xrefPos, 0);
    std::vector<uint8_t> zx = deflateBytes(rows.data(), rows.size());
    writeObject(xrefNum,
                "/Type /XRef /Size " + std::to_string(size) + " /W [1 4 2] /Root 1 0 R /Filter /FlateDecode",
                zx.data(), zx.size());
    int len = snprintf(buf, sizeof(buf), "startxref\n%llu\n%%%%EOF\n", (unsigned long long) xrefPos);
    writeRaw(buf, (size_t) len);

    const bool good = ok_ && fclose(f_) == 0;
    f_ = nullptr;
    if (!good) remove(path_.c_str());
    return good;
}

void Writer::abort() {
    if (f_) {
        fclose(f_);
        f_ = nullptr;
    }
    if (!path_.empty()) remove(path_.c_str());
}

}  // namespace pdfc
