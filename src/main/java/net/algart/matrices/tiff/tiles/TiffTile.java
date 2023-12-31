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

import net.algart.matrices.tiff.TiffException;
import net.algart.math.IRectangularArea;
import net.algart.matrices.tiff.*;

import java.util.*;
import java.util.function.Consumer;

/**
 * TIFF tile: container for samples (encoded or decoded) with given {@link TiffTileIndex index}.
 *
 * @author Denial Alievsky
 */
public final class TiffTile {
    private final TiffMap map;
    private final int samplesPerPixel;
    private final int bytesPerSample;
    private final int bytesPerPixel;
    private final boolean littleEndian;
    private final TiffTileIndex index;
    private int sizeX;
    private int sizeY;
    private int sizeInPixels;
    private int sizeInBytes;
    private boolean interleaved = false;
    private boolean encoded = false;
    private byte[] data = null;
    private long storedDataFileOffset = -1;
    private int storedDataLength = 0;
    private int storedNumberOfPixels = 0;
    private Queue<IRectangularArea> unsetArea = null;
    // - null value marks that all is empty;
    // it helps to defer actual subtracting until the moment when we know correct tile sizes

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
        this.samplesPerPixel = map.tileSamplesPerPixel();
        this.bytesPerSample = map.bytesPerSample();
        this.bytesPerPixel = samplesPerPixel * bytesPerSample;
        this.littleEndian = map.ifd().isLittleEndian();
        assert bytesPerPixel <= TiffMap.MAX_TOTAL_BYTES_PER_PIXEL :
                samplesPerPixel + "*" + bytesPerPixel + " were not checked in TiffMap!";
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

    public int bytesPerSample() {
        return bytesPerSample;
    }

    public int bytesPerPixel() {
        return bytesPerPixel;
    }

    public boolean isLittleEndian() {
        return littleEndian;
    }

    public TiffSampleType sampleType() {
        return map.sampleType();
    }

    public boolean isFloatingPoint() {
        return map.isFloatingPoint();
    }

