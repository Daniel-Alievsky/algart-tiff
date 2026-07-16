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
import net.algart.math.IRectangularArea;
import net.algart.matrices.tiff.*;
import net.algart.matrices.tiff.bits.TiffUnpackingPrecisions;
import net.algart.matrices.tiff.samples.TiffSampleType;
import net.algart.matrices.tiff.samples.TiffSamples;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagPhotometric;

import java.awt.*;
import java.nio.ByteOrder;
import java.util.*;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * TIFF tile: container for samples (encoded or decoded) with given {@link TiffTileIndex index}.
 */
public final class TiffTile {
    public enum DuplicateStatus {
        /**
         * Unique tile. Its {@link #getStoredInFileDataOffset() file offset} is unique within this IFD.
         */
        UNIQUE,

        /**
         * The first (original) tile in a group of duplicates.
         * It has subsequent duplicates pointing to its {@link #getStoredInFileDataOffset() file offset}.
         */
        FIRST_DUPLICATE,

        /**
         * A subsequent duplicate. It does not contain its own physical data on disk
         * and references a previous {@link #FIRST_DUPLICATE} tile.
         */
        SUBSEQUENT_DUPLICATE;

        /**
         * Returns {@code true} if this status represents a first or subsequent duplicate.
         */
        public boolean isShared() {
            return this != UNIQUE;
        }

        /**
         * Returns {@code true} if this status represents a subsequent duplicate.
         */
        public boolean isSubsequentDuplicate() {
            return this == SUBSEQUENT_DUPLICATE;
        }
    }

    public enum CopyMode {
        COPY_REFERENCE,
        COPY_CONTENT,
        COPY_UNPACKED_SAMPLES;

        public boolean isSamePixelFormatRequired() {
            return this == COPY_UNPACKED_SAMPLES;
        }
    }

    public enum DuplicateHandling {
        LINK_REFERENCE,
        COPY_CONTENT;

        public boolean isLinkToOriginalIfPossible() {
            return this == LINK_REFERENCE;
        }
    }

    private static final boolean DISABLE_CROPPING = false;
    // - You may set this to true for creating little invalid stripped TIFF, where the last strip is not cropped.
    // Normal value is false.

    private final TiffMap map;
    private final int samplesPerPixel;
    private final int normalizedBitDepth;
    private final int normalizedBitsPerPixel;
    private final TiffTileIndex index;
    private int sizeX;
    private int sizeY;
    private int sizeInPixels;
    private int sizeInBytes;
    private int sizeInBits;
    private int lineSizeInBytesInsideTIFF;
    private boolean interleaved = false;
    private boolean encoded = false;
    private byte[] data = null;
    private long storedInFileDataOffset = -1;
    private int storedInFileDataLength = 0;
    private int storedInFileDataCapacity = 0;
    private DuplicateStatus duplicateStatus = DuplicateStatus.UNIQUE;
    private int linearIndexOfOriginalIfDuplicate = -1;
    private boolean rescaleWhenIncreasingBitDepthRequested = false;
    private boolean colorCorrectionRequested = false;
    private Queue<IRectangularArea> unsetArea = null;
    // - null value marks that all is empty;
    // it helps to defer actual subtracting until the moment when we know the correct tile sizes
    private boolean frozen = false;
    private TiffIO.CodecReport report = null;

    /**
     * Creates new tile with given index.
     *
     * <p>Note: created tile <b>may</b> lie outside its map, it is not prohibited.
     * This condition is checked not here, but in {@link TiffMap#put(TiffTile)} and other {@link TiffMap} methods.
     *
     * @param index tile index.
     */
    public TiffTile(TiffTileIndex index) {
        this.index = Objects.requireNonNull(index, "Null tile index");
        this.map = index.map();
        assert this.map != null : "Null map for tile index " + index;
        this.samplesPerPixel = map.tileSamplesPerPixel();
        this.normalizedBitDepth = map.normalizedBitDepth();
        this.normalizedBitsPerPixel = map.tileNormalizedBitsPerPixel();
        if (normalizedBitDepth == 1 && samplesPerPixel > 1) {
            throw new AssertionError("Binary image for " + samplesPerPixel +
                    " > 1 channels is not supported: it must be checked in the TiffMap constructor");
        }
        assert this.normalizedBitsPerPixel == samplesPerPixel * normalizedBitDepth;
        assert index.ifd() == map.ifd() : "index retrieved ifd from its tile map!";
        setSizes(map.tileSizeX(), map.tileSizeY());
        assert this.data == null;
    }

    public TiffMap map() {
        return map;
    }

    public TiffIFD ifd() {
        return map.ifd();
    }

    public TiffTileIndex index() {
        return index;
    }

    public int linearIndex() {
        return index.linear();
    }

    public boolean isPlanarSeparated() {
        return map.isPlanarSeparated();
    }

    public int numberOfChannels() {
        return map.numberOfChannels();
    }

    /**
     * Returns number of samples per pixel. Note that it will be always 1 in
     * {@link #isPlanarSeparated() planar-separated} mode, even for 3-channels images.
     * In a usual (chunked) mode, this number is equal to the number of channels.
     *
     * @return number of samples per pixel.
     */
    public int samplesPerPixel() {
        return samplesPerPixel;
    }

    public boolean isBinary() {
        return map.isBinary();
    }

    public boolean isWholeBytes() {
        return map.isWholeBytes();
    }

    public boolean isRarePrecision() {
        return map.isRarePrecision();
    }

    /**
     * Returns the number of bits per each sample of this tile.
     * Always equal to {@link #map()}.{@link TiffMap#normalizedBitDepth() bitsPerSample()}.
     * Note that this number is always the same for all channels and is always divided by 8,
     * excepting the only case 1-channel 1-bit pixels.
     *
     * <p><i>Warning:</i> this number can be smaller than the result of the same method of {@link #sampleType()}
     * object! This is possible for unusual precisions, like 24-bit integer or 16/24-bit float samples.
     * See {@link TiffMap#setRarePrecisionMode(TiffMap.RarePrecisionMode)} and
     * {@link TiffReader#completeDecoding(TiffTile)} methods.</p>
     *
     * <p>Note that you can see unpacked data only in two variants:</p>
     * <ol>
     *     <li>fully unpacked, when the number of bits per sample corresponds to one of variants of
     *     {@link TiffSampleType};</li>
     *     <li>unpacked to a <i>rare precision</i>: 3-byte samples (17..24 bits/sample),
     *     16- or 24-bit floating-point formats.</li>
     * </ol>
     * <p>Inside this class, you are always dealing with the variant #2 (excepting call of
     * {@link #getUnpackedSampleBytes()} method). The {@link TiffReader} class
     * usually returns data in the option #1, unless you disable this by
     * {@link TiffMap#setRarePrecisionMode(TiffMap.RarePrecisionMode)} method.
     * The {@link TiffWriter} class always takes the data in the variant #1.</p>
     *
     * @return number of bits per each sample (1 channel for 1 pixel).
     * @see TiffMap#bitsPerUnpackedSample()
     * @see TiffTile#normalizedBitDepth()
     */
    public int normalizedBitDepth() {
        return normalizedBitDepth;
    }

