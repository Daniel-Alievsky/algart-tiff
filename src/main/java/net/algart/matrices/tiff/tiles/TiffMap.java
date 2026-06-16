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

package net.algart.matrices.tiff.tiles;

import net.algart.arrays.*;
import net.algart.io.awt.ImageToMatrix;
import net.algart.io.awt.MatrixToImage;
import net.algart.matrices.tiff.*;
import net.algart.matrices.tiff.bits.TiffUnpackingPrecisions;
import net.algart.matrices.tiff.samples.TiffSampleType;
import net.algart.matrices.tiff.samples.TiffSamples;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagDescription;
import net.algart.matrices.tiff.tags.TagPhotometric;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.ByteOrder;
import java.util.*;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * TIFF map: an object storing detailed information about a TIFF image
 * and allowing to add and manipulate its tiles (strips).
 */
public sealed class TiffMap permits TiffIOMap {
    /**
     * Possible type of tiles in the TIFF map: 2D tile grid or horizontal strips.
     * You can know the tiling type of the map by {@link TiffMap#tilingMode()} method.
     */
    public enum TilingMode {
        /**
         * True tiles: 2D grid of rectangular tiles with of equal size.
         *
         * <p>The sizes of tiles are returned by {@link TiffMap#tileSizeX()} and
         * {@link TiffMap#tileSizeY()} methods.
         *
         * <p>IFD must contain <code>TileWidth</code> and <code>TileLength</code> tags.
         * The method {@link TiffMap#ifd()}.{@link TiffIFD#hasTileInformation() hasTileInformation()}
         * returns {@code true}.</p>
         *
         * <p>The tiles on the image boundaries, partially lying outside the image,
         * are stored as full-size pixel matrices; extra pixels outside the image should be ignored.
         *
         * <p>Used for large TIFF images.</p>
         */
        TILE_GRID,

        /**
         * Horizontal strips. In terms of this library they are also called "tiles" and represented
         * with help of {@link TiffTile} and {@link TiffMap} classes.
         *
         * <p>In this case, all strips ("tiles") have a width,
         * equal to the width of the entire image, and the same height <code>H</code> (excepting the last strip,
         * which height is a reminder <code>image-height % H</code> when the full image height is not divisible
         * by <code>H</code>). The height <code>H</code> is returned by {@link #tileSizeY()} method;
         * the {@link #tileSizeX()} method returns the total image width.
         *
         * <p>IFD must <b>not</b> contain <code>TileWidth</code> and <code>TileLength</code> tags.
         * The height <code>H</code> of every strip is specified in <code>RowsPerStrip</code> tag
         * or is the full image height if there is no such tag (in the latter case, {@link TiffMap}
         * will contain only one tile).
         * Though the TIFF format allows specifying strips with different heights,
         * this library does not support this case.
         *
         * <p>If the full image height is not divisible by strip height <code>H</code>),
         * the last tile should be stored as a pixel matrix with reduced height
         * <code>image-height % H</code>: extra pixels outside the image are not stored.
         * However, if this condition is not fulfilled, for example, the last strip is stored
         * as a full-size JPEG with the height <code>H</code>, this library correctly reads such an image.
         */
        STRIPS;

        public final boolean isTileGrid() {
            return this == TILE_GRID;
        }
    }

    public enum BitImageUnpackingMode {
        NONE((byte) 0),
        UNPACK_TO_0_1((byte) 1),
        UNPACK_TO_0_255((byte) 255);

        private final byte bit1Value;

        BitImageUnpackingMode(byte bit1Value) {
            this.bit1Value = bit1Value;
        }

        public static BitImageUnpackingMode of(boolean unpack) {
            return unpack ? UNPACK_TO_0_255 : NONE;
        }


        public boolean isEnabled() {
            return this != NONE;
        }

        public byte bit1Value() {
            return bit1Value;
        }
    }

    public enum RarePrecisionMode {
        UNPACK,
        KEEP_RAW,
        FORBID;

        public RarePrecisionMode unpackIfEnabled() {
            return this == KEEP_RAW ? UNPACK : this;
        }

        public boolean isKeepRaw() {
            return this == KEEP_RAW;
        }

        public static RarePrecisionMode of(boolean unpack) {
            return unpack ? UNPACK : KEEP_RAW;
        }

        void throwIfForbidden(TiffMap map) {
            Objects.requireNonNull(map, "Null TIFF map");
            if (this == FORBID && map.isRarePrecision()) {
                throw new IllegalArgumentException("This TIFF image has a rare pixel precision, it is not allowed: " +
                        Arrays.toString(map.bitsPerSample()) + " bits/sample for " +
                        map.sampleType().prettyName() + " values");
            }
        }

        void throwIfRaw(TiffMap map, String action) {
            Objects.requireNonNull(map, "Null TIFF map");
            if (this != UNPACK && map.isRarePrecision()) {
                throw new IllegalStateException(
                        "Cannot " + action + " because the current rare precision mode is " +
                                this + ": the " + RarePrecisionMode.UNPACK + " mode is required (" +
                                Arrays.toString(map.bitsPerSample()) + " bits/sample for " +
                                map.sampleType().prettyName() + " values)");
            }
        }

        byte[] unpackIfNecessary(TiffMap map, byte[] sampleBytes, long numberOfPixels, boolean rescaleInt24)
                throws TiffException {
            Objects.requireNonNull(map, "Null TIFF map");
            Objects.requireNonNull(sampleBytes, "Null sampleBytes");
            throwIfForbidden(map);
            return this != RarePrecisionMode.UNPACK ?
                    sampleBytes :
                    TiffUnpackingPrecisions.unpackRarePrecisions(
                            sampleBytes, map.ifd(), map.numberOfChannels(), numberOfPixels, rescaleInt24);
        }
    }

    public enum ExtraChannelsMode {
        NONE,
        DROP_FOR_BUFFERED_IMAGE;

        public boolean isDropping() {
            return this == DROP_FOR_BUFFERED_IMAGE;
        }
    }

    /**
     * Maximal value of x/y-index of the tile.
     *
     * <p>This limit helps to avoid arithmetic overflow while operations with indexes.
     */
    public static final int MAX_TILE_INDEX = 1_000_000_000;

