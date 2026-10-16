// Codificador CCITT Group 4 (T.6 / Fax 4) para imágenes de 1 bit. Sin dependencias.
#pragma once

#include <cstdint>
#include <vector>

namespace g4 {

/**
 * Codifica [rows] filas de [width] píxeles. [pix] tiene un byte por píxel (0 = blanco, !=0 = negro),
 * fila a fila con paso [stride]. El resultado es el flujo T.6 con EOFB, listo para /CCITTFaxDecode
 * con /K -1 y /BlackIs1 false.
 */
std::vector<uint8_t> encode(const uint8_t *pix, int width, int rows, int stride);

}  // namespace g4