    public OptionalInt bytesPerSample() {
        return map.bytesPerSample();
    }

    public int normalizedBitsPerPixel() {
        return normalizedBitsPerPixel;
    }

    public OptionalInt bytesPerPixel() {
        OptionalInt opt = bytesPerSample();
        return opt.isPresent() ? OptionalInt.of(opt.getAsInt() * samplesPerPixel) : OptionalInt.empty();
    }

    public TiffSampleType sampleType() {
        return map.sampleType();
    }

    public Class<?> elementType() {
        return map.elementType();
    }

    public TiffMap.TilingMode tilingMode() {
        return map.tilingMode();
    }

    public ByteOrder byteOrder() {
        return map.byteOrder();
    }

    public int compressionCode() {
        return map.compressionCode();
    }

    public Optional<TagCompression> compressionOrNoneForMissing() {
        return map.compressionOrNoneForMissing();
    }

    public int photometricCode() {
        return map.photometricCode();
    }

    public Optional<TagPhotometric> photometric() {
        return map.photometric();
    }

    public int[] getYCbCrSubsampling() {
        return map.getYCbCrSubsampling();
    }

    public int fromX() {
        return index.fromX();
    }

    public int fromY() {
        return index.fromY();
    }

    public int getSizeX() {
        return sizeX;
    }

    public TiffTile setSizeX(int sizeX) {
        return setSizes(sizeX, this.sizeY);
    }

    public int getSizeY() {
        return sizeY;
    }

    public TiffTile setSizeY(int sizeY) {
        return setSizes(this.sizeX, sizeY);
    }

    /**
     * Sets the sizes of this tile.
     *
     * <p>These are purely informational properties, not affecting processing the stored data
     * and supported for additional convenience of usage this object.
     *
     * <p>There is a guarantee that the total {@link #getSizeInBits() number of bits},
     * required to store <code>sizeX*sizeY</code> pixels, will be <code>&le;Integer.MAX_VALUE</code>.
     * Moreover, there is a guarantee that the same is true for the nearest integer &ge;<code>sizeX</code>,
     * whisi is divisible by 8, i.e. <code>(sizeX&nbsp;+&nbsp;7)&nbsp;/&nbsp;8&nbsp;*&nbsp;8</code>):</p>
     * <pre>
     *     ((sizeX + 7) / 8 * 8) * sizeY * {@link #getSizeInBits()} &le; Integer.MAX_VALUE</code>
     * </pre>
     * <p>If the specified sizes are too large to fit this limitation,
     * this method throws {@link TooLargeArrayException}.</p>
     *
     * @param sizeX the tile width; must be positive.
     * @param sizeY the tile height; must be positive.
     * @return a reference to this object.
     * @throws IllegalArgumentException if the specified sizes are negative, zero, or too large.
     */
    public TiffTile setSizes(int sizeX, int sizeY) {
        if (sizeX <= 0) {
            throw new IllegalArgumentException("Zero or negative tile x-size: " + sizeX);
        }
        if (sizeY <= 0) {
            throw new IllegalArgumentException("Zero or negative tile y-size: " + sizeY);
        }
        // - zero sizes are disabled, in particular, to provide correct IRectangularArea processing
        final long alignedSizeX = ((long) sizeX + 7L) & ~7L;
        // - for checking sizes only! This value has no sense for whole-byte format!
        assert alignedSizeX >= sizeX && alignedSizeX >> 3 == (sizeX + 7) >>> 3;
        Supplier<String> alignedMsg = () -> alignedSizeX == sizeX ? "" :
                " (after aligning " + sizeX + " to slightly larger width " + alignedSizeX + ", divisible by 8)";
        if (alignedSizeX * (long) sizeY > Integer.MAX_VALUE) {
            throw new TooLargeArrayException("Very large TIFF tile " + sizeX + "x" + sizeY +
                    " >= 2^31 pixels is not supported" + alignedMsg.get());
        }
        if (alignedSizeX * (long) sizeY * (long) normalizedBitsPerPixel > Integer.MAX_VALUE) {
            throw new TooLargeArrayException("Very large TIFF tile " + sizeX + "x" + sizeY +
                    ", " + samplesPerPixel + " channels per " + normalizedBitDepth +
                    " bits >= 2^31 bits (256 MB) is not supported" + alignedMsg.get());
        }
        final int sizeInPixels = sizeX * sizeY;
        assert alignedSizeX * (long) normalizedBitsPerPixel <= Integer.MAX_VALUE :
                "impossible because " + sizeY + " > 0";
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeInPixels = sizeInPixels;
        this.sizeInBits = sizeInPixels * normalizedBitsPerPixel;
        this.sizeInBytes = (sizeInBits + 7) >>> 3;
        this.lineSizeInBytesInsideTIFF = ((sizeX * normalizedBitsPerPixel + 7) >>> 3);
        assert (long) lineSizeInBytesInsideTIFF * (long) sizeY <= Integer.MAX_VALUE :
                "too large " + lineSizeInBytesInsideTIFF + "*" + sizeY;
        // - impossible because even the number of BITS is not greater than Integer.MAX_VALUE
        return this;
    }

    public TiffTile setEqualSizes(TiffTile other) {
        Objects.requireNonNull(other, "Null other tile");
        return setSizes(other.sizeX, other.sizeY);
    }

    public boolean equalSizes(TiffTile other) {
        return other != null && sizeX == other.sizeX && sizeY == other.sizeY;
        // - note: there is no sense to check samplesPerPixel, it is not a "size", but a property of pixel format
    }

    /**
     * Returns {@link #getSizeX()} * {@link #getSizeY()}.
     *
     * @return number of pixels in the tile.
     */
    public int getSizeInPixels() {
        return sizeInPixels;
    }

    /**
     * Returns <code>({@link #getSizeInPixels()} * {@link #normalizedBitsPerPixel()} + 7) / 8</code>.
     *
     * @return the length of the minimal <code>byte[]</code> data array, enough to store all tile pixels.
     */
    public int getSizeInBytes() {
        return sizeInBytes;
    }

    /**
     * Returns <code>({@link #getSizeX()} * {@link #normalizedBitsPerPixel()} + 7) / 8</code>:
     * size of each line in bytes inside the TIFF file.
     * (According to the TIFF format, lines should be aligned to an integer number of bytes.)
     *
     * @return the number of bytes in each horizontal row of pixels.
     */
    public int getLineSizeInBytesInsideTIFF() {
        return lineSizeInBytesInsideTIFF;
    }

    /**
     * Returns {@link #getLineSizeInBytesInsideTIFF()} * {@link #getSizeY()}</code>:
     * the size of the unpacked tile according to storing rules of TIFF format.
     *
     * @return the length of the minimal <code>byte[]</code> data array, enough to store all tile pixels
     * after unpacking according TIFF rules (each line is byte-aligned).
     */
    public int getSizeInBytesInsideTIFF() {
        return lineSizeInBytesInsideTIFF * sizeY;
    }

    /**
     * Returns {@link #getSizeInPixels()} * {@link #normalizedBitsPerPixel()}.
     * There is a guarantee that this value is &le;2<sup>31</sup> and, so,
     * can be represented by <code>int</code> value.
     *
     * @return number of bits, necessary to store all tile pixels.
     */
    public int getSizeInBits() {
        return sizeInBits;
    }