    static final boolean BUILT_IN_TIMING = TiffIO.BUILT_IN_TIMING;
    static final System.Logger LOG = System.getLogger(TiffIOMap.class.getName());
    static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);

    private final TiffIFD ifd;
    private final boolean resizable;

    private final Map<TiffTileIndex, TiffTile> tileMap = new LinkedHashMap<>();
    private final boolean planarSeparated;
    private final int numberOfChannels;
    private final int numberOfSeparatedPlanes;
    private final int tileSamplesPerPixel;
    private final int[] bitsPerSample;
    private final int normalizedBitDepth;
    private final int bitsPerUnpackedSample;
    private final int tileNormalizedBitsPerPixel;
    private final int combinedNormalizedBitsPerPixel;
    private final TiffSampleType sampleType;
    private final boolean wholeBytes;
    private final boolean rarePrecision;
    private final Class<?> elementType;
    private final ByteOrder byteOrder;
    private final long maxNumberOfSamplesInArray;
    private final TilingMode tilingMode;
    private final int tileSizeX;
    private final int tileSizeY;
    private final int tileSizeInPixels;
    private final int tileSizeInBytes;
    // - Note: we store here information about samples and tile structure, but
    // SHOULD NOT store information about image sizes (like number of tiles):
    // it is probable that we do not know final sizes while creating tiles of the image!
    private final boolean hasCompression;
    private final int compressionCode;
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private final Optional<TagCompression> compression;
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private final Optional<TagCompression> compressionOrNoneIfMissing;
    // - storing Optional value is not a usual way; we do this only for quick replacement of TiffIFD method
    private final int photometricCode;
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private final Optional<TagPhotometric> photometric;
    // - storing Optional value is not a usual way; we do this only for quick replacement of TiffIFD method
    private final int[] yCbCrSubsampling;

    private final boolean rescaleWhenIncreasingBitDepthApplicable;
    private final boolean colorCorrectionApplicable;

    private volatile BitImageUnpackingMode bitImageUnpackingMode = BitImageUnpackingMode.NONE;
    private volatile RarePrecisionMode rarePrecisionMode = RarePrecisionMode.UNPACK;
    private volatile ExtraChannelsMode extraChannelsMode = ExtraChannelsMode.NONE;

    private volatile int dimX = 0;
    private volatile int dimY = 0;
    private volatile int gridCountX = 0;
    private volatile int gridCountY = 0;
    private volatile int numberOfGridTiles = 0;

    /**
     * Creates a new tile map.
     *
     * <p>Note: you should not change the tags of the passed IFD, describing sample type, number of samples
     * and tile sizes, after creating this object. The constructor saves this information in this object
     * (it is available via access methods) and will not be renewed automatically.</p>
     *
     * @param ifd       IFD.
     * @param resizable whether maximal dimensions of this set will grow while adding new tiles,
     *                  or they are fixed and must be specified in IFD.
     */
    public TiffMap(TiffIFD ifd, boolean resizable) throws TiffException {
        this.ifd = Objects.requireNonNull(ifd, "Null IFD");
        this.resizable = resizable;
        final boolean hasImageDimensions = ifd.hasImageDimensions();
        if (!hasImageDimensions && !resizable) {
            throw new IllegalArgumentException("TIFF image sizes (ImageWidth and ImageLength tags) " +
                    "are not specified" +
                    (ifd.hasGlobalIndex() ? " in IFD #" + ifd.getGlobalIndex() : "") +
                    "; it is not allowed for" +
                    (this instanceof TiffReadMap ? "" : " non-resizable") +
                    " tile " + mapKindName());
        }
        this.tilingMode = ifd.hasTileInformation() ? TilingMode.TILE_GRID : TilingMode.STRIPS;
        if (resizable && !tilingMode.isTileGrid()) {
            throw new IllegalArgumentException("TIFF image is not tiled (TileWidth and TileLength tags " +
                    "are not specified)" +
                    (ifd.hasGlobalIndex() ? " in IFD #" + ifd.getGlobalIndex() : "") +
                    "; it is not allowed for resizable tile map: any processing " +
                    "TIFF image, such as writing its fragments, requires either knowing its final fixed sizes, " +
                    "or splitting image into tiles with known fixed sizes");
        }
        this.planarSeparated = ifd.isPlanarSeparated();
        this.numberOfChannels = ifd.getSamplesPerPixel();
        assert numberOfChannels <= TiffIFD.MAX_NUMBER_OF_CHANNELS;
        this.numberOfSeparatedPlanes = planarSeparated ? numberOfChannels : 1;
        this.tileSamplesPerPixel = planarSeparated ? 1 : numberOfChannels;
        this.bitsPerSample = ifd.getBitsPerSample().clone();
        this.normalizedBitDepth = TiffIFD.normalizedBitDepth(bitsPerSample);
        // - we allow only EQUAL number of bytes/sample (but the number if bits/sample can be different)
        if (normalizedBitDepth != 1 && ((normalizedBitDepth & 7) != 0)) {
            throw new AssertionError("normalizedBitDepth must return either 1 or 8*k, k>0");
        }
        assert (long) numberOfChannels * (long) normalizedBitDepth <
                TiffIFD.MAX_NUMBER_OF_CHANNELS * TiffIFD.MAX_BITS_PER_SAMPLE;
        // - actually must be in 8 times less
        this.tileNormalizedBitsPerPixel = tileSamplesPerPixel * normalizedBitDepth;
        this.combinedNormalizedBitsPerPixel = numberOfChannels * normalizedBitDepth;
        this.sampleType = ifd.sampleType();
        this.wholeBytes = sampleType.isWholeBytes();
        this.rarePrecision = TiffUnpackingPrecisions.isRarePrecision(ifd);
        if (this.wholeBytes != ((normalizedBitDepth & 7) == 0)) {
            throw new ConcurrentModificationException("Corrupted IFD, probably by a parallel thread" +
                    " (sample type " + sampleType + " is" +
                    (wholeBytes ? "" : " NOT") +
                    " whole-bytes, but we have " + normalizedBitDepth + " bits/sample)");
        }
        if (sampleType.isBinary() != (normalizedBitDepth == 1)) {
            throw new ConcurrentModificationException("Corrupted IFD, probably by a parallel thread" +
                    " (sample type is " + sampleType +
                    ", but we have " + normalizedBitDepth + " bits/sample)");
        }
        if (sampleType.isBinary() && numberOfChannels > 1) {
            throw new ConcurrentModificationException("Corrupted IFD, probably by a parallel thread" +
                    " (binary IFD for " + numberOfChannels + " > 1 channels, it is not supported)");
        }
        if (sampleType.isBinary() != (combinedNormalizedBitsPerPixel == 1)) {
            throw new AssertionError(normalizedBitDepth + " bits/sample, but " +
                    combinedNormalizedBitsPerPixel + " bits/pixel for " + numberOfChannels + " channels");
        }
        this.bitsPerUnpackedSample = sampleType.bitsPerSample();
        if (bitsPerUnpackedSample < normalizedBitDepth) {
            throw new AssertionError(sampleType + ".bitsPerSample() = " + bitsPerUnpackedSample +
                    " is too little: less than ifd.normalizedBitDepth() = " + normalizedBitDepth);
        }
        this.elementType = sampleType.elementType();
        this.byteOrder = ifd.getByteOrder();
        this.maxNumberOfSamplesInArray = sampleType.maxNumberOfSamplesInArray();
        this.tileSizeX = ifd.getTileSizeX();
        this.tileSizeY = ifd.getTileSizeY();
        assert tileSizeX > 0 && tileSizeY > 0 : "non-positive tile sizes are not checked in IFD methods";
        this.hasCompression = ifd.hasCompression();
        this.compressionCode = ifd.optCompressionCode(-1);
        this.compression = ifd.optCompression();
        this.compressionOrNoneIfMissing = hasCompression ? compression : Optional.of(TagCompression.NONE);
        if (!hasCompression && compressionCode != -1) {
            throw new ConcurrentModificationException("Corrupted IFD, probably by a parallel thread" +
                    " (hasCompression " + sampleType +
                    ", but we have compressionCode=" + compressionCode + ")");
        }
        this.photometricCode = ifd.getPhotometricCode();
        this.photometric = ifd.optPhotometric();
        this.yCbCrSubsampling = ifd.getYCbCrSubsampling();
        this.rescaleWhenIncreasingBitDepthApplicable = TiffReader.isRescaleWhenIncreasingBitDepthApplicable(ifd);
        this.colorCorrectionApplicable = TiffReader.isColorCorrectionApplicable(ifd);
        if (hasImageDimensions) {
            setDimensions(ifd.getImageDimX(), ifd.getImageDimY(), false);
        }
        if ((long) tileSizeX * (long) tileSizeY > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Very large " +
                    (tilingMode.isTileGrid() ? "TIFF tiles " : "TIFF strips ")
                    + tileSizeX + "x" + tileSizeY +
                    " >= 2^31 pixels are not supported");
            // - note that it is also checked deeper in the next operator
        }
        this.tileSizeInPixels = tileSizeX * tileSizeY;
        if ((long) tileSizeInPixels * (long) tileNormalizedBitsPerPixel > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Very large TIFF tiles " + tileSizeX + "x" + tileSizeY +
                    ", " + tileSamplesPerPixel + " channels per " + normalizedBitDepth +
                    " bits >= 2^31 bits (256 MB) are not supported");
        }
        this.tileSizeInBytes = (tileSizeInPixels * tileNormalizedBitsPerPixel + 7) >>> 3;
    }

    public TiffIFD ifd() {
        return ifd;
    }

    public static List<TiffIFD> ifds(Collection<? extends TiffMap> maps) {
        Objects.requireNonNull(maps, "Null maps");
        final List<TiffIFD> result = new ArrayList<>(maps.size());
        for (TiffMap map : maps) {
            result.add(map == null ? null : map.ifd());
        }
        return result;
    }

    public Map<TiffTileIndex, TiffTile> tileMap() {
        return Collections.unmodifiableMap(tileMap);
    }

    public Set<TiffTileIndex> indexes() {
        return Collections.unmodifiableSet(tileMap.keySet());
    }

    public Collection<TiffTile> tiles() {
        return Collections.unmodifiableCollection(tileMap.values());
    }

    public boolean isResizable() {
        return resizable;
    }

    public boolean isPlanarSeparated() {
        return planarSeparated;
    }

    public int numberOfChannels() {
        return numberOfChannels;
    }

    public int numberOfSeparatedPlanes() {
        return numberOfSeparatedPlanes;
    }

    public int tileSamplesPerPixel() {
        return tileSamplesPerPixel;
    }

    public TiffSampleType sampleType() {
        return sampleType;
    }

    public boolean isBinary() {
        return sampleType.isBinary();
    }

    public boolean isWholeBytes() {
        return wholeBytes;
    }

    /**
     * Returns {@code true} for 3 cases of <i>rare precision</i>:
     *
     * <ul>
     *     <li>16-bit floating-point values,</li>
     *     <li>24-bit floating-point values,</li>
     *     <li>24-bit integer values (for the case of K-bit samples, 16&le;K&lt;24).</li>
     * </ul>
     *
     * @return if the tiles in this map contain 16/24-bit floating point pixels or 24-bit integer values.
     * @see TiffUnpackingPrecisions#unpackRarePrecisions(byte[], TiffIFD, int, long, boolean)
     */
    public boolean isRarePrecision() {
        return rarePrecision;
    }

    public int[] bitsPerSample() {
        return bitsPerSample.clone();
    }

    public OptionalInt tryEqualBitDepth() {
        return TiffIFD.tryEqualBitDepth(bitsPerSample);
    }

    /**
     * Minimal number of bits, necessary to store one channel of the pixel:
     * the value of BitsPerSample TIFF tag, aligned to the nearest non-lesser multiple of 8,
     * or 1 in the case of a single-channel binary matrix (BitsPerSample=1, SamplesPerPixel=1).
     * This class requires that this value is equal for all channels, even
     * if <code>BitsPerSample</code> tag contain different number of bits per channel (for example, 5+6+5).
     *
     * <p>Note that the actual number of bits, used for storing the pixel samples in memory
     * after reading data from a TIFF file, may be little greater: see {@link #bitsPerUnpackedSample()}.
     *
     * @return number of bits, necessary to store one channel of the pixel inside TIFF.
     */
    public int normalizedBitDepth() {
        return normalizedBitDepth;
    }

    public OptionalInt bytesPerSample() {
        assert wholeBytes == ((normalizedBitDepth & 7) == 0) : "must be checked ins the constructor";
        return wholeBytes ? OptionalInt.of(normalizedBitDepth >>> 3) : OptionalInt.empty();
    }

    /**
     * Number of bits, actually used for storing one channel of the pixel in memory.
     * This number of bytes is correct for data, loaded from a TIFF file by
     * {@link TiffReader}, and for source data,
     * that should be written by {@link TiffWriter}.
     *
     * <p>Usually this value is equal to results of {@link #normalizedBitDepth()},
     * excepting the following rare cases, called <b>unusual precisions</b>:</p>
     *
     * <ul>
     *     <li>every channel is encoded as N-bit integer value, where 17&le;N&le;24, and, so, requires 3 bytes:
     *     this method returns 32, {@link #normalizedBitDepth()} returns 24
     *     (image, stored in memory, must have 2<sup>k</sup> bytes (k=1..3) per every sample, to allow representing
     *     it by one of Java types <code>byte</code>, <code>short</code>, <code>int</code>,
     *     <code>float</code>, <code>double</code>);
     *     </li>
     *     <li>pixels are encoded as 16-bit or 24-bit floating point values:
     *     this method returns 32, {@link #normalizedBitDepth()} returns 16/24
     *     (in memory, such an image will be unpacked into a usual array of 32-bit <code>float</code> values).
     *     </li>
     * </ul>
     *
     * <p>Note that this difference is possible only while reading TIFF files, created by some other software.
     * While using {@link TiffWriter} class of this module,
     * it is not allowed to write image with precisions listed above.</p>
     *
     * @return number of bytes, used for storing one channel of the pixel in memory.
     * @see TiffMap#setRarePrecisionMode(RarePrecisionMode)
     */
    public int bitsPerUnpackedSample() {
        return bitsPerUnpackedSample;
    }

    public int tileNormalizedBitsPerPixel() {
        return tileNormalizedBitsPerPixel;
    }

    public int combinedNormalizedBitsPerPixel() {
        return combinedNormalizedBitsPerPixel;
    }

    public Class<?> elementType() {
        return elementType;
    }

    public ByteOrder byteOrder() {
        return byteOrder;
    }

    public int sizeOfRegionWithPossibleNonStandardPrecisions(long sizeX, long sizeY) throws TiffException {
        return TiffIFD.sizeOfRegionInBytes(sizeX, sizeY, numberOfChannels, normalizedBitDepth);
    }

    public long maxNumberOfSamplesInArray() {
        return maxNumberOfSamplesInArray;
    }

    public TagDescription description() {
        return ifd.getDescription();
    }

    public TilingMode tilingMode() {
        return tilingMode;
    }

    public boolean isTiled() {
        return tilingMode.isTileGrid();
    }

    public int tileSizeX() {
        return tileSizeX;
    }

    public int tileSizeY() {
        return tileSizeY;
    }

    public int tileSizeInPixels() {
        return tileSizeInPixels;
    }

    public int tileSizeInBytes() {
        return tileSizeInBytes;
    }

    public boolean hasCompression() {
        return hasCompression;
    }

    public int compressionCode() {
        return compressionCode;
    }

    public Optional<TagCompression> compression() {
        return compression;
    }

    /**
     * Returns {@link #ifd()}.{@link TiffIFD#optCompression() optCompression()} or
     * <code>Optional.of({@link TagCompression#NONE})</code> if <code>!{@link #hasCompression()}</code>.
     * In other words, this method "understands" that the default compression is {@link TagCompression#NONE},
     * but still allows to recognize unknown compressions which should be processed via external codecs.
     *
     * @return TIFF compression or <code>Optional.empty()</code> for unknown compression.
     */
    public Optional<TagCompression> compressionOrNoneForMissing() {
        return compressionOrNoneIfMissing;
    }

    public int photometricCode() {
        return photometricCode;
    }

    public Optional<TagPhotometric> photometric() {
        return photometric;
    }

    public int[] getYCbCrSubsampling() {
        return yCbCrSubsampling == null ? null : yCbCrSubsampling.clone();
    }

    /**
     * Returns <code>{@link TiffReader#isRescaleWhenIncreasingBitDepthApplicable(TiffIFD)
     * TiffReader.isRescaleWhenIncreasingBitDepthApplicable}({@link #ifd()})</code>.
     *
     * @return <code>true</code> if arithmetic rescaling is applicable to the TIFF image.
     * @see TiffReader#setRescaleWhenIncreasingBitDepth(boolean)
     */
    public boolean isRescaleWhenIncreasingBitDepthApplicable() {
        return rescaleWhenIncreasingBitDepthApplicable;
    }

    /**
     * Returns <code>{@link TiffReader#isColorCorrectionApplicable(TiffIFD)
     * TiffReader.isColorCorrectionApplicable}({@link #ifd()})</code>.
     *
     * @return <code>true</code> if color correction is applicable to this TIFF image.
     * @see TiffReader#setColorCorrection(boolean)
     */
    public boolean isColorCorrectionApplicable() {
        return colorCorrectionApplicable;
    }

    public final BitImageUnpackingMode getBitImageUnpackingMode() {
        return bitImageUnpackingMode;
    }

    /**
     * Sets the mode describing whether to unpack binary images (one bit per pixel, black-and-white images)
     * into {@code byte} matrices: black pixels to value 0, white pixels to a value depending on the mode.
     *
     * <p>By default, this mode is {@link BitImageUnpackingMode#NONE}.
     * In this case, {@link TiffReadMap#readMatrix()} and similar methods return binary AlgART matrices,
     * where the bits are packed into {@code long[]} values (64 bits per every {@code long}).</p>
     *
     * <p>Note that some TIFF images use <i>m</i>&gt;1 bits per pixel, where <i>m</i> is not divisible by 8,
     * such as 4-bit indexed images with a palette or 15-bit RGB images (5+5+5 bits/channel).
     * Such images are always unpacked to a format with an integer number of bytes per channel (<i>m</i>=8*<i>k</i>).
     * The only exception is 1-bit monochrome images: in this case, unpacking into bytes
     * is controlled by this method.</p>
     *
     * <p>Note: {@link TiffTile} methods {@link TiffTile#getUnpackedSampleBytes()},
     * {@link TiffTile#getUnpackedJavaArray()} and {@link TiffTile#getUnpackedMatrix()}
     * do not support this feature: they always return packed binary data,
     * as in the mode {@link BitImageUnpackingMode#NONE}.</p>
     *
     * @param bitImageUnpackingMode the unpacking mode for 1-bit monochrome images.
     * @return a reference to this object.
     */
    public TiffMap setBitImageUnpackingMode(BitImageUnpackingMode bitImageUnpackingMode) {
        this.bitImageUnpackingMode =
                Objects.requireNonNull(bitImageUnpackingMode, "Null bitImageUnpackingMode");
        return this;
    }

    public RarePrecisionMode getRarePrecisionMode() {
        return rarePrecisionMode;
    }

    /**
     * Sets the mode determining how to handle rare pixel precision (bits/sample) that differs
     * from all standard precisions supported by the {@link TiffSampleType} class.
     *
     * <p>These include 3-byte integer samples (17..24 bits/sample) and 16- or 24-bit floating-point formats.
     * Depending on the mode, these formats are handled as follows:</p>
     * <ul>
     * <li>{@link RarePrecisionMode#UNPACK}: unpacked to 32-bit integer or floating-point values;</li>
     * <li>{@link RarePrecisionMode#FORBID}: causes an exception to be thrown
     * immediately during this method call;</li>
     * <li>{@link RarePrecisionMode#KEEP_RAW}: loaded as-is.</li>
     * </ul>
     *
     * <p>This mode is used inside the {@link TiffReadMap#readSampleBytes(int, int, int, int)}
     * method after all tiles have been read.</p>
     *
     * <p>The {@link RarePrecisionMode#KEEP_RAW} mode is incompatible with
     * high-level reading methods like {@link TiffReadMap#readMatrix()} and
     * {@link TiffReadMap#readJavaArray()}: in this mode, these methods throw an {@link IllegalStateException}
     * for images with {@link #isRarePrecision() rare precision}.</p>
     *
     * <p>The default mode is {@link RarePrecisionMode#UNPACK}. Usually, there is no reason to change it,
     * except for compatibility constraints or a strict requirement to maximize memory savings while processing
     * 16/24-bit floating-point values.</p>
     *
     * <p>Note that the {@link TiffTile#getDecodedData() decoded data} in {@link TiffTile}
     * is not unpacked in the case of rare precision.
     * On the other hand, all other precisions, such as 4-bit or 12-bit (except for the 1-channel 1-bit case),
     * are always unpacked to the nearest multiple of 8 bits (byte boundary) when decoding tiles.</p>
     *
     * <p>Also note: {@link TiffTile} methods {@link TiffTile#getUnpackedSampleBytes()},
     * {@link TiffTile#getUnpackedJavaArray()}, and {@link TiffTile#getUnpackedMatrix()}
     * always unpack rare precision data, behaving as in the {@link RarePrecisionMode#UNPACK} mode.
     * However, you can access raw (not unpacked) samples via the base method
     * {@link TiffTile#getDecodedData()}.</p>
     *
     * @param rarePrecisionMode the mode for processing unusual pixel precisions.
     * @return a reference to this object.
     * @throws NullPointerException     if the argument is {@code null}.
     * @throws IllegalArgumentException if the argument is {@link RarePrecisionMode#FORBID} and the current
     * image has {@link #isRarePrecision() rare precision}.
     * @see TiffReader#completeDecoding(TiffTile)
     * @see TiffMap#bitsPerUnpackedSample()
     */
    public TiffMap setRarePrecisionMode(RarePrecisionMode rarePrecisionMode) {
        Objects.requireNonNull(rarePrecisionMode, "Null rarePrecisionMode");
        rarePrecisionMode.throwIfForbidden(this);
        this.rarePrecisionMode = rarePrecisionMode;
        return this;
    }

    /**
     * Throws {@link IllegalStateException} if this image has a {@link #isRarePrecision() rare precision}
     * and if the current {@link #setRarePrecisionMode(RarePrecisionMode) rare precision mode} is not
     * {@link RarePrecisionMode#UNPACK}.
     */
    public void ensureUnpackedRarePrecision() {
        rarePrecisionMode.throwIfRaw(this,"process non-standard TIFF pixel precision");
    }

    public ExtraChannelsMode getExtraChannelsMode() {
        return extraChannelsMode;
    }

    /**
     * Sets the mode specifying how to handle extra channels if the source TIFF contains 5 or more channels
     * when reading a {@link BufferedImage}.
     *
     * <p>Standard {@link BufferedImage} configurations (such as RGB or ARGB) support at most 4 channels.
     * Depending on the mode, extra channels are handled as follows:</p>
     * <ul>
     * <li>{@link ExtraChannelsMode#DROP_FOR_BUFFERED_IMAGE}: extra channels are automatically dropped
     * to provide a viewable image;</li>
     * <li>{@link ExtraChannelsMode#NONE}: an exception is thrown when attempting to convert such
     * multichannel images into a {@code BufferedImage}.</li>
     * </ul>
     *
     * <p>This is a "safety" parameter designed specifically for {@link TiffReadMap#readBufferedImage} and
     * similar methods.</p>
     *
     * @param extraChannelsMode the mode specifying whether to drop extra channels to stay compatible
     * with {@code BufferedImage}.
     * @return a reference to this object.
     */
    public TiffMap setExtraChannelsMode(ExtraChannelsMode extraChannelsMode) {
        this.extraChannelsMode = Objects.requireNonNull(extraChannelsMode, "Null extraChannelsMode");
        return this;
    }

    public int dimX() {
        return dimX;
    }

    public int dimY() {
        return dimY;
    }

    public long totalSizeInPixels() {
        return (long) dimX * (long) dimY;
    }

    public long totalSizeInBytes() {
        return (Math.multiplyExact(totalSizeInPixels(), (long) combinedNormalizedBitsPerPixel) + 7) >>> 3;
        // - but overflow here should be impossible due to the check in setDimensions
    }

    public void setDimensions(long dimX, long dimY) {
        setDimensions(dimX, dimY, true);
    }

    public void checkZeroDimensions() {
        if (dimX == 0 || dimY == 0) {
            throw new IllegalStateException("Zero/unset map dimensions " + dimX + "x" + dimY + " are not allowed here");
        }
    }

    public void checkTooSmallDimensionsForCurrentGrid() {
        final int gridCountX = (int) ((long) dimX + (long) tileSizeX - 1) / tileSizeX;
        final int gridCountY = (int) ((long) dimY + (long) tileSizeY - 1) / tileSizeY;
        assert gridCountX <= this.gridCountX && gridCountY <= this.gridCountY :
                "Grid dimensions were not correctly grown according map dimensions";
        if (gridCountX != this.gridCountX || gridCountY != this.gridCountY) {
            assert resizable : "Map dimensions mismatch to grid dimensions: impossible for non-resizable map";
            throw new IllegalStateException("Map dimensions " + dimX + "x" + dimY +
                    " are too small for current tile grid: " + this);
        }
    }

    /**
     * Replaces total image sizes to maximums from their current values and <code>newMinimalDimX/Y</code>.
     *
     * <p>Note: if both new x/y-sizes are not greater than existing ones, this method does nothing
     * and can be called even if not {@link #isResizable()}.</p>
     *
     * <p>Also note: negative arguments are allowed but have no effect (as if they would be zero).</p>
     *
     * @param newMinimalDimX new minimal value for {@link #dimX() sizeX};
     *                       must not be grater than <code>Integer.MAX_VALUE</code>.
     * @param newMinimalDimY new minimal value for {@link #dimY() sizeY};
     *                       must not be grater than <code>Integer.MAX_VALUE</code>.
     */
    public void expandDimensions(long newMinimalDimX, long newMinimalDimY) {
        if (newMinimalDimX > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too large image new minimal x-dimension = " +
                    newMinimalDimX + " (>2^31-1)");
        }
        if (newMinimalDimY > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too large image new minimal y-dimension = " +
                    newMinimalDimY + " (>2^31-1)");
        }
        if (needToExpandDimensions((int) newMinimalDimX, (int) newMinimalDimY)) {
            setDimensions(Math.max(dimX, newMinimalDimX), Math.max(dimY, newMinimalDimY));
        }
    }

    /**
     * Equivalent to <code>newMinimalDimX > {@link #dimX()} || newMinimalDimY > {@link #dimY()}</code>.
     * If this method returns <code>false</code>, the {@link #expandDimensions} method with the same arguments
     * does nothing.
     *
     * @param newMinimalDimX new minimal value for {@link #dimX() sizeX}.
     * @param newMinimalDimY new minimal value for {@link #dimY() sizeY}.
     * @return whether one of the current dimensions is less than the corresponding argument.
     */
    public boolean needToExpandDimensions(int newMinimalDimX, int newMinimalDimY) {
        return newMinimalDimX > dimX || newMinimalDimY > dimY;
    }

    public int gridCountX() {
        return gridCountX;
    }

    public int gridCountY() {
        return gridCountY;
    }

    public int numberOfGridTiles() {
        return numberOfGridTiles;
    }

    /**
     * Replaces tile x/y-count to maximums from their current values and <code>newMinimalGridCountX/Y</code>.
     *
     * <p>Note: the arguments are the desired minimal tile <i>counts</i>, not tile <i>indexes</i>.
     * So, you can freely specify zero arguments, and this method will do nothing in this case.
     *
     * <p>Note: if both new x/y-counts are not greater than existing ones, this method does nothing
     * and can be called even if not {@link #isResizable()}.
     *
     * <p>Note: this method is called automatically while changing total image sizes.
     *
     * @param newMinimalGridCountX new minimal value for {@link #gridCountX()}.
     * @param newMinimalGridCountY new minimal value for {@link #gridCountY()}.
     */
    public void expandGrid(int newMinimalGridCountX, int newMinimalGridCountY) {
        expandGrid(newMinimalGridCountX, newMinimalGridCountY, true);
    }

    public void checkPixelCompatibility(int numberOfChannels, TiffSampleType sampleType) throws TiffException {
        Objects.requireNonNull(sampleType, "Null sampleType");
        if (numberOfChannels <= 0) {
            throw new IllegalArgumentException("Zero or negative numberOfChannels = " + numberOfChannels);
        }
        if (numberOfChannels != this.numberOfChannels) {
            throw new TiffException("Number of channel mismatch: expected " + numberOfChannels +
                    " channels, but TIFF image contains " + this.numberOfChannels + " channels");
        }
        if (sampleType != this.sampleType) {
            throw new TiffException(
                    "Sample type mismatch: expected elements are " + sampleType.prettyName()
                            + ", but TIFF image contains elements " + this.sampleType.prettyName());
        }
    }

    public int linearIndex(int xIndex, int yIndex, int separatedPlaneIndex) {
        if (separatedPlaneIndex < 0 || separatedPlaneIndex >= numberOfSeparatedPlanes) {
            throw new IndexOutOfBoundsException("Separated plane index " + separatedPlaneIndex +
                    " is out of range 0.." + (numberOfSeparatedPlanes - 1));
        }
        if (xIndex < 0 || xIndex >= this.gridCountX || yIndex < 0 || yIndex >= this.gridCountY) {
            throw new IndexOutOfBoundsException("One of X/Y-indexes (" + xIndex + ", " + yIndex +
                    ") of the tile is out of ranges 0.." + (this.gridCountX - 1) + ", 0.." + (this.gridCountY - 1));
        }
        // - if the tile is out of bounds, it means that we do not know actual grid dimensions
        // (even it is resizable): there is no way to calculate the correct linear index
        return (separatedPlaneIndex * this.gridCountY + yIndex) * this.gridCountX + xIndex;
        // - overflow impossible: setDimensions checks that gridCountX * gridCountY * numberOfSeparatedPlanes < 2^31
    }

    public TiffTileIndex indexFromLinear(int linearIndex) {
        if (linearIndex < 0 || linearIndex >= numberOfGridTiles) {
            throw new IndexOutOfBoundsException("Linear index " + linearIndex +
                    " is out of range 0.." + (numberOfGridTiles - 1));
        }
        int b = linearIndex / gridCountX;
        int x = linearIndex - b * gridCountX;
        int separatedPlaneIndex = b / gridCountY;
        int y = b - separatedPlaneIndex * gridCountY;
        return new TiffTileIndex(this, x, y, separatedPlaneIndex);
    }

    public TiffTileIndex index(int x, int y) {
        return new TiffTileIndex(this, x, y, 0);
    }

    public TiffTileIndex index(int x, int y, int separatedPlaneIndex) {
        return new TiffTileIndex(this, x, y, separatedPlaneIndex);
    }

    public TiffTileIndex copyIndex(TiffTileIndex other) {
        Objects.requireNonNull(other, "Null other index");
        return new TiffTileIndex(this, other.xIndex(), other.yIndex(), other.separatedPlaneIndex());
    }

    public void checkTileIndexIFD(TiffTileIndex tileIndex) {
        Objects.requireNonNull(tileIndex, "Null tile index");
        if (tileIndex.ifd() != this.ifd) {
            // - Checking references, not content!
            // Checking IFD, not reference to map ("this"): there is no sense to disable creating a new map
            // and copying the tiles from the given map there.
            throw new IllegalArgumentException("Illegal tile index: tile map cannot process tiles from different IFD");
        }
    }

    public int numberOfTiles() {
        return tileMap.size();
    }

    public TiffTile getOrNew(int x, int y) {
        return getOrNew(index(x, y));
    }

    public TiffTile getOrNew(int x, int y, int separatedPlaneIndex) {
        return getOrNew(index(x, y, separatedPlaneIndex));
    }

    public TiffTile getOrNew(TiffTileIndex tileIndex) {
        TiffTile result = get(tileIndex);
        if (result == null) {
            result = new TiffTile(tileIndex);
            put(result);
        }
        return result;
    }

    public TiffTile get(TiffTileIndex tileIndex) {
        checkTileIndexIFD(tileIndex);
        return tileMap.get(tileIndex);
    }

    public void put(TiffTile tile) {
        Objects.requireNonNull(tile, "Null tile");
        final TiffTileIndex tileIndex = tile.index();
        checkTileIndexIFD(tileIndex);
        if (resizable) {
            expandGrid(tileIndex.xIndex() + 1, tileIndex.yIndex() + 1);
        } else {
            if (tileIndex.xIndex() >= gridCountX || tileIndex.yIndex() >= gridCountY) {
                // sizeX-1: tile MAY be partially outside the image, but it MUST have at least 1 pixel inside it
                throw new IndexOutOfBoundsException("New tile is completely outside the image " +
                        "(out of maximal tilemap sizes) " + dimX + "x" + dimY + ": " + tileIndex);
            }
        }
        tileMap.put(tileIndex, tile);
    }

    public TiffTile remove(TiffTileIndex tileIndex) {
        checkTileIndexIFD(tileIndex);
        return tileMap.remove(tileIndex);
    }

    public void putAll(Collection<TiffTile> tiles) {
        Objects.requireNonNull(tiles, "Null tiles");
        tiles.forEach(this::put);
    }

    /**
     * Builds the tile map by calling {@link #getOrNew(int, int, int)} for the regular grid of tiles.
     *
     * <p>If the map is a {@link net.algart.matrices.tiff.tiles.TiffMap.TilingMode#STRIPS stripped TIFF image},
     * each created or already existing tile (i.e., a strip) is automatically cropped by the grid dimensions using
     * {@link TiffTile#cropStripToMap()}.
     *
     * <p>Note: this method guarantees that the {@link #tileMap()} and {@link #tiles()} are sorted by
     * the tile {@link TiffTileIndex#linear() linear index}.
     */
    public void buildTileGrid() {
        final Map<TiffTileIndex, TiffTile> newMap = new LinkedHashMap<>();
        final int numberOfSeparatedPlanes = this.numberOfSeparatedPlanes;
        final int gridCountY = this.gridCountY;
        final int gridCountX = this.gridCountX;
        // - note: the fields above may increase while growing the map
        for (int p = 0, linear = 0; p < numberOfSeparatedPlanes; p++) {
            for (int y = 0; y < gridCountY; y++) {
                for (int x = 0; x < gridCountX; x++, linear++) {
                    final TiffTileIndex index = index(x, y, p);
                    if (index.linear() != linear) {
                        throw new ConcurrentModificationException("Invalid linear index: " + index.linear() +
                                " for (" + x + ", " + y + ", " + p +
                                "),%nprobably because of growing the map by a parallel thread:%n%s"
                                        .formatted(this));

                    }
                    final TiffTile tile = getOrNew(index);
                    tile.cropStripToMap();
                    newMap.put(index, tile);
                }
            }
        }
        this.tileMap.clear();
        this.tileMap.putAll(newMap);
    }

    public void cropAllStrips() {
        cropAll(true);
    }

    public void cropAll(boolean strippedOnly) {
        tileMap.values().forEach(tile -> tile.cropToMap(strippedOnly));
    }

    public boolean hasUnset() {
        return tileMap.values().stream().anyMatch(TiffTile::hasUnsetArea);
    }

    public void markAllAsUnset() {
        tileMap.values().forEach(TiffTile::markWholeTileAsUnset);
    }

    public void cropAllUnset() {
        tileMap.values().forEach(TiffTile::cropUnsetAreaToMap);
    }

    public List<TiffTile> findCompletedTiles() {
        return findTiles(TiffTile::isCompleted);
    }

    public List<TiffTile> findTiles(Predicate<TiffTile> filter) {
        Objects.requireNonNull(filter, "Null filter");
        return tileMap.values().stream().filter(filter).collect(Collectors.toList());
    }

    /**
     * Calls {@link TiffTile#freeData()} for all tiles in the map,
     * Unlike {@link #clear}, this method does not remove tiles from the map:
     * some special tile information like {@link TiffTile#getStoredInFileDataLength()} stays available.
     */
    public void freeAllData() {
        tileMap.values().forEach(TiffTile::freeData);
    }

    /**
     * Removes all tiles from the map.
     *
     * @param clearDimensions whether we need also to set map dimensions to 0x0.
     */
    public void clear(boolean clearDimensions) {
        tileMap.clear();
        if (clearDimensions) {
            setDimensions(0, 0);
            // - exception if !resizable
            gridCountX = 0;
            gridCountY = 0;
            numberOfGridTiles = 0;
            // - note: this is the only way to reduce tileCountX/Y!
        }
    }

    public void copyAllData(TiffMap source, boolean cloneData) {
        Objects.requireNonNull(source, "Null source TIFF map");
        if (source.numberOfSeparatedPlanes != this.numberOfSeparatedPlanes) {
            throw new IllegalArgumentException("Number of separated planes in the source (" +
                    source.numberOfSeparatedPlanes + ") and this map (" + this.numberOfSeparatedPlanes +
                    ") do not match");
        }
        if (source.gridCountX != this.gridCountX || source.gridCountY != this.gridCountY) {
            throw new IllegalArgumentException("Grid sizes in the source map (" +
                    source.gridCountX + "x" + source.gridCountY + " tiles) and this map (" +
                    this.gridCountX + "x" + source.gridCountY + " tiles) do not match");
        }
        for (TiffTile tile : source.tiles()) {
            this.getOrNew(copyIndex(tile.index())).copyData(tile, cloneData);
        }
    }

    public boolean isByteOrderCompatible(ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "Null byte order");
        return byteOrder == this.byteOrder || sampleType.isBinary() || sampleType.bitsPerSample() == 8;
        // - most typical cases; we do not try to optimize "strange" bit numbers like 4-bit samples
    }

    public double[] colorToChannelValues(Color color, boolean scaleToMaxValue) {
        Objects.requireNonNull(color, "Null color");
        float[] components = color.getRGBComponents(null);
        final double[] filler = new double[components.length];
        for (int i = 0; i < components.length; i++) {
            filler[i] = scaleToMaxValue ? components[i] * sampleType().maxUnsignedValue() : components[i];
            // - note: for signed types as INT8, the value 255 still corresponds to WHITE;
            // this is not too correct, but it is better than too "smart" solution when 0xFFFFFF
            // will be translated to 0x7F7F7F
        }
        return numberOfChannels == 1 ?
                new double[]{intensity(filler[0], filler[1], filler[2])} :
                Arrays.copyOf(filler, numberOfChannels);
    }

    public byte[] toInterleavedSamples(byte[] sampleBytes, int numberOfChannels, long numberOfPixels) {
        return toInterleaveOrSeparatedSamples(sampleBytes, numberOfChannels, numberOfPixels, true);
    }

    public byte[] toSeparatedSamples(byte[] sampleBytes, int numberOfChannels, long numberOfPixels) {
        return toInterleaveOrSeparatedSamples(sampleBytes, numberOfChannels, numberOfPixels, false);
    }

    public Object bytesToJavaArray(byte[] sampleBytes) {
        Objects.requireNonNull(sampleBytes, "Null sample bytes");
        long t1 = debugTime();
        if (bitImageUnpackingMode.isEnabled() && isBinary()) {
            return sampleBytes;
        }
        rarePrecisionMode.throwIfRaw(this, "convert bytes to Java array");
        final Object samplesArray = sampleType().javaArray(sampleBytes, byteOrder());
        if (BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t2 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s converted %d bytes (%.3f MB) to %s[] in %.3f ms%s",
                    getClass().getSimpleName(),
                    sampleBytes.length, sampleBytes.length / 1048576.0,
                    samplesArray.getClass().getComponentType().getSimpleName(),
                    (t2 - t1) * 1e-6,
                    sampleBytes == samplesArray ?
                            "" :
                            String.format(Locale.US, " %.3f MB/s",
                                    sampleBytes.length / 1048576.0 / ((t2 - t1) * 1e-9))));
        }
        return samplesArray;
    }

    public byte[] javaArrayToBytes(Object samplesArray, int fromX, int fromY, int sizeX, int sizeY) {
        Objects.requireNonNull(samplesArray, "Null samplesArray");
        final long numberOfPixels = checkRequestedArea(fromX, fromY, sizeX, sizeY);
        final Class<?> elementType = samplesArray.getClass().getComponentType();
        if (elementType == null) {
            throw new IllegalArgumentException("The specified samplesArray is not actually an array: " +
                    "it is " + samplesArray.getClass());
        }
        if (!(elementType == elementType() || isBinary() && elementType == long.class)) {
            throw new IllegalArgumentException("Invalid element type of samples array: " + elementType +
                    ", but the specified TIFF map stores " + sampleType().prettyName() + " elements");
        }
        final long numberOfSamples = Math.multiplyExact(numberOfPixels, numberOfChannels());
        // - overflow impossible after checkRequestedArea
        if (numberOfSamples > maxNumberOfSamplesInArray()) {
            throw new IllegalArgumentException("Too large area for updating TIFF in a single operation: " +
                    sizeX + "x" + sizeY + "x" + numberOfChannels() + " exceed the limit " +
                    maxNumberOfSamplesInArray());
        }
        return TiffSamples.toBytes(samplesArray, numberOfSamples, byteOrder());
    }

    public Matrix<UpdatablePArray> javaArrayAsMatrix(Object samplesArray, int sizeX, int sizeY) {
        return TiffSamples.asMatrix(samplesArray, sizeX, sizeY, numberOfChannels(), false);
    }

    public Matrix<UpdatablePArray> bytesToMatrix(byte[] sampleBytes, int sizeX, int sizeY) {
        Objects.requireNonNull(sampleBytes, "Null sample bytes");
        rarePrecisionMode.throwIfRaw(this, "convert bytes to matrix");
        final Object javaArray = bytesToJavaArray(sampleBytes);
        return javaArrayAsMatrix(javaArray, sizeX, sizeY);
    }

    public byte[] matrixToBytes(Matrix<? extends PArray> matrix) {
        Objects.requireNonNull(matrix, "Null matrix");
        final Class<?> elementType = matrix.elementType();
        if (elementType != elementType()) {
            throw new IllegalArgumentException("Invalid element type of the matrix: \"" + elementType +
                    "\" (" + net.algart.arrays.Arrays.bitsPerElement(elementType) +
                    "-bit), although the specified TIFF map stores \"" + elementType() +
                    "\" (" + bitsPerUnpackedSample() + "-bit) elements");
        }
        if (matrix.dimCount() != 3 && !(matrix.dimCount() == 2 && numberOfChannels() == 1)) {
            throw new IllegalArgumentException("Illegal number of matrix dimensions " + matrix.dimCount() +
                    ": it must be 3-dimensional dimX*dimY*C, " +
                    "where C is the number of channels (z-dimension), " +
                    "or 2-dimensional in the case of monochrome TIFF image");
        }
        final long numberOfChannels = matrix.dim(2);
        // - will be 1 for 2-dimensional matrix
        if (numberOfChannels != numberOfChannels()) {
            throw new IllegalArgumentException("Invalid number of channels in the matrix: " + numberOfChannels +
                    " (matrix " + matrix.dim(0) + "*" + matrix.dim(1) +
                    (matrix.dimCount() == 3 ? "*" + matrix.dim(2) : "") +
                    "), " +
                    (matrix.dim(0) == numberOfChannels() ?
                            "probably because of incorrect interleaving: the matrix should " +
                            "NOT be interleaved before updating the TIFF map" :
                            "because the specified TIFF map stores " + numberOfChannels() + " channels"));
        }
        PArray array = matrix.array();
        if (array.length() > maxNumberOfSamplesInArray()) {
            throw new IllegalArgumentException("Too large matrix for updating TIFF in a single operation: " + matrix
                    + " (number of elements " + array.length() + " exceed the limit " +
                    maxNumberOfSamplesInArray() + ")");
        }
        return TiffSamples.toBytes(array, byteOrder());
    }

    public <T extends PArray> List<Matrix<T>> matrixAsChannels(Matrix<T> mergedChannels) {
        Objects.requireNonNull(mergedChannels, "Null merged channels");
        return Matrices.asLayers(mergedChannels, TiffIFD.MAX_NUMBER_OF_CHANNELS);
    }

    public <T extends PArray> Matrix<T> channelsToMatrix(List<? extends Matrix<? extends T>> channels) {
        Objects.requireNonNull(channels, "Null channels");
        return Matrices.mergeLayers(net.algart.arrays.Arrays.SMM, channels);
    }

    public BufferedImage channelsToBufferedImage(List<? extends Matrix<? extends PArray>> channels) {
        final Matrix<? extends PArray> interleaved = Matrices.interleave(dropExtraChannels(channels));
        return interleavedMatrixToBufferedImage(interleaved);
    }

    public BufferedImage matrixToBufferedImage(Matrix<? extends PArray> mergedChannels) {
        return channelsToBufferedImage(matrixAsChannels(mergedChannels));
    }

    public BufferedImage interleavedMatrixToBufferedImage(Matrix<? extends PArray> interleavedChannels) {
        Objects.requireNonNull(interleavedChannels, "Null interleaved channels");
        // Note: we do not use MatrixToImage.toBufferedImage, because we need to call setUnsignedInt32
        return new MatrixToImage.InterleavedRGBToInterleaved()
                .setUnsignedInt32(true)
                .toBufferedImage(interleavedChannels);
    }

    public List<Matrix<UpdatablePArray>> bufferedImageToChannels(BufferedImage bufferedImage) {
        Objects.requireNonNull(bufferedImage, "Null bufferedImage");
        return ImageToMatrix.toChannels(bufferedImage);
    }

    public Matrix<UpdatablePArray> bufferedImageToMatrix(BufferedImage bufferedImage) {
        return channelsToMatrix(bufferedImageToChannels(bufferedImage));
    }

    public <T extends Matrix<? extends PArray>> List<T> dropExtraChannels(List<T> channels) {
        Objects.requireNonNull(channels, "Null channels");
        return getExtraChannelsMode().isDropping() && channels.size() > 4 ?
                channels.subList(0, 4) :
                channels;
    }

    @Override
    public String toString() {
        return "%s%s %dx%dx%d (%d bits) of %d TIFF %s %dx%d (grid %dx%d%s) at the image %s".formatted(
                resizable ? "resizable " : "",
                mapKindName(),
                dimX, dimY, numberOfChannels, normalizedBitDepth,
                tileMap.size(),
                isTiled() ? "tiles" : "strips",
                tileSizeX, tileSizeY, gridCountX, gridCountY,
                numberOfSeparatedPlanes == 1 ? "" : "x" + numberOfSeparatedPlanes,
                ifd);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TiffMap that = (TiffMap) o;
        return ifd == that.ifd &&
                resizable == that.resizable &&
                dimX == that.dimX && dimY == that.dimY &&
                Objects.equals(tileMap, that.tileMap) &&
                planarSeparated == that.planarSeparated &&
                numberOfChannels == that.numberOfChannels &&
                normalizedBitDepth == that.normalizedBitDepth &&
                tileSizeX == that.tileSizeX && tileSizeY == that.tileSizeY &&
                tileSizeInBytes == that.tileSizeInBytes;
        // - Important! Comparing references to IFD, not content!
        // Moreover, it makes sense to compare fields, calculated ON THE BASE of IFD:
        // they may change as a result of changing the content of the same IFD.

    }

    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(ifd), tileMap, resizable, dimX, dimY);
    }

    public static long checkRequestedArea(long fromX, long fromY, long sizeX, long sizeY) {
        final long result = TiffIFD.multiplySizes(sizeX, sizeY);
        assert sizeX >= 0 && sizeY >= 0 : "multiplySizes did not check sizes";
        assert sizeX <= Integer.MAX_VALUE && sizeY <= Integer.MAX_VALUE : "multiplySizes did not check sizes";
        if (fromX != (int) fromX || fromY != (int) fromY) {
            throw new IllegalArgumentException("Too large absolute values of fromX = " + fromX +
                    " or fromY = " + fromY + " (out of -2^31..2^31-1 ranges)");
        }
        // - Note: now all 4 numbers are in -2^31..2^31-1 range, but they are long
        if (sizeX >= Integer.MAX_VALUE - fromX || sizeY >= Integer.MAX_VALUE - fromY) {
            // - Note: ">=" instead of ">"! This allows to use "toX = fromX + sizeX" without overflow
            throw new IllegalArgumentException("Requested area [" + fromX + ".." + (fromX + sizeX - 1) +
                    " x " + fromY + ".." + (fromY + sizeY - 1) + "] is outside the 0..2^31-2 ranges");
        }
        return result;
    }

    private byte[] toInterleaveOrSeparatedSamples(
            byte[] sampleBytes,
            int numberOfChannels,
            long numberOfPixels,
            boolean interleave) {
        Objects.requireNonNull(sampleBytes, "Null sampleBytes");
        if (numberOfPixels < 0) {
            throw new IllegalArgumentException("Negative numberOfPixels = " + numberOfPixels);
        }
        if (numberOfChannels == 1) {
            return sampleBytes;
        }
        if (!isWholeBytes()) {
            throw new AssertionError("Non-whole bytes are impossible in valid TiffMap with 1 channel");
        }
        final int bytesPerSample = normalizedBitDepth >>> 3;
        assert normalizedBitDepth == bytesPerSample * 8 : "unaligned bitsPerSample impossible for whole bytes";
        return interleave ?
                TiffSamples.toInterleavedBytes(sampleBytes, numberOfChannels, bytesPerSample, numberOfPixels) :
                TiffSamples.toSeparatedBytes(sampleBytes, numberOfChannels, bytesPerSample, numberOfPixels);
    }

    String mapKindName() {
        return "map";
    }

    private void setDimensions(long dimX, long dimY, boolean checkResizable) {
        if (checkResizable && !resizable) {
            throw new IllegalArgumentException("Cannot change dimensions of a non-resizable tile map");
        }
        if (dimX < 0) {
            throw new IllegalArgumentException("Negative x-dimension: " + dimX);
        }
        if (dimY < 0) {
            throw new IllegalArgumentException("Negative y-dimension: " + dimY);
        }
        if (dimX > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too large image x-dimension = " + dimX + " (>2^31-1)");
        }
        if (dimY > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too large image y-dimension = " + dimY + " (>2^31-1)");
        }
        if (dimX * dimY > Long.MAX_VALUE / combinedNormalizedBitsPerPixel) {
            // - Very improbable! But we would like to be sure that 63-bit arithmetic
            // is enough to calculate the total size of the map in BYTES.
            throw new TooLargeArrayException("Extremely large image sizes " + dimX + "x" + dimY +
                    ", " + combinedNormalizedBitsPerPixel +
                    " bits/pixel: total number of bits is greater than 2^63-1 (!)");
        }
        final int gridCountX = (int) (dimX + (long) tileSizeX - 1) / tileSizeX;
        final int gridCountY = (int) (dimY + (long) tileSizeY - 1) / tileSizeY;
        expandGrid(gridCountX, gridCountY, checkResizable);
        this.dimX = (int) dimX;
        this.dimY = (int) dimY;
    }

    private void expandGrid(int newMinimalGridCountX, int newMinimalGridCountY, boolean checkResizable) {
        if (newMinimalGridCountX < 0) {
            throw new IllegalArgumentException("Negative new minimal tiles x-count: " + newMinimalGridCountX);
        }
        if (newMinimalGridCountY < 0) {
            throw new IllegalArgumentException("Negative new minimal tiles y-count: " + newMinimalGridCountY);
        }
        if (newMinimalGridCountX <= gridCountX && newMinimalGridCountY <= gridCountY) {
            return;
            // - even in the case !resizable
        }
        if (checkResizable && !resizable) {
            throw new IllegalArgumentException("Cannot expand tile counts in a non-resizable tile map");
        }
        final int gridCountX = Math.max(this.gridCountX, newMinimalGridCountX);
        final int gridCountY = Math.max(this.gridCountY, newMinimalGridCountY);
        if ((long) gridCountX * (long) gridCountY > Integer.MAX_VALUE / numberOfSeparatedPlanes) {
            throw new IllegalArgumentException("Too large number of tiles/strips: " +
                    (numberOfSeparatedPlanes > 1 ? numberOfSeparatedPlanes + " separated planes * " : "") +
                    gridCountX + " * " + gridCountY + " > 2^31-1");
        }
        this.gridCountX = gridCountX;
        this.gridCountY = gridCountY;
        this.numberOfGridTiles = gridCountX * gridCountY * numberOfSeparatedPlanes;
    }

    static long debugTime() {
        return BUILT_IN_TIMING && LOGGABLE_DEBUG ? System.nanoTime() : 0;
    }

    private static double intensity(double r, double g, double b) {
        if (r == g && r == b) {
            // the formula below may lead to little error here
            return r;
        } else {
            return 0.299 * r + 0.587 * g + 0.114 * b;
        }
    }
}
