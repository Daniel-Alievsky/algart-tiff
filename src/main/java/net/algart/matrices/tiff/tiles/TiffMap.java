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

package net.algart.matrices.tiff.tiles;

import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.TooLargeArrayException;
import net.algart.arrays.UpdatablePArray;
import net.algart.matrices.tiff.*;

import java.util.*;

public final class TiffMap {
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
         * </p>Used for large TIFF images.</p>
         */
        TILE_GRID,

        /**
         * Horizontal strips. In terms of this library they are also called "tiles" and represented
         * with help of {@link TiffTile} and {@link TiffMap} classes.
         *
         * <p>In this case, all strips ("tiles") has a width,
         * equal to the width of the entire image, and the same height <code>H</code> (excepting the last strip,
         * which height is a reminder <code>image-height % H</code> when the full image height is not divisible
         * by <code>H</code>). The height <code>H</code> is returned by {@link #tileSizeY()} method;
         * the {@link #tileSizeX()} method returns the total image width.
         *
         * <p>IFD must <b>not</b> contain <code>TileWidth</code> and <code>TileLength</code> tags.
         * The height <code>H</code> of every strip is specified in <code>RowsPerStrip</code> tag
         * or is the full image height if there is no such tag (in the latter case, {@link TiffMap}
         * will contain only 1 tile).
         * Though TIFF format allows to specify strips with different heights,
         * this library does not support this case.
         *
         * <p>If the full image height is not divisible by strip height <code>H</code>),
         * the last tile should be stored as a pixel matrix with reduced height
         * <code>image-height % H</code>: extra pixel outside the image are not stored.
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
    private final Map<TiffTileIndex, TiffTile> tileMap = new LinkedHashMap<>();
    private final boolean resizable;
    private final boolean planarSeparated;
    private final int numberOfChannels;
    private final int numberOfSeparatedPlanes;
    private final int tileSamplesPerPixel;
    private final int bitsPerSample;
    private final int bitsPerUnpackedSample;
    private final int tileBitsPerPixel;
    private final int totalBitsPerPixel;
    private final TiffSampleType sampleType;
    private final boolean wholeBytes;
    private final Class<?> elementType;
    private final long maxNumberOfSamplesInArray;
    private final TilingMode tilingMode;
    private final int tileSizeX;
    private final int tileSizeY;
    private final int tileSizeInPixels;
    private final int tileSizeInBytes;
    // - Note: we store here information about samples and tiles structure, but
    // SHOULD NOT store information about image sizes (like number of tiles):
    // it is probable that we do not know final sizes while creating tiles of the image!
    private volatile int dimX = 0;
    private volatile int dimY = 0;
    private volatile int gridCountX = 0;
    private volatile int gridCountY = 0;
    private volatile int numberOfGridTiles = 0;

    private TiffMap(TiffIFD ifd, boolean resizable) {
        this.ifd = Objects.requireNonNull(ifd, "Null IFD");
        this.resizable = resizable;
        final boolean hasImageDimensions = ifd.hasImageDimensions();
        try {
            if (!hasImageDimensions && !resizable) {
                throw new IllegalArgumentException("TIFF image sizes (ImageWidth and ImageLength tags) " +
                        "are not specified; it is not allowed for non-resizable tile map");
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
            this.bitsPerSample = ifd.alignedBitDepth();
            // - so, we allow only EQUAL number of bytes/sample (but number if bits/sample can be different)
            assert (long) numberOfChannels * (long) bitsPerSample <
                    TiffIFD.MAX_NUMBER_OF_CHANNELS * TiffIFD.MAX_BITS_PER_SAMPLE;
            // - actually must be in 8 times less
            this.tileBitsPerPixel = tileSamplesPerPixel * bitsPerSample;
            this.totalBitsPerPixel = numberOfChannels * bitsPerSample;
            this.sampleType = ifd.sampleType();
            this.wholeBytes = sampleType.isConsistingOfWholeBytes();
            if (this.wholeBytes && (bitsPerSample & 7) != 0) {
                throw new ConcurrentModificationException("Corrupted IFD, probably from a parallel thread" +
                        " (sample type " + sampleType +
                        " is whole-bytes, but we have " + bitsPerSample + " bits/sample)");
            }
            if ((totalBitsPerPixel == 1) != sampleType.isBinary()) {
                throw new ConcurrentModificationException("Corrupted IFD, probably from a parallel thread" +
                        " (sample type is " + sampleType +
                        ", but we have " + totalBitsPerPixel + " bits/pixel)");
            }
            if (sampleType.isBinary() && numberOfChannels > 1) {
                throw new AssertionError("Binary IFD for " + numberOfChannels +
                        " > 1 channels is not supported: invalid TiffIFD class");
            }
            this.bitsPerUnpackedSample = sampleType.bitsPerSample();
            this.elementType = sampleType.elementType();
            this.maxNumberOfSamplesInArray = sampleType.maxNumberOfSamplesInArray();
            this.tileSizeX = ifd.getTileSizeX();
            this.tileSizeY = ifd.getTileSizeY();
            assert tileSizeX > 0 && tileSizeY > 0 : "non-positive tile sizes are not checked in IFD methods";
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
            if ((long) tileSizeInPixels * (long) tileBitsPerPixel > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Very large TIFF tiles " + tileSizeX + "x" + tileSizeY +
                        ", " + tileSamplesPerPixel + " channels per " + bitsPerSample +
                        " bits >= 2^31 bits (256 MB) are not supported");
            }
            this.tileSizeInBytes = (tileSizeInPixels * tileBitsPerPixel + 7) >>> 3;
        } catch (TiffException e) {
            throw new IllegalArgumentException("Illegal IFD: " + e.getMessage(), e);
        }
    }

    /**
     * Creates new tile map.
     *
     * <p>Note: you should not change the tags of the passed IFD, describing sample type, number of samples
     * and tile sizes, after creating this object. The constructor saves this information in this object
     * (it is available via access methods) and will not be renewed automatically.
     *
     * @param ifd       IFD.
     * @param resizable whether maximal dimensions of this set will grow while adding new tiles,
     *                  or they are fixed and must be specified in IFD.
     */
    public static TiffMap newMap(TiffIFD ifd, boolean resizable) {
        return new TiffMap(ifd, resizable);
    }

