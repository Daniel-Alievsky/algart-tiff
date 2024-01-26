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

package net.algart.matrices.tiff;

import io.scif.formats.tiff.TiffCompression;
import net.algart.matrices.tiff.codecs.*;
import net.algart.matrices.tiff.tags.TagPhotometricInterpretation;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * <p>{@link TiffWriter} tries to write only compressions from this list.
 * But some of these codecs actually do not support writing and throw an exception while attempt to write.
 *
 * <p>{@link TiffReader} tries to read any compression, but may use special logic for compressions from this list.
 */
enum KnownCompression {
    UNCOMPRESSED(TiffCompression.UNCOMPRESSED, UncompressedCodec::new, null),
    LZW(TiffCompression.LZW, LZWCodec::new, null),
    DEFLATE(TiffCompression.DEFLATE, ZlibCodec::new, null),
    PROPRIETARY_DEFLATE(TiffCompression.PROPRIETARY_DEFLATE, ZlibCodec::new, null),
    JPEG(TiffCompression.JPEG, JPEGCodec::new, KnownCompression::customizeForWritingJpeg),
    // OLD_JPEG(TiffCompression.OLD_JPEG, ExtendedJPEGCodec::new, true),
    // - OLD_JPEG does not work: see https://github.com/scifio/scifio/issues/510
    PACK_BITS(TiffCompression.PACK_BITS, PackbitsCodec::new, null),

    JPEG_2000_LOSSLESS(TiffCompression.JPEG_2000, JPEG2000Codec::new,
            KnownCompression::customizeForWritingJpeg2000Lossless),
    JPEG_2000_LOSSLESS_ALTERNATIVE(TiffCompression.ALT_JPEG2000, JPEG2000Codec::new,
            KnownCompression::customizeForWritingJpeg2000Lossless),
    JPEG_2000_LOSSLESS_OLYMPUS(TiffCompression.OLYMPUS_JPEG2000, JPEG2000Codec::new,
            KnownCompression::customizeForWritingJpeg2000Lossless),
    JPEG_2000_NORMAL(TiffCompression.JPEG_2000_LOSSY, JPEG2000Codec::new,
            (tile, defaultOptions) -> customizeForWritingJpeg2000(tile, defaultOptions, false)),

    NIKON(TiffCompression.NIKON, null, null),
    LURAWAVE(TiffCompression.LURAWAVE, null, null);

    private static final Map<Integer, TiffCompression> tiffCompressionMap = new HashMap<>();

    static {
        EnumSet.allOf(TiffCompression.class).forEach(v -> tiffCompressionMap.put(v.getCode(), v));
    }

    private final TiffCompression compression;
    private final Supplier<TiffCodec> extended;
    // - This "extended" codec is implemented inside this module, and we are sure that it does not need SCIFIO context.
    private final BiFunction<TiffTile, TiffCodec.Options, TiffCodec.Options> customizeForWriting;

    KnownCompression(
            TiffCompression compression,
            Supplier<TiffCodec> extended,
            BiFunction<TiffTile, TiffCodec.Options, TiffCodec.Options> customizeForWriting) {
        this.compression = Objects.requireNonNull(compression);
        this.extended = extended;
        this.customizeForWriting = customizeForWriting;
    }

    public TiffCompression compression() {
        return compression;
    }

    /**
     * Extended codec (must be able to work without context).
     */
    public TiffCodec extendedCodec() {
        return extended == null ? null : extended.get();
    }

    public TiffCodec.Options customizeForWriting(TiffTile tile, TiffCodec.Options defaultOptions) {
        return customizeForWriting == null ? defaultOptions : customizeForWriting.apply(tile, defaultOptions);
    }

    public static TiffCompression compressionOfCodeOrNull(int code) {
        return tiffCompressionMap.get(code);
    }

    public static KnownCompression valueOfOrNull(TiffCompression compression) {
        Objects.requireNonNull(compression, "Null compression");
        for (KnownCompression value : values()) {
            if (value.compression == compression) {
                return value;
            }
        }
        return null;
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
