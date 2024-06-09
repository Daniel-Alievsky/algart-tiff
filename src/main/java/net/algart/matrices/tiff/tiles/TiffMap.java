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

import net.algart.arrays.TooLargeArrayException;
import net.algart.matrices.tiff.*;

import java.util.*;

public final class TiffMap {
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
    private final boolean tiled;
    private final int tileSizeX;
    private final int tileSizeY;
    private final int tileSizeInPixels;
    private final int tileSizeInBytes;
    // - Note: we store here information about samples and tiles structure, but
    // SHOULD NOT store information about image sizes (like number of tiles):
    // it is probable that we do not know final sizes while creating tiles of the image!
    private volatile int dimX = 0;
    private volatile int dimY = 0;
    private volatile int gridTileCountX = 0;
    private volatile int gridTileCountY = 0;
    private volatile int numberOfGridTiles = 0;

    public TiffMap(TiffIFD ifd) {
        this(ifd, false);
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
    public TiffMap(TiffIFD ifd, boolean resizable) {
        this.ifd = Objects.requireNonNull(ifd, "Null IFD");
        this.resizable = resizable;
        final boolean hasImageDimensions = ifd.hasImageDimensions();
        try {
            if (!hasImageDimensions && !resizable) {
                throw new IllegalArgumentException("TIFF image sizes (ImageWidth and ImageLength tags) " +
                        "are not specified; it is not allowed for non-resizable tile map");
            }
            this.tiled = ifd.hasTileInformation();
            if (resizable && !tiled) {
                throw new IllegalArgumentException("TIFF image is not tiled (TileWidth and TileLength tags " +
                        "are not specified); it is not allowed for resizable tile map: any processing " +
                        "TIFF image, such as writing its fragments, requires either knowing its final fixed sizes, " +
                        "or splitting image into tiles with known fixed sizes");
            }
            this.planarSeparated = ifd.isPlanarSeparated();
            this.numberOfChannels = ifd.getSamplesPerPixel();
            assert numberOfChannels <= TiffTools.MAX_NUMBER_OF_CHANNELS;
            this.numberOfSeparatedPlanes = planarSeparated ? numberOfChannels : 1;
            this.tileSamplesPerPixel = planarSeparated ? 1 : numberOfChannels;
            this.bitsPerSample = ifd.alignedBitDepth();
            // - so, we allow only EQUAL number of bytes/sample (but number if bits/sample can be different)
            assert (long) numberOfChannels * (long) bitsPerSample <
                    TiffTools.MAX_NUMBER_OF_CHANNELS * TiffTools.MAX_BITS_PER_SAMPLE;
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
            this.tileSizeX = ifd.getTileSizeX();
            this.tileSizeY = ifd.getTileSizeY();
            assert tileSizeX > 0 && tileSizeY > 0 : "non-positive tile sizes are not checked in IFD methods";
            if (hasImageDimensions) {
                setDimensions(ifd.getImageDimX(), ifd.getImageDimY(), false);
            }
            if ((long) tileSizeX * (long) tileSizeY > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Very large " +
                        (tiled ? "TIFF tiles " : "non-tiled TIFF ")
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

    public Optional<String> description() {
        return ifd.optDescription();
    }

    public boolean isTiled() {
        return tiled;
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
        assert tileCountX <= this.gridTileCountX && tileCountY <= this.gridTileCountY :
                "Grid dimensions were not correctly grown according map dimensions";
        if (tileCountX != this.gridTileCountX || tileCountY != this.gridTileCountY) {
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

    public int gridTileCountX() {
        return gridTileCountX;
    }

    public int gridTileCountY() {
        return gridTileCountY;
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
     * @param newMinimalTileCountX new minimal value for {@link #gridTileCountX()}.
     * @param newMinimalTileCountY new minimal value for {@link #gridTileCountY()}.
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
        int tileCountX = this.gridTileCountX;
        int tileCountY = this.gridTileCountY;
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

    public TiffTileIndex multiplaneIndex(int separatedPlaneIndex, int x, int y) {
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

    public TiffTile getOrNewMultiplane(int separatedPlaneIndex, int x, int y) {
        return getOrNew(multiplaneIndex(separatedPlaneIndex, x, y));
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
            if (tileIndex.xIndex() >= gridTileCountX || tileIndex.yIndex() >= gridTileCountY) {
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

    public TiffMap buildGrid() {
        for (int p = 0; p < numberOfSeparatedPlanes; p++) {
            for (int y = 0; y < gridTileCountY; y++) {
                for (int x = 0; x < gridTileCountX; x++) {
                    getOrNewMultiplane(p, x, y).cropToMap(true);
                }
            }
        }
        return this;
    }

    public void cropAll(boolean nonTiledOnly) {
        tileMap.values().forEach(tile -> tile.cropToMap(nonTiledOnly));
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
            gridTileCountX = 0;
            gridTileCountY = 0;
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
                (resizable ? "": dimX + "x" + dimY + " ") +
                "of " + tileMap.size() + " TIFF tiles (grid " + gridTileCountX + "x" + gridTileCountY +
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

    private byte[] toInterleaveOrSeparatedSamples(
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
                TiffTools.toInterleavedBytes(samples, numberOfChannels, bytesPerSample, numberOfPixels) :
                TiffTools.toSeparatedBytes(samples, numberOfChannels, bytesPerSample, numberOfPixels);
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
        if (newMinimalTileCountX <= gridTileCountX && newMinimalTileCountY <= gridTileCountY) {
            return;
            // - even in a case !resizable
        }
        if (checkResizable && !resizable) {
            throw new IllegalArgumentException("Cannot expand tile counts in a non-resizable tile map");
        }
        final int tileCountX = Math.max(this.gridTileCountX, newMinimalTileCountX);
        final int tileCountY = Math.max(this.gridTileCountY, newMinimalTileCountY);
        if ((long) tileCountX * (long) tileCountY > Integer.MAX_VALUE / numberOfSeparatedPlanes) {
            throw new IllegalArgumentException("Too large number of tiles/strips: " +
                    (numberOfSeparatedPlanes > 1 ? numberOfSeparatedPlanes + " separated planes * " : "") +
                    tileCountX + " * " + tileCountY + " > 2^31-1");
        }
        this.gridTileCountX = tileCountX;
        this.gridTileCountY = tileCountY;
        this.numberOfGridTiles = tileCountX * tileCountY * numberOfSeparatedPlanes;
    }

}