    /**
     * Returns the rectangle inside the tile (starting at coordinates (0,0))
     * that may contain some useful data.
     *
     * <p>Usually it is the entire tile rectangle <code>0..{@link #getSizeX()}-1 x 0..{@link #getSizeY()}-1</code>,
     * but it is cropped by the overall map dimensions in the case when the map
     * is not {@link TiffMap#isResizable() resizable}. (For a resizable map, we cannot be sure that its sizes
     * will not be increased by future operations.)</p>
     *
     * @return the rectangle inside the tile that may contain data.
     */
    public IRectangularArea actualRectangle() {
        final int sizeXInTile = map.isResizable() ? sizeX : Math.min(sizeX, map.dimX() - fromX());
        final int sizeYInTile = map.isResizable() ? sizeY : Math.min(sizeY, map.dimY() - fromY());
        return rectangleInTile(0, 0, sizeXInTile, sizeYInTile);
    }

    public IRectangularArea rectangleInTile(int fromXInTile, int fromYInTile, int sizeXInTile, int sizeYInTile) {
        if (sizeXInTile <= 0) {
            throw new IllegalArgumentException("Zero or negative sizeXInTile = " + sizeXInTile);
        }
        if (sizeYInTile <= 0) {
            throw new IllegalArgumentException("Zero or negative sizeYInTile = " + sizeYInTile);
        }
        final long minX = (long) index.fromX() + (long) fromXInTile;
        final long minY = (long) index.fromY() + (long) fromYInTile;
        final long maxX = minX + (long) sizeXInTile - 1;
        final long maxY = minY + (long) sizeYInTile - 1;
        return IRectangularArea.of(minX, minY, maxX, maxY);
    }

    public boolean isFullyInsideMap() {
        return index.fromX() + sizeX <= map.dimX() && index.fromY() + sizeY <= map.dimY();
    }

    /**
     * Equivalent to {@link #cropToMap(boolean) cropToMap(true)}.
     *
     * @return a reference to this object.
     * @throws IllegalStateException if this tile is completely outside map dimensions.
     */
    public TiffTile cropStripToMap() {
        return cropToMap(true);
    }

    /**
     * Reduces the sizes of this tile so that it will completely lie inside map dimensions.
     *
     * <p>This operation can be useful for
     * {@link net.algart.matrices.tiff.tiles.TiffMap.TilingMode#STRIPS stripped TIFF image},
     * especially while writing.
     * But there are no reasons to call this for
     * {@link net.algart.matrices.tiff.tiles.TiffMap.TilingMode#TILE_GRID tiled image}.
     * For tiled image, a TIFF file usually contains full-size encoded tiles even on the image boundary;
     * they should be cropped after decoding by external means. You can disable an attempt to reduce
     * tile in tiled image by passing <code>strippedOnly=true</code>.
     *
     * @param strippedOnly if <code>true</code>, this function will not do anything when the map
     *                     is a  {@link net.algart.matrices.tiff.tiles.TiffMap.TilingMode#TILE_GRID tiled image}.
     *                     While using for reading/writing TIFF files,
     *                     this argument usually should be <code>true</code>.
     * @return a reference to this object.
     * @throws IllegalStateException if this tile is completely outside map dimensions.
     */
    public TiffTile cropToMap(boolean strippedOnly) {
        checkOutsideMap();
        if (DISABLE_CROPPING || (strippedOnly && map.tilingMode().isTileGrid())) {
            return this;
        } else {
            return setSizes(Math.min(sizeX, map.dimX() - index.fromX()), Math.min(sizeY, map.dimY() - index.fromY()));
        }
    }

    /**
     * Returns the current unset area in this tile.
     * Note that initially the unset area consists from a single rectangle equal to {@link #actualRectangle()}.
     *
     * <p>Note that this information is <i>independent</i> on data: this area is not changed by the methods
     * like {@link #setDecodedData(byte[])} or {@link #copyData(TiffTile, CopyMode)}.
     * However, this area is set to empty ({@link #markWholeTileAsSet()}) when loading the tile from the file
     * by {@link TiffTileIO#readAt} method.
     *
     * @return the current unset area in this tile.
     */
    public Collection<IRectangularArea> getUnsetArea() {
        return unsetArea == null ? List.of(actualRectangle()) : Collections.unmodifiableCollection(unsetArea);
    }

    public TiffTile markWholeTileAsUnset() {
        unsetArea = null;
        return this;
    }

    public TiffTile markWholeTileAsSet() {
        unsetArea = new LinkedList<>();
        return this;
    }

    @SuppressWarnings("UnusedReturnValue")
    public TiffTile markNewAreaAsSet(IRectangularArea... newlyFilledArea) {
        Objects.requireNonNull(newlyFilledArea, "Null newlyFilledArea");
        initializeEmptyArea();
        IRectangularArea.subtractCollection(unsetArea, newlyFilledArea);
        return this;
    }

    @SuppressWarnings("UnusedReturnValue")
    public TiffTile markNewRectangleAsSet(int fromXInTile, int fromYInTile, int sizeXInTile, int sizeYInTile) {
        if (sizeXInTile > 0 && sizeYInTile > 0) {
            markNewAreaAsSet(rectangleInTile(fromXInTile, fromYInTile, sizeXInTile, sizeYInTile));
        }
        return this;
    }

    @SuppressWarnings("UnusedReturnValue")
    public TiffTile cropUnsetAreaToMap() {
        checkOutsideMap();
        if (!isFullyInsideMap()) {
            // - little optimization
            long minX = map.dimX();
            long minY = map.dimY();
            markNewAreaAsSet(
                    IRectangularArea.of(0, minY, Integer.MAX_VALUE, Integer.MAX_VALUE),
                    IRectangularArea.of(minX, 0, Integer.MAX_VALUE, Integer.MAX_VALUE));
            // Integer.MAX_VALUE is enough: we work with 32-bit coordinates
            // Note that Long.MAX_VALUE is not permitted here, maximal allowed value is Long.MAX_VALUE-1
        }
        return this;
    }

    public boolean isCompleted() {
        return !hasUnsetArea();
    }

    public boolean isCompletelyUnset() {
        return unsetArea == null;
    }

    public boolean hasUnsetArea() {
        return unsetArea == null || !unsetArea.isEmpty();
    }

    /**
     * Returns <code>true</code>>, if the stored pixel samples (as supposed) are interleaved, like RGBRGB...,
     * or <code>false</code> if not (RRR...GGG...BBB...).
     * It doesn't matter in the case of monochrome images and in the case of {@link #isEncoded() encoded} data.
     * Default value is <code>false</code>.
     *
     * <p>By default, the data are considered to be <b>not</b> interleaved, in other words, {@link #isSeparated()
     * separated}. Methods, reading and decoding the tile from TIFF, always return separated tile.
     * Methods encoding the file for writing to TIFF may work both with interleaved tiles,
     * but it should be explicitly declared.</p>
     *
     * <p>This is a purely informational property, not affecting processing the stored data
     * by methods of this object and supported for additional convenience of usage this class.</p>
     *
     * @return whether the data in the tile are interleaved.
     */
    public boolean isInterleaved() {
        return interleaved;
    }

