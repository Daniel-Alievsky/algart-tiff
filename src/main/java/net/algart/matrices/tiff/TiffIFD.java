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

import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.arrays.TooLargeArrayException;
import net.algart.matrices.tiff.tags.*;

import java.lang.reflect.Array;
import java.nio.ByteOrder;
import java.util.*;

public class TiffIFD {
    /**
     * Maximal supported number of channels.
     * For comparison, the popular OpenCV library has a limit of 512 channels.
     *
     * <p>This limit helps to avoid "crazy" or corrupted TIFF and also help to avoid arithmetic overflow.
     */
    public static final int MAX_NUMBER_OF_CHANNELS = 128;
    /**
     * Maximal supported number of bits per sample.
     *
     * <p>This limit helps to avoid "crazy" or corrupted TIFF and also help to avoid arithmetic overflow.
     */
    public static final int MAX_BITS_PER_SAMPLE = 256;

    /**
     * Maximal number of bits, that can be packed inside a single Java <code>byte[]</code> array.
     */
    public static final long MAX_NUMBER_OF_BITS_IN_BYTE_ARRAY = (1L << 34) - 1;

    private static final long MAX_LONG_DIV_MAX_BITS_PER_PIXEL = Long.MAX_VALUE /
            (MAX_NUMBER_OF_CHANNELS * MAX_BITS_PER_SAMPLE);

    public record UnsupportedTypeValue(int type, int count, long valueOrOffset) {
        public UnsupportedTypeValue {
            if (count < 0) {
                throw new IllegalArgumentException("Negative count of values");
            }
        }

        @Override
        public String toString() {
            return "Unsupported TIFF value (unknown type code " + type + ", " + count + " elements)";
        }
    }

    public enum StringFormat {
        BRIEF(true, false),
        NORMAL(true, false),
        NORMAL_SORTED(true, true),
        DETAILED(false, false),
        JSON(false, false);
        private final boolean compactArrays;
        private final boolean sorted;

        StringFormat(boolean compactArrays, boolean sorted) {
            this.compactArrays = compactArrays;
            this.sorted = sorted;
        }

        public boolean isJson() {
            return this == JSON;
        }
    }

    public static final int LAST_IFD_OFFSET = 0;

    public static final int DEFAULT_TILE_SIZE_X = 512;
    public static final int DEFAULT_TILE_SIZE_Y = 512;
    public static final int DEFAULT_STRIP_SIZE = 128;

    /**
     * Compression code for {@link TagCompression#NONE}.
     */
    public static final int COMPRESSION_NONE = 1;
    /**
     * Compression code for {@link TagCompression#CCITT_MODIFIED_HUFFMAN_RLE}.
     */
    public static final int COMPRESSION_CCITT_MODIFIED_HUFFMAN_RLE = 2;
    /**
     * Compression code for {@link TagCompression#CCITT_T4}.
     */
    public static final int COMPRESSION_CCITT_T4 = 3;
    /**
     * Compression code for {@link TagCompression#CCITT_T6}.
     */
    public static final int COMPRESSION_CCITT_T6 = 4;
    /**
     * Compression code for {@link TagCompression#LZW}.
     */
    public static final int COMPRESSION_LZW = 5;
    /**
     * Compression code for {@link TagCompression#OLD_JPEG}.
     */
    public static final int COMPRESSION_OLD_JPEG = 6;
    /**
     * Compression code for {@link TagCompression#JPEG}.
     */
    public static final int COMPRESSION_JPEG = 7;
    /**
     * Compression code for {@link TagCompression#DEFLATE}.
     */
    public static final int COMPRESSION_DEFLATE = 8;
    /**
     * Compression code for {@link TagCompression#DEFLATE_PROPRIETARY}.
     */
    public static final int COMPRESSION_DEFLATE_PROPRIETARY = 32946;
    /**
     * Compression code for {@link TagCompression#PACK_BITS}.
     */
    public static final int COMPRESSION_PACK_BITS = 32773;

    /**
     * Contiguous (chunked) samples format (PlanarConfiguration), for example: RGBRGBRGB....
     */
    public static final int PLANAR_CONFIGURATION_CHUNKED = 1;

    /**
     * Planar samples format (PlanarConfiguration), for example: RRR...GGG...BBB...
     * Note: the specification adds a warning that PlanarConfiguration=2 is not in widespread use and
     * that Baseline TIFF readers are not required to support it.
     */
    public static final int PLANAR_CONFIGURATION_SEPARATE = 2;

    /**
     * Normal TIFF bits order 76543210.
     */
    public static final int FILL_ORDER_NORMAL = 1;
    /**
     * Reversed TIFF bits order 01234567.
     */
    public static final int FILL_ORDER_REVERSED = 2;

    public static final int SAMPLE_FORMAT_UINT = 1;
    public static final int SAMPLE_FORMAT_INT = 2;
    public static final int SAMPLE_FORMAT_IEEEFP = 3;
    public static final int SAMPLE_FORMAT_VOID = 4;
    public static final int SAMPLE_FORMAT_COMPLEX_INT = 5;
    public static final int SAMPLE_FORMAT_COMPLEX_IEEEFP = 6;

    public static final int FILETYPE_REDUCED_IMAGE = 1;

    private final Map<Integer, Object> map;
    private final Map<Integer, TiffEntry> detailedEntries;
    private boolean littleEndian = false;
    private boolean bigTiff = false;
    private long fileOffsetForReading = -1;
    private long fileOffsetForWriting = -1;
    private long nextIFDOffset = -1;
    private Integer subIFDType = null;
    private volatile boolean frozen = false;

    private volatile long[] cachedTileOrStripByteCounts = null;
    private volatile long[] cachedTileOrStripOffsets = null;

    public TiffIFD() {
        this(new LinkedHashMap<>());
    }

    @SuppressWarnings("CopyConstructorMissesField")
    public TiffIFD(TiffIFD ifd) {
        fileOffsetForReading = ifd.fileOffsetForReading;
        fileOffsetForWriting = ifd.fileOffsetForWriting;
        nextIFDOffset = ifd.nextIFDOffset;
        map = new LinkedHashMap<>(ifd.map);
        detailedEntries = ifd.detailedEntries == null ? null : new LinkedHashMap<>(ifd.detailedEntries);
        subIFDType = ifd.subIFDType;
        frozen = false;
        // - Important: a copy is not frozen!
        // And it is the only way to clear this flag.
    }

    public static TiffIFD valueOf(Map<Integer, Object> ifdEntries) {
        return new TiffIFD(ifdEntries);
    }

    private TiffIFD(Map<Integer, Object> ifdEntries) {
        this(ifdEntries, null);
    }

    // Note: detailedEntries is not cloned by this constructor.
    TiffIFD(Map<Integer, Object> ifdEntries, Map<Integer, TiffEntry> detailedEntries) {
        Objects.requireNonNull(ifdEntries);
        this.map = new LinkedHashMap<>(ifdEntries);
        this.detailedEntries = detailedEntries;
    }

    public boolean isLittleEndian() {
        return littleEndian;
    }

    public TiffIFD setLittleEndian(boolean littleEndian) {
        this.littleEndian = littleEndian;
        return this;
    }

    public ByteOrder getByteOrder() {
        return isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    }

