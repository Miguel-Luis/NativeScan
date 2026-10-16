#include "g4_encoder.h"

#include <algorithm>
#include <cstdlib>

namespace g4 {
namespace {

struct Code {
    uint16_t bits;
    uint8_t len;
};

// Cadena "0101..." -> código. Las tablas están escritas tal cual el estándar T.4.
constexpr Code C(const char *s) {
    uint16_t v = 0;
    uint8_t n = 0;
    for (; s[n]; n++) v = (uint16_t) ((v << 1) | (s[n] == '1' ? 1 : 0));
    return {v, n};
}

const Code kWhiteTerm[64] = {
    C("00110101"), C("000111"), C("0111"), C("1000"), C("1011"), C("1100"), C("1110"), C("1111"),
    C("10011"), C("10100"), C("00111"), C("01000"), C("001000"), C("000011"), C("110100"), C("110101"),
    C("101010"), C("101011"), C("0100111"), C("0001100"), C("0001000"), C("0010111"), C("0000011"), C("0000100"),
    C("0101000"), C("0101011"), C("0010011"), C("0100100"), C("0011000"), C("00000010"), C("00000011"), C("00011010"),
    C("00011011"), C("00010010"), C("00010011"), C("00010100"), C("00010101"), C("00010110"), C("00010111"), C("00101000"),
    C("00101001"), C("00101010"), C("00101011"), C("00101100"), C("00101101"), C("00000100"), C("00000101"), C("00001010"),
    C("00001011"), C("01010010"), C("01010011"), C("01010100"), C("01010101"), C("00100100"), C("00100101"), C("01011000"),
    C("01011001"), C("01011010"), C("01011011"), C("01001010"), C("01001011"), C("00110010"), C("00110011"), C("00110100"),
};

const Code kBlackTerm[64] = {
    C("0000110111"), C("010"), C("11"), C("10"), C("011"), C("0011"), C("0010"), C("00011"),
    C("000101"), C("000100"), C("0000100"), C("0000101"), C("0000111"), C("00000100"), C("00000111"), C("000011000"),
    C("0000010111"), C("0000011000"), C("0000001000"), C("00001100111"), C("00001101000"), C("00001101100"),
    C("00000110111"), C("00000101000"), C("00000010111"), C("00000011000"), C("000011001010"), C("000011001011"),
    C("000011001100"), C("000011001101"), C("000001101000"), C("000001101001"), C("000001101010"), C("000001101011"),
    C("000011010010"), C("000011010011"), C("000011010100"), C("000011010101"), C("000011010110"), C("000011010111"),
    C("000001101100"), C("000001101101"), C("000011011010"), C("000011011011"), C("000001010100"), C("000001010101"),
    C("000001010110"), C("000001010111"), C("000001100100"), C("000001100101"), C("000001010010"), C("000001010011"),
    C("000000100100"), C("000000110111"), C("000000111000"), C("000000100111"), C("000000101000"), C("000001011000"),
    C("000001011001"), C("000000101011"), C("000000101100"), C("000001011010"), C("000001100110"), C("000001100111"),
};

// Índice k = longitud / 64 - 1 (64..1728).
const Code kWhiteMakeup[27] = {
    C("11011"), C("10010"), C("010111"), C("0110111"), C("00110110"), C("00110111"), C("01100100"),
    C("01100101"), C("01101000"), C("01100111"), C("011001100"), C("011001101"), C("011010010"),
    C("011010011"), C("011010100"), C("011010101"), C("011010110"), C("011010111"), C("011011000"),
    C("011011001"), C("011011010"), C("011011011"), C("010011000"), C("010011001"), C("010011010"),
    C("011000"), C("010011011"),
};

const Code kBlackMakeup[27] = {
    C("0000001111"), C("000011001000"), C("000011001001"), C("000001011011"), C("000000110011"),
    C("000000110100"), C("000000110101"), C("0000001101100"), C("0000001101101"), C("0000001001010"),
    C("0000001001011"), C("0000001001100"), C("0000001001101"), C("0000001110010"), C("0000001110011"),
    C("0000001110100"), C("0000001110101"), C("0000001110110"), C("0000001110111"), C("0000001010010"),
    C("0000001010011"), C("0000001010100"), C("0000001010101"), C("0000001011010"), C("0000001011011"),
    C("0000001100100"), C("0000001100101"),
};

// 1792..2560 en pasos de 64 (comunes a blanco y negro).
const Code kExtMakeup[13] = {
    C("00000001000"), C("00000001100"), C("00000001101"), C("000000010010"), C("000000010011"),
    C("000000010100"), C("000000010101"), C("000000010110"), C("000000010111"), C("000000011100"),
    C("000000011101"), C("000000011110"), C("000000011111"),
};

class BitWriter {
public:
    explicit BitWriter(std::vector<uint8_t> &out) : out_(out) {}