    public boolean isSeparated() {
        return !interleaved;
    }

    /**
     * Sets the flag, are the stored pixel samples are interleaved (like RGBRGB...) or not (RRR...GGG...BBB...).
     * See {@link #isInterleaved()}.
     *
     * @param interleaved whether the data should be considered as interleaved.
     * @return a reference to this object.
     */
    public TiffTile setInterleaved(boolean interleaved) {
        this.interleaved = interleaved;
        return this;
    }

    //Note: there is no setEncoded() method, it could violate invariants provided by setDecodedData
    public boolean isEncoded() {
        return encoded;
    }

    public void checkReadyForNewDecodedData(Boolean requiredInterleavedState) {
        if (encoded) {
            throw new IllegalStateException("TIFF tile is not ready to store new decoded data, because " +
                    "it is encoded (probably contains encoded data): " + this);
        }
        if (requiredInterleavedState != null && requiredInterleavedState != interleaved) {
            final String status = interleaved ? "interleaved" : "separated";
            throw new IllegalStateException("TIFF tile is not ready to store new decoded data, because " +
                    "it is " + status + " (probably contains decoded, but already " + status + " data): " + this);
        }
    }

    /**
     * Throws an exception if this tile does not contain encoded data.
     * Called in the {@link #getEncodedData()} method.
     *
     * @throws IllegalStateException if the tile is {@link #isEmpty() empty} or not {@link #isEncoded() encoded}.
     */
    public void checkEncodedData() {
        checkEmpty();
        if (!isEncoded()) {
            throw new IllegalStateException("TIFF tile is not encoded: " + this);
        }
    }

    public byte[] getEncodedData() {
        checkEncodedData();
        return data;
    }

    /**
     * Equivalent to <code>{@link #getEncodedData()}.length</code>.
     *
     * @return the length of encoded data <code>byte[]</code> array.
     */
    public int getEncodedDataLength() {
        checkEncodedData();
        return data.length;
    }

    public TiffTile setEncodedData(byte[] data) {
        return setEncodedData(data, false);
    }

    public TiffTile setEncodedData(byte[] data, boolean unfreeze) {
        return setData(data, true, false, unfreeze);
    }

    /**
     * Throws an exception if this tile does not contain decoded data.
     * Called in the {@link #getDecodedData()} method.
     *
     * @throws IllegalStateException if the tile is {@link #isEmpty() empty} or {@link #isEncoded() encoded}.
     */
    public void checkDecodedData() {
        checkEmpty();
        if (isEncoded()) {
            throw new IllegalStateException("TIFF tile data are not decoded and cannot be retrieved: " + this);
        }
    }

    /**
     * Returns a reference to the decoded data.
     * Every pixel in the unpacked data consists of {@link #samplesPerPixel()}
     * <i>samples</i>, and every sample is represented with either 1 bit or 1, 2, 3 or 4 whole bytes.
     * If the samples in the TIFF file are K-bit integers where <code>K%8&nbsp;&ne;&nbsp;0</code>,
     * they are automatically unpacked into <code>&#8968;K/8&#8969;*8</code> bit integers while decoding.
     *
     * <p>In addition to the standard precisions provided by {@link TiffSampleType}, samples can be represented
     * in the following {@link #isRarePrecision() rare precisions}:</p>
     *
     * <ul>
     *     <li>16-bit floating-point values,</li>
     *     <li>24-bit floating-point values,</li>
     *     <li>24-bit integer values (for the case of K-bit samples, 16&le;K&lt;24).</li>
     * </ul>
     *
     * @return decoded data.
     * @throws IllegalStateException if the tile is {@link #isEmpty() empty} or {@link #isEncoded() encoded}.
     * @see #getUnpackedSampleBytes()
     * @see TiffMap#isRarePrecision()
     */
    public byte[] getDecodedData() {
        checkDecodedData();
        return data;
    }

    /**
     * Equivalent to <code>{@link #getDecodedData()}.length</code>.
     *
     * @return the length of decoded data <code>byte[]</code> array.
     */
    public int getDecodedDataLength() {
        checkDecodedData();
        return data.length;
    }

    public TiffTile setDecodedData(byte[] data) {
        return setDecodedData(data, false);
    }

    /**
     * Sets the decoded data.
     *
     * @param data     decoded data.
     * @param unfreeze if {@code true}, the {@link #isFrozen() frozen} flag is cleared;
     *                 usually should be {@code false}.
     * @return a reference to this object.
     */
    public TiffTile setDecodedData(byte[] data, boolean unfreeze) {
        return setData(data, false, true, unfreeze);
    }

    public TiffTile setPartiallyDecodedData(byte[] data) {
        return setData(data, false, false, false);
    }

    public TiffTile fillWhenEmpty() {
        return fillWhenEmpty(null, (byte) 0);
    }

    public TiffTile fillWhenEmpty(Consumer<TiffTile> initializer, byte byteFiller) {
        checkFrozen();
        // - if frozen, then isEmpty() below returns true
        if (isEmpty()) {
            byte[] newData = new byte[sizeInBytes];
            if (byteFiller != 0) {
                Arrays.fill(newData, byteFiller);
            }
            setDecodedData(newData);
            if (initializer != null) {
                initializer.accept(this);
            }
        }
        return this;
    }

    public double[] colorToChannelValues(Color color, boolean scaleToMaxValue) {
        return map.colorToChannelValues(color, samplesPerPixel(), scaleToMaxValue);
    }

    /**
     * Gets the decoded data while unpacking rare precision data: 16/24-bit floating-point data
     * and any 3-byte/sample integer data. The same operations are performed by
     * {@link TiffReader} automatically
     * if the {@link TiffMap#setRarePrecisionMode(TiffMap.RarePrecisionMode) rare precision mode} is set
     * to {@link net.algart.matrices.tiff.tiles.TiffMap.RarePrecisionMode#UNPACK}.
     *
     * <p>This method is rarely necessary: {@link #getDecodedData()} is enough for most needs.
     *
     * <p>Note that we have no a separate method "setUnpackedSampleBytes".
     * The unpacked samples can be set via the {@link #setDecodedData(byte[])} method:
     * we do not support writing rare precision.</p>
     *
     * <p>This method uses {@link #isRescaleWhenIncreasingBitDepthRequested()} to detect
     * whether this tile was decoded by a {@link TiffReader} with the
     * {@link TiffReader#setRescaleWhenIncreasingBitDepth(boolean)} flag enabled.
     * If this flag is {@code true}, 3-byte integer samples will be rescaled as specified
     * in the documentation for the {@link TiffReader#setRescaleWhenIncreasingBitDepth(boolean)} method.
     *
     * @return unpacked data. Note that for standard precisions, this method may return
     * a direct reference to the internal data array; for rare precisions,
     * a newly allocated unpacked array is returned.
     * @see TiffUnpackingPrecisions#unpackRarePrecisions(byte[], TiffIFD, int, long, boolean)
     * @see #normalizedBitDepth()
     * @see TiffMap#bitsPerUnpackedSample()
     */
    public byte[] getUnpackedSampleBytes() {
        byte[] samples = getDecodedData();
        try {
            samples = TiffUnpackingPrecisions.unpackRarePrecisions(
                    samples, ifd(), samplesPerPixel, sizeInPixels, isRescaleWhenIncreasingBitDepthRequested());
        } catch (TiffException e) {
            throw new IllegalStateException("Illegal IFD inside the tile map", e);
        }
        return samples;
    }

