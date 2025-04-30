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

import net.algart.arrays.Matrix;
import net.algart.arrays.PackedBitArraysPer8;
import net.algart.arrays.TooLargeArrayException;
import net.algart.arrays.UpdatablePArray;
import net.algart.math.IRectangularArea;
import net.algart.matrices.tiff.*;
import net.algart.matrices.tiff.data.TiffUnusualPrecisions;

import java.lang.reflect.Array;
import java.nio.ByteOrder;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * TIFF tile: container for samples (encoded or decoded) with given {@link TiffTileIndex index}.
 */
public final class TiffTile {
    private static final boolean DISABLE_CROPPING = false;
    // - You may set this to true for creating little invalid stripped TIFF, where the last strip is not cropped.
    // Normal value is false.

    private final TiffMap map;
    private final int samplesPerPixel;
    private final int bitsPerSample;
    private final int bitsPerPixel;
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
    private int estimatedNumberOfPixels = 0;
    private Queue<IRectangularArea> unsetArea = null;
    // - null value marks that all is empty;
    // it helps to defer actual subtracting until the moment when we know the correct tile sizes
    private boolean disposed = false;

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
        this.bitsPerSample = map.alignedBitsPerSample();
        this.bitsPerPixel = map.tileAlignedBitsPerPixel();
        assert this.bitsPerPixel == samplesPerPixel * bitsPerSample;
        assert index.ifd() == map.ifd() : "index retrieved ifd from its tile map!";
        setSizes(map.tileSizeX(), map.tileSizeY());
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

    public boolean isPlanarSeparated() {
        return map.isPlanarSeparated();
    }

    public int samplesPerPixel() {
        return samplesPerPixel;
    }

    public boolean isBinary() {
        return map.isBinary();
    }

    public boolean isWholeBytes() {
        return map.isWholeBytes();
    }

    /**
     * Returns the number of bits per each sample of this tile.
     * Always equal to {@link #map()}.{@link TiffMap#alignedBitsPerSample() bitsPerSample()}.
     * Note that this number is always the same for all channels and is always divided by 8,
     * excepting the only case 1-channel 1-bit pixels.
     *
     * <p><i>Warning:</i> this number can be smaller than the result of the same method of {@link #sampleType()}
     * object! This is possible for unusual precisions, like 24-bit integer or 16/24-bit float samples.
     * See {@link TiffReader#setAutoUnpackUnusualPrecisions(boolean)} and
     * {@link TiffReader#completeDecoding(TiffTile)} methods.</p>
     *
     * <p>Note that you can see unpacked data only in two variants:</p>
     * <ol>
     *     <li>fully unpacked, when the number of bits per sample corresponds to one of variants of
     *     {@link TiffSampleType};</li>
     *     <li>unpacked to <i>unusual precision</i>: 3-byte samples (17..24 bits/sample),
     *     16- or 24-bit floating-point formats.</li>
     * </ol>
     * <p>Inside this class, you are always dealing with the variant #2 (excepting call of
     * {@link #getUnpackedData()} method). The {@link TiffReader} class
     * usually returns data in the option #1, unless you disable this by
     * {@link TiffReader#setAutoUnpackUnusualPrecisions(boolean)} method.
     * The {@link TiffWriter} class always takes the data in the variant #1.</p>
     *
     * @return number of bits per each sample (1 channel for 1 pixel).
     * @see TiffMap#bitsPerUnpackedSample()
     * @see TiffTile#bitsPerSample()
     */
    public int bitsPerSample() {
        return bitsPerSample;
    }

    public OptionalInt bytesPerSample() {
        return map.bytesPerSample();
    }