    void put(Code c) {
        acc_ = (acc_ << c.len) | c.bits;
        n_ += c.len;
        while (n_ >= 8) {
            out_.push_back((uint8_t) (acc_ >> (n_ - 8)));
            n_ -= 8;
        }
        acc_ &= (1u << n_) - 1;
    }

    void flush() {
        if (n_ > 0) {
            out_.push_back((uint8_t) (acc_ << (8 - n_)));
            n_ = 0;
            acc_ = 0;
        }
    }

private:
    std::vector<uint8_t> &out_;
    uint32_t acc_ = 0;
    int n_ = 0;
};

void putRun(BitWriter &w, int len, bool black) {
    while (len >= 2560) {
        w.put(kExtMakeup[12]);
        len -= 2560;
    }
    if (len >= 1792) {
        int k = len / 64;  // 28..39
        w.put(kExtMakeup[k - 28]);
        len -= k * 64;
    } else if (len >= 64) {
        int k = len / 64;  // 1..27
        w.put((black ? kBlackMakeup : kWhiteMakeup)[k - 1]);
        len -= k * 64;
    }
    w.put((black ? kBlackTerm : kWhiteTerm)[len]);
}

// Posiciones de cambio de color de una fila (la primera es blanco->negro), terminadas en 4 centinelas.
void transitions(const uint8_t *row, int width, std::vector<int> &t) {
    t.clear();
    uint8_t prev = 0;
    for (int x = 0; x < width; x++) {
        uint8_t cur = row[x] ? 1 : 0;
        if (cur != prev) {
            t.push_back(x);
            prev = cur;
        }
    }
    for (int i = 0; i < 4; i++) t.push_back(width);
}

}  // namespace

std::vector<uint8_t> encode(const uint8_t *pix, int width, int rows, int stride) {
    std::vector<uint8_t> out;
    out.reserve((size_t) width * rows / 40 + 64);
    BitWriter w(out);

    std::vector<int> ref, cur;
    ref.assign(4, width);  // fila de referencia imaginaria toda blanca

    for (int y = 0; y < rows; y++) {
        transitions(pix + (size_t) y * stride, width, cur);
        int a0 = -1;
        int color = 0;  // 0 = blanco, 1 = negro
        size_t ci = 0, ri = 0;
        while (a0 < width) {
            while (ci < cur.size() - 2 && (cur[ci] <= a0 || (int) (ci & 1) != color)) ci++;
            while (ri > 0 && ref[ri - 1] > a0) ri--;
            while (ri < ref.size() - 2 && (ref[ri] <= a0 || (int) (ri & 1) != color)) ri++;
            const int a1 = cur[ci];
            const int b1 = ref[ri];
            const int b2 = ref[ri + 1];

            if (b2 < a1) {
                w.put(C("0001"));  // pass
                a0 = b2;
            } else if (std::abs(a1 - b1) <= 3) {
                switch (a1 - b1) {
                    case 0: w.put(C("1")); break;
                    case 1: w.put(C("011")); break;
                    case 2: w.put(C("000011")); break;
                    case 3: w.put(C("0000011")); break;
                    case -1: w.put(C("010")); break;
                    case -2: w.put(C("000010")); break;
                    default: w.put(C("0000010")); break;
                }
                a0 = a1;
                color ^= 1;
            } else {
                const int a2 = cur[ci + 1];
                w.put(C("001"));
                putRun(w, a1 - std::max(a0, 0), color == 1);
                putRun(w, a2 - a1, color == 0);
                a0 = a2;
            }
        }
        ref.swap(cur);
    }

    w.put(C("000000000001"));  // EOFB = 2 x EOL
    w.put(C("000000000001"));
    w.flush();
    return out;
}

}  // namespace g4
