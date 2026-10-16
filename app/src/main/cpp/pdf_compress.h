// Generador de PDF comprimido: remuestreo + JPEG (color/gris) o CCITT G4 (1 bpp), escrito en streaming.
#pragma once

#include <opencv2/core.hpp>
#include <cstdint>
#include <cstdio>
#include <string>
#include <vector>

namespace pdfc {

struct PageParams {
    bool bilevel = false;   // true: umbraliza y codifica a 1 bpp (CCITT G4)
    int dpi = 200;          // resolución objetivo (nunca se amplía)
    int jpegQuality = 80;   // 1..100, solo color / gris
};

/**
 * Escribe un PDF 1.5 página a página: los flujos de imagen ya comprimidos van directo al archivo y
 * solo se mantiene en RAM la página en curso. Catálogo, páginas y tabla de referencias cruzadas se
 * emiten al final en un object stream y un xref stream, ambos con FlateDecode.
 */
class Writer {
public:
    ~Writer();

    bool open(const std::string &path);

    /** [src] es RGBA (4 canales) o BGR (3 canales), 8 bits. No se modifica ni se copia. */
    bool addPage(const cv::Mat &src, const PageParams &p);

    /** Cierra el documento. Falso si no hay páginas o falló la escritura. */
    bool finish();

    /** Cierra y borra el archivo parcial. */
    void abort();

    int pageCount() const { return (int) pages_.size(); }

private:
    struct PageInfo {
        float width, height;  // en puntos
    };

    bool writeObject(int num, const std::string &dict, const uint8_t *data, size_t len);
    bool writeRaw(const void *data, size_t len);

    FILE *f_ = nullptr;
    std::string path_;
    uint64_t pos_ = 0;
    bool ok_ = true;
    std::vector<uint64_t> offsets_;  // por número de objeto (0 = sin usar)
    std::vector<PageInfo> pages_;
};

}  // namespace pdfc
