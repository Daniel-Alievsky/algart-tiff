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
    UNCOMPRESSED(TiffCompression.UNCOMPRESSED, UncompressedCodec::new, KnownCompression::standardWriteOptions),
    LZW(TiffCompression.LZW, LZWCodec::new, KnownCompression::standardWriteOptions),
    DEFLATE(TiffCompression.DEFLATE, ZlibCodec::new, KnownCompression::standardWriteOptions),
    PROPRIETARY_DEFLATE(TiffCompression.PROPRIETARY_DEFLATE,
            ZlibCodec::new, KnownCompression::standardWriteOptions),
    JPEG(TiffCompression.JPEG, JPEGCodec::new, KnownCompression::jpegWriteOptions),
    // OLD_JPEG(TiffCompression.OLD_JPEG, ExtendedJPEGCodec::new, true),
    // - OLD_JPEG does not work: see https://github.com/scifio/scifio/issues/510
    PACK_BITS(TiffCompression.PACK_BITS, PackbitsCodec::new, KnownCompression::standardWriteOptions),

    JPEG_2000_LOSSLESS(TiffCompression.JPEG_2000, JPEG2000Codec::new,
            KnownCompression::jpeg2000LosslessWriteOptions),
    JPEG_2000_LOSSLESS_ALTERNATIVE(TiffCompression.ALT_JPEG2000, JPEG2000Codec::new,
            KnownCompression::jpeg2000LosslessWriteOptions),
    JPEG_2000_LOSSLESS_OLYMPUS(TiffCompression.OLYMPUS_JPEG2000, JPEG2000Codec::new,
            KnownCompression::jpeg2000LosslessWriteOptions),
    JPEG_2000_NORMAL(TiffCompression.JPEG_2000_LOSSY, JPEG2000Codec::new,
            (tile, defaultOptions) -> jpeg2000WriteOptions(tile, defaultOptions, false)),

    NIKON(TiffCompression.NIKON, null, KnownCompression::standardWriteOptions),
    LURAWAVE(TiffCompression.LURAWAVE, null, KnownCompression::standardWriteOptions);

    private static final Map<Integer, TiffCompression> tiffCompressionMap = new HashMap<>();

    static {
        EnumSet.allOf(TiffCompression.class).forEach(v -> tiffCompressionMap.put(v.getCode(), v));
    }

    private final TiffCompression compression;
    private final Supplier<TiffCodec> extended;
    // - This "extended" codec is implemented inside this module, and we are sure that it does not need SCIFIO context.
    private final BiFunction<TiffTile, TiffCodec.Options, TiffCodec.Options> writeOptions;

    KnownCompression(
            TiffCompression compression,
            Supplier<TiffCodec> extended,
            BiFunction<TiffTile, TiffCodec.Options, TiffCodec.Options> writeOptions) {
        this.compression = Objects.requireNonNull(compression);
        this.extended = extended;
        this.writeOptions = Objects.requireNonNull(writeOptions);
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

    public TiffCodec.Options writeOptions(TiffTile tile, TiffCodec.Options defaultOptions) {
        return writeOptions.apply(tile, defaultOptions);
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

    public static TiffCodec.Options standardWriteOptions(TiffTile tile, TiffCodec.Options defaultOptions) {
        Objects.requireNonNull(tile, "Null tile");
        final TiffCodec.Options options = new TiffCodec.Options();
        if (defaultOptions != null) {
            options.setTo(defaultOptions);
        }
        options.setWidth(tile.getSizeX());
        options.setHeight(tile.getSizeY());
        options.setBitsPerSample(8 * tile.bytesPerSample());
        options.setNumberOfChannels(tile.samplesPerPixel());
        options.setLittleEndian(tile.isLittleEndian());
        options.setInterleaved(true);
        options.setSigned(false);
        return options;
    }

    public static JPEGCodec.JPEGOptions jpegWriteOptions(TiffTile tile, TiffCodec.Options defaultOptions) {
        final TiffCodec.Options options = standardWriteOptions(tile, defaultOptions);
        final JPEGCodec.JPEGOptions result = JPEGCodec.JPEGOptions.getDefaultOptions(options);
        if (result.getQuality() > 1.0) {
            // - for JPEG, maximal possible quality is 1.0
            // (for comparison, maximal quality in JPEG-2000 is Double.MAX_VALUE)
            result.setQuality(1.0);
        }
        if (tile.ifd().optInt(Tags.PHOTOMETRIC_INTERPRETATION, -1) ==
                TagPhotometricInterpretation.RGB.code()) {
            result.setPhotometricInterpretation(TagPhotometricInterpretation.RGB);
        }
        return result;
    }

    public static JPEG2000Codec.JPEG2000Options jpeg2000LosslessWriteOptions(TiffTile tile, TiffCodec.Options defaultOptions) {
        return jpeg2000WriteOptions(tile, defaultOptions, true);
    }

    public static JPEG2000Codec.JPEG2000Options jpeg2000WriteOptions(
            TiffTile tile,
            TiffCodec.Options defaultOptions,
            boolean lossless) {
        final TiffCodec.Options options = standardWriteOptions(tile, defaultOptions);
        final JPEG2000Codec.JPEG2000Options result = JPEG2000Codec.JPEG2000Options.getDefaultOptions(options, lossless);
        if (defaultOptions instanceof JPEG2000Codec.JPEG2000Options options2000) {
            result.setNumDecompositionLevels(options2000.getNumDecompositionLevels());
            result.setResolution(options2000.getResolution());
            if (options2000.getCodeBlockSize() != null) {
                result.setCodeBlockSize(options2000.getCodeBlockSize());
            }
            if (options2000.getQuality() > 0.0) {
                // - i.e. if it is specified
                result.setQuality(options2000.getQuality());
            }
        }
        return result;
    }
}
