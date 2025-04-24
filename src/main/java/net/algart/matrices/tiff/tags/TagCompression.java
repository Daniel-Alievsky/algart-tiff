/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2025 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.matrices.tiff.tags;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.codecs.*;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Possible values (compression types) for TIFF Compression Tag (259).
 */
public enum TagCompression {
    /**
     * Uncompressed TIFF image (type 1).
     */
    NONE(TiffIFD.COMPRESSION_NONE, "Uncompressed", UncompressedCodec::new),

    /**
     * CCITT RLE: Modified Huffman compression (type 2).
     * For binary images only (1 sample/pixel, 1 bit/sample).
     */
    CCITT_MODIFIED_HUFFMAN_RLE(TiffIFD.COMPRESSION_CCITT_MODIFIED_HUFFMAN_RLE,
            "CCITT Modified Huffman RLE compression", CCITTFaxCodec::new),

    /**
     * CCITT T.4: Bi-level encoding/Group 3 facsimile compression (type 3).
     * For binary images only (1 sample/pixel, 1 bit/sample).
     */
    CCITT_T4(TiffIFD.COMPRESSION_CCITT_T4, "CCITT T.4/Group 3 Fax compression", CCITTFaxCodec::new),

    /**
     * CCITT T.6: Bi-level encoding/Group 4 facsimile compression (type 4).
     * For binary images only (1 sample/pixel, 1 bit/sample).
     */
    CCITT_T6(TiffIFD.COMPRESSION_CCITT_T6, "CCITT T.6/Group 4 Fax compression", CCITTFaxCodec::new),

    /**
     * LZW compression (type 5).
     */
    LZW(TiffIFD.COMPRESSION_LZW, "LZW", LZWCodec::new),

    /**
     * "Old-style" (obsolete) JPEG compression (type 6).
     * Not supported in the current version.
     */
    OLD_JPEG(TiffIFD.COMPRESSION_OLD_JPEG, "Old-style JPEG", null),

    /**
     * JPEG compression (type 7).
     */
    JPEG(TiffIFD.COMPRESSION_JPEG, "JPEG", JPEGCodec::new) {
        @Override
        public TiffCodec.Options customizeReading(TiffTile tile, TiffCodec.Options options) throws TiffException {
            return customizeReadingJpeg(tile, options);
        }

        @Override
        public TiffCodec.Options customizeWriting(TiffTile tile, TiffCodec.Options options) throws TiffException {
            return customizeWritingJpeg(tile, options);
        }
    },

    /**
     * Zlib deflate compression (ZIP), compatible with ZLib and {@link java.util.zip.DeflaterOutputStream} (type 8).
     */
    DEFLATE(TiffIFD.COMPRESSION_DEFLATE, "ZLib-Deflate", DeflateCodec::new),

    /**
     * Deflate compression, equivalent to "{@link #DEFLATE Zlib deflate}"
     * but with another value 32946 in the Compression tag (type 32946).
     * See Oracle's document "TIFF Metadata Format Specification and Usage Notes":
     *
     * <blockquote>
     *     ZLib and Deflate compression are identical except for the value of the TIFF Compression field:
     *     for ZLib the Compression field has value 8 whereas for Deflate it has value 32946 (0x80b2).
     *     In both cases each image segment (strip or tile) is written as a single complete zlib data stream.
     * </blockquote>
     */
    DEFLATE_PROPRIETARY(TiffIFD.COMPRESSION_DEFLATE_PROPRIETARY, "ZLib-Deflate (32946)", DeflateCodec::new),

    /**
     * PackBits run-length compression (type 32773).
     * Oriented for binary or byte images, but can be used for any bit depth.
     */
    PACK_BITS(TiffIFD.COMPRESSION_PACK_BITS, "PackBits", PackBitsCodec::new),

    /**
     * JPEG-2000 Aperio lossless compression (type 33003).
     *
     * <p>Note that while writing TIFF in this format, {@link net.algart.matrices.tiff.TiffWriter}
     * does not try to use YCbCr encoding,
     * as Aperio recommends for type 33003.
     */
    JPEG_2000_LOSSLESS(33003, "JPEG-2000 lossless", JPEG2000Codec::new),

    /**
     * JPEG-2000 Aperio lossy compression (type 33004).
     */
    JPEG_2000(33004, "JPEG-2000 lossy", JPEG2000Codec::new),

    /**
     * JPEG-2000 Aperio lossless compression for RGB (type 33005).
     */
    JPEG_2000_LOSSLESS_ALTERNATIVE(33005, "JPEG-2000 lossless alternative", JPEG2000Codec::new),