    public Class<?> elementType() {
        return map.elementType();
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
     * <p>There is a guarantee that the total {@link #getSizeInBytes() number of bytes},
     * required to store <tt>sizeX*sizeY</tt> pixels, will be <tt>&le;Integer.MAX_VALUE.</tt>
     *
     * @param sizeX the tile width; must be positive.
     * @param sizeY the tile height; must be positive.
     * @return a reference to this object.
     */
    public TiffTile setSizes(int sizeX, int sizeY) {
        if (sizeX <= 0) {
            throw new IllegalArgumentException("Zero or negative tile x-size: " + sizeX);
        }
        if (sizeY <= 0) {
            throw new IllegalArgumentException("Zero or negative tile y-size: " + sizeY);
        }
        // - zero sizes are disabled to provide correct IRectangularArea processing
        if ((long) sizeX * (long) sizeY > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Very large TIFF tile " + sizeX + "x" + sizeY +
                    " >= 2^31 pixels is not supported");
        }
        final int sizeInPixels = sizeX * sizeY;
        if ((long) sizeInPixels * (long) bytesPerPixel > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Very large TIFF tile " + sizeX + "x" + sizeY +
                    ", " + samplesPerPixel + " channels per " + bytesPerSample +
                    " bytes >= 2^31 bytes is not supported");
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeInPixels = sizeInPixels;
        this.sizeInBytes = sizeInPixels * bytesPerPixel;
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

    public int getSizeInPixels() {
        return sizeInPixels;
    }

    public int getSizeInBytes() {
        return sizeInBytes;
    }

    public IRectangularArea rectangle() {
        return rectangleInTile(0, 0, sizeX, sizeY);
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
     * Reduces sizes of this tile so that it will completely lie inside map dimensions.
     *
     * <p>This operation can be useful for <i>stripped</i> TIFF image, especially while writing.
     * But you should not call this for <i>tiled</i> image (when {@link TiffMap#isTiled()} returns <tt>true</tt>).
     * For tiled image, TIFF file usually contains full-size encoded tiles even on image boundary;
     * they should be cropped after decoding by external means. You can disable attempt to reduce
     * tile in tiled image by passing <tt>nonTiledOnly=true</tt>.
     *
     * @param nonTiledOnly if <tt>true</tt>, this function will not do anything when the map
     *                     is {@link TiffMap#isTiled() tiled}. While using for reading/writing TIFF files,
     *                     this argument usually should be <tt>true</tt>.
     * @return a reference to this object.
     * @throws IllegalStateException if this tile is completely outside map dimensions.
     */
    public TiffTile cropToMap(boolean nonTiledOnly) {
        checkOutsideMap();
        if (nonTiledOnly && map.isTiled()) {
            return this;
        } else {
            return setSizes(Math.min(sizeX, map.dimX() - index.fromX()), Math.min(sizeY, map.dimY() - index.fromY()));
        }
    }

    public Collection<IRectangularArea> getUnsetArea() {
        return unsetArea == null ? List.of(rectangle()) : Collections.unmodifiableCollection(unsetArea);
    }

    public TiffTile unsetAll() {
        unsetArea = null;
        return this;
    }

    public TiffTile removeUnset() {
        unsetArea = new LinkedList<>();
        return this;
    }

    public TiffTile reduceUnset(IRectangularArea... newlyFilledArea) {
        Objects.requireNonNull(newlyFilledArea, "Null newlyFilledArea");
        initializeEmptyArea();
        IRectangularArea.subtractCollection(unsetArea, newlyFilledArea);
        return this;
    }

    public TiffTile reduceUnsetInTile(int fromXInTile, int fromYInTile, int sizeXInTile, int sizeYInTile) {
        if (sizeXInTile > 0 && sizeYInTile > 0) {
            reduceUnset(rectangleInTile(fromXInTile, fromYInTile, sizeXInTile, sizeYInTile));
        }
        return this;
    }

    public TiffTile cropUnsetToMap() {
        checkOutsideMap();
        if (!isFullyInsideMap()) {
            // - little optimization
            reduceUnset(
                    IRectangularArea.valueOf(0, map.dimY(), Integer.MAX_VALUE, Integer.MAX_VALUE),
                    IRectangularArea.valueOf(map.dimX(), 0, Integer.MAX_VALUE, Integer.MAX_VALUE));
            // Integer.MAX_VALUE is enough: we work with 32-bit coordinates
            // Note that Long.MAX_VALUE is not permitted here, maximal allowed value is Long.MAX_VALUE-1
        }
        return this;
    }

    public boolean isCompleted() {
        return !hasUnset();
    }

    public boolean hasUnset() {
        return unsetArea == null || !unsetArea.isEmpty();
    }

    /**
     * Returns <tt>true</tt>>, if the stored pixel samples (as supposed) are interleaved, like RGBRGB...,
     * or <tt>false</tt> if not (RRR...GGG...BBB...).
     * It doesn't matter in a case of monochrome images and in a case of {@link #isEncoded() encoded} data.
     * Default value is <tt>false</tt>.
     *
     * <p>By default, the data are considered to be <b>not</b> interleaved, in other words, {@link #isSeparated()
     * separated}. Methods, reading and decoding the tile from TIFF, always return separated tile.
     * Methods, encoding the file for writing to TIFF, may work both with interleaved tiles,
     * but it should be explicitly declared, like in
     * {@link TiffWriter#setAutoInterleaveSource(boolean)} method (with <tt>false</tt> argument).</p>
     *
     * <p>This is purely informational property, not affecting processing the stored data
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

    public boolean isEmpty() {
        return data == null;
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

    public byte[] getData() {
        checkEmpty();
        return data;
    }

    public byte[] getEncodedData() {
        checkEmpty();
        if (!isEncoded()) {
            throw new IllegalStateException("TIFF tile is not encoded: " + this);
        }
        return data;
    }

    public TiffTile setEncodedData(byte[] data) {
        return setData(data, true, false);
    }

    public TiffTile fillEmpty(Consumer<TiffTile> initializer) {
        if (isEmpty()) {
            setDecodedData(new byte[sizeInBytes]);
            if (initializer != null) {
                initializer.accept(this);
            }
        }
        return this;
    }

    public byte[] getDecodedData() {
        checkEmpty();
        if (isEncoded()) {
            throw new IllegalStateException("TIFF tile data are not decoded and cannot be retrieved: " + this);
        }
        return data;
    }

    /**
     * Gets the decoded data with unpacking non-usual precisions: 16/24-bit floating points data
     * and any 3-byte/sample integer data. The same operations are performed by
     * {@link TiffReader} automatically
     * if the {@link TiffReader#isAutoUnpackUnusualPrecisions()} mode is set.
     *
     * <p>This method is necessary rarely: {@link #getDecodedData()} is enough for most needs.
     *
     * @return unpacked data.
     */
    public byte[] unpackUnusualDecodedData() {
        byte[] samples = getDecodedData();
        try {
            samples = TiffTools.unpackUnusualPrecisions(
                    samples, ifd(), samplesPerPixel, sizeX * sizeY, true);
        } catch (TiffException e) {
            throw new IllegalStateException("Illegal IFD inside the tile map", e);
        }
        return samples;
    }

    public TiffTile setDecodedData(byte[] data) {
        return setData(data, false, true);
    }

    public TiffTile setPartiallyDecodedData(byte[] data) {
        return setData(data, false, false);
    }

    public TiffTile free() {
        this.data = null;
        this.interleaved = false;
        // - before possible setting new decoded data, we should restore default status interleaved = false
        this.encoded = false;
        // - method checkReadyForNewDecodedData() require that the tile should not be declared as encoded
        // Note: we should not clear information about stored data file range, because
        // it will be used even after flushing data to disk (with freeing this tile)
        return this;
    }

    /**
     * Return the length of the last non-null {@link #getData() data array}, stored in this tile,
     * or 0 after creating this object.
     *
     * <p>Immediately after reading tile from file, as well as
     * immediately before/after writing it into file, this method returns the number of encoded bytes,
     * which are actually stored in the file for this tile.
     *
     * <p>Note: {@link #free()} method does not change this value! So, you can know the stored data size
     * even after freeing data inside this object.
     *
     * @return the length of the last non-null data array, which was stored in this object.
     */
    public int getStoredDataLength() {
        return storedDataLength;
    }

    public boolean hasStoredDataFileOffset() {
        return storedDataFileOffset >= 0;
    }

    public long getStoredDataFileOffset() {
        checkStoredFilePosition();
        return storedDataFileOffset;
    }

    public TiffTile setStoredDataFileOffset(long storedDataFileOffset) {
        if (storedDataFileOffset < 0) {
            throw new IllegalArgumentException("Negative storedDataFileOffset = " + storedDataFileOffset);
        }
        this.storedDataFileOffset = storedDataFileOffset;
        return this;
    }

    public TiffTile removeStoredDataFileOffset() {
        storedDataFileOffset = -1;
        return this;
    }

    public TiffTile setStoredDataFileRange(long storedDataFileOffset, int storedDataLength) {
        if (storedDataFileOffset < 0) {
            throw new IllegalArgumentException("Negative storedDataFileOffset = " + storedDataFileOffset);
        }
        if (storedDataLength < 0) {
            throw new IllegalArgumentException("Negative storedDataLength = " + storedDataLength);
        }
        this.storedDataLength = storedDataLength;
        this.storedDataFileOffset = storedDataFileOffset;
        return this;
    }

    public TiffTile copyStoredDataFileRange(TiffTile other) {
        Objects.requireNonNull(other, "Null other tile");
        this.storedDataLength = other.storedDataLength;
        this.storedDataFileOffset = other.storedDataFileOffset;
        return this;
    }

    /**
     * Returns the number of pixels, actually stored in the {@link #getData() data array} in this tile
     * in the decoded form, or 0 after creating this object.
     *
     * <p>Note: that this method throws <tt>IllegalStateException</tt> if the data are
     * {@link #isEncoded() encoded}, for example, immediately after reading tile from file.
     * If the tile is {@link #isEmpty() empty} (no data),
     * the exception is not thrown, though usually there is no sense to call this method in this situation.</p>
     *
     * <p>If the data are not {@link #isEncoded() encoded}, the following equality is <i>usually</i> true:</p>
     *
     * <pre>{@link #getStoredDataLength()} == {@link #getStoredNumberOfPixels()} * {@link #bytesPerPixel()}</pre>
     *
     * <p>The only possible exception is when you sets the data with help of
     * {@link #setPartiallyDecodedData(byte[])} (when data are almost decoded, but, maybe, some additional
     * unpacking is necessary). This condition is always checked inside {@link #setDecodedData(byte[])}
     * method. You may also check this directly by {@link #checkDataLengthAlignment()} method.</p>
     *
     * <p><b>Warning:</b> the stored number of pixels, returned by this method, may <b>differ</b> from the tile
     * size {@link #getSizeX()} * {@link #getSizeY()}! Usually it occurs after decoding encoded tile, when the
     * decoding method returns only sequence of pixels and does not return information about the size.
     * In this situation, the external code sets the tile sizes from a priory information, but the decoded tile
     * may be actually less; for example, it takes place for the last strip in non-tiled TIFF format.
     * You can check, does the actual number of stored pixels equal to tile size, via
     * {@link #checkStoredNumberOfPixels()} method.
     *
     * @return the number of pixels in the last non-null data array, which was stored in this object.
     */
    @SuppressWarnings("JavadocDeclaration")
    public int getStoredNumberOfPixels() {
        if (isEncoded()) {
            throw new IllegalStateException("TIFF tile data are not decoded, number of pixels is unknown: " + this);
        }
        return storedNumberOfPixels;
    }

    public void checkDataLengthAlignment() {
        if (!encoded && storedNumberOfPixels * bytesPerPixel != data.length) {
            throw new IllegalStateException("Unaligned length of decoded data " + data.length +
                    ": it must be a multiple of the pixel length " +
                    bytesPerPixel + " = " + samplesPerPixel + " * " + bytesPerSample +
                    " (channels per pixel * bytes per channel sample)");
        }
    }


    public TiffTile checkStoredNumberOfPixels() throws IllegalStateException {
        checkEmpty();
        int storedNumberOfPixels = getStoredNumberOfPixels();
        if (storedNumberOfPixels != sizeX * sizeY) {
            throw new IllegalStateException("Number of stored pixels " + storedNumberOfPixels +
                    " does not match tile sizes " + sizeX + "x" + sizeY + " = " + (sizeX * sizeY));
        }
        assert storedNumberOfPixels * bytesPerPixel == storedDataLength;
        return this;
    }

    public TiffTile adjustNumberOfPixels(boolean allowDecreasing) {
        return changeNumberOfPixels(sizeX * sizeY, allowDecreasing);
    }

    public TiffTile changeNumberOfPixels(int newNumberOfPixels, boolean allowDecreasing) {
        if (newNumberOfPixels < 0) {
            throw new IllegalArgumentException("Negative new number of pixels = " + newNumberOfPixels);
        }
        final byte[] data = getDecodedData();
        // - performs all necessary state checks
        int numberOfPixels = storedNumberOfPixels;
        if (numberOfPixels == newNumberOfPixels) {
            return this;
        }
        if ((long) newNumberOfPixels * (long) bytesPerPixel > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too large requested number of pixels in tile: " + newNumberOfPixels +
                    " pixels * " + samplesPerPixel + " samples/pixel * " + bytesPerSample + " bytes/sample >= 2^31");
        }
        if (newNumberOfPixels < numberOfPixels && !allowDecreasing) {
            throw new IllegalArgumentException("The new number of pixels " + newNumberOfPixels +
                    " is less than actually stored " + numberOfPixels + "; this is not allowed: data may be lost");
        }
        final int newLength = newNumberOfPixels * bytesPerPixel;
        byte[] newData;
        if (interleaved) {
            newData = Arrays.copyOf(data, newLength);
        } else {
            newData = new byte[newLength];
            // - zero-filled by Java
            final int size = numberOfPixels * bytesPerSample;
            final int newSize = newNumberOfPixels * bytesPerSample;
            final int sizeToCopy = Math.min(size, newSize);
            for (int s = 0, disp = 0, newDisp = 0; s < samplesPerPixel; s++, disp += size, newDisp += newSize) {
                System.arraycopy(data, disp, newData, newDisp, sizeToCopy);
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
        data = TiffTools.toInterleavedSamples(data, samplesPerPixel(), bytesPerSample(), getStoredNumberOfPixels());
        setInterleaved(true);
        setDecodedData(data);
        return this;
    }

    public TiffTile separateSamples() {
        byte[] data = getDecodedData();
        if (!isInterleaved()) {
            throw new IllegalStateException("TIFF tile is already separated: " + this);
        }
        data = TiffTools.toSeparatedSamples(data, samplesPerPixel(), bytesPerSample(), getStoredNumberOfPixels());
        setInterleaved(false);
        setDecodedData(data);
        return this;
    }

    @Override
    public String toString() {
        return "TIFF " +
                (isEmpty() ? "(empty) " : "") +
                (encoded ? "encoded" : "non-encoded") +
                (interleaved ? " interleaved" : "") +
                " tile" +
                (isEmpty() ?
                        ", " + sizeX + "x" + sizeY :
                        ", actual sizes " + sizeX + "x" + sizeY + " (" +
                                storedNumberOfPixels + " pixels, " + storedDataLength + " bytes)" +
                                (isCompleted() ? ", completed" : ", partial")) +
                ", index " + index +
                (hasStoredDataFileOffset() ? " at file offset " + storedDataFileOffset : "");
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
                samplesPerPixel == tiffTile.samplesPerPixel && bytesPerSample == tiffTile.bytesPerSample &&
                storedDataFileOffset == tiffTile.storedDataFileOffset &&
                storedDataLength == tiffTile.storedDataLength &&
                Objects.equals(index, tiffTile.index) &&
                Arrays.equals(data, tiffTile.data);
        // Note: doesn't check "map" to avoid infinite recursion!
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(index, sizeX, sizeY,
                interleaved, encoded, storedDataFileOffset, storedDataLength);
        result = 31 * result + Arrays.hashCode(data);
        return result;
        // Note: doesn't check this.map to avoid infinite recursion!
    }

    private TiffTile setData(byte[] data, boolean encoded, boolean checkAligned) {
        Objects.requireNonNull(data, "Null " + (encoded ? "encoded" : "decoded") + " data");
        final int storedNumberOfPixels = data.length / bytesPerPixel;
        if (!encoded && checkAligned && storedNumberOfPixels * bytesPerPixel != data.length) {
            throw new IllegalArgumentException("Invalid length of decoded data " + data.length +
                    ": it must be a multiple of the pixel length " +
                    bytesPerPixel + " = " + samplesPerPixel + " * " + bytesPerSample +
                    " (channels per pixel * bytes per channel sample)");
        }
        this.data = data;
        this.storedDataLength = data.length;
        this.storedNumberOfPixels = storedNumberOfPixels;
        this.encoded = encoded;
        if (!encoded) {
            removeStoredDataFileOffset();
            // - data file offset has no sense for decoded data
        }
        return this;
    }

    private void initializeEmptyArea() {
        if (unsetArea == null) {
            unsetArea = new LinkedList<>();
            unsetArea.add(rectangle());
        }
    }

    private void checkEmpty() {
        if (data == null) {
            throw new IllegalStateException("TIFF tile is still not filled by any data: " + this);
        }
    }

    private void checkOutsideMap() {
        if (index.fromX() >= map.dimX() || index.fromY() >= map.dimY()) {
            throw new IllegalStateException("Tile is fully outside the map dimensions " +
                    map.dimX() + "x" + map.dimY() + ": " + this);
        }
    }

    private void checkStoredFilePosition() {
        if (storedDataFileOffset < 0) {
            throw new IllegalStateException("File offset of the TIFF tile is not set yet: " + this);
        }
    }
}