    public int bitsPerPixel() {
        return bitsPerPixel;
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

    public ByteOrder byteOrder() {
        return map.byteOrder();
    }

    public int compressionCode() {
        return map.compressionCode();
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
        if (alignedSizeX * (long) sizeY * (long) bitsPerPixel > Integer.MAX_VALUE) {
            throw new TooLargeArrayException("Very large TIFF tile " + sizeX + "x" + sizeY +
                    ", " + samplesPerPixel + " channels per " + bitsPerSample +
                    " bits >= 2^31 bits (256 MB) is not supported" + alignedMsg.get());
        }
        final int sizeInPixels = sizeX * sizeY;
        assert alignedSizeX * (long) bitsPerPixel <= Integer.MAX_VALUE : "impossible because " + sizeY + " > 0";
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeInPixels = sizeInPixels;
        this.sizeInBits = sizeInPixels * bitsPerPixel;
        this.sizeInBytes = (sizeInBits + 7) >>> 3;
        this.lineSizeInBytesInsideTIFF = ((sizeX * bitsPerPixel + 7) >>> 3);
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
        // - note: there is no sense to check samplesPerPixel, it is not a "size", but property of pixel format
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
     * Returns <code>({@link #getSizeInPixels()} * {@link #bitsPerPixel()} + 7) / 8</code>.
     *
     * @return the length of the minimal <code>byte[]</code> data array, enough to store all tile pixels.
     */
    public int getSizeInBytes() {
        return sizeInBytes;
    }

    /**
     * Returns <code>({@link #getSizeX()} * {@link #bitsPerPixel()} + 7) / 8</code>:
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
     * Returns {@link #getSizeInPixels()} * {@link #bitsPerPixel()}.
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
        return IRectangularArea.valueOf(minX, minY, maxX, maxY);
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
        if (DISABLE_CROPPING || (strippedOnly && map.getTilingMode().isTileGrid())) {
            return this;
        } else {
            return setSizes(Math.min(sizeX, map.dimX() - index.fromX()), Math.min(sizeY, map.dimY() - index.fromY()));
        }
    }

    /**
     * Returns the current unset area in this tile.
     * Note that initially the unset area consists from a single rectangle equal to {@link #actualRectangle()}.
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

    public TiffTile markNewAreaAsSet(IRectangularArea... newlyFilledArea) {
        Objects.requireNonNull(newlyFilledArea, "Null newlyFilledArea");
        initializeEmptyArea();
        IRectangularArea.subtractCollection(unsetArea, newlyFilledArea);
        return this;
    }

    public TiffTile markNewRectangleAsSet(int fromXInTile, int fromYInTile, int sizeXInTile, int sizeYInTile) {
        if (sizeXInTile > 0 && sizeYInTile > 0) {
            markNewAreaAsSet(rectangleInTile(fromXInTile, fromYInTile, sizeXInTile, sizeYInTile));
        }
        return this;
    }

    public TiffTile cropUnsetAreaToMap() {
        checkOutsideMap();
        if (!isFullyInsideMap()) {
            // - little optimization
            markNewAreaAsSet(
                    IRectangularArea.valueOf(0, map.dimY(), Integer.MAX_VALUE, Integer.MAX_VALUE),
                    IRectangularArea.valueOf(map.dimX(), 0, Integer.MAX_VALUE, Integer.MAX_VALUE));
            // Integer.MAX_VALUE is enough: we work with 32-bit coordinates
            // Note that Long.MAX_VALUE is not permitted here, maximal allowed value is Long.MAX_VALUE-1
        }
        return this;
    }

    public boolean isCompleted() {
        return !hasUnsetArea();
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
        return setData(data, true, false);
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
     * Returns the decoded data. Every pixel in the unpacked data consists of {@link #samplesPerPixel()}
     * <i>samples</i>, and every sample is represented with either 1 bit or 1, 2, 3 or 4 whole bytes.
     * If the samples in the TIFF file are K-bit integers where <code>K%8&nbsp;&ne;&nbsp;0</code>,
     * they are automatically unpacked into <code>&#8968;K/8&#8969;*8</code> bit integers while decoding.
     *
     * <p>In addition to the standard precisions provided by {@link TiffSampleType}, samples can be represented
     * in the following unusual precisions:</p>
     *
     * <ul>
     *     <li>16-bit floating points values,</li>
     *     <li>24-bit floating points values,</li>
     *     <li>24-bit integer values (for a case of K-bit samples, 16&le;K&lt;24).</li>
     * </ul>
     *
     * @return unpacked data.
     * @throws IllegalStateException if the tile is {@link #isEmpty() empty} or {@link #isEncoded() encoded}.
     * @see #getUnpackedData()
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

    /**
     * Sets the decoded data.
     *
     * <p>Note that we have no a separate method "setUnpackedSamples":
     * the unpacked samples can be set via this method.
     * We do not support writing non-standard precision.</p>
     *
     * @param data decoded (unpacked) data.
     * @return a reference to this object.
     */
    public TiffTile setDecodedData(byte[] data) {
        return setData(data, false, true);
    }

    public TiffTile setPartiallyDecodedData(byte[] data) {
        return setData(data, false, false);
    }

    public TiffTile fillWhenEmpty() {
        return fillWhenEmpty(null);
    }

    public TiffTile fillWhenEmpty(Consumer<TiffTile> initializer) {
        checkDisposed();
        if (isEmpty()) {
            setDecodedData(new byte[sizeInBytes]);
            if (initializer != null) {
                initializer.accept(this);
            }
        }
        return this;
    }

    /**
     * Gets the decoded data with unpacking non-usual precisions: 16/24-bit floating points data
     * and any 3-byte/sample integer data. The same operations are performed by
     * {@link TiffReader} automatically
     * if the {@link TiffReader#setAutoUnpackUnusualPrecisions(boolean)} mode is set.
     *
     * <p>This method is rarely necessary: {@link #getDecodedData()} is enough for most needs.
     *
     * <p>The argument <code>autoScaleWhenIncreasingBitDepth</code> specifies how to unpack 3-byte integer data.
     * Usually it should be equal to {@link TiffReader#setAutoScaleWhenIncreasingBitDepth(boolean)
     * the corresponding flag} of {@link TiffReader} class used for reading this tile.
     * The typical value is <code>true</code>.
     *
     * @param autoScaleWhenIncreasingBitDepth the last argument passed to of
     *                                        {@link TiffUnusualPrecisions#unpackUnusualPrecisions} method for
     *                                        unpacking data.
     * @return unpacked data.
     * @see TiffUnusualPrecisions#unpackUnusualPrecisions(byte[], TiffIFD, int, long, boolean)
     * @see #bitsPerSample()
     * @see TiffMap#bitsPerUnpackedSample()
     */
    public byte[] getUnpackedSamples(boolean autoScaleWhenIncreasingBitDepth) {
        byte[] samples = getDecodedData();
        try {
            samples = TiffUnusualPrecisions.unpackUnusualPrecisions(
                    samples, ifd(), samplesPerPixel, sizeInPixels, autoScaleWhenIncreasingBitDepth);
        } catch (TiffException e) {
            throw new IllegalStateException("Illegal IFD inside the tile map", e);
        }
        return samples;
    }

    public Object getUnpackedJavaArray(boolean autoScaleWhenIncreasingBitDepth) {
        final byte[] samples = getUnpackedSamples(autoScaleWhenIncreasingBitDepth);
        return sampleType().javaArray(samples, byteOrder());
    }

    public TiffTile setUnpackedJavaArray(Object samplesArray) {
        Objects.requireNonNull(samplesArray, "Null samplesArray");
        final Class<?> elementType = samplesArray.getClass().getComponentType();
        if (elementType == null) {
            throw new IllegalArgumentException("The specified samplesArray is not actual an array: " +
                    "it is " + samplesArray.getClass());
        }
        if (elementType != elementType()) {
            throw new IllegalArgumentException("Invalid element type of samples array: " + elementType +
                    ", but the specified TIFF tile stores " + elementType() + " elements");
        }
        final int length = Array.getLength(samplesArray);
        final byte[] samples = TiffSampleType.bytes(samplesArray, length, byteOrder());
        return setDecodedData(samples);
    }

    public Matrix<UpdatablePArray> getUnpackedMatrix() {
        return getUnpackedMatrix(true);
    }

    public Matrix<UpdatablePArray> getUnpackedMatrix(boolean autoScaleWhenIncreasingBitDepth) {
        return TiffSampleType.asMatrix(
                getUnpackedJavaArray(autoScaleWhenIncreasingBitDepth),
                sizeX, sizeY, samplesPerPixel, interleaved);
    }

    public TiffTile copyData(TiffTile source, boolean cloneData) {
        Objects.requireNonNull(source, "Null source tile");
        if (source.isEmpty()) {
            free();
        } else {
            final byte[] data = cloneData ? source.data.clone() : source.data;
            setData(data, source.isEncoded(), false);
        }
        return this;
    }

    private boolean isByteOrderCompatible(TiffTile source) {
        return map.isByteOrderCompatible(source.byteOrder());
    }

    public TiffTile copyUnpackedSamples(TiffTile source, boolean autoScaleWhenIncreasingBitDepth) {
        Objects.requireNonNull(source, "Null source tile");
        if (sampleType() != source.sampleType()) {
            throw new IllegalArgumentException("The specified source tile has incompatible " +
                    "sample type (" + source.elementType().getSimpleName() + ") than this tile: " + this);
        }
        if (samplesPerPixel != source.samplesPerPixel) {
            throw new IllegalArgumentException("The specified source tile has incompatible " +
                    "samples per pixel (" + source.samplesPerPixel + ") than this tile: " + this);
        }
        if (source.isEmpty()) {
            free();
            return this;
        }
        source.checkDecodedData();
        if (isByteOrderCompatible(source)) {
            final byte[] decodedData = source.getUnpackedSamples(autoScaleWhenIncreasingBitDepth);
            setDecodedData(decodedData);
        } else {
            final Object javaArray = source.getUnpackedJavaArray(autoScaleWhenIncreasingBitDepth);
            setUnpackedJavaArray(javaArray);
            // - theoretically, we could use a quicker algorithm for reordering bytes
            // via ByteBuffer without copying into a separate Java array,
            // but usually there is no sense to optimize this:
            // the actual data decompression/compression by the codec is much slower
        }
        return this;
    }

    public boolean isEmpty() {
        return data == null;
    }

    public boolean isDisposed() {
        return disposed;
    }

    public void free() {
        this.data = null;
        this.interleaved = false;
        // - before possible setting new decoded data, we should restore default status interleaved = false
        this.encoded = false;
        // - method checkReadyForNewDecodedData() requires that the tile should not be declared as encoded
        // Note: we should not clear information about stored data file range, because
        // it will be used even after flushing data to disk (with freeing this tile)
    }

    /**
     * Calls {@link #free()} and marks this tile as <i>disposed</i>.
     *
     * <p>After {@link #free()} method, the tile becomes {@link #isEmpty() empty},
     * but can be filled with some data again, for example, using {@link #setDecodedData(byte[])}
     * or {@link #fillWhenEmpty()}.
     * Unlike this, after this method,
     * the tile cannot be modified at all: any attempt to get or set data
     * ({@link #getDecodedData()}, {@link #getEncodedData()}, {@link  #setDecodedData(byte[])},
     * {@link #setEncodedData(byte[])}, {@link #fillWhenEmpty()} etc.) will result in an exception.</p>
     *
     * <p>{@link TiffWriter} class checks {@link #isDisposed()} method and does not attempt to update
     * <i>disposed</i> tiles.</p>
     *
     * <p>This method is automatically called by {@link TiffWriter#writeTile(TiffTile, boolean)} method
     * when its second argument is <code>true</code>:
     * usually there is no any sense to work with a tile after once it has been written into the TIFF file.</p>
     *
     * <p>Note: there is no way to clear the <i>disposed</i> status in this object.</p>
     */
    public void dispose() {
        free();
        this.disposed = true;
    }

    public long getStoredInFileDataOffset() {
        checkStoredFileOffset();
        return storedInFileDataOffset;
    }

    /**
     * Return the length of the last non-null encoded data array, stored in the TIFF file,
     * or 0 after creating this object.
     *
     * <p>Immediately after reading tile from the file, as well as
     * immediately before/after writing it into file, this method returns the number of encoded bytes,
     * which are actually stored in the file for this tile.
     *
     * <p>Note: {@link #free()} method does not change this value! So, you can know the stored data size
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

    public TiffTile setStoredInFileDataRange
            (long storedInFileDataOffset,
             int storedInFileDataLength,
             boolean resetCapacity) {
        if (storedInFileDataOffset < 0) {
            throw new IllegalArgumentException("Negative storedInFileDataOffset = " + storedInFileDataOffset);
        }
        if (storedInFileDataLength < 0) {
            throw new IllegalArgumentException("Negative storedInFileDataLength = " + storedInFileDataLength);
        }
        this.storedInFileDataOffset = storedInFileDataOffset;
        this.storedInFileDataLength = storedInFileDataLength;
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

    public TiffTile setStoredInFileDataCapacity(int storedInFileDataCapacity) {
        if (storedInFileDataCapacity < this.storedInFileDataLength) {
            throw new IllegalArgumentException("Too small storedInFileDataCapacity = " + storedInFileDataCapacity +
                    " < storedInFileDataLength = " + this.storedInFileDataLength);
        }
        this.storedInFileDataCapacity = storedInFileDataCapacity;
        return this;
    }

    public TiffTile copyStoredInFileDataRange(TiffTile other) {
        Objects.requireNonNull(other, "Null other tile");
        this.storedInFileDataOffset = other.storedInFileDataOffset;
        this.storedInFileDataLength = other.storedInFileDataLength;
        this.storedInFileDataCapacity = other.storedInFileDataCapacity;
        return this;
    }

    /**
     * Returns the estimated number of pixels, that can be stored in the {@link #getDecodedData() data array}
     * in this tile in the decoded form, or 0 after creating this object.
     *
     * <p>Note: that this method throws <code>IllegalStateException</code> if the data are
     * {@link #isEncoded() encoded}, for example, immediately after reading tile from the file.
     * If the tile is {@link #isEmpty() empty} (no data),
     * the exception is not thrown, though usually there is no sense to call this method in this situation.</p>
     *
     * <p>If the data are not {@link #isEncoded() encoded}, the following equality is <i>usually</i> true:</p>
     *
     * <pre>{@link #getDecodedDataLength()} == ({@link #getEstimatedNumberOfPixels()} * {@link
     * #bitsPerPixel()} + 7) / 8</pre>
     *
     * <p>The only possible exception is when you set the data with help of
     * {@link #setPartiallyDecodedData(byte[])} (when data are almost decoded, but, maybe, some additional
     * unpacking is necessary). This condition is always checked inside {@link #setDecodedData(byte[])} method.
     * You may also check this directly by {@link #checkDataLengthAlignment()} method.</p>
     *
     * <p><b>Warning:</b> the estimated number of pixels, returned by this method, may <b>differ</b> from the tile
     * size {@link #getSizeX()} * {@link #getSizeY()}! Usually it occurs after decoding encoded tile, when the
     * decoding method returns only a sequence of pixels and does not return information about the size.
     * In this situation, the external code sets the tile sizes from a-priory information, but the decoded tile
     * may be actually less; for example, it takes place for the last strip in non-tiled TIFF format.
     * You can check, does the actual number of stored pixels equal to tile size, via
     * {@link #checkStoredNumberOfPixels()} method.
     *
     * @return the number of pixels in the last non-null data array, which was stored in this object.
     */
    @SuppressWarnings("JavadocDeclaration")
    public int getEstimatedNumberOfPixels() {
        // - This method is private, because it does not return exact number of pixels for 1-bit channels
        // and should be used carefully.
        // Maybe in future we will support better field "numberOfPixels", always correct also for 1-bit channels,
        // then this method will become public.
        if (isEncoded()) {
            throw new IllegalStateException("TIFF tile data are not decoded, number of pixels is unknown: " + this);
        }
        return estimatedNumberOfPixels;
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
        checkEmpty();
        final int estimatedNumberOfPixels = getEstimatedNumberOfPixels();
        // - IllegalStateException if encoded
        assert !encoded;
        final int expectedNumberOfBytes = (estimatedNumberOfPixels * bitsPerPixel + 7) >>> 3;
        if (expectedNumberOfBytes != data.length) {
            assert bitsPerPixel != 1 : "unaligned estimatedNumberOfPixels cannot appear for 1 bit/pixel";
            // - in the current version it means that we have whole bytes: bitsPerPixel = 8*K;
            // see assertions in setData for a case of bitsPerPixel == 1
            throw new IllegalStateException("Unaligned length of decoded data " + data.length +
                    ": it is not equal to ceil(number of pixels * bits per pixel / 8) = ceil(" +
                    estimatedNumberOfPixels + " * " + bitsPerPixel + " / 8) = " + expectedNumberOfBytes +
                    ", as if the last pixel is stored \"partially\"");
        }
    }

    /**
     * Checks whether the length of the data array length matches the declared tile sizes {@link #getSizeInBytes()}.
     * If it is not so, throws <code>IllegalStateException</code>.
     *
     * <p>This method must not be called for {@link #isEncoded() encoded} tile.
     *
     * <p>This method is called before encoding and writing any tile to the TIFF file.
     */
    public TiffTile checkStoredNumberOfPixels() {
        checkEmpty();
        final int estimatedNumberOfPixels = getEstimatedNumberOfPixels();
        // - necessary to throw IllegalStateException if encoded
        assert !encoded;
        // Deprecated check (storedInFileDataLength was set =data.length in old versions)
        // if (data.length != storedInFileDataLength) {
        //     throw new IllegalStateException("Stored data length field " + storedInFileDataLength +
        //             " is set to the value, different from the actual data length " + data.length);
        // }
        if (data.length != sizeInBytes) {
            throw new IllegalStateException("Number of stored pixels " + estimatedNumberOfPixels +
                    " does not match tile sizes " + sizeX + "x" + sizeY + " = " + sizeInPixels);
        }
        final int dataLength = (estimatedNumberOfPixels * bitsPerPixel + 7) >>> 3;
        if (dataLength != data.length) {
            // - this check must be AFTER possible throwing IllegalStateException:
            // in another case, we will throw AssertionError instead of correct IllegalStateException
            throw new AssertionError("Invalid estimatedNumberOfPixels " + estimatedNumberOfPixels +
                    ": does not match data.length = " + data.length);
        }
        // - in other words, checkDataLengthAlignment() must be unnecessary:
        // if bitsPerPixel = 1, unaligned data is impossible;
        // if bitsPerPixel = 8*i, dataLength = sizeInBytes is divided by bytesPerPixel
        return this;
    }

    public TiffTile adjustNumberOfPixels(boolean allowDecreasing) {
        return changeNumberOfPixels(sizeInPixels, allowDecreasing);
    }

    public TiffTile changeNumberOfPixels(long newNumberOfPixels, boolean allowDecreasing) {
        if (newNumberOfPixels < 0) {
            throw new IllegalArgumentException("Negative new number of pixels = " + newNumberOfPixels);
        }
        final long newNumberOfBits = newNumberOfPixels * (long) bitsPerPixel;
        if (newNumberOfPixels > Integer.MAX_VALUE || newNumberOfBits > Integer.MAX_VALUE) {
            // - first check is necessary for a case of overflow in newNumberOfBits
            throw new IllegalArgumentException("Too large requested number of pixels in tile: " + newNumberOfPixels +
                    " pixels * " + samplesPerPixel + " samples/pixel * " + bitsPerSample + " bits/sample >= " +
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
            if ((bitsPerPixel & 7) != 0) {
                throw new AssertionError("Unsupported bits per pixel " + bitsPerPixel + " for " +
                        samplesPerPixel + " channel (more than one)");
                // - for example, 1-bit RGB is not supported:
                // we cannot calculate the number of pixels to separate or interleave them
            }
            newData = new byte[newLength];
            // - zero-filled by Java
            final long size = (long) getEstimatedNumberOfPixels() * bitsPerSample;
            // bitsPerPixels is multiply of 8, so, estimatedNumberOfPixels is the actual number of stored pixels
            final long newSize = newNumberOfPixels * bitsPerSample;
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
        data = map.toInterleavedSamples(data, samplesPerPixel, getEstimatedNumberOfPixels());
        // - getEstimatedNumberOfPixels can return an invalid value only for 1 channel,
        // when the 3rd argument is not used
        setInterleaved(true);
        setDecodedData(data);
        return this;
    }

    public TiffTile separateSamples() {
        byte[] data = getDecodedData();
        if (!isInterleaved()) {
            throw new IllegalStateException("TIFF tile is already separated: " + this);
        }
        data = map.toSeparatedSamples(data, samplesPerPixel, getEstimatedNumberOfPixels());
        // - getEstimatedNumberOfPixels can return invalid value only for 1 channel, when this argument is not used
        setInterleaved(false);
        setDecodedData(data);
        return this;
    }

    @Override
    public String toString() {
        final byte[] data = this.data;
        // - unlike the field, this variable cannot be changed from a parallel thread
        return "TIFF " +
                (disposed ? "(DISPOSED) " : isEmpty() ? "(empty) " : "") +
                (encoded ? "encoded" : "non-encoded") +
                (interleaved ? " interleaved" : " separated") +
                " tile" +
                ", " + elementType().getSimpleName() + "[" + sizeX + "x" + sizeY + "x" + samplesPerPixel + "]" +
                (data == null ?
                        "" :
                        " (" + data.length + " bytes)" + (isCompleted() ? ", completed" : ", partial")) +
                ", " + bitsPerSample + " bits/sample" +
                ", index " + index +
                (isStoredInFile() ?
                        " at file region " + storedInFileDataOffset + ".." + storedInFileDataOffset +
                                "+" + (storedInFileDataLength - 1) +
                                "/" + (storedInFileDataCapacity - 1) :
                        ", no file position");
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
                samplesPerPixel == tiffTile.samplesPerPixel && bitsPerSample == tiffTile.bitsPerSample &&
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

    private TiffTile setData(byte[] data, boolean encoded, boolean checkAligned) {
        Objects.requireNonNull(data, "Null " + (encoded ? "encoded" : "decoded") + " data");
        checkDisposed();
        final long numberOfBits = 8L * (long) data.length;
        final long numberOfPixels = numberOfBits / bitsPerPixel;
        if (bitsPerPixel > 1) {
            // - if it is 1, data cannot be unaligned (X % 1 == 0 always)
            if ((bitsPerPixel & 7) != 0) {
                throw new AssertionError("Unsupported bits per pixel " + bitsPerPixel);
                // - for example, 1-bit RGB is not supported:
                // we cannot calculate the number of pixels to separate or interleave them
            }
            final int bytesPerPixel = bitsPerPixel >>> 3;
            assert numberOfPixels == data.length / bytesPerPixel;
            if (checkAligned && !encoded && numberOfPixels * bytesPerPixel != data.length) {
                throw new IllegalArgumentException("Invalid length of decoded data " + data.length +
                        " bytes, or " + numberOfBits + " bits: not a multiple of the bits-per-pixel " +
                        bitsPerPixel + " = " + samplesPerPixel + " * " + bitsPerSample +
                        " (channels per pixel * bits per channel sample), " +
                        "as if the last pixel is stored \"partially\"");
            }
        } else {
            assert bitsPerPixel == 1 : "zero or negative bitsPerPixel = " + bitsPerPixel;
            final long expectedNumberOfBytes = (numberOfPixels + 7) >>> 3;
            assert expectedNumberOfBytes == data.length;
            // - in other words, the condition, required by checkAligned argument, is always fulfilled
        }

        if (numberOfPixels > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Cannot store " + numberOfPixels +
                    " pixels: very large TIFF tiles >= 2^31 pixels are not supported");
        }
        this.data = data;
        // this.storedInFileDataLength = data.length;
        // - this is a deprecated solution; now storedInFileDataLength has an independent sense
        // and used to detect, whether new data can be overwritten at the same position in the file
        this.estimatedNumberOfPixels = (int) numberOfPixels;
        this.encoded = encoded;
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
            checkDisposed();
            throw new IllegalStateException("TIFF tile is still not filled by any data: " + this);
        }
    }

    private void checkDisposed() {
        if (disposed) {
            assert isEmpty() : "disposed tile must be empty";
            throw new IllegalStateException("TIFF tile is disposed, access to its data is prohibited: " + this);
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