    public static TiffMap newFixed(TiffIFD ifd) {
        return newMap(ifd, false);
    }

    public static TiffMap newResizable(TiffIFD ifd) {
        return newMap(ifd, true);
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

    /**
     * Minimal number of bits, necessary to store one channel of the pixel:
     * the value of BitsPerSample TIFF tag, aligned to the nearest non-lesser multiple of 8,
     * or 1 in the case of a single-channel binary matrix (BitsPerSample=1, SamplesPerPixel=1).
     * This class requires that this value is equal for all channels, even
     * if <code>BitsPerSample</code> tag contain different number of bits per channel (for example, 5+6+5).
     *
     * <p>Note that the actual number of bits, used for storing the pixel samples in memory
     * after reading data from TIFF file, may be little greater: see {@link #bitsPerUnpackedSample()}.
     *
     * @return number of bytes, necessary to store one channel of the pixel inside TIFF.
     */
    public int bitsPerSample() {
        return bitsPerSample;
    }

    /**
     * Number of bits, actually used for storing one channel of the pixel in memory.
     * This number of bytes is correct for data, loaded from TIFF file by
     * {@link TiffReader}, and for source data,
     * that should be written by {@link TiffWriter}.
     *
     * <p>Usually this value is equal to results of {@link #bitsPerSample()}, excepting the following rare cases,
     * called <b>unusual precisions</b>:</p>
     *
     * <ul>
     *     <li>every channel is encoded as N-bit integer value, where 17&le;N&le;24, and, so, requires 3 bytes:
     *     this method returns 32, {@link #bitsPerSample()} returns 24
     *     (image, stored in memory, must have 2<sup>k</sup> bytes (k=1..3) per every sample, to allow to represent
     *     it by one of Java types <code>byte</code>, <code>short</code>, <code>int</code>,
     *     <code>float</code>, <code>double</code>);
     *     </li>
     *     <li>pixels are encoded as 16-bit or 24-bit floating point values:
     *     this method returns 32, {@link #bitsPerSample()} returns 16/24
     *     (in memory, such image will be unpacked into usual array of 32-bit <code>float</code> values).
     *     </li>
     * </ul>
     *
     * <p>Note that this difference is possible only while reading TIFF files, created by some other software.
     * While using {@link TiffWriter} class of this module,
     * it is not allowed to write image with precisions listed above.</p>
     *
     * @return number of bytes, used for storing one channel of the pixel in memory.
     * @see TiffReader#setAutoUnpackUnusualPrecisions(boolean)
     */
    public int bitsPerUnpackedSample() {
        return bitsPerUnpackedSample;
    }

    public int tileBitsPerPixel() {
        return tileBitsPerPixel;
    }

    public int totalBitsPerPixel() {
        return totalBitsPerPixel;
    }

    public TiffSampleType sampleType() {
        return sampleType;
    }

    public boolean isBinary() {
        return sampleType().isBinary();
    }

    public boolean isWholeBytes() {
        return wholeBytes;
    }

    public Class<?> elementType() {
        return elementType;
    }

    public int sizeOfRegionWithPossibleNonStandardPrecisions(long sizeX, long sizeY) throws TiffException {
        return TiffIFD.sizeOfRegionInBytes(sizeX, sizeY, numberOfChannels, bitsPerSample);
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
        return (Math.multiplyExact(totalSizeInPixels(), (long) totalBitsPerPixel) + 7) >>> 3;
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
        final int tileCountX = (int) ((long) dimX + (long) tileSizeX - 1) / tileSizeX;
        final int tileCountY = (int) ((long) dimY + (long) tileSizeY - 1) / tileSizeY;
        assert tileCountX <= this.gridCountX && tileCountY <= this.gridCountY :
                "Grid dimensions were not correctly grown according map dimensions";
        if (tileCountX != this.gridCountX || tileCountY != this.gridCountY) {
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
     * <p>Also note: negative arguments are allowed, but have no effect (as if they would be zero).</p>
     *
     * @param newMinimalDimX new minimal value for {@link #dimX() sizeX}.
     * @param newMinimalDimY new minimal value for {@link #dimY() sizeY}.
     */
    public void expandDimensions(int newMinimalDimX, int newMinimalDimY) {
        if (newMinimalDimX > dimX || newMinimalDimY > dimY) {
            setDimensions(Math.max(dimX, newMinimalDimX), Math.max(dimY, newMinimalDimY));
        }
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
     * Replaces tile x/y-count to maximums from their current values and <code>newMinimalTileCountX/Y</code>.
     *
     * <p>Note: the arguments are the desired minimal tile <i>counts</i>, not tile <i>indexes</i>.
     * So, you can freely specify zero arguments, and this method will do nothing in this case.
     *
     * <p>Note: if both new x/y-counts are not greater than existing ones, this method does nothing
     * and can be called even if not {@link #isResizable()}.
     *
     * <p>Note: this method is called automatically while changing total image sizes.
     *
     * @param newMinimalTileCountX new minimal value for {@link #gridCountX()}.
     * @param newMinimalTileCountY new minimal value for {@link #gridCountY()}.
     */
    public void expandGrid(int newMinimalTileCountX, int newMinimalTileCountY) {
        expandGrid(newMinimalTileCountX, newMinimalTileCountY, true);
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

    public int linearIndex(int separatedPlaneIndex, int xIndex, int yIndex) {
        if (separatedPlaneIndex < 0 || separatedPlaneIndex >= numberOfSeparatedPlanes) {
            throw new IndexOutOfBoundsException("Separated plane index " + separatedPlaneIndex +
                    " is out of range 0.." + (numberOfSeparatedPlanes - 1));
        }
        int tileCountX = this.gridCountX;
        int tileCountY = this.gridCountY;
        if (xIndex < 0 || xIndex >= tileCountX || yIndex < 0 || yIndex >= tileCountY) {
            throw new IndexOutOfBoundsException("One of X/Y-indexes (" + xIndex + ", " + yIndex +
                    ") of the tile is out of ranges 0.." + (tileCountX - 1) + ", 0.." + (tileCountY - 1));
        }
        // - if the tile is out of bounds, it means that we do not know actual grid dimensions
        // (even it is resizable): there is no way to calculate correct linear index
        return (separatedPlaneIndex * tileCountY + yIndex) * tileCountX + xIndex;
        // - overflow impossible: setDimensions checks that tileCountX * tileCountY * numberOfSeparatedPlanes < 2^31
    }

    public TiffTileIndex index(int x, int y) {
        return new TiffTileIndex(this, 0, x, y);
    }

    public TiffTileIndex multiPlaneIndex(int separatedPlaneIndex, int x, int y) {
        return new TiffTileIndex(this, separatedPlaneIndex, x, y);
    }

    public TiffTileIndex copyIndex(TiffTileIndex other) {
        Objects.requireNonNull(other, "Null other index");
        return new TiffTileIndex(this, other.channelPlane(), other.xIndex(), other.yIndex());
    }

    public void checkTileIndexIFD(TiffTileIndex tileIndex) {
        Objects.requireNonNull(tileIndex, "Null tile index");
        if (tileIndex.ifd() != this.ifd) {
            // - Checking references, not content!
            // Checking IFD, not reference to map ("this"): there is no sense to disable creating new map
            // and copying there the tiles from the given map.
            throw new IllegalArgumentException("Illegal tile index: tile map cannot process tiles from different IFD");
        }
    }

    public int numberOfTiles() {
        return tileMap.size();
    }

    public TiffTile getOrNew(int x, int y) {
        return getOrNew(index(x, y));
    }

    public TiffTile getOrNewMultiPlane(int separatedPlaneIndex, int x, int y) {
        return getOrNew(multiPlaneIndex(separatedPlaneIndex, x, y));
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

    public void putAll(Collection<TiffTile> tiles) {
        Objects.requireNonNull(tiles, "Null tiles");
        tiles.forEach(this::put);
    }

    public TiffMap buildTileGrid() {
        for (int p = 0; p < numberOfSeparatedPlanes; p++) {
            for (int y = 0; y < gridCountY; y++) {
                for (int x = 0; x < gridCountX; x++) {
                    getOrNewMultiPlane(p, x, y).cropToMap();
                }
            }
        }
        return this;
    }

    public void cropAll() {
        cropAll(true);
    }

    public void cropAll(boolean strippedOnly) {
        tileMap.values().forEach(tile -> tile.cropToMap(strippedOnly));
    }

    public boolean hasUnset() {
        return tileMap.values().stream().anyMatch(TiffTile::hasUnset);
    }

    public void unsetAll() {
        tileMap.values().forEach(TiffTile::unsetAll);
    }

    public void cropAllUnset() {
        tileMap.values().forEach(TiffTile::cropUnsetToMap);
    }

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

    public byte[] toInterleavedSamples(byte[] samples, int numberOfChannels, long numberOfPixels) {
        return toInterleaveOrSeparatedSamples(samples, numberOfChannels, numberOfPixels, true);
    }

    public byte[] toSeparatedSamples(byte[] samples, int numberOfChannels, long numberOfPixels) {
        return toInterleaveOrSeparatedSamples(samples, numberOfChannels, numberOfPixels, false);
    }

    @Override
    public String toString() {
        return (resizable ? "resizable " : "") + "map " +
                (resizable ? "?x?" : dimX + "x" + dimY) +
                "x" + numberOfChannels + " (" + bitsPerSample + " bits) " +
                "of " + tileMap.size() + " TIFF tiles (grid " + gridCountX + "x" + gridCountY +
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
                bitsPerSample == that.bitsPerSample &&
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

    public static byte[] toInterleavedBytes(
            byte[] bytes,
            int numberOfChannels,
            int bytesPerSample,
            long numberOfPixels) {
        Objects.requireNonNull(bytes, "Null bytes");
        final int size = checkSizes(numberOfChannels, bytesPerSample, numberOfPixels);
        // - exception usually should not occur: this function is typically called after analysing IFD
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

    static byte[] toSeparatedBytes(
            byte[] bytes,
            int numberOfChannels,
            int bytesPerSample,
            long numberOfPixels) {
        Objects.requireNonNull(bytes, "Null bytes");
        final int size = checkSizes(numberOfChannels, bytesPerSample, numberOfPixels);
        // - exception usually should not occur: this function is typically called after analysing IFD
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
        final int bytesPerSample = bitsPerSample >>> 3;
        assert bitsPerSample == bytesPerSample * 8 : "unaligned bitsPerSample impossible for whole bytes";
        return interleave ?
                toInterleavedBytes(samples, numberOfChannels, bytesPerSample, numberOfPixels) :
                toSeparatedBytes(samples, numberOfChannels, bytesPerSample, numberOfPixels);
    }

    private static int checkSizes(int numberOfChannels, int bytesPerSample, long numberOfPixels) {
        TiffIFD.checkNumberOfChannels(numberOfChannels);
        TiffIFD.checkBitsPerSample(8L * (long) bytesPerSample);
        // - so, numberOfChannels * bytesPerSample is not too large value
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
        if ((long) dimX * (long) dimY > Long.MAX_VALUE / totalBitsPerPixel) {
            // - Very improbable! But we would like to be sure that 63-bit arithmetic
            // is enough to calculate total size of the map in BYTES.
            throw new TooLargeArrayException("Extremely large image sizes " + dimX + "x" + dimY +
                    ": total number of bits is greater than 2^63-1 (!)");
        }
        final int tileCountX = (int) ((long) dimX + (long) tileSizeX - 1) / tileSizeX;
        final int tileCountY = (int) ((long) dimY + (long) tileSizeY - 1) / tileSizeY;
        expandGrid(tileCountX, tileCountY, checkResizable);
        this.dimX = dimX;
        this.dimY = dimY;
    }

    private void expandGrid(int newMinimalTileCountX, int newMinimalTileCountY, boolean checkResizable) {
        if (newMinimalTileCountX < 0) {
            throw new IllegalArgumentException("Negative new minimal tiles x-count: " + newMinimalTileCountX);
        }
        if (newMinimalTileCountY < 0) {
            throw new IllegalArgumentException("Negative new minimal tiles y-count: " + newMinimalTileCountY);
        }
        if (newMinimalTileCountX <= gridCountX && newMinimalTileCountY <= gridCountY) {
            return;
            // - even in a case !resizable
        }
        if (checkResizable && !resizable) {
            throw new IllegalArgumentException("Cannot expand tile counts in a non-resizable tile map");
        }
        final int tileCountX = Math.max(this.gridCountX, newMinimalTileCountX);
        final int tileCountY = Math.max(this.gridCountY, newMinimalTileCountY);
        if ((long) tileCountX * (long) tileCountY > Integer.MAX_VALUE / numberOfSeparatedPlanes) {
            throw new IllegalArgumentException("Too large number of tiles/strips: " +
                    (numberOfSeparatedPlanes > 1 ? numberOfSeparatedPlanes + " separated planes * " : "") +
                    tileCountX + " * " + tileCountY + " > 2^31-1");
        }
        this.gridCountX = tileCountX;
        this.gridCountY = tileCountY;
        this.numberOfGridTiles = tileCountX * tileCountY * numberOfSeparatedPlanes;
    }
}
