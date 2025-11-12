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

package net.algart.matrices.tiff.tiles;

import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.TooLargeArrayException;
import net.algart.arrays.UpdatablePArray;
import net.algart.matrices.tiff.*;
import net.algart.matrices.tiff.tags.TagCompression;

import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * TIFF map: an object storing detailed information about a TIFF image
 * and allowing to add and manipulate its tiles (strips).
 */
public sealed class TiffMap permits TiffIOMap {
    /**
     * Possible type of tiles in the TIFF map: 2D tile grid or horizontal strips.
     * You can know the tiling type of the map by {@link TiffMap#getTilingMode()} method.
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

    /**
     * Maximal value of x/y-index of the tile.
     *
     * <p>This limit helps to avoid arithmetic overflow while operations with indexes.
     */
    public static final int MAX_TILE_INDEX = 1_000_000_000;

    private final TiffIFD ifd;
    private final boolean resizable;

    private final Map<TiffTileIndex, TiffTile> tileMap = new LinkedHashMap<>();
    private final boolean planarSeparated;
    private final int numberOfChannels;
    private final int numberOfSeparatedPlanes;
    private final int tileSamplesPerPixel;
    private final int[] bitsPerSample;
    private final int alignedBitsPerSample;
    private final int bitsPerUnpackedSample;
    private final int tileAlignedBitsPerPixel;
    private final int totalAlignedBitsPerPixel;
    private final TiffSampleType sampleType;
    private final boolean wholeBytes;
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
    private final int compressionCode;
    private final TagCompression compression;
    private final int photometricInterpretationCode;
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
    public TiffMap(TiffIFD ifd, boolean resizable) {
        this.ifd = Objects.requireNonNull(ifd, "Null IFD");
        this.resizable = resizable;
        final boolean hasImageDimensions = ifd.hasImageDimensions();
        try {
            if (!hasImageDimensions && !resizable) {
                throw new IllegalArgumentException("TIFF image sizes (ImageWidth and ImageLength tags) " +
                        "are not specified; it is not allowed for non-resizable tile " + mapKindName());
            }
            this.tilingMode = ifd.hasTileInformation() ? TilingMode.TILE_GRID : TilingMode.STRIPS;
            if (resizable && !tilingMode.isTileGrid()) {
                throw new IllegalArgumentException("TIFF image is not tiled (TileWidth and TileLength tags " +
                        "are not specified); it is not allowed for resizable tile map: any processing " +
                        "TIFF image, such as writing its fragments, requires either knowing its final fixed sizes, " +
                        "or splitting image into tiles with known fixed sizes");
            }
            this.planarSeparated = ifd.isPlanarSeparated();
            this.numberOfChannels = ifd.getSamplesPerPixel();
            assert numberOfChannels <= TiffIFD.MAX_NUMBER_OF_CHANNELS;
            this.numberOfSeparatedPlanes = planarSeparated ? numberOfChannels : 1;
            this.tileSamplesPerPixel = planarSeparated ? 1 : numberOfChannels;
            this.bitsPerSample = ifd.getBitsPerSample().clone();
            this.alignedBitsPerSample = TiffIFD.alignedBitDepth(bitsPerSample);
            // - we allow only EQUAL number of bytes/sample (but the number if bits/sample can be different)
            assert (long) numberOfChannels * (long) alignedBitsPerSample <
                    TiffIFD.MAX_NUMBER_OF_CHANNELS * TiffIFD.MAX_BITS_PER_SAMPLE;
            // - actually must be in 8 times less
            this.tileAlignedBitsPerPixel = tileSamplesPerPixel * alignedBitsPerSample;
            this.totalAlignedBitsPerPixel = numberOfChannels * alignedBitsPerSample;
            this.sampleType = ifd.sampleType();
            this.wholeBytes = sampleType.isWholeBytes();
            if (this.wholeBytes != ((alignedBitsPerSample & 7) == 0)) {
                throw new ConcurrentModificationException("Corrupted IFD, probably from a parallel thread" +
                        " (sample type " + sampleType + " is" +
                        (wholeBytes ? "" : " NOT") +
                        " whole-bytes, but we have " + alignedBitsPerSample + " bits/sample)");
            }
            if ((totalAlignedBitsPerPixel == 1) != sampleType.isBinary()) {
                throw new ConcurrentModificationException("Corrupted IFD, probably from a parallel thread" +
                        " (sample type is " + sampleType +
                        ", but we have " + totalAlignedBitsPerPixel + " bits/pixel)");
            }
            if (sampleType.isBinary() && numberOfChannels > 1) {
                throw new AssertionError("Binary IFD for " + numberOfChannels +
                        " > 1 channels is not supported: invalid TiffIFD class");
            }
            this.bitsPerUnpackedSample = sampleType.bitsPerSample();
            if (bitsPerUnpackedSample < alignedBitsPerSample) {
                throw new AssertionError(sampleType + ".bitsPerSample() = " + bitsPerUnpackedSample +
                        " is too little: less than ifd.alignedBitDepth() = " + alignedBitsPerSample);
            }
            this.elementType = sampleType.elementType();
            this.byteOrder = ifd.getByteOrder();
            this.maxNumberOfSamplesInArray = sampleType.maxNumberOfSamplesInArray();
            this.tileSizeX = ifd.getTileSizeX();
            this.tileSizeY = ifd.getTileSizeY();
            assert tileSizeX > 0 && tileSizeY > 0 : "non-positive tile sizes are not checked in IFD methods";
            this.compressionCode = ifd.getCompressionCode();
            this.compression = ifd.optCompression();
            this.photometricInterpretationCode = ifd.getPhotometricInterpretationCode();
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
            if ((long) tileSizeInPixels * (long) tileAlignedBitsPerPixel > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Very large TIFF tiles " + tileSizeX + "x" + tileSizeY +
                        ", " + tileSamplesPerPixel + " channels per " + alignedBitsPerSample +
                        " bits >= 2^31 bits (256 MB) are not supported");
            }
            this.tileSizeInBytes = (tileSizeInPixels * tileAlignedBitsPerPixel + 7) >>> 3;
        } catch (TiffException e) {
            throw new IllegalArgumentException("Illegal IFD: " + e.getMessage(), e);
        }
    }

    public TiffIFD ifd() {
        return ifd;
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

    public int[] bitsPerSample() {
        return bitsPerSample.clone();
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
     * @return number of bytes, necessary to store one channel of the pixel inside TIFF.
     */
    public int alignedBitsPerSample() {
        return alignedBitsPerSample;
    }

    public OptionalInt bytesPerSample() {
        assert wholeBytes == ((alignedBitsPerSample & 7) == 0) : "must be checked ins the constructor";
        return wholeBytes ? OptionalInt.of(alignedBitsPerSample >>> 3) : OptionalInt.empty();
    }

    /**
     * Number of bits, actually used for storing one channel of the pixel in memory.
     * This number of bytes is correct for data, loaded from a TIFF file by
     * {@link TiffReader}, and for source data,
     * that should be written by {@link TiffWriter}.
     *
     * <p>Usually this value is equal to results of {@link #alignedBitsPerSample()},
     * excepting the following rare cases, called <b>unusual precisions</b>:</p>
     *
     * <ul>
     *     <li>every channel is encoded as N-bit integer value, where 17&le;N&le;24, and, so, requires 3 bytes:
     *     this method returns 32, {@link #alignedBitsPerSample()} returns 24
     *     (image, stored in memory, must have 2<sup>k</sup> bytes (k=1..3) per every sample, to allow representing
     *     it by one of Java types <code>byte</code>, <code>short</code>, <code>int</code>,
     *     <code>float</code>, <code>double</code>);
     *     </li>
     *     <li>pixels are encoded as 16-bit or 24-bit floating point values:
     *     this method returns 32, {@link #alignedBitsPerSample()} returns 16/24
     *     (in memory, such an image will be unpacked into a usual array of 32-bit <code>float</code> values).
     *     </li>
     * </ul>
     *
     * <p>Note that this difference is possible only while reading TIFF files, created by some other software.
     * While using {@link TiffWriter} class of this module,
     * it is not allowed to write image with precisions listed above.</p>
     *
     * @return number of bytes, used for storing one channel of the pixel in memory.
     * @see TiffReader#setUnusualPrecisions(net.algart.matrices.tiff.TiffReader.UnusualPrecisions)
     */
    public int bitsPerUnpackedSample() {
        return bitsPerUnpackedSample;
    }

    public int tileAlignedBitsPerPixel() {
        return tileAlignedBitsPerPixel;
    }

    public int totalAlignedBitsPerPixel() {
        return totalAlignedBitsPerPixel;
    }

    public Class<?> elementType() {
        return elementType;
    }

    public ByteOrder byteOrder() {
        return byteOrder;
    }

    public int sizeOfRegionWithPossibleNonStandardPrecisions(long sizeX, long sizeY) throws TiffException {
        return TiffIFD.sizeOfRegionInBytes(sizeX, sizeY, numberOfChannels, alignedBitsPerSample);
    }

    public long maxNumberOfSamplesInArray() {
        return maxNumberOfSamplesInArray;
    }

    public Optional<String> description() {
        return ifd.optDescription();
    }

    public TilingMode getTilingMode() {
        return tilingMode;
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

    public int compressionCode() {
        return compressionCode;
    }

    public TagCompression compression() {
        return compression;
    }

    public int photometricInterpretationCode() {
        return photometricInterpretationCode;
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
        return (Math.multiplyExact(totalSizeInPixels(), (long) totalAlignedBitsPerPixel) + 7) >>> 3;
        // - but overflow here should be impossible due to the check in setDimensions
    }

    public void setDimensions(int dimX, int dimY) {
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
     * @param newMinimalDimX new minimal value for {@link #dimX() sizeX}.
     * @param newMinimalDimY new minimal value for {@link #dimY() sizeY}.
     */
    public void expandDimensions(int newMinimalDimX, int newMinimalDimY) {
        if (needToExpandDimensions(newMinimalDimX, newMinimalDimY)) {
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
     * If the map is a {@link net.algart.matrices.tiff.tiles.TiffMap.TilingMode#STRIPS stripped TIFF image},
     * each created tile (i.e., a strip) is automatically cropped by the grid dimensions using
     * {@link TiffTile#cropStripToMap()}.
     */
    public void buildTileGrid() {
        for (int p = 0; p < numberOfSeparatedPlanes; p++) {
            for (int y = 0; y < gridCountY; y++) {
                for (int x = 0; x < gridCountX; x++) {
                    getOrNew(x, y, p).cropStripToMap();
                }
            }
        }
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

    public byte[] toInterleavedSamples(byte[] samples, int numberOfChannels, long numberOfPixels) {
        return toInterleaveOrSeparatedSamples(samples, numberOfChannels, numberOfPixels, true);
    }

    public byte[] toSeparatedSamples(byte[] samples, int numberOfChannels, long numberOfPixels) {
        return toInterleaveOrSeparatedSamples(samples, numberOfChannels, numberOfPixels, false);
    }

    @Override
    public String toString() {
        return (resizable ? "resizable " : "") + mapKindName() + " " +
                dimX + "x" + dimY + "x" + numberOfChannels + " (" + alignedBitsPerSample + " bits) " +
                "of " + tileMap.size() + " TIFF tiles (grid " + gridCountX + "x" + gridCountY +
                (numberOfSeparatedPlanes == 1 ? "" : "x" + numberOfSeparatedPlanes) +
                ") at the image " + ifd;
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
                alignedBitsPerSample == that.alignedBitsPerSample &&
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

    public static byte[] toInterleavedBytes(
            byte[] bytes,
            int numberOfChannels,
            int bytesPerSample,
            long numberOfPixels) {
        Objects.requireNonNull(bytes, "Null bytes");
        final int size = checkSizes(numberOfChannels, bytesPerSample, numberOfPixels);
        // - exception usually should not occur: this function is typically called after analyzing IFD
        if (bytes.length < size) {
            throw new IllegalArgumentException("Too short samples array: " + bytes.length + " < " + size);
        }
        if (numberOfChannels == 1) {
            return bytes;
        }
        final int bandSize = bytesPerSample * (int) numberOfPixels;
        final byte[] interleavedBytes = new byte[size];
        if (bytesPerSample == 1) {
            Matrix<UpdatablePArray> mI = Matrix.as(interleavedBytes, numberOfChannels, numberOfPixels);
            Matrix<UpdatablePArray> mS = Matrix.as(bytes, numberOfPixels, numberOfChannels);
            Matrices.interleave(null, mI, mS.asLayers());
//            if (numberOfChannels == 3) {
//                quickInterleave3(interleavedBytes, bytes, bandSize);
//            } else {
//                for (int i = 0, disp = 0; i < bandSize; i++) {
//                    for (int bandDisp = i, j = 0; j < numberOfChannels; j++, bandDisp += bandSize) {
            // note: we must check j, not bandDisp, because "bandDisp += bandSize" can lead to overflow
//                        interleavedBytes[disp++] = bytes[bandDisp];
//                    }
//                }
//            }
        } else {
            for (int i = 0, disp = 0; i < bandSize; i += bytesPerSample) {
                for (int bandDisp = i, j = 0; j < numberOfChannels; j++, bandDisp += bandSize) {
                    for (int k = 0; k < bytesPerSample; k++) {
                        interleavedBytes[disp++] = bytes[bandDisp + k];
                    }
                }
            }
        }
        return interleavedBytes;
    }

    public static byte[] toSeparatedBytes(
            byte[] bytes,
            int numberOfChannels,
            int bytesPerSample,
            long numberOfPixels) {
        Objects.requireNonNull(bytes, "Null bytes");
        final int size = checkSizes(numberOfChannels, bytesPerSample, numberOfPixels);
        // - exception usually should not occur: this function is typically called after analyzing IFD
        if (bytes.length < size) {
            throw new IllegalArgumentException("Too short samples array: " + bytes.length + " < " + size);
        }
        if (numberOfChannels == 1) {
            return bytes;
        }
        final int bandSize = bytesPerSample * (int) numberOfPixels;
        final byte[] separatedBytes = new byte[size];
        if (bytesPerSample == 1) {
            final Matrix<UpdatablePArray> mI = Matrix.as(bytes, numberOfChannels, numberOfPixels);
            final Matrix<UpdatablePArray> mS = Matrix.as(separatedBytes, numberOfPixels, numberOfChannels);
            Matrices.separate(null, mS.asLayers(), mI);
        } else {
            for (int i = 0, disp = 0; i < bandSize; i += bytesPerSample) {
                for (int bandDisp = i, j = 0; j < numberOfChannels; j++, bandDisp += bandSize) {
                    for (int k = 0; k < bytesPerSample; k++) {
                        separatedBytes[bandDisp + k] = bytes[disp++];
                    }
                }
            }
        }
        return separatedBytes;
    }

    byte[] toInterleaveOrSeparatedSamples(
            byte[] samples,
            int numberOfChannels,
            long numberOfPixels,
            boolean interleave) {
        Objects.requireNonNull(samples, "Null samples");
        if (numberOfPixels < 0) {
            throw new IllegalArgumentException("Negative numberOfPixels = " + numberOfPixels);
        }
        if (numberOfChannels == 1) {
            return samples;
        }
        if (!isWholeBytes()) {
            throw new AssertionError("Non-whole bytes are impossible in valid TiffMap with 1 channel");
        }
        final int bytesPerSample = alignedBitsPerSample >>> 3;
        assert alignedBitsPerSample == bytesPerSample * 8 : "unaligned bitsPerSample impossible for whole bytes";
        return interleave ?
                toInterleavedBytes(samples, numberOfChannels, bytesPerSample, numberOfPixels) :
                toSeparatedBytes(samples, numberOfChannels, bytesPerSample, numberOfPixels);
    }

    String mapKindName() {
        return "map";
    }

    private static int checkSizes(int numberOfChannels, int bytesPerSample, long numberOfPixels) {
        TiffIFD.checkNumberOfChannels(numberOfChannels);
        TiffIFD.checkBitsPerSample(8L * (long) bytesPerSample);
        // - so, numberOfChannels * bytesPerSample is a not-too-large value
        if (numberOfPixels < 0) {
            throw new IllegalArgumentException("Negative numberOfPixels = " + numberOfPixels);
        }
        long size;
        if (numberOfPixels > Integer.MAX_VALUE ||
                (size = numberOfPixels * (long) numberOfChannels * (long) bytesPerSample) > Integer.MAX_VALUE) {
            throw new TooLargeArrayException("Too large number of pixels " + numberOfPixels +
                    " (" + numberOfChannels + " samples/pixel, " +
                    bytesPerSample + " bytes/sample): it requires > 2 GB to store");
        }
        return (int) size;
    }

    private void setDimensions(int dimX, int dimY, boolean checkResizable) {
        if (checkResizable && !resizable) {
            throw new IllegalArgumentException("Cannot change dimensions of a non-resizable tile map");
        }
        if (dimX < 0) {
            throw new IllegalArgumentException("Negative x-dimension: " + dimX);
        }
        if (dimY < 0) {
            throw new IllegalArgumentException("Negative y-dimension: " + dimY);
        }
        if ((long) dimX * (long) dimY > Long.MAX_VALUE / totalAlignedBitsPerPixel) {
            // - Very improbable! But we would like to be sure that 63-bit arithmetic
            // is enough to calculate the total size of the map in BYTES.
            throw new TooLargeArrayException("Extremely large image sizes " + dimX + "x" + dimY +
                    ", " + totalAlignedBitsPerPixel + " bits/pixel: total number of bits is greater than 2^63-1 (!)");
        }
        final int gridCountX = (int) ((long) dimX + (long) tileSizeX - 1) / tileSizeX;
        final int gridCountY = (int) ((long) dimY + (long) tileSizeY - 1) / tileSizeY;
        expandGrid(gridCountX, gridCountY, checkResizable);
        this.dimX = dimX;
        this.dimY = dimY;
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
}