    public TiffIFD setByteOrder(ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder);
        return setLittleEndian(byteOrder == ByteOrder.LITTLE_ENDIAN);
    }

    public boolean isBigTiff() {
        return bigTiff;
    }

    public TiffIFD setBigTiff(boolean bigTiff) {
        this.bigTiff = bigTiff;
        return this;
    }

    public boolean hasFileOffsetForReading() {
        return fileOffsetForReading >= 0;
    }

    public long getFileOffsetForReading() {
        if (fileOffsetForReading < 0) {
            throw new IllegalStateException("IFD offset of the TIFF tile is not set while reading");
        }
        return fileOffsetForReading;
    }

    public TiffIFD setFileOffsetForReading(long fileOffsetForReading) {
        if (fileOffsetForReading < 0) {
            throw new IllegalArgumentException("Negative IFD offset in the file: " + fileOffsetForReading);
        }
        this.fileOffsetForReading = fileOffsetForReading;
        return this;
    }

    public TiffIFD removeFileOffsetForReading() {
        this.fileOffsetForReading = -1;
        return this;
    }

    public boolean hasFileOffsetForWriting() {
        return fileOffsetForWriting >= 0;
    }

    public long getFileOffsetForWriting() {
        if (fileOffsetForWriting < 0) {
            throw new IllegalStateException("IFD offset of the TIFF tile for writing is not set");
        }
        return fileOffsetForWriting;
    }

    public TiffIFD setFileOffsetForWriting(long fileOffsetForWriting) {
        if (fileOffsetForWriting < 0) {
            throw new IllegalArgumentException("Negative IFD file offset: " + fileOffsetForWriting);
        }
        if ((fileOffsetForWriting & 0x1) != 0) {
            throw new IllegalArgumentException("Odd IFD file offset " + fileOffsetForWriting +
                    " is prohibited for writing valid TIFF");
            // - But we allow such offsets for reading!
            // Such minor inconsistency in the file is not a reason to decline ability to read it.
        }
        this.fileOffsetForWriting = fileOffsetForWriting;
        return this;
    }

    public TiffIFD removeFileOffsetForWriting() {
        this.fileOffsetForWriting = -1;
        return this;
    }

    public boolean hasNextIFDOffset() {
        return nextIFDOffset >= 0;
    }

    /**
     * Returns <code>true</code> if this IFD is marked as the last ({@link #getNextIFDOffset()} returns 0).
     *
     * @return whether this IFD is the last one in the TIFF file.
     */
    public boolean isLastIFD() {
        return nextIFDOffset == LAST_IFD_OFFSET;
    }

    public long getNextIFDOffset() {
        if (nextIFDOffset < 0) {
            throw new IllegalStateException("Next IFD offset is not set");
        }
        return nextIFDOffset;
    }

    public TiffIFD setNextIFDOffset(long nextIFDOffset) {
        if (nextIFDOffset < 0) {
            throw new IllegalArgumentException("Negative next IFD offset: " + nextIFDOffset);
        }
        this.nextIFDOffset = nextIFDOffset;
        return this;
    }

    public TiffIFD setLastIFD() {
        return setNextIFDOffset(LAST_IFD_OFFSET);
    }

    public TiffIFD removeNextIFDOffset() {
        this.nextIFDOffset = -1;
        return this;
    }

    public boolean isMainIFD() {
        return subIFDType == null;
    }

    public Integer getSubIFDType() {
        return subIFDType;
    }

    public TiffIFD setSubIFDType(Integer subIFDType) {
        this.subIFDType = subIFDType;
        return this;
    }

    public boolean isFrozen() {
        return frozen;
    }

    /**
     * Disables most possible changes in this map,
     * excepting the only ones, which are absolutely necessary for making final IFD inside the file.
     * Such "necessary" changes are performed by special "updateXxx" method.
     *
     * <p>This method is usually called before writing process.
     * This helps to avoid bugs, connected with changing IFD properties (such as compression, tile sizes etc.)
     * when we already started to write image into the file, for example, have written some its tiles.
     *
     * @return a reference to this object.
     */
    public TiffIFD freeze() {
        this.frozen = true;
        return this;
    }

    public Map<Integer, Object> map() {
        return Collections.unmodifiableMap(map);
    }

    public int numberOfEntries() {
        return map.size();
    }

    public boolean containsKey(int key) {
        return map.containsKey(key);
    }

    public Object get(int key) {
        return map.get(key);
    }

    public <R> Optional<R> optValue(final int tag, final Class<? extends R> requiredClass) {
        Objects.requireNonNull(requiredClass, "Null requiredClass");
        Object value = get(tag);
        if (!requiredClass.isInstance(value)) {
            // - in particular, if value == null
            return Optional.empty();
        }
        return Optional.of(requiredClass.cast(value));
    }

    public <R> Optional<R> getValue(int tag, Class<? extends R> requiredClass) throws TiffException {
        Objects.requireNonNull(requiredClass, "Null requiredClass");
        Object value = get(tag);
        if (value == null) {
            return Optional.empty();
        }
        if (!requiredClass.isInstance(value)) {
            throw new TiffException("TIFF tag " + Tags.tiffTagName(tag, true) +
                    " has wrong type: " + value.getClass().getSimpleName() +
                    " instead of expected " + requiredClass.getSimpleName());
        }
        return Optional.of(requiredClass.cast(value));
    }

    public <R> R reqValue(int tag, Class<? extends R> requiredClass) throws TiffException {
        return getValue(tag, requiredClass).orElseThrow(() -> new TiffException(
                "TIFF tag " + Tags.tiffTagName(tag, true) + " is required, but it is absent"));
    }

    public OptionalInt optType(int tag) {
        TiffEntry entry = detailedEntries == null ? null : detailedEntries.get(tag);
        return entry == null ? OptionalInt.empty() : OptionalInt.of(entry.type());
    }

    public boolean optBoolean(int tag, boolean defaultValue) {
        return optValue(tag, Boolean.class).orElse(defaultValue);
    }

    public int reqInt(int tag) throws TiffException {
        return checkedIntValue(reqValue(tag, Number.class), tag);
    }

    public int getInt(int tag, int defaultValue) throws TiffException {
        return checkedIntValue(getValue(tag, Number.class).orElse(defaultValue), tag);
    }

    public int optInt(int tag, int defaultValue) {
        return truncatedIntValue(optValue(tag, Number.class).orElse(defaultValue));
    }

    public long reqLong(int tag) throws TiffException {
        return reqValue(tag, Number.class).longValue();
    }

    public long getLong(int tag, int defaultValue) throws TiffException {
        return getValue(tag, Number.class).orElse(defaultValue).longValue();
    }

    public long optLong(int tag, long defaultValue) {
        return optValue(tag, Number.class).orElse(defaultValue).longValue();
    }

    public long[] getLongArray(int tag) throws TiffException {
        final Object value = get(tag);
        long[] results = null;
        if (value instanceof long[] v) {
            results = v.clone();
        } else if (value instanceof Number v) {
            results = new long[]{v.longValue()};
        } else if (value instanceof Number[] v) {
            results = new long[v.length];
            for (int i = 0; i < results.length; i++) {
                results[i] = v[i].longValue();
            }
        } else if (value instanceof int[] v) {
            results = new long[v.length];
            for (int i = 0; i < v.length; i++) {
                results[i] = v[i];
            }
        } else if (value != null) {
            throw new TiffException("TIFF tag " + Tags.tiffTagName(tag, true) +
                    " has wrong type: " + value.getClass().getSimpleName() +
                    " instead of expected Number, Number[], long[] or int[]");
        }
        return results;
    }

    public int[] getIntArray(int tag) throws TiffException {
        final Object value = get(tag);
        int[] results = null;
        if (value instanceof int[] v) {
            results = v.clone();
        } else if (value instanceof Number v) {
            results = new int[]{checkedIntValue(v, tag)};
        } else if (value instanceof long[] v) {
            results = new int[v.length];
            for (int i = 0; i < v.length; i++) {
                results[i] = checkedIntValue(v[i], tag);
            }
        } else if (value instanceof Number[] v) {
            results = new int[v.length];
            for (int i = 0; i < results.length; i++) {
                results[i] = checkedIntValue(v[i].longValue(), tag);
            }
        } else if (value != null) {
            throw new TiffException("TIFF tag " + Tags.tiffTagName(tag, true) +
                    " has wrong type: " + value.getClass().getSimpleName() +
                    " instead of expected Number, Number[], long[] or int[]");
        }
        return results;
    }

    public int getSamplesPerPixel() throws TiffException {
        int compressionValue = optInt(Tags.COMPRESSION, 0);
        if (compressionValue == TiffIFD.COMPRESSION_OLD_JPEG) {
            return 3;
            // always 3 channels: RGB
        }
        final int samplesPerPixel = getInt(Tags.SAMPLES_PER_PIXEL, 1);
        if (samplesPerPixel < 1) {
            throw new TiffException("TIFF tag SamplesPerPixel contains illegal zero or negative value: " +
                    samplesPerPixel);
        }
        if (samplesPerPixel > MAX_NUMBER_OF_CHANNELS) {
            throw new TiffException("TIFF tag SamplesPerPixel contains too large value: " + samplesPerPixel +
                    " (maximal support number of channels is " + MAX_NUMBER_OF_CHANNELS + ")");
        }
        return samplesPerPixel;
    }

    public int[] getBitsPerSample() throws TiffException {
        int[] bitsPerSample = getIntArray(Tags.BITS_PER_SAMPLE);
        if (bitsPerSample == null) {
            bitsPerSample = new int[]{1};
            // - In the following loop, this array will be appended to necessary length.
        }
        if (bitsPerSample.length == 0) {
            throw new TiffException("Zero length of BitsPerSample array");
        }
        for (int i = 0; i < bitsPerSample.length; i++) {
            final int bits = bitsPerSample[i];
            if (bits <= 0) {
                throw new TiffException("Zero or negative BitsPerSample[" + i + "] = " + bits);
            }
            if (bits > MAX_BITS_PER_SAMPLE) {
                throw new TiffException("Too large BitsPerSample[" + i + "] = " + bits + " > " +
                        MAX_BITS_PER_SAMPLE);
            }
        }
        final int samplesPerPixel = getSamplesPerPixel();
        if (bitsPerSample.length < samplesPerPixel) {
            // - Result must contain at least samplesPerPixel elements (SCIFIO agreement)
            // It is not a bug, because getSamplesPerPixel() MAY return 3 for OLD_JPEG
            int[] newBitsPerSample = new int[samplesPerPixel];
            for (int i = 0; i < newBitsPerSample.length; i++) {
                newBitsPerSample[i] = bitsPerSample[i < bitsPerSample.length ? i : 0];
            }
            bitsPerSample = newBitsPerSample;
        }
        return bitsPerSample;
    }

    public int[] getBytesPerSample() throws TiffException {
        final int[] bitsPerSample = getBitsPerSample();
        final int[] result = new int[bitsPerSample.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (bitsPerSample[i] + 7) >>> 3;
            // ">>>" for a case of integer overflow
            assert result[i] >= 0;
        }
        return result;
    }

    /**
     * Detects the TIFF sample type, allowing to store samples of this TIFF image.
     * Note that {@link TiffSampleType#bitsPerSample()} cannot be less than
     * {@link #alignedBitDepth()}.
     *
     * @return TIFF sample type
     * @throws TiffException in the case of format problems.
     */
    public TiffSampleType sampleType() throws TiffException {
        TiffSampleType result = sampleType(true);
        assert result != null;
        return result;
    }

    public TiffSampleType sampleType(boolean requireNonNullResult) throws TiffException {
        final int alignedBitDepth;
        if (requireNonNullResult) {
            alignedBitDepth = alignedBitDepth();
        } else {
            try {
                alignedBitDepth = alignedBitDepth();
            } catch (TiffException e) {
                return null;
            }
        }
        if (alignedBitDepth == 1) {
            return TiffSampleType.BIT;
        }
        int[] sampleFormats = getIntArray(Tags.SAMPLE_FORMAT);
        if (sampleFormats == null) {
            sampleFormats = new int[]{SAMPLE_FORMAT_UINT};
        }
        if (sampleFormats.length == 0) {
            throw new TiffException("Zero length of SampleFormat array");
        }
        for (int v : sampleFormats) {
            if (v != sampleFormats[0]) {
                if (requireNonNullResult) {
                    throw new UnsupportedTiffFormatException("Unsupported TIFF IFD: " +
                            "different sample format for different samples (" +
                            Arrays.toString(sampleFormats) + ")");
                } else {
                    return null;
                }
            }
        }
        final int bytesPerSample = (alignedBitDepth + 7) >>> 3;
        TiffSampleType result = null;
        switch (sampleFormats[0]) {
            case SAMPLE_FORMAT_UINT -> {
                switch (bytesPerSample) {
                    case 1 -> result = TiffSampleType.UINT8;
                    case 2 -> result = TiffSampleType.UINT16;
                    case 3, 4 -> result = TiffSampleType.UINT32;
                    // - note: 3-byte format should be converted to 4-byte (TiffTools.unpackUnusualPrecisions)
                }
                if (result == null && requireNonNullResult) {
                    throw new UnsupportedTiffFormatException("Unsupported TIFF bit depth: " +
                            Arrays.toString(getBitsPerSample()) + " bits/sample, or " + bytesPerSample +
                            " bytes/sample for unsigned integers, " +
                            "but only 1..4 bytes/sample are supported for integers");

                }
            }
            case SAMPLE_FORMAT_INT -> {
                switch (bytesPerSample) {
                    case 1 -> result = TiffSampleType.INT8;
                    case 2 -> result = TiffSampleType.INT16;
                    case 3, 4 -> result = TiffSampleType.INT32;
                    // - note: 3-byte format should be converted to 4-byte (TiffTools.unpackUnusualPrecisions)
                }
                if (result == null && requireNonNullResult) {
                    throw new UnsupportedTiffFormatException("Unsupported TIFF bit depth: " +
                            Arrays.toString(getBitsPerSample()) + " bits/sample, or " + bytesPerSample +
                            " bytes/sample for signed integers, " +
                            "but only 1..4 bytes/sample are supported for integers");
                }
            }
            case SAMPLE_FORMAT_IEEEFP -> {
                switch (bytesPerSample) {
                    case 2, 3, 4 -> result = TiffSampleType.FLOAT;
                    case 8 -> result = TiffSampleType.DOUBLE;
                    // - note: 2/3-byte float format should be converted to 4-byte (TiffTools.unpackUnusualPrecisions)
                }
                if (result == null && requireNonNullResult) {
                    throw new UnsupportedTiffFormatException("Unsupported TIFF bit depth: " +
                            Arrays.toString(getBitsPerSample()) + " bits/sample, or " +
                            bytesPerSample + " bytes/sample for floating point values, " +
                            "but only 2, 3, 4 bytes/sample cases are supported");
                }
            }
            case SAMPLE_FORMAT_VOID -> {
                if (bytesPerSample == 1) {
                    result = TiffSampleType.UINT8;
                } else {
                    if (requireNonNullResult) {
                        throw new UnsupportedTiffFormatException("Unsupported TIFF bit depth: " +
                                Arrays.toString(getBitsPerSample()) + " bits/sample, or " +
                                bytesPerSample + " bytes/sample for void values " +
                                "(only 1 byte/sample is supported for unknown data type)");
                    }
                }
            }
            default -> {
                if (requireNonNullResult) {
                    throw new UnsupportedTiffFormatException("Unsupported TIFF data type: SampleFormat=" +
                            Arrays.toString(sampleFormats));
                }
            }
        }
        assert result == null || result.bitsPerSample() >= alignedBitDepth;
        return result;
    }

    public long[] getTileOrStripByteCounts() throws TiffException {
        final boolean tiled = hasTileInformation();
        final int tag = tiled ? Tags.TILE_BYTE_COUNTS : Tags.STRIP_BYTE_COUNTS;
        long[] counts = getLongArray(tag);
        if (tiled && counts == null) {
            counts = getLongArray(Tags.STRIP_BYTE_COUNTS);
            // - rare situation, when tile byte counts are actually stored in StripByteCounts
        }
        if (counts == null) {
            throw new TiffException("Invalid IFD: no required StripByteCounts/TileByteCounts tag");
        }
        final long numberOfTiles = (long) getTileCountX() * (long) getTileCountY();
        if (counts.length < numberOfTiles) {
            throw new TiffException("StripByteCounts/TileByteCounts length (" + counts.length +
                    ") does not match expected number of strips/tiles (" + numberOfTiles + ")");
        }
        return counts;
    }

    public long[] cachedTileOrStripByteCounts() throws TiffException {
        long[] result = this.cachedTileOrStripByteCounts;
        if (result == null) {
            this.cachedTileOrStripByteCounts = result = getTileOrStripByteCounts();
        }
        return result;
    }

    public int cachedTileOrStripByteCountLength() throws TiffException {
        return cachedTileOrStripByteCounts().length;
    }

    public int cachedTileOrStripByteCount(int index) throws TiffException {
        long[] byteCounts = cachedTileOrStripByteCounts();
        if (index < 0) {
            throw new IllegalArgumentException("Negative index = " + index);
        }
        if (index >= byteCounts.length) {
            throw new TiffException((hasTileInformation() ?
                    "Tile index is too big for TileByteCounts" :
                    "Strip index is too big for StripByteCounts") +
                    "array: it contains only " + byteCounts.length + " elements");
        }
        long result = byteCounts[index];
        if (result < 0) {
            throw new TiffException(
                    "Negative value " + result + " in " +
                            (hasTileInformation() ? "TileByteCounts" : "StripByteCounts") + " array");
        }
        if (result > Integer.MAX_VALUE) {
            throw new TiffException("Too large tile/strip #" + index + ": " + result + " bytes > 2^31-1");
        }
        return (int) result;
    }

    public long[] getTileOrStripOffsets() throws TiffException {
        final boolean tiled = hasTileInformation();
        final int tag = tiled ? Tags.TILE_OFFSETS : Tags.STRIP_OFFSETS;
        long[] offsets = getLongArray(tag);
        if (tiled && offsets == null) {
            // - rare situation, when tile offsets are actually stored in StripOffsets
            offsets = getLongArray(Tags.STRIP_OFFSETS);
        }
        if (offsets == null) {
            throw new TiffException("Invalid IFD: no required StripOffsets/TileOffsets tag");
        }
        final long numberOfTiles = (long) getTileCountX() * (long) getTileCountY();
        if (offsets.length < numberOfTiles) {
            throw new TiffException("StripByteCounts/TileByteCounts length (" + offsets.length +
                    ") does not match expected number of strips/tiles (" + numberOfTiles + ")");
        }
        // Note: old getStripOffsets method also performed correction:
        // if (offsets[i] < 0) {
        //     offsets[i] += 0x100000000L;
        // }
        // But it is not necessary with new TiffReader: if reads correct 32-bit unsigned values.

        return offsets;
    }

    public long[] cachedTileOrStripOffsets() throws TiffException {
        long[] result = this.cachedTileOrStripOffsets;
        if (result == null) {
            this.cachedTileOrStripOffsets = result = getTileOrStripOffsets();
        }
        return result;
    }

    public long cachedTileOrStripOffset(int index) throws TiffException {
        long[] offsets = cachedTileOrStripOffsets();
        if (index < 0) {
            throw new IllegalArgumentException("Negative index = " + index);
        }
        if (index >= offsets.length) {
            throw new TiffException((hasTileInformation() ?
                    "Tile index is too big for TileOffsets" :
                    "Strip index is too big for StripOffsets") +
                    "array: it contains only " + offsets.length + " elements");
        }
        final long result = offsets[index];
        if (result < 0) {
            throw new TiffException(
                    "Negative value " + result + " in " +
                            (hasTileInformation() ? "TileOffsets" : "StripOffsets") + " array");
        }
        return result;
    }

    public Optional<String> optDescription() {
        return optValue(Tags.IMAGE_DESCRIPTION, String.class);
    }

    public int getCompressionCode() throws TiffException {
        return getInt(Tags.COMPRESSION, COMPRESSION_NONE);
    }

    // Note: there is no getCompression() method, which ALWAYS returns some compression:
    // we cannot be sure that we know all possible compressions!
    public Optional<TagCompression> optCompression() {
        final int code = optInt(Tags.COMPRESSION, -1);
        return code == -1 ? Optional.empty() : Optional.ofNullable(TagCompression.valueOfCodeOrNull(code));
    }

    public int optPredictorCode() {
        return optInt(Tags.PREDICTOR, TagPredictor.NONE.code());
    }

    public TagPredictor optPredictor() {
        return TagPredictor.valueOfCodeOrUnknown(optPredictorCode());
    }

    public String compressionPrettyName() {
        final int code = optInt(Tags.COMPRESSION, -1);
        if (code == -1) {
            return "unspecified compression";
        }
        final TagCompression compression = TagCompression.valueOfCodeOrNull(code);
        return compression == null ? "unsupported compression " + code : compression.prettyName();
    }

    public TagPhotometricInterpretation getPhotometricInterpretation() throws TiffException {
        if (!containsKey(Tags.PHOTOMETRIC_INTERPRETATION)
                && getInt(Tags.COMPRESSION, 0) == COMPRESSION_OLD_JPEG) {
            return TagPhotometricInterpretation.RGB;
        }
        final int code = getInt(Tags.PHOTOMETRIC_INTERPRETATION, -1);
        return TagPhotometricInterpretation.valueOfCodeOrUnknown(code);
    }

    public int[] getYCbCrSubsampling() throws TiffException {
        final Object value = get(Tags.Y_CB_CR_SUB_SAMPLING);
        if (value == null) {
            return new int[]{2, 2};
        }
        int[] result;
        if (value instanceof int[] ints) {
            result = ints;
        } else if (value instanceof short[] shorts) {
            result = new int[shorts.length];
            for (int k = 0; k < result.length; k++) {
                result[k] = shorts[k];
            }
        } else {
            throw new TiffException("TIFF tag YCbCrSubSampling has the wrong type " +
                    value.getClass().getSimpleName() + ": must be int[] or short[]");
        }
        if (result.length < 2) {
            throw new TiffException("TIFF tag YCbCrSubSampling contains only " + result.length +
                    " elements: " + Arrays.toString(result) + "; it must contain at least 2 numbers");
        }
        for (int v : result) {
            if (v != 1 && v != 2 && v != 4) {
                throw new TiffException("TIFF tag YCbCrSubSampling must contain only values 1, 2 or 4, " +
                        "but it is " + Arrays.toString(result));
            }
        }
        return Arrays.copyOf(result, 2);
    }

    public int[] getYCbCrSubsamplingLogarithms() throws TiffException {
        int[] result = getYCbCrSubsampling();
        for (int k = 0; k < result.length; k++) {
            final int v = result[k];
            result[k] = 31 - Integer.numberOfLeadingZeros(v);
            assert 1 << result[k] == v;
        }
        return result;
    }

    public int getPlanarConfiguration() throws TiffException {
        final int result = getInt(Tags.PLANAR_CONFIGURATION, 1);
        if (result != 1 && result != 2) {
            throw new TiffException("TIFF tag PlanarConfiguration must contain only values 1 or 2, " +
                    "but it is " + result);
        }
        return result;
    }

    public boolean isPlanarSeparated() throws TiffException {
        return getPlanarConfiguration() == PLANAR_CONFIGURATION_SEPARATE;
    }

    public boolean isChunked() throws TiffException {
        return getPlanarConfiguration() == PLANAR_CONFIGURATION_CHUNKED;
    }

    public boolean isReversedFillOrder() throws TiffException {
        final int result = getInt(Tags.FILL_ORDER, 1);
        if (result != FILL_ORDER_NORMAL && result != FILL_ORDER_REVERSED) {
            throw new TiffException("TIFF tag FillOrder must contain only values 1 or 2, " +
                    "but it is " + result);
        }
        return result == FILL_ORDER_REVERSED;
    }

    public boolean hasImageDimensions() {
        return containsKey(Tags.IMAGE_WIDTH) && containsKey(Tags.IMAGE_LENGTH);
    }

    public int getImageDimX() throws TiffException {
        final int imageWidth = reqInt(Tags.IMAGE_WIDTH);
        if (imageWidth <= 0) {
            throw new TiffException("Zero or negative image width = " + imageWidth);
            // - impossible in a correct TIFF
        }
        return imageWidth;
    }

    public int getImageDimY() throws TiffException {
        final int imageLength = reqInt(Tags.IMAGE_LENGTH);
        if (imageLength <= 0) {
            throw new TiffException("Zero or negative image height = " + imageLength);
            // - impossible in a correct TIFF
        }
        return imageLength;
    }

    public int getRowsPerStrip() throws TiffException {
        final long[] rowsPerStrip = getLongArray(Tags.ROWS_PER_STRIP);
        final int imageDimY = getImageDimY();
        if (rowsPerStrip == null || rowsPerStrip.length == 0) {
            // - zero rowsPerStrip.length is possible only as a result of manual modification of this IFD
            return imageDimY == 0 ? 1 : imageDimY;
            // - imageDimY == 0 is checked to be on the safe side
        }

        // rowsPerStrip should never be more than the total number of rows
        for (int i = 0; i < rowsPerStrip.length; i++) {
            int rows = (int) Math.min(rowsPerStrip[i], imageDimY);
            if (rows <= 0) {
                throw new TiffException("Zero or negative RowsPerStrip[" + i + "] = " + rows);
            }
            rowsPerStrip[i] = rows;
        }
        final int result = (int) rowsPerStrip[0];
        assert result > 0 : result + " was not checked in the loop above?";
        for (int i = 1; i < rowsPerStrip.length; i++) {
            if (result != rowsPerStrip[i]) {
                throw new TiffException("Non-uniform RowsPerStrip is not supported");
            }
        }
        return result;
    }

    /**
     * Returns the tile width in tiled image. If there are no tiles,
     * returns max(w,1), where w is the image width.
     *
     * <p>Note: the result is always positive!
     *
     * @return tile width.
     * @throws TiffException in the case of incorrect IFD.
     */
    public int getTileSizeX() throws TiffException {
        if (hasTileInformation()) {
            // - Note: we refuse to handle situation, when TileLength presents, but TileWidth not, or vice versa
            final int tileWidth = reqInt(Tags.TILE_WIDTH);
            // - TIFF allows to use values <= 2^32-1, but in any case we cannot allocate Java array for such tile
            if (tileWidth <= 0) {
                throw new TiffException("Zero or negative tile width = " + tileWidth);
                // - impossible in a correct TIFF
            }
            return tileWidth;
        }
        final int imageDimX = getImageDimX();
        return imageDimX == 0 ? 1 : imageDimX;
        // - imageDimX == 0 is checked to be on the safe side
    }

    /**
     * Returns the tile height in tiled image, strip height in other images. If there are no tiles or strips,
     * returns max(h,1), where h is the image height.
     *
     * <p>Note: the result is always positive!
     *
     * @return tile/strip height.
     * @throws TiffException in the case of incorrect IFD.
     */
    public int getTileSizeY() throws TiffException {
        if (hasTileInformation()) {
            // - Note: we refuse to handle situation, when TileLength presents, but TileWidth not, or vice versa
            final int tileLength = reqInt(Tags.TILE_LENGTH);
            if (tileLength <= 0) {
                throw new TiffException("Zero or negative tile length (height) = " + tileLength);
                // - impossible in a correct TIFF
            }
            return tileLength;
        }
        final int stripRows = getRowsPerStrip();
        assert stripRows > 0 : "getStripRows() did not check non-positive result";
        return stripRows;
        // - unlike old SCIFIO getTileLength, we do not return 0 if rowsPerStrip==0:
        // it allows to avoid additional checks in a calling code
    }

    public int getTileCountX() throws TiffException {
        int tileSizeX = getTileSizeX();
        if (tileSizeX <= 0) {
            throw new TiffException("Zero or negative tile width = " + tileSizeX);
        }
        final long imageWidth = getImageDimX();
        final long n = (imageWidth + (long) tileSizeX - 1) / tileSizeX;
        assert n <= Integer.MAX_VALUE : "ceil(" + imageWidth + "/" + tileSizeX + ") > Integer.MAX_VALUE";
        return (int) n;
    }

    public int getTileCountY() throws TiffException {
        int tileSizeY = getTileSizeY();
        if (tileSizeY <= 0) {
            throw new TiffException("Zero or negative tile height = " + tileSizeY);
        }
        final long imageLength = getImageDimY();
        final long n = (imageLength + (long) tileSizeY - 1) / tileSizeY;
        assert n <= Integer.MAX_VALUE : "ceil(" + imageLength + "/" + tileSizeY + ") > Integer.MAX_VALUE";
        return (int) n;
    }

    /**
     * Checks that all bits per sample (<code>BitsPerSample</code> tag) for all channels are equal to the same integer,
     * and returns this integer. If it is not so, returns an empty optional result.
     * Note that some software does not support the case of unequal bits per sample.
     *
     * <p>Note: {@link TiffReader} class does not strictly require this condition, it requires only
     * equality of number of <i>bytes</i> per sample: see {@link #alignedBitDepth()}
     * (though the complete support of TIFF with different number of bits is not provided).
     * In comparison, {@link TiffWriter} class really <i>does</i> require this condition: it cannot
     * create TIFF files with different number of bits per channel.
     *
     * @return bits per sample, if this value is the same for all channels, or empty value in another case.
     * @throws TiffException in the case of any problems while parsing IFD, in particular,
     *                       if <code>BitsPerSample</code> tag contains zero or negative values.
     */
    public OptionalInt tryEqualBitDepth() throws TiffException {
        final int[] bitsPerSample = getBitsPerSample();
        final int bits0 = bitsPerSample[0];
        for (int i = 1; i < bitsPerSample.length; i++) {
            if (bitsPerSample[i] != bits0) {
                return OptionalInt.empty();
            }
        }
        return OptionalInt.of(bits0);
    }

    public OptionalInt tryEqualBitDepthAlignedByBytes() throws TiffException {
        OptionalInt result = tryEqualBitDepth();
        return result.isPresent() && (result.getAsInt() & 7) == 0 ? result : OptionalInt.empty();
    }

    /**
     * Returns the number of bits per each sample. It is calculated based on the array
     * <code>B&nbsp;=&nbsp;{@link #getBitsPerSample()}</code> (numbers of bits per each channel) as follows:
     * <ol>
     *     <li>if this array consists of the single element <code>B[0]==1</code>, the result is 1;</li>
     *     <li>in another case, if all the values <code>(B[i]+7)/8</code> (<code>&#8968;(double)B[i]/8&#8969;</code>)
     *     are equal to the same value <code>b</code>, the result is b*8
     *     (this is the number of whole bytes needed to store each sample);</li>
     *     <li>if some of values <code>(B[i]+7)/8</code> are different, {@link UnsupportedTiffFormatException}
     *     is thrown.</li>
     * </ol>
     *
     * <p>This method requires that the number of bytes, necessary to store each channel, must be
     * equal for all channels. This is also requirement for TIFF files, that can be read by {@link TiffReader} class.
     * However, equality of number of <i>bits</i> is not required; it allows, for example, to process
     * an old HiRes RGB format with 5+6+5 bits/channels.
     *
     * @return number of bits per each sample, aligned to the integer number of bytes, excepting a case
     * of pure binary 1-bit image, where the result is 1.
     * @throws TiffException if &#8968;bitsPerSample/8&#8969; values are different for some channels.
     */
    public int alignedBitDepth() throws TiffException {
        final int[] bitsPerSample = getBitsPerSample();
        if (bitsPerSample.length == 1 && bitsPerSample[0] == 1) {
            // - we do not support the BIT format for RGB and other multi-channels TIFF;
            // if this situation occurred, we process this in a common way like any N-bit image, N <= 8
            return 1;
            // - see also ordinaryBitDepth()
        }
        final int bytes0 = (bitsPerSample[0] + 7) >>> 3;
        // ">>>" for a case of integer overflow
        assert bytes0 > 0;
        // - for example, if we have 5 bits R + 6 bits G + 4 bits B, it will be ceil(6/8) = 1 byte;
        // usually the same for all components
        for (int k = 1; k < bitsPerSample.length; k++) {
            if ((bitsPerSample[k] + 7) >>> 3 != bytes0) {
                throw new UnsupportedTiffFormatException("Unsupported TIFF IFD: " +
                        "different number of bytes per samples " +
                        Arrays.toString(getBytesPerSample()) + ", based on the following number of bits: " +
                        Arrays.toString(getBitsPerSample()));
            }
            // - note that LibTiff does not support different BitsPerSample values for different components;
            // we do not support different number of BYTES for different components
        }
        return bytes0 << 3;
    }

    public int ordinaryBitDepth() throws TiffException {
        final int[] bitsPerSample = getBitsPerSample();
        if (!isOrdinaryPrecisionWithCheckingEquality(bitsPerSample)) {
            throw new UnsupportedTiffFormatException("The number of bits per sample " +
                    bitsPerSample[0] + " bits per sample for " + bitsPerSample.length +
                    " samples is not supported: " +
                    Arrays.toString(bitsPerSample) + " bits/samples");
        }
        return bitsPerSample[0];
    }

    public boolean isStandardYCbCrNonJpeg() throws TiffException {
        final TagCompression compression = optCompression().orElse(null);
        return compression != null && compression.isStandard() && !compression.isJpeg() &&
                getPhotometricInterpretation() == TagPhotometricInterpretation.Y_CB_CR;
    }

    public boolean isStandardCompression() {
        final TagCompression compression = optCompression().orElse(null);
        return compression != null && compression.isStandard();
    }

    public boolean isJpeg() {
        final TagCompression compression = optCompression().orElse(null);
        return compression != null && compression.isJpeg();
    }

    public boolean isStandardInvertedCompression() throws TiffException {
        final TagCompression compression = optCompression().orElse(null);
        return compression != null && compression.isStandard() && !compression.isJpeg() &&
                getPhotometricInterpretation().isInvertedBrightness();
    }

    public boolean isThumbnail() {
        return (optInt(Tags.NEW_SUBFILE_TYPE, 0) & FILETYPE_REDUCED_IMAGE) != 0;
    }

    public TiffIFD putMatrixInformation(Matrix<? extends PArray> matrix) {
        return putMatrixInformation(matrix, false, false);
    }

    public TiffIFD putMatrixInformation(Matrix<? extends PArray> matrix, boolean signedIntegers) {
        return putMatrixInformation(matrix, signedIntegers, false);
    }

    // interleaved is usually false, but may be used together with TiffWriter.updateMatrix for interleaved matrices
    public TiffIFD putMatrixInformation(Matrix<? extends PArray> matrix, boolean signedIntegers, boolean interleaved) {
        Objects.requireNonNull(matrix, "Null matrix");
        final int dimChannelsIndex = interleaved ? 0 : 2;
        final long numberOfChannels = matrix.dim(dimChannelsIndex);
        checkNumberOfChannels(numberOfChannels);
        assert numberOfChannels == (int) numberOfChannels : "must be checked in checkNumberOfChannels";
        final long dimX = matrix.dim(interleaved ? 1 : 0);
        final long dimY = matrix.dim(interleaved ? 2 : 1);
        putImageDimensions(dimX, dimY);
        putPixelInformation((int) numberOfChannels, TiffSampleType.valueOf(matrix.elementType(), signedIntegers));
        return this;
    }

    public TiffIFD putChannelsInformation(List<? extends Matrix<? extends PArray>> channels) {
        return putChannelsInformation(channels, false);
    }

    public TiffIFD putChannelsInformation(List<? extends Matrix<? extends PArray>> channels, boolean signedIntegers) {
        Objects.requireNonNull(channels, "Null channels");
        Matrices.checkDimensionEquality(channels, true);
        final int numberOfChannels = channels.size();
        if (numberOfChannels == 0) {
            throw new IllegalArgumentException("Empty channels list");
        }
        Matrix<? extends PArray> matrix = channels.get(0);
        checkNumberOfChannels(numberOfChannels);
        final long dimX = matrix.dimX();
        final long dimY = matrix.dimY();
        putImageDimensions(dimX, dimY);
        putPixelInformation(numberOfChannels, TiffSampleType.valueOf(matrix.elementType(), signedIntegers));
        return this;
    }

    public TiffIFD putImageDimensions(int dimX, int dimY) {
        //noinspection RedundantCast
        return putImageDimensions((long) dimX, (long) dimY);
    }

    public TiffIFD putImageDimensions(long dimX, long dimY) {
        checkImmutable();
        updateImageDimensions(dimX, dimY);
        return this;
    }

    public TiffIFD removeImageDimensions() {
        remove(Tags.IMAGE_WIDTH);
        remove(Tags.IMAGE_LENGTH);
        return this;
    }

    public TiffIFD putPixelInformation(int numberOfChannels, Class<?> elementType) {
        return putPixelInformation(numberOfChannels, TiffSampleType.valueOf(elementType, false));
    }

    /**
     * Puts base pixel type and channels information: BitsPerSample, SampleFormat, SamplesPerPixel.
     *
     * @param numberOfChannels number of channels (in other words, number of samples per every pixel);
     *                         must be not &le;{@link TiffIFD#MAX_NUMBER_OF_CHANNELS}.
     * @param sampleType       type of pixel samples.
     * @return a reference to this object.
     */
    public TiffIFD putPixelInformation(int numberOfChannels, TiffSampleType sampleType) {
        Objects.requireNonNull(sampleType, "Null sampleType");
        putNumberOfChannels(numberOfChannels);
        // - note: actual number of channels will be 3 in the case of OLD_JPEG;
        // but it is not a recommended usage (we may set OLD_JPEG compression later)
        putSampleType(sampleType);
        return this;
    }

    public TiffIFD putNumberOfChannels(int numberOfChannels) {
        checkNumberOfChannels(numberOfChannels);
        put(Tags.SAMPLES_PER_PIXEL, numberOfChannels);
        return this;
    }

    public TiffIFD putBitsPerSample(int bitsPerSample) {
        return putBitsPerSample(bitsPerSample, false, false);
    }

    public TiffIFD putBitsPerSample(int bitsPerSample, boolean signed, boolean floatingPoint) {
        checkBitsPerSample(bitsPerSample);
        final int samplesPerPixel;
        try {
            samplesPerPixel = getSamplesPerPixel();
        } catch (TiffException e) {
            throw new IllegalStateException("Cannot set TIFF samples type: SamplesPerPixel tag is invalid", e);
        }
        put(Tags.BITS_PER_SAMPLE, nInts(samplesPerPixel, bitsPerSample));
        if (floatingPoint) {
            put(Tags.SAMPLE_FORMAT, nInts(samplesPerPixel, SAMPLE_FORMAT_IEEEFP));
        } else if (signed) {
            put(Tags.SAMPLE_FORMAT, nInts(samplesPerPixel, SAMPLE_FORMAT_INT));
        } else {
            remove(Tags.SAMPLE_FORMAT);
        }
        return this;
    }

    public TiffIFD putSampleType(TiffSampleType sampleType) {
        Objects.requireNonNull(sampleType, "Null sampleType");
        final int bitsPerSample = sampleType.bitsPerSample();
        final boolean signed = sampleType.isSigned();
        final boolean floatingPoint = sampleType.isFloatingPoint();
        return putBitsPerSample(bitsPerSample, signed, floatingPoint);
    }

    public TiffIFD putCompression(TagCompression compression) {
        return putCompression(compression, false);
    }

    public TiffIFD putCompression(TagCompression compression, boolean putAlsoDefaultUncompressed) {
        if (compression == null && putAlsoDefaultUncompressed) {
            compression = TagCompression.NONE;
        }
        if (compression == null) {
            remove(Tags.COMPRESSION);
        } else {
            putCompressionCode(compression.code());
        }
        return this;
    }

    public TiffIFD putCompressionCode(int compression) {
        put(Tags.COMPRESSION, compression);
        return this;
    }

    public TiffIFD putPhotometricInterpretation(TagPhotometricInterpretation photometricInterpretation) {
        Objects.requireNonNull(photometricInterpretation, "Null photometricInterpretation");
        if (!photometricInterpretation.isUnknown()) {
            put(Tags.PHOTOMETRIC_INTERPRETATION, photometricInterpretation.code());
        }
        return this;
    }

    /**
     * Puts TIFF Predictor tag.
     * Note that only {@link TagPredictor#HORIZONTAL} case is supported by this library
     * (besides {@link TagPredictor#NONE}).
     *
     * <p>Note: if this image is binary (1 bit/pixel), we do not recommend to use this tag:
     * this library supports this case, but this is non-standard, and the resulting TIFF will not be readable
     * by usual viewers.
     *
     * @param predictor predictor tag.
     * @return a reference to this object.
     */
    public TiffIFD putPredictor(TagPredictor predictor) {
        Objects.requireNonNull(predictor, "Null predictor");
        if (predictor == TagPredictor.NONE) {
            removePredictor();
        } else if (!predictor.isUnknown()) {
            put(Tags.PREDICTOR, predictor.code());
        }
        return this;
    }

    public TiffIFD removePredictor() {
        remove(Tags.PREDICTOR);
        return this;
    }

    public TiffIFD putPlanarSeparated(boolean planarSeparated) {
        if (planarSeparated) {
            put(Tags.PLANAR_CONFIGURATION, PLANAR_CONFIGURATION_SEPARATE);
        } else {
            remove(Tags.PLANAR_CONFIGURATION);
        }
        return this;
    }

    /**
     * Returns <code>true</code> if IFD contains tags <code>TileWidth</code> and <code>TileLength</code>.
     * We call such a TIFF image <b>tiled</b>.
     * This means that the image is stored in tiled form (2D grid of rectangular tiles),
     * but not separated by strips.
     *
     * <p>If IFD contains <b>only one</b> from these tags &mdash; there is <code>TileWidth</code>,
     * but <code>TileLength</code> is not specified, or vice versa &mdash; this method throws
     * <code>FormatException</code>. It allows guaranteeing: if this method returns <code>true</code>,
     * than the tile sizes are completely specified by these two tags and do not depend on the
     * actual image sizes. Note: when this method returns <code>false</code>, the tiles sizes,
     * returned by {@link #getTileSizeY()} methods, are determined by
     * <code>RowsPerStrip</code> tag, and {@link #getTileSizeX()} is always equal to the value
     * of <code>ImageWidth</code> tag.
     *
     * <p>Note that some TIFF files use <code>StripOffsets</code> and <code>StripByteCounts</code> tags even
     * in the case of tiled image with <code>TileWidth</code> and <code>TileLength</code> tags,
     * for example, cramps-tile.tif from the known image set <i>libtiffpic</i>
     * (see <a href="https://download.osgeo.org/libtiff/">https://download.osgeo.org/libtiff/</a>).
     *
     * @return whether this IFD contain tile size information.
     * @throws TiffException if one of tags <code>TileWidth</code> and <code>TileLength</code> is present in IFD,
     *                       but the second is absent.
     */
    public boolean hasTileInformation() throws TiffException {
        final boolean hasWidth = containsKey(Tags.TILE_WIDTH);
        final boolean hasLength = containsKey(Tags.TILE_LENGTH);
        if (hasWidth != hasLength) {
            throw new TiffException("Inconsistent tiling information: tile width (TileWidth tag) is " +
                    (hasWidth ? "" : "NOT ") + "specified, but tile height (TileLength tag) is " +
                    (hasLength ? "" : "NOT ") + "specified");
        }
        return hasWidth;
    }

    public TiffIFD putTileSizes(int tileSizeX, int tileSizeY) {
        if (tileSizeX <= 0) {
            throw new IllegalArgumentException("Zero or negative tile x-size");
        }
        if (tileSizeY <= 0) {
            throw new IllegalArgumentException("Zero or negative tile y-size");
        }
        if ((tileSizeX & 15) != 0 || (tileSizeY & 15) != 0) {
            throw new IllegalArgumentException("Illegal tile sizes " + tileSizeX + "x" + tileSizeY
                    + ": they must be multiples of 16");
        }
        put(Tags.TILE_WIDTH, tileSizeX);
        put(Tags.TILE_LENGTH, tileSizeY);
        return this;
    }

    public TiffIFD defaultTileSizes() {
        return putTileSizes(DEFAULT_TILE_SIZE_X, DEFAULT_TILE_SIZE_Y);
    }

    public TiffIFD removeTileInformation() {
        remove(Tags.TILE_WIDTH);
        remove(Tags.TILE_LENGTH);
        return this;
    }

    public boolean hasStripInformation() {
        return containsKey(Tags.ROWS_PER_STRIP);
    }

    public TiffIFD putOrRemoveStripSize(Integer stripSizeY) {
        if (stripSizeY == null) {
            remove(Tags.ROWS_PER_STRIP);
        } else {
            putStripSize(stripSizeY);
        }
        return this;
    }

    public TiffIFD putStripSize(int stripSizeY) {
        if (stripSizeY <= 0) {
            throw new IllegalArgumentException("Zero or negative strip y-size");
        }
        put(Tags.ROWS_PER_STRIP, new long[]{stripSizeY});
        return this;
    }

    public TiffIFD defaultStripSize() {
        return putStripSize(DEFAULT_STRIP_SIZE);
    }

    public TiffIFD removeStripInformation() {
        remove(Tags.ROWS_PER_STRIP);
        return this;
    }

    public TiffIFD putImageDescription(String imageDescription) {
        Objects.requireNonNull(imageDescription, "Null image description");
        put(Tags.IMAGE_DESCRIPTION, imageDescription);
        return this;
    }

    public TiffIFD removeImageDescription() {
        remove(Tags.IMAGE_DESCRIPTION);
        return this;
    }

    public void removeDataPositioning() {
        remove(Tags.STRIP_OFFSETS);
        remove(Tags.STRIP_BYTE_COUNTS);
        remove(Tags.TILE_OFFSETS);
        remove(Tags.TILE_BYTE_COUNTS);
    }

    /**
     * Puts new values for <code>ImageWidth</code> and <code>ImageLength</code> tags.
     *
     * <p>Note: this method works even when IFD is frozen by {@link #freeze()} method.
     *
     * @param dimX new TIFF image width (<code>ImageWidth</code> tag); must be in 1..Integer.MAX_VALUE range.
     * @param dimY new TIFF image height (<code>ImageLength</code> tag); must be in 1..Integer.MAX_VALUE range.
     */
    public TiffIFD updateImageDimensions(long dimX, long dimY) {
        if (dimX <= 0) {
            throw new IllegalArgumentException("Zero or negative image width (x-dimension): " + dimX);
        }
        if (dimY <= 0) {
            throw new IllegalArgumentException("Zero or negative image height (y-dimension): " + dimY);
        }
        if (dimX > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too large image width = " + dimX + " (>2^31-1)");
        }
        if (dimY > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too large image height = " + dimY + " (>2^31-1)");
        }
        if (!containsKey(Tags.TILE_WIDTH) || !containsKey(Tags.TILE_LENGTH)) {
            // - we prefer not to throw exception here, like in hasTileInformation method
            checkImmutable("Image dimensions cannot be updated in non-tiled TIFF");
        }
        clearCache();
        removeEntries(Tags.IMAGE_WIDTH, Tags.IMAGE_LENGTH);
        // - to avoid illegal detection of the type
        map.put(Tags.IMAGE_WIDTH, dimX);
        map.put(Tags.IMAGE_LENGTH, dimY);
        return this;
    }

    /**
     * Puts new values for <code>TileOffsets</code> / <code>TileByteCounts</code> tags or
     * <code>StripOffsets</code> / <code>StripByteCounts</code> tag, depending on result of
     * {@link #hasTileInformation()} methods (<code>true</code> or <code>false</code> correspondingly).
     *
     * <p>Note: this method works even when IFD is frozen by {@link #freeze()} method.
     *
     * @param offsets    byte offset of each tile/strip in TIFF file.
     * @param byteCounts number of (compressed) bytes in each tile/strip.
     */
    public void updateDataPositioning(long[] offsets, long[] byteCounts) {
        Objects.requireNonNull(offsets, "Null offsets");
        Objects.requireNonNull(byteCounts, "Null byte counts");
        final boolean tiled;
        final long tileCountX;
        final long tileCountY;
        final int numberOfSeparatedPlanes;
        try {
            tiled = hasTileInformation();
            tileCountX = getTileCountX();
            tileCountY = getTileCountY();
            numberOfSeparatedPlanes = isPlanarSeparated() ? getSamplesPerPixel() : 1;
        } catch (TiffException e) {
            throw new IllegalStateException("Illegal IFD: " + e.getMessage(), e);
        }
        final long totalCount = tileCountX * tileCountY * numberOfSeparatedPlanes;
        if (tileCountX * tileCountY > Integer.MAX_VALUE || totalCount > Integer.MAX_VALUE ||
                offsets.length != totalCount || byteCounts.length != totalCount) {
            throw new IllegalArgumentException("Incorrect offsets array (" +
                    offsets.length + " values) or byte-counts array (" + byteCounts.length +
                    " values) not equal to " + totalCount + " - actual number of " +
                    (tiled ? "tiles, " + tileCountX + " x " + tileCountY :
                            "strips, " + tileCountY) +
                    (numberOfSeparatedPlanes == 1 ? "" : " x " + numberOfSeparatedPlanes + " separated channels"));
        }
        clearCache();
        removeEntries(Tags.TILE_OFFSETS, Tags.STRIP_OFFSETS, Tags.TILE_BYTE_COUNTS, Tags.STRIP_BYTE_COUNTS);
        // - to avoid illegal detection of the type
        map.put(tiled ? Tags.TILE_OFFSETS : Tags.STRIP_OFFSETS, offsets);
        map.put(tiled ? Tags.TILE_BYTE_COUNTS : Tags.STRIP_BYTE_COUNTS, byteCounts);
        // Just in case, let's also remove extra tags:
        map.remove(tiled ? Tags.STRIP_OFFSETS : Tags.TILE_OFFSETS);
        map.remove(tiled ? Tags.STRIP_BYTE_COUNTS : Tags.TILE_BYTE_COUNTS);
    }

    public Object put(int key, Object value) {
        checkImmutable();
        removeEntries(key);
        // - necessary to avoid possible bugs with detection of type
        clearCache();
        return map.put(key, value);
    }

    public Object remove(int key) {
        checkImmutable();
        removeEntries(key);
        clearCache();
        return map.remove(key);
    }

    public void clear() {
        checkImmutable();
        clearCache();
        if (detailedEntries != null) {
            detailedEntries.clear();
        }
        map.clear();
    }

    @Override
    public String toString() {
        return toString(StringFormat.BRIEF);
    }

    public String toString(StringFormat format) {
        Objects.requireNonNull(format, "Null format");
        final boolean json = format.isJson();
        final StringBuilder sb = new StringBuilder();
        sb.append(json ?
                "{\n" :
                "IFD");
        final String ifdTypeName = subIFDType == null ? "main" : Tags.tiffTagName(subIFDType, false);
        sb.append((json ?
                "  \"ifdType\" : \"%s\",\n" :
                " (%s)").formatted(ifdTypeName));
        int dimX = 0;
        int dimY = 0;
        int channels;
        int tileSizeX = 1;
        int tileSizeY = 1;
        try {
            final TiffSampleType sampleType = sampleType(false);
            sb.append((json ?
                    "  \"elementType\" : \"%s\",\n" :
                    " %s").formatted(
                    sampleType == null ? "???" : sampleType.isBinary() ? "bit" :
                            sampleType.elementType().getSimpleName()));
            channels = getSamplesPerPixel();
            if (hasImageDimensions()) {
                dimX = getImageDimX();
                dimY = getImageDimY();
                tileSizeX = getTileSizeX();
                tileSizeY = getTileSizeY();
                sb.append((json ?
                        "  \"dimX\" : %d,\n  \"dimY\" : %d,\n  \"channels\" : %d,\n" :
                        "[%dx%dx%d], ").formatted(dimX, dimY, channels));
            } else {
                sb.append((json ?
                        "  \"channels\" : %d,\n" :
                        "[?x?x%d], ").formatted(channels));
            }
        } catch (Exception e) {
            sb.append(json ?
                    "  \"exceptionBasic\" : \"%s\",\n".formatted(escapeJsonString(e.getMessage())) :
                    " [cannot detect basic information: " + e.getMessage() + "] ");
        }
        try {
            final TiffSampleType sampleType = sampleType(false);
            final long tileCountX = (dimX + (long) tileSizeX - 1) / tileSizeX;
            final long tileCountY = (dimY + (long) tileSizeY - 1) / tileSizeY;
            sb.append(json ?
                    ("""
                              "precision" : "%s",
                              "byteOrder" : "%s",
                              "bigTiff" : %s,
                              "tiled" : %s,
                            """).formatted(
                            sampleType.prettyName(),
                            getByteOrder(),
                            isBigTiff(),
                            hasTileInformation()) :
                    "%s, precision %s%s, %s, ".formatted(
                            isLittleEndian() ? "little-endian" : "big-endian",
                            sampleType == null ? "???" : sampleType.prettyName(),
                            isBigTiff() ? " [BigTIFF]" : "",
                            compressionPrettyName()));
            if (hasTileInformation()) {
                sb.append(
                        json ?
                                ("""
                                          "tiles" : {
                                            "sizeX" : %d,
                                            "sizeY" : %d,
                                            "countX" : %d,
                                            "countY" : %d,
                                            "count" : %d
                                          },
                                        """).formatted(
                                        tileSizeX, tileSizeY, tileCountX, tileCountY,
                                        tileCountX * tileCountY) :
                                "%dx%d=%d tiles %dx%d (last tile %sx%s)".formatted(
                                        tileCountX,
                                        tileCountY,
                                        tileCountX * tileCountY,
                                        tileSizeX,
                                        tileSizeY,
                                        remainderToString(dimX, tileSizeX),
                                        remainderToString(dimY, tileSizeY)));
            } else {
                sb.append(
                        json ?
                                ("""
                                          "strips" : {
                                            "sizeY" : %d,
                                            "countY" : %d
                                          },
                                        """).formatted(tileSizeY, tileCountY) :
                                "%d strips per %d lines (last strip %s, virtual \"tiles\" %dx%d)".formatted(
                                        tileCountY,
                                        tileSizeY,
                                        dimY == tileCountY * tileSizeY ?
                                                "full" :
                                                remainderToString(dimY, tileSizeY) + " lines",
                                        tileSizeX,
                                        tileSizeY));
            }
            sb.append(json ?
                    "  \"chunked\" : %s,\n".formatted(isChunked()) :
                    isChunked() ? ", chunked (interleaved)" : ", planar (separated)");
        } catch (Exception e) {
            sb.append(json ?
                    "  \"exceptionAdditional\" : \"%s\",\n".formatted(escapeJsonString(e.getMessage())) :
                    " [cannot detect additional information: " + e.getMessage() + "]");
        }
        if (!json) {
            if (hasFileOffsetForReading()) {
                sb.append(", reading offset @%d=0x%X".formatted(fileOffsetForReading, fileOffsetForReading));
            }
            if (hasFileOffsetForWriting()) {
                sb.append(", writing offset @%d=0x%X".formatted(fileOffsetForWriting, fileOffsetForWriting));
            }
            if (hasNextIFDOffset()) {
                sb.append(isLastIFD() ? ", LAST" : ", next IFD at @%d=0x%X".formatted(nextIFDOffset, nextIFDOffset));
            }
        }
        if (format == StringFormat.BRIEF) {
            assert !json;
            return sb.toString();
        }
        if (json) {
            sb.append("  \"map\" : {\n");
        } else {
            sb.append("; ").append(numberOfEntries()).append(" entries:");
        }
        final Map<Integer, TiffEntry> entries = this.detailedEntries;
        final Collection<Integer> keySequence = format.sorted ? new TreeSet<>(map.keySet()) : map.keySet();
        boolean firstEntry = true;
        for (Integer tag : keySequence) {
            final Object v = this.get(tag);
            boolean manyValues = v != null && v.getClass().isArray();
            String tagName = Tags.tiffTagName(tag, !json);
            if (json) {
                sb.append(firstEntry ? "" : ",\n");
                firstEntry = false;
                sb.append("    \"%s\" : ".formatted(escapeJsonString(tagName)));
                if (manyValues) {
                    sb.append("[");
                    appendIFDArray(sb, v, false, true);
                    sb.append("]");
                } else if (v instanceof TagRational) {
                    sb.append("\"").append(v).append("\"");
                } else if (v instanceof Number || v instanceof Boolean) {
                    sb.append(v);
                } else {
                    sb.append("\"");
                    escapeJsonString(sb, String.valueOf(v));
                    sb.append("\"");
                }
            } else {
                sb.append("%n".formatted());
                Object additional = null;
                try {
                    switch (tag) {
                        case Tags.PHOTOMETRIC_INTERPRETATION ->
                                additional = getPhotometricInterpretation().prettyName();
                        case Tags.COMPRESSION -> additional = compressionPrettyName();
                        case Tags.PLANAR_CONFIGURATION -> {
                            if (v instanceof Number number) {
                                switch (number.intValue()) {
                                    case PLANAR_CONFIGURATION_CHUNKED -> additional = "chunky";
                                    case PLANAR_CONFIGURATION_SEPARATE -> additional = "rarely-used planar";
                                }
                            }
                        }
                        case Tags.SAMPLE_FORMAT -> {
                            if (v instanceof Number number) {
                                switch (number.intValue()) {
                                    case SAMPLE_FORMAT_UINT -> additional = "unsigned integer";
                                    case SAMPLE_FORMAT_INT -> additional = "signed integer";
                                    case SAMPLE_FORMAT_IEEEFP -> additional = "IEEE float";
                                    case SAMPLE_FORMAT_VOID -> additional = "undefined";
                                    case SAMPLE_FORMAT_COMPLEX_INT -> additional = "complex integer";
                                    case SAMPLE_FORMAT_COMPLEX_IEEEFP -> additional = "complex float";
                                }
                            }
                        }
                        case Tags.FILL_ORDER -> additional = !isReversedFillOrder() ?
                                "default bits order: highest first (big-endian, 7-6-5-4-3-2-1-0)" :
                                "reversed bits order: lowest first (little-endian, 0-1-2-3-4-5-6-7)";
                        case Tags.PREDICTOR -> {
                            if (v instanceof Number number) {
                                final TagPredictor predictor = TagPredictor.valueOfCodeOrUnknown(number.intValue());
                                if (predictor != TagPredictor.UNKNOWN) {
                                    additional = predictor.prettyName();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    additional = e;
                }
                sb.append("    ").append(tagName).append(" = ");
                if (manyValues) {
                    sb.append(v.getClass().getComponentType().getSimpleName());
                    sb.append("[").append(Array.getLength(v)).append("]");
                    sb.append(" {");
                    appendIFDArray(sb, v, format.compactArrays, false);
                    sb.append("}");
                } else if (v instanceof String) {
                    sb.append("\"").append(v).append("\"");
                } else {
                    sb.append(v);
                }
                if (entries != null) {
                    final TiffEntry tiffEntry = entries.get(tag);
                    if (tiffEntry != null) {
                        sb.append(" : ").append(TagTypes.typeToString(tiffEntry.type()));
                        int valueCount = tiffEntry.valueCount();
                        if (valueCount != 1) {
                            sb.append("[").append(valueCount).append("]");
                        }
                        if (manyValues) {
                            sb.append(" at @").append(tiffEntry.valueOffset());
                        }
                    }
                }
                if (additional != null) {
                    sb.append("   [it means: ").append(additional).append("]");
                }
            }
        }
        if (json) {
            sb.append("\n  }\n}");
        }
        return sb.toString();
    }

    public static void checkNumberOfChannels(long numberOfChannels) {
        if (numberOfChannels <= 0) {
            throw new IllegalArgumentException("Zero or negative numberOfChannels = " + numberOfChannels);
        }
        if (numberOfChannels > MAX_NUMBER_OF_CHANNELS) {
            throw new IllegalArgumentException("Very large number of channels " + numberOfChannels + " > " +
                    MAX_NUMBER_OF_CHANNELS + " is not supported");
        }
    }

    public static void checkBitsPerSample(long bitsPerSample) {
        if (bitsPerSample <= 0) {
            throw new IllegalArgumentException("Zero or negative bitsPerSample = " + bitsPerSample);
        }
        if (bitsPerSample > MAX_BITS_PER_SAMPLE) {
            throw new IllegalArgumentException("Very large number of bits per sample " + bitsPerSample + " > " +
                    MAX_BITS_PER_SAMPLE + " is not supported");
        }
    }

    /**
     * Checks whether the specified sizes are allowed in principle for any kind of work
     * and returns their product.
     *
     * <p>Both <code>sizeX</code> and <code>sizeY</code> must be
     * in <code>0..Integer.MAX_VALUE</code> range, and
     * <code>sizeX&nbsp;*&nbsp;sizeY&nbsp;*&nbsp;{@link
     * #MAX_NUMBER_OF_CHANNELS}&nbsp;*&nbsp;{@link #MAX_BITS_PER_SAMPLE}</code>
     * value must be &le;Long.MAX_VALUE
     * (so we have a guarantee than we can calculate total number of pixel bits without 64-bit overflow).
     *
     * @param sizeX X-size of some region.
     * @param sizeY Y-size of some region.
     * @return the product sizeX * sizeY.
     * @throws IllegalArgumentException if one of arguments is negative or <code>&ge; 2<sup>31</sup></code>.
     * @throws TooLargeArrayException   if <code>sizeX&nbsp;*&nbsp;sizeY&nbsp;*&nbsp;{@link
     *                                  #MAX_NUMBER_OF_CHANNELS}&nbsp;*&nbsp;{@link #MAX_BITS_PER_SAMPLE}&nbsp;&ge;
     *                                  2<sup>63</sup></code>.
     */
    public static long multiplySizes(long sizeX, long sizeY) {
        if (sizeX < 0) {
            throw new IllegalArgumentException("Negative sizeX = " + sizeX);
        }
        if (sizeY < 0) {
            throw new IllegalArgumentException("Negative sizeY = " + sizeY);
        }
        if (sizeX > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too large sizeX = " + sizeX + " >2^31-1");
        }
        if (sizeY > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too large sizeY = " + sizeY + " >2^31-1");
        }
        final long result = sizeX * sizeY;
        if (result > MAX_LONG_DIV_MAX_BITS_PER_PIXEL) {
            throw new TooLargeArrayException("Extremely large area " + sizeX + "x" + sizeY +
                    ": number of pixel bits may exceed the limit 2^63 for too large number of channels " +
                    MAX_NUMBER_OF_CHANNELS + " and too many bits per sample " + MAX_BITS_PER_SAMPLE +
                    " (" + sizeX + " * " + sizeY + " * " + MAX_NUMBER_OF_CHANNELS + " * " + MAX_BITS_PER_SAMPLE +
                    " = " + (double) result * (double) (MAX_NUMBER_OF_CHANNELS * MAX_BITS_PER_SAMPLE) +
                    " > 2^63-1 = " + (double) Long.MAX_VALUE + ")");
        }
        return result;
    }

    public static int sizeOfRegionInBytes(long sizeX, long sizeY, int numberOfChannels, int bitsPerSample)
            throws TiffException {
        final long n = multiplySizes(sizeX, sizeY);
        checkNumberOfChannels(numberOfChannels);
        checkBitsPerSample(bitsPerSample);
        // - so, numberOfChannels * bitsPerSample is not too large value
        final long size = n * numberOfChannels * bitsPerSample;
        if (size > MAX_NUMBER_OF_BITS_IN_BYTE_ARRAY) {
            throw new TooLargeTiffImageException("Too large requested image " + sizeX + "x" + sizeY +
                    " (" + numberOfChannels + " samples/pixel, " +
                    bitsPerSample + " bits/sample): it requires > 2 GB to store (" +
                    Integer.MAX_VALUE + " bytes)");
        }
        assert size >= 0;
        return (int) ((size + 7) >>> 3);
    }

    private void clearCache() {
        cachedTileOrStripByteCounts = null;
        cachedTileOrStripOffsets = null;
    }

    private void checkImmutable() {
        checkImmutable("IFD cannot be modified");
    }

    private void checkImmutable(String nameOfPart) {
        if (frozen) {
            throw new IllegalStateException(nameOfPart + ": it is frozen for future writing TIFF");
        }
    }

    private void removeEntries(int... tags) {
        if (detailedEntries != null) {
            for (int tag : tags) {
                detailedEntries.remove(tag);
            }
        }
    }

    private static boolean isOrdinaryPrecisionWithCheckingEquality(int[] bitsPerSample) throws UnsupportedTiffFormatException {
        final int bits = bitsPerSample[0];
        for (int i = 1; i < bitsPerSample.length; i++) {
            if (bitsPerSample[i] != bits) {
                throw new UnsupportedTiffFormatException("The number of " +
                        "bits per samples is unequal for different channels: " +
                        Arrays.toString(bitsPerSample) +
                        " (this variant is not supported, in particular for writing)");
            }
        }
        final boolean binary = bitsPerSample.length == 1 && bits == 1;
        // - see alignedBitDepth()
        // Note: while writing, bits == 1 is possible also for more than 1 channel,
        // which is recognized as non-binary UINT8
        return binary || bits == 8 || bits == 16 || bits == 32 || bits == 64;
    }

    private static int truncatedIntValue(Number value) {
        Objects.requireNonNull(value);
        long result = value.longValue();
        if (result > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (result < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) result;
    }

    private static int checkedIntValue(Number value, int tag) throws TiffException {
        Objects.requireNonNull(value);
        long result = value.longValue();
        if (result > Integer.MAX_VALUE) {
            throw new TiffException("Very large " + Tags.tiffTagName(tag, true) +
                    " = " + value + " >= 2^31 is not supported");
        }
        if (result < Integer.MIN_VALUE) {
            throw new TiffException("Very large (by absolute value) negative " +
                    Tags.tiffTagName(tag, true) +
                    " = " + value + " < -2^31 is not supported");
        }
        return (int) result;
    }

    private static int[] nInts(int count, int filler) {
        final int[] result = new int[count];
        Arrays.fill(result, filler);
        return result;
    }

    private static String remainderToString(long a, long b) {
        long r = a / b;
        if (r * b == a) {
            return String.valueOf(b);
        } else {
            return String.valueOf(a - r * b);
        }
    }

    private static void appendIFDArray(StringBuilder sb, Object v, boolean compact, boolean jsonMode) {
        final boolean numeric = v instanceof byte[] || v instanceof short[] ||
                v instanceof int[] || v instanceof long[] ||
                v instanceof float[] || v instanceof double[] ||
                v instanceof Number[];
        if (!numeric && jsonMode) {
            return;
        }
        if (!jsonMode && v instanceof byte[] bytes) {
            appendIFDHexBytesArray(sb, bytes, compact);
            return;
        }
        final int len = Array.getLength(v);
        final int left = v instanceof short[] ? 25 : 10;
        final int right = v instanceof short[] ? 10 : 5;
        final int mask = v instanceof short[] ? 0xFFFF : 0;
        for (int k = 0; k < len; k++) {
            if (compact && k == left && len >= left + 5 + right) {
                sb.append(", ...");
                k = len - right - 1;
                continue;
            }
            if (k > 0) {
                sb.append(", ");
            }
            Object o = Array.get(v, k);
            if (mask != 0) {
                o = ((Number) o).intValue() & mask;
            }
            if (jsonMode && o instanceof TagRational) {
                sb.append("\"").append(o).append("\"");
            } else {
                sb.append(o);
            }
        }
    }

    private static void appendIFDHexBytesArray(StringBuilder sb, byte[] v, boolean compact) {
        final int len = v.length;
        for (int k = 0; k < len; k++) {
            if (compact && k == 20 && len >= 35) {
                sb.append(", ...");
                k = len - 11;
                continue;
            }
            if (k > 0) {
                sb.append(", ");
            }
            appendHexByte(sb, v[k] & 0xFF);
        }
    }

    private static void appendHexByte(StringBuilder sb, int v) {
        if (v < 16) {
            sb.append('0');
        }
        sb.append(Integer.toHexString(v));
    }

    private static String escapeJsonString(CharSequence string) {
        final StringBuilder result = new StringBuilder();
        escapeJsonString(result, string);
        return result.toString();
    }

    // Clone of the method JsonGeneratorImpl.writeEscapedString
    private static void escapeJsonString(StringBuilder result, CharSequence string) {
        int len = string.length();
        for (int i = 0; i < len; i++) {
            int begin = i;
            int end = i;
            char c = string.charAt(i);
            // find all the characters that need not be escaped
            // unescaped = %x20-21 | %x23-5B | %x5D-10FFFF
            while (c >= 0x20 && c != 0x22 && c != 0x5c) {
                i++;
                end = i;
                if (i < len) {
                    c = string.charAt(i);
                } else {
                    break;
                }
            }
            // Write characters without escaping
            if (begin < end) {
                result.append(string, begin, end);
                if (i == len) {
                    break;
                }
            }

            switch (c) {
                case '"':
                case '\\':
                    result.append('\\');
                    result.append(c);
                    break;
                case '\b':
                    result.append('\\');
                    result.append('b');
                    break;
                case '\f':
                    result.append('\\');
                    result.append('f');
                    break;
                case '\n':
                    result.append('\\');
                    result.append('n');
                    break;
                case '\r':
                    result.append('\\');
                    result.append('r');
                    break;
                case '\t':
                    result.append('\\');
                    result.append('t');
                    break;
                default:
                    String hex = "000" + Integer.toHexString(c);
                    result.append("\\u").append(hex.substring(hex.length() - 4));
            }
        }
    }

    // Helper class for internal needs, analog of SCIFIO TiffIFDEntry
    record TiffEntry(int tag, int type, int valueCount, long valueOffset) {
    }
}