    public Object getUnpackedJavaArray() {
        checkSeparated("convert the tile to an unpacked Java array");
        final byte[] samples = getUnpackedSampleBytes();
        // Note: we SHOULD NOT call map.bytesToJavaArray(samples)!
        // This method checks bitImageUnpackingMode and rarePrecisionMode,
        // but these settings, according the contract, DO NOT affect TiffTile methods.
        return sampleType().javaArray(samples, byteOrder());
    }

    public TiffTile setUnpackedJavaArray(Object samplesArray) {
        Objects.requireNonNull(samplesArray, "Null samplesArray");
        checkSeparated("set the tile data from an unpacked Java array");
        checkRarePrecision("convert Java array to bytes");
        final byte[] samples = map.javaArrayToBytes(samplesArray, sizeX, sizeY, samplesPerPixel);
        return setDecodedData(samples);
    }

    /**
     * Gets the decoded data in the form of a matrix, similar to {@link TiffReadMap#readMatrix()}.
     *
     * <p>Note: this method requires that the tile is
     * {@link #isSeparated() separated}, and otherwise throws an {@link IllegalStateException}.
     * This is typical usage, as decoded tiles are usually separated.</p>
     *
     * @return the unpacked data as a 3D matrix
     * @throws IllegalStateException if the tile is interleaved.
     */
    public Matrix<UpdatablePArray> getUnpackedMatrix() {
        checkSeparated("convert the tile to an unpacked matrix");
        return TiffSamples.asMatrix(getUnpackedJavaArray(), sizeX, sizeY, samplesPerPixel, false);
    }

    public TiffTile setUnpackedMatrix(Matrix<? extends PArray> matrix) {
        Objects.requireNonNull(matrix, "Null matrix");
        checkSeparated("set the tile data from an unpacked matrix");
        checkRarePrecision("convert matrix to bytes");
        final byte[] samples = map.matrixToBytes(matrix, sizeX, sizeY, samplesPerPixel);
        return setDecodedData(samples);
    }

    public TiffTile copyData(TiffTile source, CopyMode copyMode) {
        Objects.requireNonNull(source, "Null source tile");
        Objects.requireNonNull(copyMode, "Null copy mode");
        if (copyMode.isSamePixelFormatRequired()) {
            if (sampleType() != source.sampleType()) {
                throw new IllegalArgumentException("The specified source tile has incompatible " +
                        "sample type (" + source.elementType().getSimpleName() + ") than this tile: " + this);
            }
            if (samplesPerPixel != source.samplesPerPixel) {
                throw new IllegalArgumentException("The specified source tile has incompatible " +
                        "samples per pixel (" + source.samplesPerPixel + ") than this tile: " + this);
            }
            checkRarePrecision("copy unpacked samples");
            // - for a rare precision 24-bit, we have no simple way to change byte order and prefer to disable it
        }
        if (source.isEmpty()) {
            freeData();
        } else {
            switch (copyMode) {
                case COPY_REFERENCE -> {
                    setData(source.data, source.encoded, false, true);
                }
                case COPY_CONTENT -> {
                    setData(source.data.clone(), source.encoded, false, true);
                }
                case COPY_UNPACKED_SAMPLES ->  {
                    source.checkDecodedData();
                    if (map.isByteOrderCompatible(source.byteOrder())) {
                        final byte[] decodedData = source.getUnpackedSampleBytes();
                        setDecodedData(decodedData, true);
                    } else {
                        assert byteOrder() != source.byteOrder();
                        assert elementType() != boolean.class;
                        final byte[] decodedData = source.getUnpackedSampleBytes();
                        final byte[] swapped = JArrays.copyAndSwapByteOrder(decodedData, elementType());
                        setDecodedData(swapped, true);
                    }
                }
            }
        }
        frozen = source.frozen;
        return this;
    }

    public boolean isEmpty() {
        return data == null;
    }

    /**
     * Returns {@code true} if this tile was <i>frozen</i> by {@link #freeAndFreeze()} method.
     *
     * @return whether the tile is empty and frozen.
     */
    public boolean isFrozen() {
        return frozen;
    }

    public TiffIO.CodecReport getReport() {
        return report;
    }

    public TiffTile setReport(TiffIO.CodecReport report) {
        this.report = report;
        return this;
    }

    public void freeData() {
        this.data = null;
        this.interleaved = false;
        // - before possibly setting new decoded data, we should restore the default status interleaved = false
        this.encoded = false;
        // - method checkReadyForNewDecodedData() requires that the tile should not be declared as encoded
        // Note: we should not clear information about stored data file range, because
        // it will be used even after flushing data to disk (with freeing this tile)
    }

    /**
     * Calls {@link #freeData()} and marks this tile as <i>frozen</i>.
     *
     * <p>After {@link #freeData()} method, the tile becomes {@link #isEmpty() empty},
     * but can be filled with some data again, for example, using {@link #setDecodedData(byte[])}
     * or {@link #fillWhenEmpty()}.
     * Unlike this, after this method,
     * the tile usually cannot be modified: any attempt to get or set data
     * ({@link #getDecodedData()}, {@link #getEncodedData()}, {@link  #setDecodedData(byte[])},
     * {@link #setEncodedData(byte[])}, {@link #fillWhenEmpty()} etc.) will result in an exception.</p>
     *
     * <p>{@link TiffWriter} class checks {@link #isFrozen()} method and does not attempt to update
     * <i>frozen</i> tiles.</p>
     *
     * <p>This method is automatically called by {@link TiffWriter#writeTile(TiffTile, boolean)} method
     * when its second argument is <code>true</code>:
     * usually there is no any sense to work with a tile after once it has been written into the TIFF file.</p>
     *
     * <p>The <i>frozen</i> status may be cleared ("unfreezing") by the methods
     * {@link #setDecodedData(byte[], boolean)} and {@link #setEncodedData(byte[], boolean)}
     * with additional {@code boolean} argument "unfreeze".
     * Also, the <i>frozen</i> status is copied from the source tile by
     * the {@link #copyData(TiffTile, CopyMode)} method.</p>
     */
    public void freeAndFreeze() {
        freeData();
        this.frozen = true;
    }

    public void unfreeze() {
        this.frozen = false;
    }

    public long getStoredInFileDataOffset() {
        checkStoredFileOffset();
        return storedInFileDataOffset;
    }

    /**
     * Return the length of the last non-null encoded data array, stored in the TIFF file,
     * or 0 after creating this object.
     *
     * <p>Immediately after reading a tile from the file, as well as
     * immediately before/after writing it into the file, this method returns the number of encoded bytes,
     * which are actually stored in the file for this tile.
     *
     * <p>Note: {@link #freeData()} method does not change this value! So, you can know the stored data size
     * even after freeing data inside this object.
     *
     * @return the length of the last non-null encoded data, which was read or write to the TIFF file.
     */
    public int getStoredInFileDataLength() {
        return storedInFileDataLength;
    }

