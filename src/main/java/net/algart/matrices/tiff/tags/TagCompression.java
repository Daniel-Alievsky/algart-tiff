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
import net.algart.matrices.tiff.codecs.*;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.util.*;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public enum TagCompression {
    UNCOMPRESSED(1, "Uncompressed", UncompressedCodec::new, null),
    LZW(5, "LZW", LZWCodec::new, null),
    DEFLATE(8, "ZLib-Deflate", ZlibCodec::new, null),
    DEFLATE_PROPRIETARY(32946, "ZLib-Deflate proprietary", ZlibCodec::new, null),
    JPEG(7, "JPEG", JPEGCodec::new, TagCompression::customizeForWritingJpeg),
    JPEG_OLD_STYLE(6, "Old-style JPEG", null, null),
    // - OLD_JPEG does not work: see https://github.com/scifio/scifio/issues/510
    PACK_BITS(32773, "PackBits", PackbitsCodec::new, null),

    JPEG_2000_LOSSLESS(33003, "JPEG-2000", JPEG2000Codec::new,
            TagCompression::customizeForWritingJpeg2000Lossless),
    JPEG_2000_LOSSY(33004, "JPEG-2000 lossy", JPEG2000Codec::new,
            (tile, defaultOptions) -> customizeForWritingJpeg2000(tile, defaultOptions, false)),
    JPEG_2000_LOSSLESS_ALTERNATIVE(33005, "JPEG-2000 alternative", JPEG2000Codec::new,
            TagCompression::customizeForWritingJpeg2000Lossless),
    JPEG_2000_LOSSLESS_OLYMPUS(34712, "JPEG-2000 Olympus", JPEG2000Codec::new,
            TagCompression::customizeForWritingJpeg2000Lossless);

    private static final Map<Integer, TagCompression> LOOKUP =
            Arrays.stream(values()).collect(Collectors.toMap(TagCompression::code, v -> v));

    private static final Map<Integer, TiffCompression> tiffCompressionMap = new HashMap<>();

    static {
        EnumSet.allOf(TiffCompression.class).forEach(v -> tiffCompressionMap.put(v.getCode(), v));
    }

    private final int code;
    private final String name;
    private final Supplier<TiffCodec> codec;
    // - This "extended" codec is implemented inside this module, and we are sure that it does not need SCIFIO context.
    private final BiFunction<TiffTile, TiffCodec.Options, TiffCodec.Options> customizeForWriting;

    TagCompression(
            int code,
            String name,
            Supplier<TiffCodec> codec,
            BiFunction<TiffTile, TiffCodec.Options, TiffCodec.Options> customizeForWriting) {
        this.code = code;
        this.name = Objects.requireNonNull(name);
        this.codec = codec;
        this.customizeForWriting = customizeForWriting;
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

    public boolean isStandard() {
        return code <= 10 || this == TagCompression.PACK_BITS;
        // - actually maximal supported standard compression is DEFLATE=8
    }

    public TiffCodec.Options customizeForWriting(TiffTile tile, TiffCodec.Options defaultOptions) {
        return customizeForWriting == null ? defaultOptions : customizeForWriting.apply(tile, defaultOptions);
    }

    public static TagCompression valueOfCodeOrNull(int code) {
        return LOOKUP.get(code);
    }

    public static TiffCompression compressionOfCodeOrNull(int code) {
        return tiffCompressionMap.get(code);
    }

    public static JPEGCodec.JPEGOptions customizeForWritingJpeg(TiffTile tile, TiffCodec.Options defaultOptions) {
        final JPEGCodec.JPEGOptions result = new JPEGCodec.JPEGOptions().setTo(defaultOptions);
        if (tile.ifd().optInt(Tags.PHOTOMETRIC_INTERPRETATION, -1) ==
                TagPhotometricInterpretation.RGB.code()) {
            result.setPhotometricInterpretation(TagPhotometricInterpretation.RGB);
        }
        return result;
    }

    public static JPEG2000Codec.JPEG2000Options customizeForWritingJpeg2000(
            TiffTile tile,
            TiffCodec.Options defaultOptions,
            boolean lossless) {
        return new JPEG2000Codec.JPEG2000Options().setTo(defaultOptions, lossless);
    }

    private static JPEG2000Codec.JPEG2000Options customizeForWritingJpeg2000Lossless(
            TiffTile tile,
            TiffCodec.Options defaultOptions) {
        return customizeForWritingJpeg2000(tile, defaultOptions, true);
    }
}
