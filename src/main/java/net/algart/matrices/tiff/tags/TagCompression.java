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

package net.algart.matrices.tiff.tags;

import io.scif.formats.tiff.TiffCompression;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.codecs.*;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public enum TagCompression {
    UNCOMPRESSED(1, "Uncompressed", UncompressedCodec::new),
    LZW(5, "LZW", LZWCodec::new),
    DEFLATE(8, "ZLib-Deflate", ZlibCodec::new),
    DEFLATE_PROPRIETARY(32946, "ZLib-Deflate proprietary", ZlibCodec::new),
    JPEG(7, "JPEG", JPEGCodec::new) {
        @Override
        public TiffCodec.Options customizeReading(TiffTile tile, TiffCodec.Options options) throws TiffException {
            return customizeReadingJpeg(tile, options);
        }

        @Override
        public TiffCodec.Options customizeWriting(TiffTile tile, TiffCodec.Options options) throws TiffException {
            return customizeWritingJpeg(tile, options);
        }
    },
    JPEG_OLD_STYLE(6, "Old-style JPEG", null),
    // - OLD_JPEG does not work: see https://github.com/scifio/scifio/issues/510
    PACK_BITS(32773, "PackBits", PackbitsCodec::new),

    JPEG_2000_LOSSLESS(33003, "JPEG-2000", JPEG2000Codec::new),
    JPEG_2000_LOSSY(33004, "JPEG-2000 lossy", JPEG2000Codec::new),
    JPEG_2000_LOSSLESS_ALTERNATIVE(33005, "JPEG-2000 alternative", JPEG2000Codec::new),
    JPEG_2000_LOSSLESS_OLYMPUS(34712, "JPEG-2000 Olympus", JPEG2000Codec::new);

    private static final Map<Integer, TagCompression> LOOKUP =
            Arrays.stream(values()).collect(Collectors.toMap(TagCompression::code, v -> v));

    private static final Map<Integer, TiffCompression> tiffCompressionMap = new HashMap<>();

    static {
        EnumSet.allOf(TiffCompression.class).forEach(v -> tiffCompressionMap.put(v.getCode(), v));
    }

    private final int code;
    private final String name;
    private final Supplier<TiffCodec> codec;

    TagCompression(int code, String name, Supplier<TiffCodec> codec) {
        this.code = code;
        this.name = Objects.requireNonNull(name);
        this.codec = codec;
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
        return this == JPEG || this == JPEG_OLD_STYLE;
    }

    public boolean isJpeg2000() {
        return this == JPEG_2000_LOSSLESS || this == JPEG_2000_LOSSLESS_ALTERNATIVE ||
                this == JPEG_2000_LOSSLESS_OLYMPUS || this == JPEG_2000_LOSSY;
    }

    public boolean isJpeg2000Lossy() {
        return this == JPEG_2000_LOSSY;
    }

    public boolean isStandard() {
        return code <= 10 || this == PACK_BITS;
        // - actually maximal supported standard compression is DEFLATE=8
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

    public static TagCompression valueOfCodeOrNull(int code) {
        return LOOKUP.get(code);
    }

    public static TiffCompression compressionOfCodeOrNull(int code) {
        return tiffCompressionMap.get(code);
    }

    // Note: corrections, performed by this method, may be tested with the image jpeg_ycbcr_encoded_as_rgb.tiff
    public static TiffCodec.Options customizeReadingJpeg(TiffTile tile, TiffCodec.Options options)
            throws TiffException {
        TiffIFD ifd = tile.ifd();
        return new JPEGCodec.JPEGOptions()
                .setPhotometricInterpretation(ifd.getPhotometricInterpretation())
                .setYCbCrSubsampling(ifd.getYCbCrSubsampling())
                .setInterleaved(false);
        // JPEGCodec works faster in with non-interleaved data, and in any case it is better
        // because TiffReader needs non-interleaved results.
    }

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
