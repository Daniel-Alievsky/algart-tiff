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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

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
    JPEG(TiffIFD.COMPRESSION_JPEG, "JPEG", JPEGCodec::new),

    /**
     * The same compression code as in {@link #JPEG},
     * but the default photometric interpretation (when it is not explicitly specified)
     * is set to {@link TagPhotometricInterpretation#RGB}.
     * In comparison, {@link #JPEG} variant uses {@link TagPhotometricInterpretation#Y_CB_CR}
     * as the default color space: this is more typical for JPEG format.
     *
     * <p>Note: if the photometric interpretation is already set in {@link TiffIFD}, for example, using
     * {@link TiffIFD#putPhotometricInterpretation(TagPhotometricInterpretation)} method,
     * behavior of this variant and {@link #JPEG} is identical.
     * This option makes sense when the IFD does not contain this tag,
     * and we need to choose the default photometric interpretation.
     * This is typically useful when creating a new IFD image with help of {@link net.algart.matrices.tiff.TiffWriter}
     * on the base of a newly created IFD.
     *
     * <p>This compression never appears while reading TIFF by {@link net.algart.matrices.tiff.TiffReader},
     * but can be useful while writing by {@link net.algart.matrices.tiff.TiffWriter}.</p>
     *
     * <p>This compression is ignored (equivalent to {@link #JPEG}) if the TIFF writer
     * is {@link net.algart.matrices.tiff.TiffWriter#setEnforceUseExternalCodec(boolean)
     * enforced to use external codec}.
     */
    JPEG_RGB(TiffIFD.COMPRESSION_JPEG, "JPEG (RGB)", JPEGCodec::new),

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
     * JPEG-2000 standard compression (type 34712).
     * Default quality is chosen as for lossy JPEG-2000 formats
     * (see {@link JPEG2000Codec.JPEG2000Options#DEFAULT_NORMAL_QUALITY}).
     */
    JPEG_2000(34712, "JPEG-2000", JPEG2000Codec::new, false),

    /**
     * The same compression code as in {@link #JPEG_2000},
     * but the default quality is chosen as for lossless JPEG-2000 formats.
     *
     * <p>This compression never appears while reading TIFF by {@link net.algart.matrices.tiff.TiffReader},
     * but can be useful while writing by {@link net.algart.matrices.tiff.TiffWriter}.</p>
     */
    JPEG_2000_LOSSLESS(34712, "JPEG-2000 lossless", JPEG2000Codec::new, true),
    // - Note: this variant has the same code as the previous one;
    // it must be specified AFTER: it can only be a result of setting compression for writing
    // and cannot appear when parsing an existing TIFF.

    /**
     * JPEG-2000 Aperio proprietary compression (type 33003).
     *
     * <p>Note {@link net.algart.matrices.tiff.TiffWriter} does not support this compression:
     * the current version of JAI ImageIO (jai-imageio-jpeg2000) cannot write JPEG-2000 in YCbCr color space,
     * as Aperio requires for type 33003.
     */
    JPEG_2000_APERIO_33003(33003, "JPEG-2000 Aperio proprietary 33003",
            JPEG2000Codec::new, false, false),

    /**
     * JPEG-2000 Aperio compression (type 33004, probably lossless).
     *
     * <p>Note {@link net.algart.matrices.tiff.TiffWriter} does not support this compression.</p>
     */
    JPEG_2000_APERIO_33004(33004, "JPEG-2000 Aperio 33004 lossless",
            JPEG2000Codec::new, true, false),

    /**
     * JPEG-2000 Aperio compression for RGB (type 33005).
     */
    JPEG_2000_APERIO(33005, "JPEG-2000 Aperio 33005",
            JPEG2000Codec::new, false);

    private static final Map<Integer, TagCompression> LOOKUP;

    static {
        final Map<Integer, TagCompression> map = new HashMap<>();
        for (TagCompression v : values()) {
            map.putIfAbsent(v.code(), v);
        }
        LOOKUP = map;
    }

    private static final boolean ALWAYS_ALLOW_WRITING = false;
    // - should be false; true value allows testing writing even for compressions that are not really supported

    private final int code;
    private final String name;
    private final Supplier<TiffCodec> codec;
    private final Boolean jpeg2000Lossless;
    private final boolean writingSupported;

    TagCompression(int code, String name, Supplier<TiffCodec> codec) {
        this(code, name, codec, null);
    }

    TagCompression(int code, String name, Supplier<TiffCodec> codec, Boolean jpeg2000Lossless) {
        this(code, name, codec, jpeg2000Lossless, true);
    }

    TagCompression(
            int code,
            String name,
            Supplier<TiffCodec> codec,
            Boolean jpeg2000Lossless,
            boolean writingSupported) {
        this.code = code;
        this.name = Objects.requireNonNull(name);
        this.codec = codec;
        this.jpeg2000Lossless = jpeg2000Lossless;
        this.writingSupported = ALWAYS_ALLOW_WRITING || writingSupported;
    }

    /**
     * Returns an {@link Optional} containing the {@link TagCompression} with the given {@link #name()}
     * (case-insensitive).
     * <p>If no compression with the specified name exists or if the argument is {@code null},
     * an empty optional is returned.
     *
     * @param name the enum name; may be {@code null}.
     * @return optional compression.
     */
    public static Optional<TagCompression> fromName(String name) {
        for (TagCompression value : values()) {
            if (value.name().equalsIgnoreCase(name)) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
    /**
     * Returns an {@link Optional} containing the {@link TagCompression} with the given {@link #code()}.
     * <p>If no data kind with the specified name exists, an empty optional is returned.
     *
     * @param code the enum code.
     * @return optional compression.
     */
    public static Optional<TagCompression> fromCode(int code) {
        return Optional.ofNullable(LOOKUP.get(code));
    }

    public static Optional<TagCompression> fromCode(String codeString) {
        final int code;
        try {
            code = Integer.parseInt(codeString);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return fromCode(code);
    }

    public static String toPrettyString(int code) {
        final Optional<TagCompression> compression = fromCode(code);
        //noinspection OptionalIsPresent
        return "type " + code + (compression.isEmpty() ? "" : ": \"" + compression.get().prettyName() + "\"");
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

    /**
     * Returns <code>true</code> for {@link #JPEG} and {@link #JPEG_RGB}.
     *
     * @return whether it is the standard JPEG compression (code {@value TiffIFD#COMPRESSION_JPEG}).
     */
    public boolean isStandardJpeg() {
        return code == TiffIFD.COMPRESSION_JPEG;
    }

    public boolean isJpegOrOldJpeg() {
        return isStandardJpeg() || this == OLD_JPEG;
    }

    public boolean isPreferRGB() {
        return this == JPEG_RGB;
    }

    public boolean isJpeg2000() {
        return jpeg2000Lossless != null;
    }

    public boolean isJpeg2000Lossy() {
        return jpeg2000Lossless != null && !jpeg2000Lossless;
    }

    public boolean isWritingSupported() {
        return writingSupported;
    }

    public boolean isStandard() {
        return code <= 10 || this == PACK_BITS;
        // - actually, the maximal supported standard compression is DEFLATE=8
    }

    /**
     * Should return <code>true</code> if the format of compressed data can depend on the byte order in the entire
     * TIFF file ({@link TiffCodec.Options#isLittleEndian()}) even in the case when we have 8 or fewer bits per sample,
     * such as in typical 8-bit JPEG RGB or YCbCr images.
     * This is highly unusual and not common in standard TIFF usage,
     * but theoretically, a codec might use this metadata when encoding control or auxiliary information.
     *
     * @return whether the byte order may affect encoding in the case of byte-sized or binary samples;
     *         usually <code>false</code>.
     */
    public boolean canUseByteOrderForByteData() {
        return false;
        // - none of our codecs use byte order information for 8-bit samples
    }

    public TiffCodec.Options customizeReading(TiffTile tile, TiffCodec.Options options) throws TiffException {
        if (isStandardJpeg()) {
            return customizeReadingJpeg(tile, options);
        }
        return options;
    }

    public TiffCodec.Options customizeWriting(TiffTile tile, TiffCodec.Options options) throws TiffException {
        if (isStandardJpeg()) {
            return customizeWritingJpeg(tile, options);
        }
        if (isJpeg2000()) {
            return customizeWritingJpeg2000(tile, options, !isJpeg2000Lossy(), isWritingSupported());
        }
        return options;
    }


    // Note: corrections, performed by this method, may be tested with the image jpeg_ycbcr_encoded_as_rgb.tiff
    private static TiffCodec.Options customizeReadingJpeg(TiffTile tile, TiffCodec.Options options)
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
    private static TiffCodec.Options customizeWritingJpeg(TiffTile tile, TiffCodec.Options options)
            throws TiffException {
        final JPEGCodec.JPEGOptions result = new JPEGCodec.JPEGOptions().setTo(options);
        if (tile.ifd().optInt(Tags.PHOTOMETRIC_INTERPRETATION, -1) ==
                TagPhotometricInterpretation.RGB.code()) {
            result.setPhotometricInterpretation(TagPhotometricInterpretation.RGB);
        }
        return result;
    }

    private static JPEG2000Codec.JPEG2000Options customizeWritingJpeg2000(
            TiffTile tile,
            TiffCodec.Options defaultOptions,
            boolean lossless,
            boolean writingSupported) {
        return new JPEG2000Codec.JPEG2000Options().setTo(defaultOptions, lossless)
                .setWritingSupported(writingSupported);
    }
}
