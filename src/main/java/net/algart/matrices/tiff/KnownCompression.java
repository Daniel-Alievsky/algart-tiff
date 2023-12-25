/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2023 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import io.scif.codec.*;
import io.scif.formats.tiff.TiffCompression;
import net.algart.matrices.tiff.codecs.JPEG2000Codec;
import net.algart.matrices.tiff.codecs.JPEGCodec;
import net.algart.matrices.tiff.tiles.TiffTile;
import org.scijava.Context;

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
    UNCOMPRESSED(TiffCompression.UNCOMPRESSED, PassthroughCodec::new, null,
            KnownCompression::writeOptionsStandard),
    LZW(TiffCompression.LZW, LZWCodec::new, null, KnownCompression::writeOptionsStandard),
    DEFLATE(TiffCompression.DEFLATE, ZlibCodec::new, null, KnownCompression::writeOptionsStandard),
    JPEG(TiffCompression.JPEG, null, JPEGCodec::new, KnownCompression::writeOptionsStandard),
    // OLD_JPEG(TiffCompression.OLD_JPEG, null, ExtendedJPEGCodec::new, true),
    // - OLD_JPEG does not work: see https://github.com/scifio/scifio/issues/510
    PACK_BITS(TiffCompression.PACK_BITS, PackbitsCodec::new, null, KnownCompression::writeOptionsStandard),

    JPEG_2000(TiffCompression.JPEG_2000, null, JPEG2000Codec::new,
            KnownCompression::writeJpeg200Options),
    JPEG_2000_LOSSY(TiffCompression.JPEG_2000_LOSSY, null, JPEG2000Codec::new,
            KnownCompression::writeJpeg200NotLossLessOptions),
    ALT_JPEG_2000(TiffCompression.ALT_JPEG2000, null, JPEG2000Codec::new,
            KnownCompression::writeJpeg200Options),
    OLYMPUS_JPEG2000(TiffCompression.OLYMPUS_JPEG2000, null, JPEG2000Codec::new,
            KnownCompression::writeJpeg200Options),
    NIKON(TiffCompression.NIKON, null, null, KnownCompression::writeOptionsStandard),
    LURAWAVE(TiffCompression.LURAWAVE, null, null, KnownCompression::writeOptionsStandard);

    private static final Map<Integer, TiffCompression> tiffCompressionMap = new HashMap<>();
    static {
        EnumSet.allOf(TiffCompression.class).forEach(v -> tiffCompressionMap.put(v.getCode(), v));
    }

    private final TiffCompression compression;
    private final Supplier<Codec> noContext;
    private final Supplier<Codec> extended;
    private final BiFunction<TiffTile, CodecOptions, CodecOptions> writeOptions;

    KnownCompression(
            TiffCompression compression,
            Supplier<Codec> noContext,
            Supplier<Codec> extended,
            BiFunction<TiffTile, CodecOptions, CodecOptions> writeOptions) {
        this.compression = Objects.requireNonNull(compression);
        this.noContext = noContext;
        this.extended = extended;
        this.writeOptions = Objects.requireNonNull(writeOptions);
    }

    public TiffCompression compression() {
        return compression;
    }

    public Codec noContextCodec() {
        return noContext == null ? null : noContext.get();
    }

    /**
     * Extended codec (must be able to work without context).
     */
    public Codec extendedCodec() {
        return extended == null ? null : extended.get();
    }

    public CodecOptions writeOptions(TiffTile tile, CodecOptions defaultOptions) {
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

    public static CodecOptions writeOptionsStandard(TiffTile tile, CodecOptions defaultOptions) {
        Objects.requireNonNull(tile, "Null tile");
        final CodecOptions options = new CodecOptions(
                defaultOptions == null ? CodecOptions.getDefaultOptions() : defaultOptions);
        options.width = tile.getSizeX();
        options.height = tile.getSizeY();
        options.bitsPerSample = 8 * tile.bytesPerSample();
        options.channels = tile.samplesPerPixel();
        options.littleEndian = tile.isLittleEndian();
        options.interleaved = true;
        options.signed = false;
        return options;
    }

    public static JPEG2000CodecOptions writeJpeg200Options(TiffTile tile, CodecOptions defaultOptions) {
        final CodecOptions options = writeOptionsStandard(tile, defaultOptions);
        options.lossless = true;
        final JPEG2000CodecOptions result = JPEG2000CodecOptions.getDefaultOptions(options);
        if (defaultOptions instanceof JPEG2000CodecOptions options2000) {
            result.numDecompositionLevels = options2000.numDecompositionLevels;
            result.resolution = options2000.resolution;
            if (options2000.codeBlockSize != null) {
                result.codeBlockSize = options2000.codeBlockSize;
            }
            if (options2000.quality > 0.0) {
                result.quality = options2000.quality;
            }
        }
        return result;
    }

    public static JPEG2000CodecOptions writeJpeg200NotLossLessOptions(TiffTile tile, CodecOptions defaultOptions) {
        final JPEG2000CodecOptions result = writeJpeg200Options(tile, defaultOptions);
        result.lossless = false;
        return result;
    }
}
