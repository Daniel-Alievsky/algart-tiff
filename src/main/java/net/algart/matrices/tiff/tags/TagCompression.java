/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2026 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import net.algart.arrays.Arrays;
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
    CCITT_MODIFIED_HUFFMAN(TiffIFD.COMPRESSION_CCITT_MODIFIED_HUFFMAN,
            "CCITT Modified Huffman RLE", CCITTFaxCodec::new),

    /**
     * CCITT T.4: Bi-level encoding/Group 3 facsimile compression (type 3).
     * For binary images only (1 sample/pixel, 1 bit/sample).
     */
    CCITT_T4(TiffIFD.COMPRESSION_CCITT_T4, "CCITT T.4 Group 3 Fax", CCITTFaxCodec::new),

    /**
     * CCITT T.6: Bi-level encoding/Group 4 facsimile compression (type 4).
     * For binary images only (1 sample/pixel, 1 bit/sample).
     */
    CCITT_T6(TiffIFD.COMPRESSION_CCITT_T6, "CCITT T.6 Group 4 Fax", CCITTFaxCodec::new),

    /**
     * LZW compression (type 5).
     */
    LZW(TiffIFD.COMPRESSION_LZW, "LZW", LZWCodec::new),

    /**
     * "Old-style" (obsolete) JPEG compression (type 6).
     */
    OLD_JPEG(TiffIFD.COMPRESSION_OLD_JPEG, "Old-style JPEG", OldJPEGCodec::new),
    // - Note: however, nearestWritable() returns JPEG!

    /**
     * JPEG compression (type 7).
     */
    JPEG(TiffIFD.COMPRESSION_JPEG, "JPEG", JPEGCodec::new),

    /**
     * The same compression code as in {@link #JPEG},
     * but the default photometric interpretation (when it is not explicitly specified)
     * is set to {@link TagPhotometric#RGB}.
     * In comparison, {@link #JPEG} variant uses {@link TagPhotometric#Y_CB_CR}
     * as the default color space: this is more typical for JPEG format.
     *
     * <p>Note: if the photometric interpretation is already set in {@link TiffIFD}, for example, using
     * {@link TiffIFD#putPhotometric(TagPhotometric)} method,
     * behavior of this variant and {@link #JPEG} is identical.
     * This option makes sense when the IFD does not contain this tag,
     * and we need to choose the default photometric interpretation.
     * This is typically useful when creating a new IFD image with help of {@link net.algart.matrices.tiff.TiffWriter}
     * on the base of a newly created IFD.
     *
     * <p>This can be useful while writing by {@link net.algart.matrices.tiff.TiffWriter}.</p>
     *
     * <p>This compression is ignored (equivalent to {@link #JPEG}) if the TIFF writer
     * is {@link net.algart.matrices.tiff.TiffWriter#setEnforceUseExternalCodec(boolean)
     * enforced to use external codec}.
     */
    JPEG_RGB(TiffIFD.COMPRESSION_JPEG, "JPEG RGB", JPEGCodec::new),
    // - Note: this variant has the same code as the previous one; it must be specified AFTER

    /**
     * Zlib deflate compression (ZIP), compatible with ZLib and {@link java.util.zip.DeflaterOutputStream} (type 8).
     */
    DEFLATE(TiffIFD.COMPRESSION_DEFLATE, "Deflate (ZIP)", DeflateCodec::new),


    /**
     * JBIG B&amp;W bi-level compression (type 9).
     * Not supported in the current version.
     */
    JBIG_BW(9, "JBIG B&W", null),

    /**
     * JBIG Color compression (type 10).
     * Not supported in the current version.
     */
    JBIG_COLOR(10, "JBIG Color", null),

    /**
     * JPEG 99 compression (type 99).
     * Not supported in the current version.
     */
    JPEG_99(99, "JPEG 99", null),

    /**
     * IMPACJ compression (type 103).
     * Not supported in the current version.
     */
    IMPACJ(103, "IMPACJ", null),

    /**
     * Kodak 262 RAW compression (type 262).
     * Not supported in the current version.
     */
    KODAK_262(262, "Kodak 262", null),

    /**
     * JPEG XR Hamamatsu NDPI compression (type 22610).
     * Not supported in the current version.
     */
    JPEGXR_NDPI(22610, "JPEG XR NDPI", null),

    /**
     * NeXT RLE compression (type 32766).
     * Not supported in the current version.
     */
    NEXT(32766, "NeXT RLE", null),

    /**
     * Sony ARW Digital Camera RAW compression (type 32767).
     * Not supported in the current version.
     */
    SONY_ARW(32767, "Sony ARW RAW", null),

    /**
     * Packed RAW compression (type 32769).
     * Not supported in the current version.
     */
    PACKED_RAW(32769, "Packed RAW", null),

    /**
     * Samsung SRW Digital Camera RAW compression (type 32770).
     * Not supported in the current version.
     */
    SAMSUNG_SRW(32770, "Samsung SRW RAW", null),

    /**
     * CCITT RLEW: CCITT Modified Huffman RLE with word alignment (type 32771).
     * Not supported in the current version.
     */
    CCITT_RLEW(32771, "CCITT Modified Huffman RLE, Word Aligned", null),

    /**
     * Samsung SRW2 Digital Camera RAW compression (type 32772).
     * Not supported in the current version.
     */
    SAMSUNG_SRW2(32772, "Samsung SRW2 RAW", null),

    /**
     * PackBits run-length compression (type 32773).
     * Oriented for binary or byte images, but can be used for any bit depth.
     */
    PACK_BITS(TiffIFD.COMPRESSION_PACK_BITS, "PackBits", PackBitsCodec::new),

    /**
     * Macintosh Binary Image (MBI) / Apple VideoView RLE (type 32775).
     * Not supported in the current version.
     */
    MBI_RLE(32775, "MBI RLE / Apple VideoView", null),

    /**
     * Apple ThunderScan RLE compression (type 32809).
     *
     * <p>Note {@link net.algart.matrices.tiff.TiffWriter} does not support this compression.
     */
    THUNDER_SCAN(TiffIFD.COMPRESSION_THUNDER_SCAN, "Apple ThunderScan",
            ThunderScanCodec::new, DEFLATE),

    /**
     * IT8 CT Pad: Prepress data exchange (type 32895).
     * Not supported in the current version.
     */
    IT8_CT_PAD(32895, "IT8 CT Pad", null),

    /**
     * IT8 Linework (type 32896).
     * Not supported in the current version.
     */
    IT8_LW(32896, "IT8 Linework", null),

    /**
     * IT8 Monochrome Picture (type 32897).
     * Not supported in the current version.
     */
    IT8_MP(32897, "IT8 Monochrome Picture", null),

    /**
     * IT8 Binary Linework (type 32898).
     * Not supported in the current version.
     */
    IT8_BL(32898, "IT8 Binary Linework", null),

    /**
     * Pixar Film RLE compression (type 32908).
     * Not supported in the current version.
     */
    PIXAR_FILM(32908, "Pixar Film RLE", null),

    /**
     * Pixar Logarithmic compression (type 32909).
     * Not supported in the current version.
     */
    PIXAR_LOG(32909, "Pixar Logarithmic", null),

    /**
     * Deflate compression, equivalent to "{@link #DEFLATE Zlib deflate}"
     * but with another value 32946 in the Compression tag (type 32946).
     * See Oracle's document "TIFF Metadata Format Specification and Usage Notes":
     *
     * <blockquote>
     * ZLib and Deflate compression are identical except for the value of the TIFF Compression field:
     * for ZLib the Compression field has value 8 whereas for Deflate it has value 32946 (0x80b2).
     * In both cases each image segment (strip or tile) is written as a single complete zlib data stream.
     * </blockquote>
     */
    DEFLATE_DEPRECATED(TiffIFD.COMPRESSION_DEFLATE_DEPRECATED, "Deflate (ZIP) deprecated 32946",
            DeflateCodec::new),

    /**
     * Kodak DCS (Digital Camera System) compression (type 32947).
     * Not supported in the current version.
     */
    KODAK_DCS(32947, "Kodak DCS", null),

    /**
     * JPEG-2000 Aperio proprietary compression (type 33003).
     *
     * <p>Note {@link net.algart.matrices.tiff.TiffWriter} does not support this compression:
     * the current version of JAI ImageIO (jai-imageio-jpeg2000) cannot write JPEG-2000 in YCbCr color space,
     * as Aperio requires for type 33003.
     */
    JPEG_2000_APERIO_33003(33003, "JPEG-2000 Aperio proprietary 33003",
            JPEG2000Codec::new, null, false),
    // - Note: however, nearestWritable() returns JPEG_2000_APERIO!

    /**
     * JPEG-2000 Aperio compression (type 33004, probably lossless).
     *
     * <p>Note {@link net.algart.matrices.tiff.TiffWriter} does not support this compression.</p>
     */
    JPEG_2000_APERIO_33004(33004, "JPEG-2000 Aperio 33004 lossless",
            JPEG2000Codec::new, null, true),
    // - Note: however, nearestWritable() returns JPEG_2000_APERIO!

    /**
     * JPEG-2000 Aperio compression for RGB (type 33005).
     *
     * <p>For writing, the <code>PhotometricInterpretation</code> will be automatically set
     * to RGB (default value).</p>
     */
    JPEG_2000_APERIO(TiffIFD.COMPRESSION_JPEG_2000_APERIO, "JPEG-2000 Aperio 33005",
            JPEG2000Codec::new, null, false),

    /**
     * Alternate JPEG compression (type 33007).
     * Not supported in the current version.
     */
    ALT_JPEG(33007, "Alt JPEG", null),

    /**
     * JBIG: ISO/IEC 11544 bi-level image compression (type 34661).
     * Not supported in the current version.
     */
    JBIG(34661, "JBIG", null),

    /**
     * SGI LogL / LogLuv (CIE Log Luminance/Chroma) compression (type 34676).
     */
    SGI_LOGL(34676, "SGI LogL / LogLuv", LogLuvCodec::new, DEFLATE),

    /**
     * SGI LogLuv 24-bit (CIE Log Luminance/Chroma) compression (type 34677).
     */
    SGI_LOG24(34677, "SGI LogLuv 24-bit", LogLuvCodec::new, DEFLATE),

    /**
     * LuraDocument LURACODE compression (type 34692).
     * Not supported in the current version.
     */
    LURA_DOC(34692, "LuraDocument", null),

    /**
     * JPEG-2000 standard compression (type 34712).
     * Default quality is chosen as for lossless JPEG-2000 formats.
     *
     * <p>For writing, the <code>PhotometricInterpretation</code> will be automatically set
     * to RGB (default value).</p>
     */
    JPEG_2000_LOSSLESS(TiffIFD.COMPRESSION_JPEG_2000, "JPEG-2000 lossless",
            JPEG2000Codec::new, null, true),

    /**
     * The same compression code as in {@link #JPEG_2000_LOSSLESS},
     * but the default quality is chosen as for lossy JPEG-2000 formats
     * (see {@link JPEG2000Codec.JPEG2000Options#DEFAULT_NORMAL_QUALITY}).
     *
     * <p>This compression never appears while reading TIFF by {@link net.algart.matrices.tiff.TiffReader},
     * but can be useful while writing by {@link net.algart.matrices.tiff.TiffWriter}.</p>
     */
    JPEG_2000(TiffIFD.COMPRESSION_JPEG_2000, "JPEG-2000",
            JPEG2000Codec::new, null, false),
    // - Note: this variant has the same code as the previous one;
    // it must be specified AFTER: it can only be a result of setting compression for writing
    // and cannot appear when parsing an existing TIFF.

    /**
     * Nikon NEF (Lossy Huffman) (type 34713).
     * Used in Nikon Digital Camera raw files. Not supported.
     */
    NIKON_NEF(34713, "Nikon NEF", null),

    /**
     * JBIG2 bi-level image compression (type 34715).
     * Not supported in the current version.
     */
    JBIG2(34715, "JBIG2", null),

    /**
     * Microsoft Document Imaging (MDI) Binary (type 34718).
     * Not supported in the current version.
     */
    MDI_BINARY(34718, "MDI Binary", null),

    /**
     * Microsoft Document Imaging (MDI) Progressive (type 34719).
     * Not supported in the current version.
     */
    MDI_PROGRESSIVE(34719, "MDI Progressive", null),

    /**
     * Microsoft Document Imaging (MDI) Vector (type 34720).
     * Not supported in the current version.
     */
    MDI_VECTOR(34720, "MDI Vector", null),

    /**
     * ESRI LERC (Limited Error Raster Compression) (type 34887).
     * Not supported in the current version.
     */
    LERC(34887, "LERC", null),

    /**
     * JPEG Lossy compression (type 34892).
     */
    JPEG_LOSSY(TiffIFD.COMPRESSION_JPEG_LOSSY, "JPEG Lossy", JPEGCodec::new, JPEG),

    /**
     * LZMA compression (XZ format, Lempel–Ziv–Markov chain Algorithm) (type 34925).
     */
    LZMA(TiffIFD.COMPRESSION_LZMA, "LZMA (XZ)", LZMACodec::new),

    /**
     * Deprecated Zstandard compression tag (type 34926).
     * Not supported in the current version.
     */
    ZSTD_DEPRECATED(34926, "Zstandard deprecated 34926", null),

    /**
     * Deprecated WebP compression tag (type 34927).
     * Not supported in the current version.
     */
    WEBP_DEPRECATED(34927, "WebP deprecated 34927", null),

    /**
     * PNG compression (type 34933).
     */
    PNG(34933, "PNG", () -> new AWTCodec("png", true)),

    /**
     * JPEG XR compression (type 34934).
     * Not supported in the current version.
     */
    JPEG_XR(34934, "JPEG XR", null),

    /**
     * Dotphoton JetRAW compression (type 48124).
     * Not supported in the current version.
     */
    JETRAW(48124, "JetRAW", null),

    /**
     * Zstandard (ZSTD) compression (type 50000).
     */
    ZSTD(TiffIFD.COMPRESSION_ZSTD, "Zstandard (ZSTD)", ZstdCodec::new),

    /**
     * WebP compression (type 50001).
     */
    WEBP(TiffIFD.COMPRESSION_WEBP, "WebP", WebPCodec::new, JPEG),

    /**
     * JPEG XL compression (type 50002).
     * Not supported in the current version.
     */
    JPEG_XL(50002, "JPEG XL", null),

    /**
     * PixTIFF compression (type 50013).
     * Not supported in the current version.
     */
    PIXTIFF(50013, "PixTIFF", null),

    /**
     * JPEG XL DNG compression (type 52546).
     * Not supported in the current version.
     */
    JPEG_XL_DNG(52546, "JPEG XL DNG", null),

    /**
     * Electron Event Representation v0 compression (type 65000).
     * Not supported in the current version.
     */
    EER_V0(65000, "EER v0", null),

    /**
     * Electron Event Representation v1 compression (type 65001).
     * Not supported in the current version.
     */
    EER_V1(65001, "EER v1", null),

    /**
     * Electron Event Representation v2 compression (type 65002).
     * Not supported in the current version.
     */
    EER_V2(65002, "EER v2", null);

    private static final boolean ALWAYS_ALLOW_WRITING = false;
    // - should be false; true value allows testing writing even for compressions that are not really supported
    private static final boolean DEFLATE_DEPRECATED_WRITING = Arrays.SystemSettings.getBooleanProperty(
            "net.algart.matrices.tiff.deflateDeprecatedWriting", false);

    private static final Map<Integer, TagCompression> CODE_LOOKUP = new HashMap<>();
    private static final Map<String, TagCompression> NAME_LOOKUP = new HashMap<>();
    private static final Map<String, TagCompression> PRETTY_NAME_LOOKUP = new HashMap<>();

    static {
        for (TagCompression v : values()) {
            CODE_LOOKUP.putIfAbsent(v.code, v);
            // - Note: the order of values is IMPORTANT here
            final String name = v.name().toUpperCase();
            if (NAME_LOOKUP.containsKey(name)) {
                throw new AssertionError("Duplicate name (ignoring case): " + name);
            }
            NAME_LOOKUP.put(name, v);
            final String pretty = v.prettyName().toUpperCase();
            if (PRETTY_NAME_LOOKUP.containsKey(pretty)) {
                throw new AssertionError("Duplicate pretty name (ignoring case): " + pretty);
            }
            PRETTY_NAME_LOOKUP.put(pretty, v);
        }
    }

    private final int code;
    private final String name;
    private final Supplier<TiffCodec> codec;
    private final Boolean jpeg2000Lossless;
    private final TagCompression nearestWritable;

    TagCompression(int code, String name, Supplier<TiffCodec> codec) {
        this(code, name, codec, null);
    }

    TagCompression(int code, String name, Supplier<TiffCodec> codec, TagCompression nearestWritable) {
        this(code, name, codec, nearestWritable, null);
    }

    // If writing is not supported at all, nearestWritable is usually DEFLATE or NONE;
    // codec must be either null or a supplier returning non-null
    TagCompression(
            int code,
            String name,
            Supplier<TiffCodec> codec,
            TagCompression nearestWritable,
            Boolean jpeg2000Lossless) {
        this.code = code;
        this.name = Objects.requireNonNull(name);
        this.codec = codec;
        this.nearestWritable = ALWAYS_ALLOW_WRITING || nearestWritable == null ?
                null :
                nearestWritable;
        this.jpeg2000Lossless = jpeg2000Lossless;
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
        return Optional.ofNullable(name == null ? null : NAME_LOOKUP.get(name.toUpperCase()));
    }

    /**
     * Returns an {@link Optional} containing the {@link TagCompression} with the given {@link #prettyName()}
     * (case-insensitive).
     * <p>If no compression with the specified pretty name exists or if the argument is {@code null},
     * an empty optional is returned.
     *
     * @param name the compression pretty name; may be {@code null}.
     * @return optional compression.
     */
    public static Optional<TagCompression> fromPrettyName(String name) {
        return Optional.ofNullable(name == null ? null : PRETTY_NAME_LOOKUP.get(name.toUpperCase()));
    }

    /**
     * Returns an {@link Optional} containing the {@link TagCompression} with the given {@link #code()}.
     * <p>If no data kind with the specified name exists, an empty optional is returned.
     *
     * @param code the enum code.
     * @return optional compression.
     */
    public static Optional<TagCompression> fromCode(int code) {
        return Optional.ofNullable(CODE_LOOKUP.get(code));
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

    @Override
    public String toString() {
        return name() + " (" + prettyName() + ")";
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
     * Returns the codec.
     *
     * @see #isSupported()
     */
    public TiffCodec codec() {
        return codec == null ? null : codec.get();
    }

    public boolean isOldFormat() {
        return code == TiffIFD.COMPRESSION_CCITT_T4 ||
                code == TiffIFD.COMPRESSION_CCITT_T6 ||
                code == TiffIFD.COMPRESSION_CCITT_MODIFIED_HUFFMAN ||
                code == TiffIFD.COMPRESSION_OLD_JPEG ||
                code == TiffIFD.COMPRESSION_THUNDER_SCAN;
    }

    /**
     * Returns <code>true</code> for {@link #JPEG}, {@link #JPEG_RGB} or {@link #JPEG_LOSSY}.
     * All these compressions use the same {@link JPEGCodec}, though {@link #JPEG_LOSSY} does not allow compression
     * (only decompression).
     *
     * @return whether it is standard JPEG compression (code {@value TiffIFD#COMPRESSION_JPEG} or
     * a rare equivalent code {@link TiffIFD#COMPRESSION_JPEG_LOSSY}).
     */
    public boolean isJpegCodec() {
        return code == TiffIFD.COMPRESSION_JPEG || code == TiffIFD.COMPRESSION_JPEG_LOSSY;
    }

    /**
     * Returns {@code true} if the codec is based on AWT ImageReader.
     * In this case, the preferred {@link TiffCodec.Options#setInterleaved(boolean) interleaved} flag in the codec
     * options is {@code false}: AWT functions can return channels in separated banks, and there is no reason
     * for the {@link TiffCodec#decompress(byte[], TiffCodec.Options)} method to interleave them back
     * to the RGBRGB... format &mdash; the TIFF reader needs separated channels.
     *
     * @return whether this codec uses AWT functions for decompressing data.
     */
    public boolean isAWTBasedReading() {
        return isJpegCodec() || isStandardOrOldJpeg() || isJpeg2000() || this == WEBP;
    }

    /**
     * Returns {@code true} if this compression method relies on additional metadata
     * embedded directly in the TIFF file, separate from IFD tags, TIFF tiles or strips.
     *
     * <p>When this is {@code true} (as with {@link #OLD_JPEG}), the compressed
     * streams in strips or tiles are incomplete. They require external information
     * stored elsewhere in the file.
     * Since these are referenced via file offsets, the image <b>cannot be
     * safely copied</b> or moved to a new TIFF structure without a codec that
     * understands how to re-embed this metadata.
     *
     * <p>This is {@code true} only for {@link #OLD_JPEG} format, which stores Huffman or Quantization tables
     * in separate fragments of the file and refers to them via offsets stored in special IFD tags.
     * In comparison, {@link #JPEG} format can store the same metadata in <code>JPEGTables</code> tag,
     * but this information is completely stored inside IFD and does not refer to any offsets inside the file;
     * Thus, this method returns {@code false} for {@link #JPEG}:
     * it is possible to copy such an image directly to another TIFF by simply preserving the IFD tags
     * and bit-to-bit copying tiles/strips: there is no need to use the codec.
     *
     * @return {@code true} if the format requires file-embedded metadata besides the standard IFD information.
     */
    public boolean hasAdditionalFileEmbeddedMetadata() {
        return this == OLD_JPEG;
    }

    public boolean isStandardOrOldJpeg() {
        return code == TiffIFD.COMPRESSION_JPEG || code == TiffIFD.COMPRESSION_OLD_JPEG;
    }

    public boolean isRGBPreferred() {
        return this == JPEG_RGB;
    }

    public boolean isJpeg2000() {
        return jpeg2000Lossless != null;
    }

    public boolean isJpeg2000Lossy() {
        return jpeg2000Lossless != null && !jpeg2000Lossless;
    }

    public boolean isJpegFamily() {
        return isJpegCodec() || isStandardOrOldJpeg() || isJpeg2000();
    }

    /**
     * Returns {@code true} if the codec always creates an RGB compressed image and
     * requires the {@link TagPhotometric#RGB} photometric tag to be correctly viewed.
     * In the current version, this is true for JPEG-2000 codecs only.
     *
     * @return whether the photometric interpretation tag <b>must</b> be {@link TagPhotometric#RGB} while writing
     * a TIFF image with this compression.
     */
    public boolean isRGBRequired() {
        return code == TiffIFD.COMPRESSION_JPEG_2000 ||
                code == TiffIFD.COMPRESSION_JPEG_2000_APERIO;
        // - current version of JAI ImageIO (jai-imageio-jpeg2000) cannot write JPEG-2000 in YCbCr color space
    }

    /**
     * Returns this compression type if it {@link #isWritingSupported() supports writing}, or the most closely
     * related type supporting writing otherwise.
     *
     * <p>In most cases, this method simply returns this object. However, for
     * obsolete or proprietary formats that cannot be written (such as {@link #OLD_JPEG}
     * or {@link #THUNDER_SCAN}), it returns a functional equivalent that can be recommended
     * for writing instead of this type (for example, {@link #JPEG} for {@link #OLD_JPEG}
     * or {@link #DEFLATE} for {@link #THUNDER_SCAN}).
     *
     * <p>The returned value is guaranteed to have {@link #isWritingSupported()}
     * returning {@code true}. If no specific related type is defined for an
     * unsupported format, {@link #NONE} is returned as a safe fallback.
     *
     * @return the nearest writable compression type; never {@code null}.
     * @see #isWritingSupported()
     */
    public TagCompression nearestWritable() {
        TagCompression predefined = predefinedNearestWritable();
        return predefined != null ? predefined :
                nearestWritable != null ? nearestWritable :
                codec != null ? this : NONE;
    }

    /**
     * Returns {@code true} if this compression type is supported (at least) for reading
     * via the class {@link net.algart.matrices.tiff.TiffReader}.
     * Equivalent to <code>{@link #codec()}&nbsp;!=&nbsp;null</code>.
     *
     * @return whether this compression type can be read.
     */
    public boolean isSupported() {
        return codec != null;
    }

    /**
     * Returns {@code true} if this compression type is supported both for reading and writing
     * via the classes {@link net.algart.matrices.tiff.TiffReader} and {@link net.algart.matrices.tiff.TiffWriter}.
     *
     * @return whether this compression type can be read and written.
     */
    public boolean isWritingSupported() {
        return predefinedNearestWritable() == null && nearestWritable == null && codec != null;
    }

    public boolean isCompressionQualitySupported() {
        return isJpeg2000() || isJpegCodec();
    }

    public boolean isLosslessCompressionLevelSupported() {
        return this == DEFLATE || this == PNG || this == LZMA;
    }

    /**
     * Returns {@code true} if the decompressed data unpacked by the codec
     * should be further processed, for example, inverted in some photometric interpretations,
     * expanded to a whole number of bytes (e.g., 4 bits to 8, 12 bits to 16), etc.
     * This is {@code true} for the following codecs:
     * {@link #NONE}, {@link #CCITT_T4}, {@link #CCITT_T6},
     * {@link #CCITT_MODIFIED_HUFFMAN},
     * {@link #LZW}, {@link #DEFLATE}, {@link #DEFLATE_DEPRECATED},
     * {@link #PACK_BITS}, {@link #THUNDER_SCAN},  {@link #LZMA}, {@link #ZSTD}.
     *
     * <p>For high-level compressions like JPEG, this method returns {@code false}.
     * Such codecs typically return a ready-to-use image, similar to the result of reading from a file.
     * In particular, for the CMYK photometric interpretation, the codec should correctly translate the colors
     * to RGB space (the standard model for {@link net.algart.matrices.tiff.TiffReader}
     * and {@link java.awt.image.BufferedImage}).</p>
     *
     * @return whether this compression codec works with a low-level bit stream.
     */
    public boolean isLowLevelBitsProcessing() {
        return switch (code) {
            case TiffIFD.COMPRESSION_NONE,
                 TiffIFD.COMPRESSION_CCITT_MODIFIED_HUFFMAN,
                 TiffIFD.COMPRESSION_CCITT_T4,
                 TiffIFD.COMPRESSION_CCITT_T6,
                 TiffIFD.COMPRESSION_LZW,
                 TiffIFD.COMPRESSION_DEFLATE,
                 TiffIFD.COMPRESSION_DEFLATE_DEPRECATED,
                 TiffIFD.COMPRESSION_PACK_BITS,
                 TiffIFD.COMPRESSION_THUNDER_SCAN,
                 TiffIFD.COMPRESSION_LZMA,
                 TiffIFD.COMPRESSION_ZSTD -> true;
            default -> false;
        };
    }

    public boolean isLowLevelInvertedBrightness(TagPhotometric photometric) {
        return isLowLevelBitsProcessing() && photometric != null && photometric.isInvertedBrightness();
    }

    public boolean isLowLevelInvertedBrightness(int photometricCode) {
        return isLowLevelBitsProcessing() && TagPhotometric.isInvertedBrightness(photometricCode);
    }

    /**
     * Should return <code>true</code> if the format of compressed data can depend on the byte order in the entire
     * TIFF file ({@link TiffCodec.Options#isLittleEndian()}) even in the case when we have 8 or fewer bits per sample,
     * such as in typical 8-bit JPEG RGB or YCbCr images.
     * This is highly unusual and not common in standard TIFF usage,
     * but theoretically, a codec might use this metadata when encoding control or auxiliary information.
     *
     * @return whether the byte order may affect encoding in the case of byte-sized or binary samples;
     * usually <code>false</code>.
     */
    public boolean canUseByteOrderForByteData() {
        return false;
        // - none of our codecs use byte order information for 8-bit samples
    }

    public TiffCodec.Options customizeReading(TiffTile tile, TiffCodec.Options options) {
        // Note: this method does not damage customization already performed in the options;
        // so, it uses clone() or Options.setTo() method
        if (isAWTBasedReading()) {
            options = customizeAWTReading(tile, options);
        }
        if (isJpeg2000()) {
            options = customizeReadingJpeg2000(tile, options);
            // - does nothing in the current implementation, but we MUST provide the correct class JPEG2000Options
            // for possible future implementations or usage of TiffCodec.Customizer
            // (this is checked in TiffReaderTest)
        }
        return options;
    }

    public TiffCodec.Options customizeWriting(TiffTile tile, TiffCodec.Options options) {
        // Note: this method does not damage customization already performed in the options;
        // so, it uses clone() or Options.setTo() method
        if (isJpeg2000()) {
            return customizeWritingJpeg2000(tile, options, !isJpeg2000Lossy(), isWritingSupported());
        }
        return options;
    }

    private TagCompression predefinedNearestWritable() {
        return switch (this) {
            case OLD_JPEG -> JPEG;
            case JPEG_2000_APERIO_33003, JPEG_2000_APERIO_33004 -> JPEG_2000_APERIO;
            case DEFLATE_DEPRECATED -> DEFLATE_DEPRECATED_WRITING ? null : DEFLATE;
            default -> null;
        };
        // - these special cases allows to place JPEG and JPEG_2000_APERIO enum constants
        // BEFORE the replaced OLD_JPEG, JPEG_2000_APERIO_33003, JPEG_2000_APERIO_33004
    }

    private static TiffCodec.Options customizeAWTReading(TiffTile tile, TiffCodec.Options options) {
        return options.clone().setInterleaved(false);
        // AWT-based codecs, as well as LosslessJPEGCodec work faster in with non-interleaved data, and in any case,
        // it is better because TiffReader needs non-interleaved results.
    }

    // Deprecated solution: it was necessary in the versions until 1.5.1, where
    // TiffWriter.buildOptions did not add PhotometricInterpretation to TiffCodec.Options
    //
    // Note: corrections, performed by this method, may be tested with the image jpeg_ycbcr_encoded_as_rgb.tiff
    // private static TiffCodec.Options customizeWritingJpeg(TiffTile tile, TiffCodec.Options options)
    // throws TiffException {
    //
    //     if (tile.ifd().optInt(Tags.PHOTOMETRIC_INTERPRETATION, -1) ==
    //             TagPhotometricInterpretation.RGB.code()) {
    //        result.setPhotometricInterpretation(TagPhotometricInterpretation.RGB);
    //     }
    // }

    private static JPEG2000Codec.JPEG2000Options customizeReadingJpeg2000(
            TiffTile tile,
            TiffCodec.Options defaultOptions) {
        return new JPEG2000Codec.JPEG2000Options().setTo(defaultOptions);
    }

    private static JPEG2000Codec.JPEG2000Options customizeWritingJpeg2000(
            TiffTile tile,
            TiffCodec.Options defaultOptions,
            boolean lossless,
            boolean writingSupported) {
        return new JPEG2000Codec.JPEG2000Options()
                .setTo(defaultOptions, lossless)
                .setWritingSupported(writingSupported);
    }
}