    /**
     * JPEG-2000 Olympus lossless compression (type 34712).
     */
    JPEG_2000_LOSSLESS_OLYMPUS(34712, "JPEG-2000 lossless Olympus", JPEG2000Codec::new);

    private static final Map<Integer, TagCompression> LOOKUP =
            Arrays.stream(values()).collect(Collectors.toMap(TagCompression::code, v -> v));

    private final int code;
    private final String name;
    private final Supplier<TiffCodec> codec;

    TagCompression(int code, String name, Supplier<TiffCodec> codec) {
        this.code = code;
        this.name = Objects.requireNonNull(name);
        this.codec = codec;
    }

    public static TagCompression ofOrNull(int code) {
        return LOOKUP.get(code);
    }

    public static String toPrettyString(int code) {
        final TagCompression compression = ofOrNull(code);
        return "type " + code + (compression == null ? "" : ": \"" + compression.prettyName() + "\"");
    }

    public int code() {
        return code;
    }

    public String prettyName() {
        return name;
    }

    /**
     * Extended codec (must be able to work without context).
     */
    public TiffCodec codec() {
        return codec == null ? null : codec.get();
    }

    public boolean isJpeg() {
        return this == JPEG || this == OLD_JPEG;
    }

    public boolean isJpeg2000() {
        return this == JPEG_2000_LOSSLESS || this == JPEG_2000_LOSSLESS_ALTERNATIVE ||
                this == JPEG_2000_LOSSLESS_OLYMPUS || this == JPEG_2000;
    }

    public boolean isJpeg2000Lossy() {
        return this == JPEG_2000;
    }

    public boolean isStandard() {
        return code <= 10 || this == PACK_BITS;
        // - actually, the maximal supported standard compression is DEFLATE=8
    }

    /**
     * Should return <code>true</code> if the format of compressed data can depend on the byte order in the entire
     * TIFF file ({@link TiffCodec.Options#isLittleEndian()}) even in the case when we have 8 or fewer bits per sample,
     * such as in typical 8-bit JPEG RGB or YCbCr images.
     *
     * This is highly unusual and not common in standard TIFF usage,
     * but theoretically, a codec might use this metadata when encoding control or auxiliary information.
     *
     * @return whether the byte order may affect encoding in the case of byte-sized or binary samples;
     *         usually <code>false</code>.
     */
    public boolean canUseByteOrderForByteData() {
        return isJpeg2000();
        // - probably even JPEG-2000 does not depend on it for byte samples, but
        // for future compatibility we prefer to return <code>true</code> here
    }

    public TiffCodec.Options customizeReading(TiffTile tile, TiffCodec.Options options) throws TiffException {
        return options;
    }

    public TiffCodec.Options customizeWriting(TiffTile tile, TiffCodec.Options options) throws TiffException {
        if (isJpeg2000()) {
            return customizeWritingJpeg2000(tile, options, !isJpeg2000Lossy());
        }
        return options;
    }


    // Note: corrections, performed by this method, may be tested with the image jpeg_ycbcr_encoded_as_rgb.tiff
    public static TiffCodec.Options customizeReadingJpeg(TiffTile tile, TiffCodec.Options options)
            throws TiffException {
        TiffIFD ifd = tile.ifd();
        return new JPEGCodec.JPEGOptions()
                .setPhotometricInterpretation(ifd.getPhotometricInterpretation())
                .setYCbCrSubsampling(ifd.getYCbCrSubsampling())
                .setInterleaved(false);
        // JPEGCodec works faster in with non-interleaved data, and in any case, it is better
        // because TiffReader needs non-interleaved results.
    }

    @SuppressWarnings("RedundantThrows")
    public static TiffCodec.Options customizeWritingJpeg(TiffTile tile, TiffCodec.Options options)
            throws TiffException {
        final JPEGCodec.JPEGOptions result = new JPEGCodec.JPEGOptions().setTo(options);
        if (tile.ifd().optInt(Tags.PHOTOMETRIC_INTERPRETATION, -1) ==
                TagPhotometricInterpretation.RGB.code()) {
            result.setPhotometricInterpretation(TagPhotometricInterpretation.RGB);
        }
        return result;
    }

    public static JPEG2000Codec.JPEG2000Options customizeWritingJpeg2000(
            TiffTile tile,
            TiffCodec.Options defaultOptions,
            boolean lossless) {
        return new JPEG2000Codec.JPEG2000Options().setTo(defaultOptions, lossless);
    }
}
