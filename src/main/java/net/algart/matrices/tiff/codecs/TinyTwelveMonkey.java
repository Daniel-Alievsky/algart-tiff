/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2024 Daniel Alievsky, AlgART Laboratory (http://algart.net)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package net.algart.matrices.tiff.codecs;

import net.algart.matrices.tiff.TiffIFD;

import java.io.IOException;
import java.io.InputStream;

/**
 * Tiny collection of necessary constants/methods from TwelveMonkey library
 * to provide an ability to use adapted copies of CCITTFaxDecoderStream and CCITTFaxEncoderStream.
 */
class TinyTwelveMonkey {
    private TinyTwelveMonkey() {
    }

    private static final System.Logger LOG = System.getLogger(TinyTwelveMonkey.class.getName());

    static final int COMPRESSION_CCITT_MODIFIED_HUFFMAN_RLE = 2;
    static final int COMPRESSION_CCITT_T4 = 3;
    static final int COMPRESSION_CCITT_T6 = 4;

    static final int GROUP3OPT_2DENCODING = 1;
    static final int GROUP3OPT_UNCOMPRESSED = 2;
    static final int GROUP3OPT_FILLBITS = 4;
    static final int GROUP3OPT_BYTEALIGNED = 8;
    static final int GROUP4OPT_UNCOMPRESSED = 2;
    static final int GROUP4OPT_BYTEALIGNED = 4;

    static final int TAG_GROUP3OPTIONS = 292;
    static final int TAG_GROUP4OPTIONS = 293;

    static final int FILL_LEFT_TO_RIGHT = 1; // normal bits order (76543210)

    static int findCCITTType(final int encodedCompression, final InputStream stream) throws IOException {
        int compressionType = CCITTFaxDecoderStreamAdapted.findCompressionType(encodedCompression, stream);
        if (compressionType != encodedCompression) {
            LOG.log(System.Logger.Level.DEBUG,
                    "Detected compression type %d, does not match encoded compression type: %d".formatted(
                            compressionType, encodedCompression));
        }
        return compressionType;
    }

    public static long getCCITTReadingOptions(TiffIFD ifd, int compression) {
        return switch (compression) {
            case COMPRESSION_CCITT_MODIFIED_HUFFMAN_RLE -> 0L;
            case COMPRESSION_CCITT_T4 -> ifd.optLong(TAG_GROUP3OPTIONS, 0L);
            case COMPRESSION_CCITT_T6 -> ifd.optLong(TAG_GROUP4OPTIONS, 0L);
            default -> throw new IllegalArgumentException("No CCITT options for compression: " + compression);
        };
    }

    public static long getCCITTWritingOptions(TiffIFD ifd, int compression) {
        if (compression != COMPRESSION_CCITT_MODIFIED_HUFFMAN_RLE) {
            return ifd.optLong(compression == COMPRESSION_CCITT_T4 ? TAG_GROUP3OPTIONS : TAG_GROUP4OPTIONS, 0L);
        }
        return 0L;
    }

    static boolean isTrue(final boolean pExpression, final String pMessage) {
        return isTrue(pExpression, pExpression, pMessage);
    }

    static <T> T isTrue(final boolean condition, final T value, final String message) {
        if (!condition) {
            throw new IllegalArgumentException(String.format(message == null ? "expression may not be %s" : message,
                    value));
        }

        return value;
    }

}