    public boolean isStoredInFile() {
        return storedInFileDataOffset >= 0;
    }

    public TiffTile clearStoredInFile() {
        storedInFileDataOffset = -1;
        return this;
    }

    public TiffTile setStoredInFileDataRange(long storedInFileDataOffset, int storedInFileDataLength) {
        return setStoredInFileDataRange(storedInFileDataOffset, storedInFileDataLength, true);
    }

    public TiffTile setStoredInFileDataRange(
            long storedInFileDataOffset,
            long storedInFileDataLength,
            boolean resetCapacity) {
        if (storedInFileDataOffset < 0) {
            throw new IllegalArgumentException("Negative data offset in the file: " + storedInFileDataOffset);
        }
        if (storedInFileDataOffset == 0) {
            throw new IllegalArgumentException("Zero data offset in the file is not allowed for TIFF format");
        }
        if (storedInFileDataLength < 0) {
            throw new IllegalArgumentException("Negative length of data in the file: " + storedInFileDataLength);
        }
        if (storedInFileDataLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too large storedInFileDataLength = " +
                    storedInFileDataLength + " (>2^31-1)");
        }
        this.storedInFileDataOffset = storedInFileDataOffset;
        this.storedInFileDataLength = (int) storedInFileDataLength;
        if (resetCapacity) {
            resetStoredInFileDataCapacity();
        }
        return this;
    }

    public int getStoredInFileDataCapacity() {
        return storedInFileDataCapacity;
    }

    public TiffTile resetStoredInFileDataCapacity() {
        this.storedInFileDataCapacity = this.storedInFileDataLength;
        return this;
    }

    public TiffTile expandStoredInFileDataCapacity(int newStoredInFileDataCapacity) {
        if (newStoredInFileDataCapacity >= this.storedInFileDataLength) {
            // - probably extra check: usually there are no ways to make
            // storedInFileDataCapacity < storedInFileDataLength outside this method
            this.storedInFileDataCapacity = Math.max(this.storedInFileDataCapacity, newStoredInFileDataCapacity);
        }
        return this;
    }

    public OptionalInt optLinearIndexOfOriginalIfDuplicate() {
        return linearIndexOfOriginalIfDuplicate < 0 ?
                OptionalInt.empty() :
                OptionalInt.of(linearIndexOfOriginalIfDuplicate);
    }

    public int getLinearIndexOfOriginalIfDuplicate() {
        if (linearIndexOfOriginalIfDuplicate < 0) {
            throw new IllegalStateException("The TIFF tile is not a duplicate: " + this);
        }
        return linearIndexOfOriginalIfDuplicate;
    }

    public TiffTile setLinearIndexOfOriginalIfDuplicate(int linearIndexOfOriginalIfDuplicate) {
        if (linearIndexOfOriginalIfDuplicate < 0) {
            throw new IllegalArgumentException("Negative linearIndexOfOriginalIfDuplicate = " +
                    linearIndexOfOriginalIfDuplicate);
        }
        this.linearIndexOfOriginalIfDuplicate = linearIndexOfOriginalIfDuplicate;
        return this;
    }

    public boolean isDuplicate() {
        return linearIndexOfOriginalIfDuplicate >= 0;
    }

    public TiffTile clearDuplicate() {
        linearIndexOfOriginalIfDuplicate = -1;
        return this;
    }

    public TiffTile copyStoredInFileDataRange(TiffTile other) {
        Objects.requireNonNull(other, "Null other tile");
        this.storedInFileDataOffset = other.storedInFileDataOffset;
        this.storedInFileDataLength = other.storedInFileDataLength;
        this.storedInFileDataCapacity = other.storedInFileDataCapacity;
        return this;
    }

    public TiffTile linkAsDuplicateOf(TiffTile other) {
        Objects.requireNonNull(other, "Null other tile");
        if (!isEmpty()) {
            throw new IllegalStateException("Cannot mark a tile as a duplicate when it contains data: " + this);
        }
        setLinearIndexOfOriginalIfDuplicate(other.linearIndex());
        copyStoredInFileDataRange(other);
        return this;
    }

    /**
     * Returns the estimated number of pixels that can be stored in the {@link #getDecodedData() data array}.
     *
     * <p>If the data is {@link #isEmpty() empty}
     * (e.g., immediately after creating this object), or if the data is {@link #isEncoded() encoded}
     * (e.g., after reading the tile from a file), this throws an {@link IllegalStateException}.
     * Otherwise, it returns</p>
     *
     * <pre>8 * {@link #getDecodedDataLength()} / {@link #normalizedBitsPerPixel()}</pre>
     *
     * <p>Note that the following equality is <i>usually</i> true:</p>
     *
     * <pre>{@link #getDecodedDataLength()} == ({@link #estimatedNumberOfPixels()} * {@link
     * #normalizedBitsPerPixel()} + 7) / 8</pre>
     *
     * <p>The only possible exception is when you set the data using
     * {@link #setPartiallyDecodedData(byte[])} (when the data is almost decoded, but perhaps some additional
     * unpacking is necessary). In this situation, the length of an internal {@code data} array, returned by
     * {@link #getDecodedDataLength()}, may be non-aligned in cases when each pixel contains {@code k > 1} bytes
     * (here <code>{@link #normalizedBitsPerPixel()}&nbsp;==&nbsp;8&nbsp;*&nbsp;k</code>): it is possible that</p>
     *
     * <pre>{@code data.length % k != 0}</pre>
     *
     * <p>This condition is always checked inside the {@link #setDecodedData(byte[])} method.
     * You may also check this directly via the {@link #checkDataLengthAlignment()} method.</p>
     *
     * <p><b>Warning:</b> the estimated number of pixels returned by this method may <b>differ</b> from the tile
     * size {@link #getSizeX()} * {@link #getSizeY()}! Usually, it occurs after decoding an encoded tile, when the
     * decoding method returns only a sequence of pixels and does not return information about the size.
     * In this situation, {@link TiffReader} sets the tile sizes from the IFD information, but the decoded tile
     * may actually be smaller. For example, this occurs for the last strip in a non-tiled TIFF format,
     * when cropping tiles is disabled by {@link TiffReader#setCropTilesToImageBoundaries(boolean)} method;
     * however, {@link TiffReader} automatically corrects the data length inside the
     * {@link TiffReader#completeDecoding(TiffTile)} method even in this case.
     * You can check whether the actual number of stored pixels equals the tile size via the
     * {@link #checkDataLengthMatchesTileSize()} method.</p>
     *
     * <p>Also note that for a binary image (1 bit per pixel), this method always returns a number
     * <i>rounded up</i> to the nearest multiple of 8. This is calculated based on the length of the
     * decoded {@code byte[]} data array as {@code length * 8}.</p>
     *
     * @return the number of pixels in the last non-null data array which was stored in this object.
     */
    @SuppressWarnings("JavadocDeclaration")
    public int estimatedNumberOfPixels() {
        checkEmpty();
        if (isEncoded()) {
            throw new IllegalStateException("TIFF tile data are not decoded, " +
                    "number of pixels cannot be estimated: " + this);
        }
        final long numberOfPixels = 8L * (long) data.length / normalizedBitsPerPixel();
        if (numberOfPixels > Integer.MAX_VALUE) {
            throw new AssertionError("Too large numberOfPixels = " + numberOfPixels +
                    " (must be checked in setData)");
        }
        return (int) numberOfPixels;
    }

    public boolean isRescaleWhenIncreasingBitDepthRequested() {
        return rescaleWhenIncreasingBitDepthRequested;
    }

    public void setRescaleWhenIncreasingBitDepthRequested(boolean rescaleWhenIncreasingBitDepthRequested) {
        this.rescaleWhenIncreasingBitDepthRequested = rescaleWhenIncreasingBitDepthRequested;
    }

    public boolean isColorCorrectionRequested() {
        return colorCorrectionRequested;
    }

    public void setColorCorrectionRequested(boolean colorCorrectionRequested) {
        this.colorCorrectionRequested = colorCorrectionRequested;
    }

    /**
     * Checks whether the length of the data array in bytes is correctly aligned: the data contains an integer number
     * of whole pixels. If it is not so, throws <code>IllegalStateException</code>.
     *
     * <p>Note that unaligned length is impossible for 1 bit/sample, because we support only 1-channel images
     * with 1 bit/sample.</p>
     *
     * <p>This method must not be called for {@link #isEncoded() encoded} tile.
     *
     * <p>This method is called after reading and complete decoding any tile of the TIFF file.
     */
    public void checkDataLengthAlignment() {
        final int estimatedNumberOfPixels = estimatedNumberOfPixels();
        // - IllegalStateException if encoded
        assert !encoded;
        final int expectedNumberOfBytes = (estimatedNumberOfPixels * normalizedBitsPerPixel + 7) >>> 3;
        if (expectedNumberOfBytes != data.length) {
            assert normalizedBitsPerPixel != 1 : "unaligned estimatedNumberOfPixels cannot appear for 1 bit/pixel";
            // - in the current version it means that we have whole bytes: bitsPerPixel = 8*K;
            // see assertions in setData for a case of bitsPerPixel == 1
            throw new IllegalStateException("Unaligned length of decoded data " + data.length +
                    ": it is not equal to ceil(number of pixels * bits per pixel / 8) = ceil(" +
                    estimatedNumberOfPixels + " * " + normalizedBitsPerPixel + " / 8) = " + expectedNumberOfBytes +
                    ", as if the last pixel is stored \"partially\"");
        }
    }

    /**
     * Checks whether the data array length ({@link #getDecodedDataLength()})
     * matches the declared tile size {@link #getSizeInBytes()}.
     * If it is not so, throws <code>IllegalStateException</code>.
     *
     * <p>This method must not be called for {@link #isEncoded() encoded} tile.
     *
     * <p>This method is called before encoding and writing any tile to the TIFF file.
     */
    public TiffTile checkDataLengthMatchesTileSize() {
        final int decodedDataLength = getDecodedDataLength();
        assert !encoded;
        // Deprecated check (storedInFileDataLength was set =data.length in old versions)
        // if (data.length != storedInFileDataLength) {
        //     throw new IllegalStateException("Stored data length field " + storedInFileDataLength +
        //             " is set to the value, different from the actual data length " + data.length);
        // }
        if (decodedDataLength != sizeInBytes) {
            throw new IllegalStateException("The data length " + decodedDataLength +
                    " does not match tile sizes " + sizeX + "x" + sizeY + " = " + sizeInPixels +
                    ", " + normalizedBitsPerPixel + " bits/pixel");
        }
        return this;
    }

    public TiffTile adjustNumberOfPixels(boolean allowDecreasing) {
        return changeNumberOfPixels(sizeInPixels, allowDecreasing);
    }

    public TiffTile changeNumberOfPixels(long newNumberOfPixels, boolean allowDecreasing) {
        if (newNumberOfPixels < 0) {
            throw new IllegalArgumentException("Negative new number of pixels = " + newNumberOfPixels);
        }
        final long newNumberOfBits = newNumberOfPixels * (long) normalizedBitsPerPixel;
        if (newNumberOfPixels > Integer.MAX_VALUE || newNumberOfBits > Integer.MAX_VALUE) {
            // - first check is necessary for a case of overflow in newNumberOfBits
            throw new IllegalArgumentException("Too large requested number of pixels in tile: " + newNumberOfPixels +
                    " pixels * " + samplesPerPixel + " samples/pixel * " + normalizedBitDepth + " bits/sample >= " +
                    "2^31 bits (256 MB), such large tiles are not supported");
        }
        final int newLength = (int) ((newNumberOfBits + 7) >>> 3);
        final byte[] data = getDecodedData();
        // - performs all necessary state checks
        if (newLength == data.length) {
            // - nothing to change: data has a correct length
            return this;
        }
        // The following code is rarely executed (excepting 1-bit case), for example, while reading a stripped TIFF,
        // where the last strip is not cropped correctly
        // (see resources\demo\images\tiff\algart\jpeg_rgb_stripped_with_uncropped_last_strip.tiff)
        if (newLength < data.length && !allowDecreasing) {
            throw new IllegalArgumentException("The new number of pixels " + newNumberOfPixels +
                    " is less than actually stored; this is not allowed: data may be lost");
        }
        byte[] newData;
        if (interleaved || samplesPerPixel == 1) {
            // Note: for interleaved tile we ALSO do not need estimatedNumberOfPixels.
            // In future versions, this can allow us to implement multichannel 1-bit images,
            // but ONLY IF they are always stored interleaved (as for Deflate/LZW and similar "old" formats).
            newData = Arrays.copyOf(data, newLength);
        } else {
            if ((normalizedBitsPerPixel & 7) != 0) {
                throw new AssertionError("Unsupported bits per pixel " + normalizedBitsPerPixel + " for " +
                        samplesPerPixel + " channel (more than one)");
                // - for example, 1-bit RGB is not supported:
                // we cannot calculate the number of pixels to separate or interleave them
            }
            newData = new byte[newLength];
            // - zero-filled by Java
            final long size = (long) estimatedNumberOfPixels() * normalizedBitDepth;
            // normalizedBitDepth is multiply of 8, so, estimatedNumberOfPixels is the actual number of stored pixels
            final long newSize = newNumberOfPixels * normalizedBitDepth;
            final long sizeToCopy = Math.min(size, newSize);
            for (long s = 0, disp = 0, newDisp = 0; s < samplesPerPixel; s++, disp += size, newDisp += newSize) {
                PackedBitArraysPer8.copyBitsNoSync(newData, newDisp, data, disp, sizeToCopy);
                // - actually, this is equivalent to System.arraycopy,
                // but we use copyBitsNoSync for possible future versions if they allow multichannel 1-bit images
            }
        }
        return setDecodedData(newData);
    }

    public TiffTile interleaveSamplesIfNecessary() {
        checkEmpty();
        if (!isInterleaved()) {
            interleaveSamples();
        }
        return this;
    }

    public TiffTile separateSamplesIfNecessary() {
        checkEmpty();
        if (isInterleaved()) {
            separateSamples();
        }
        return this;
    }

    public TiffTile interleaveSamples() {
        byte[] data = getDecodedData();
        if (isInterleaved()) {
            throw new IllegalStateException("TIFF tile is already interleaved: " + this);
        }
        data = map.toInterleavedSamples(data, samplesPerPixel, estimatedNumberOfPixels());
        // - estimatedNumberOfPixels() can return an unexact value only for 1 bit/sample,
        // but in this case samplesPerPixel==1 (see assertion in the constructor), and the 3rd argument is not used
        setInterleaved(true);
        setDecodedData(data);
        return this;
    }

    public TiffTile separateSamples() {
        byte[] data = getDecodedData();
        if (!isInterleaved()) {
            throw new IllegalStateException("TIFF tile is already separated: " + this);
        }
        data = map.toSeparatedSamples(data, samplesPerPixel, estimatedNumberOfPixels());
        // - estimatedNumberOfPixels() can return an unexact value only for 1 bit/sample,
        // but in this case samplesPerPixel==1 (see assertion in the constructor), and the 3rd argument is not used
        setInterleaved(false);
        setDecodedData(data);
        return this;
    }

    @Override
    public String toString() {
        final byte[] data = this.data;
        // - unlike the field, this variable cannot be changed from a parallel thread
        return "TIFF " +
                (frozen ? "(FROZEN) " : isEmpty() ? "(empty) " : "") +
                (encoded ? "encoded" : "non-encoded") +
                (interleaved ? " interleaved" : " separated") +
                " tile" +
                ", " + elementType().getSimpleName() + "[" + sizeX + "x" + sizeY + "x" + samplesPerPixel + "]" +
                (data == null ? "" : " (" + data.length + " bytes)") +
                (isCompleted() ? ", completed" : isCompletelyUnset() ? ", completely unset" : ", partial") +
                ", " + normalizedBitDepth + " bits/sample" +
                ", index " + index +
                (isStoredInFile() ?
                        " at file region " + storedInFileDataOffset + ".." + storedInFileDataOffset +
                        "+" + (storedInFileDataLength - 1) +
                        "/" + (storedInFileDataCapacity - 1) :
                        ", no file position") +
                (isDuplicate() ? ", duplicate of " + linearIndexOfOriginalIfDuplicate : "");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TiffTile tiffTile = (TiffTile) o;
        return sizeX == tiffTile.sizeX && sizeY == tiffTile.sizeY &&
                interleaved == tiffTile.interleaved && encoded == tiffTile.encoded &&
                samplesPerPixel == tiffTile.samplesPerPixel && normalizedBitDepth == tiffTile.normalizedBitDepth &&
                storedInFileDataOffset == tiffTile.storedInFileDataOffset &&
                storedInFileDataLength == tiffTile.storedInFileDataLength &&
                storedInFileDataCapacity == tiffTile.storedInFileDataCapacity &&
                Objects.equals(index, tiffTile.index) &&
                Arrays.equals(data, tiffTile.data);
        // Note: doesn't check "map" to avoid infinite recursion!
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(index, sizeX, sizeY,
                interleaved, encoded, storedInFileDataOffset, storedInFileDataLength, storedInFileDataCapacity);
        result = 31 * result + Arrays.hashCode(data);
        return result;
        // Note: doesn't check this.map to avoid infinite recursion!
    }

    private TiffTile setData(byte[] data, boolean encoded, boolean checkAligned, boolean unfreeze) {
        Objects.requireNonNull(data, "Null " + (encoded ? "encoded" : "decoded") + " data");
        if (!unfreeze) {
            checkFrozen();
        }
        final long numberOfBits = 8L * (long) data.length;
        final long numberOfPixels = numberOfBits / normalizedBitsPerPixel;
        if (normalizedBitsPerPixel > 1) {
            // - if it is 1, data cannot be unaligned (X % 1 == 0 always)
            if ((normalizedBitsPerPixel & 7) != 0) {
                throw new AssertionError("Unsupported bits per pixel " + normalizedBitsPerPixel);
                // - for example, 1-bit RGB is not supported:
                // we cannot calculate the number of pixels to separate or interleave them
            }
            final int bytesPerPixel = normalizedBitsPerPixel >>> 3;
            assert numberOfPixels == data.length / bytesPerPixel;
            if (checkAligned && !encoded && numberOfPixels * bytesPerPixel != data.length) {
                throw new IllegalArgumentException("Invalid length of decoded data " + data.length +
                        " bytes, or " + numberOfBits + " bits: not a multiple of the bits-per-pixel " +
                        normalizedBitsPerPixel + " = " + samplesPerPixel + " * " + normalizedBitDepth +
                        " (channels per pixel * bits per channel sample), " +
                        "as if the last pixel is stored \"partially\"");
            }
        } else {
            assert normalizedBitsPerPixel == 1 : "zero or negative bitsPerPixel = " + normalizedBitsPerPixel;
            assert numberOfPixels == numberOfBits : "numberOfBits / 1 != numberOfBits = " + numberOfBits;
            // - in the case of 1 bit/pixel, we have no information here about EXACT number of pixels:
            // 10 bytes can contain from 73 to 80 bits
            final long expectedNumberOfBytes = (numberOfPixels + 7) >>> 3;
            assert expectedNumberOfBytes == data.length;
            // - in other words, the condition, required by checkAligned argument, is always fulfilled
        }

        if (numberOfPixels > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Cannot store " + numberOfPixels +
                    " pixels: very large TIFF tiles >= 2^31 pixels are not supported");
        }
        this.encoded = encoded;
        this.data = data;
        // this.storedInFileDataLength = data.length;
        // - this is a deprecated solution; now storedInFileDataLength has an independent sense
        // and used to detect whether new data can be overwritten at the same position in the file
        if (unfreeze) {
            this.frozen = false;
        }
        return this;
    }

    private void initializeEmptyArea() {
        if (unsetArea == null) {
            unsetArea = new LinkedList<>();
            unsetArea.add(actualRectangle());
        }
    }

    private void checkEmpty() {
        if (data == null) {
            checkFrozen();
            throw new IllegalStateException("TIFF tile is still not filled by any data: " + this);
        }
    }

    private void checkSeparated(String action) {
        if (isInterleaved()) {
            throw new IllegalStateException("Cannot " + action + " because this tile is interleaved");
        }
    }

    private void checkRarePrecision(String action) {
        if (isRarePrecision()) {
            throw new IllegalStateException("Cannot " + action + " in the tile because this image has " +
                    "a rare precision (" +
                    Arrays.toString(map.bitsPerSample()) + " bits/sample for " +
                    map.sampleType().prettyName() + " values)");
        }
    }

    private void checkFrozen() {
        if (frozen) {
            assert isEmpty() : "frozen tile must be empty";
            throw new IllegalStateException("TIFF tile is frozen, access to its data is prohibited: " + this);
        }
    }

    private void checkOutsideMap() {
        if (index.fromX() >= map.dimX() || index.fromY() >= map.dimY()) {
            throw new IllegalStateException("Tile is fully outside the map dimensions " +
                    map.dimX() + "x" + map.dimY() + ": " + this);
        }
    }

    private void checkStoredFileOffset() {
        if (storedInFileDataOffset < 0) {
            throw new IllegalStateException("File offset of the TIFF tile is not set yet: " + this);
        }
    }
}
