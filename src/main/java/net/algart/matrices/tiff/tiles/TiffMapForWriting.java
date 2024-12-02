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
import net.algart.arrays.PArray;
import net.algart.arrays.PackedBitArraysPer8;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffSampleType;
import net.algart.matrices.tiff.TiffWriter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class TiffMapForWriting extends TiffMap {
    private final TiffWriter owningWriter;

    public TiffMapForWriting(TiffWriter owningWriter, TiffIFD ifd, boolean resizable) {
        super(ifd, resizable);
        this.owningWriter = Objects.requireNonNull(owningWriter, "Null owning writer");
    }

    /**
     * Returns the associated owning writer, passed to the constructor.
     * Never returns <code>null</code>.
     *
     * @return the writer-owner.
     */
    public TiffWriter writer() {
        return owningWriter;
    }

    public List<TiffTile> updateSamples(byte[] samples, long fromX, long fromY, long sizeX, long sizeY) {
        Objects.requireNonNull(samples, "Null samples");
        TiffReader.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        assert fromX == (int) fromX && fromY == (int) fromY && sizeX == (int) sizeX && sizeY == (int) sizeY;
        return updateSamples(samples, (int) fromX, (int) fromY, (int) sizeX, (int) sizeY);
    }

    public List<TiffTile> updateSamples(byte[] samples, int fromX, int fromY, int sizeX, int sizeY) {
        Objects.requireNonNull(samples, "Null samples");
        TiffReader.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        checkRequestedAreaInArray(samples, sizeX, sizeY, totalAlignedBitsPerPixel());
        List<TiffTile> updatedTiles = new ArrayList<>();
        if (sizeX == 0 || sizeY == 0) {
            // - if no pixels are updated, no need to expand the map and to check correct expansion
            return updatedTiles;
        }
        final int toX = fromX + sizeX;
        final int toY = fromY + sizeY;
        if (needToExpandDimensions(toX, toY)) {
            if (!isResizable()) {
                throw new IndexOutOfBoundsException("Requested area [" + fromX + ".." + (fromX + sizeX - 1) +
                        " x " + fromY + ".." + (fromY + sizeY - 1) + "] is outside the TIFF image dimensions " +
                        dimX() + "x" + dimY() + ": this is not allowed for non-resizable tile map");
            }
            expandDimensions(toX, toY);
        }

        final int mapTileSizeX = tileSizeX();
        final int mapTileSizeY = tileSizeY();
        final int numberOfSeparatedPlanes = numberOfSeparatedPlanes();
        final int samplesPerPixel = tileSamplesPerPixel();
        final long bitsPerSample = alignedBitsPerSample();
        final long bitsPerPixel = tileAlignedBitsPerPixel();
        // - "long" here leads to stricter requirements later on

        final int minXIndex = Math.max(0, TiffMapForReading.divFloor(fromX, mapTileSizeX));
        final int minYIndex = Math.max(0, TiffMapForReading.divFloor(fromY, mapTileSizeY));
        if (minXIndex >= gridCountX() || minYIndex >= gridCountY()) {
            throw new AssertionError("Map was not expanded/checked properly: minimal tile index (" +
                    minXIndex + "," + minYIndex + ") is out of tile grid 0<=x<" +
                    gridCountX() + ", 0<=y<" + gridCountY() + "; map: " + this);
        }
        final int maxXIndex = Math.min(gridCountX() - 1, TiffMapForReading.divFloor(toX - 1, mapTileSizeX));
        final int maxYIndex = Math.min(gridCountY() - 1, TiffMapForReading.divFloor(toY - 1, mapTileSizeY));
        if (minYIndex > maxYIndex || minXIndex > maxXIndex) {
            // - possible when fromX < 0 or fromY < 0
            return updatedTiles;
        }

        final long tileChunkedRowSizeInBits = (long) mapTileSizeX * bitsPerPixel;
        final long samplesChunkedRowSizeInBits = (long) sizeX * bitsPerPixel;
        final long tileOneChannelRowSizeInBits = (long) mapTileSizeX * bitsPerSample;
        final long samplesOneChannelRowSizeInBits = (long) sizeX * bitsPerSample;

        final boolean sourceInterleaved = isConsideredInterleaved();
        for (int p = 0; p < numberOfSeparatedPlanes; p++) {
            // - for a rare case PlanarConfiguration=2 (RRR...GGG...BBB...)
            for (int yIndex = minYIndex; yIndex <= maxYIndex; yIndex++) {
                final int tileStartY = Math.max(yIndex * mapTileSizeY, fromY);
                final int fromYInTile = tileStartY % mapTileSizeY;
                final int yDiff = tileStartY - fromY;

                for (int xIndex = minXIndex; xIndex <= maxXIndex; xIndex++) {
                    final int tileStartX = Math.max(xIndex * mapTileSizeX, fromX);
                    final int fromXInTile = tileStartX % mapTileSizeX;
                    final int xDiff = tileStartX - fromX;

                    final TiffTile tile = getOrNewMultiPlane(p, xIndex, yIndex);
                    if (tile.isDisposed()) {
                        // - we cannot write to already dispose tile: it will result in an exception
                        continue;
                    }
                    tile.checkReadyForNewDecodedData(false);
                    tile.cropToMap();
                    // - In stripped image, we should correct the height of the last row.
                    // It is important for writing: without this correction, GIMP and other libtiff-based programs
                    // will report about an error (see libtiff, tif_jpeg.c, assigning segment_width/segment_height)
                    // However, if tiling is requested via TILE_WIDTH/TILE_LENGTH tags, we SHOULD NOT do this.
                    tile.fillWhenEmpty(owningWriter.getTileInitializer());
                    final byte[] data = tile.getDecodedData();

                    final int tileSizeX = tile.getSizeX();
                    final int tileSizeY = tile.getSizeY();
                    final int sizeXInTile = Math.min(toX - tileStartX, tileSizeX - fromXInTile);
                    assert sizeXInTile > 0 : "sizeXInTile=" + sizeXInTile;
                    final int sizeYInTile = Math.min(toY - tileStartY, tileSizeY - fromYInTile);
                    assert sizeYInTile > 0 : "sizeYInTile=" + sizeYInTile;
                    tile.reduceUnsetInTile(fromXInTile, fromYInTile, sizeXInTile, sizeYInTile);

                    // Tile must be interleaved always (RGBRGB...).
                    // A) planarSeparated=false, autoInterleave=false:
                    //      source pixels should be RGBRGB..., tile also will be RGBRGB...
                    // B) planarSeparated=false, autoInterleave=true:
                    //      source pixels are RRR...GGG..BBB..., every tile will also be RRR...GGG..BBB...
                    //      (will be interleaved later by tile.interleaveSamples() call)
                    // C) planarSeparated=true, autoInterleave is ignored:
                    //      source pixels are RRR...GGG..BBB..., we will have separate RRR tiles, GGG tiles, BBB tiles
                    //      (actually each tile is monochrome).
                    if (sourceInterleaved) {
//                        System.out.printf("!!!Chunked: %d%n", samplesPerPixel);
                        // - Case A: source data are already interleaved (like RGBRGB...): maybe, external code
                        // prefers to use interleaved form, for example, OpenCV library.
                        // Here tile will be actually interleaved directly after this method;
                        // we'll need to inform it about this fact (by setInterleaved(true)) later in encode().
                        final long partSizeXInBits = (long) sizeXInTile * bitsPerPixel;
                        long tOffset = (fromYInTile * (long) tileSizeX + fromXInTile) * bitsPerPixel;
                        long sOffset = (yDiff * (long) sizeX + xDiff) * bitsPerPixel;
                        for (int i = 0; i < sizeYInTile; i++) {
                            assert sOffset >= 0 && tOffset >= 0 : "possibly int instead of long";
                            PackedBitArraysPer8.copyBitsNoSync(data, tOffset, samples, sOffset, partSizeXInBits);
                            tOffset += tileChunkedRowSizeInBits;
                            sOffset += samplesChunkedRowSizeInBits;
                        }
                    } else {
//                        System.out.printf("!!!Separate: %d%n", samplesPerPixel);
                        // - Source data are separated to channel planes: standard form, more convenient for image
                        // processing; this form is used for results of TiffReader by default (unless
                        // you specify another behavior by setInterleaveResults method).
                        // Here are 2 possible cases:
                        //      B) planarSeparated=false (most typical): results in the file should be interleaved;
                        // we must prepare a single tile, but with SEPARATED data (they will be interleaved later);
                        //      C) planarSeparated=true (rare): for 3 channels (RGB) we must prepare 3 separate tiles;
                        // in this case samplesPerPixel=1.
                        final long partSizeXInBits = (long) sizeXInTile * bitsPerSample;
                        for (int s = 0; s < samplesPerPixel; s++) {
                            long tOffset = (((s * (long) tileSizeY) + fromYInTile)
                                    * (long) tileSizeX + fromXInTile) * bitsPerSample;
                            long sOffset = (((p + s) * (long) sizeY + yDiff) * (long) sizeX + xDiff) * bitsPerSample;
                            // (long) cast is important for processing large bit matrices!
                            for (int i = 0; i < sizeYInTile; i++) {
                                assert sOffset >= 0 && tOffset >= 0 : "possibly int instead of long";
                                PackedBitArraysPer8.copyBitsNoSync(data, tOffset, samples, sOffset, partSizeXInBits);
                                tOffset += tileOneChannelRowSizeInBits;
                                sOffset += samplesOneChannelRowSizeInBits;
                            }
                        }
                    }
                    updatedTiles.add(tile);
                }
            }
        }
        return updatedTiles;
    }

    public List<TiffTile> updateJavaArray(
            Object samplesArray,
            int fromX,
            int fromY,
            int sizeX,
            int sizeY) {
        Objects.requireNonNull(samplesArray, "Null samplesArray");
        final long numberOfPixels = TiffReader.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        final Class<?> elementType = samplesArray.getClass().getComponentType();
        if (elementType == null) {
            throw new IllegalArgumentException("The specified samplesArray is not actual an array: " +
                    "it is " + samplesArray.getClass());
        }
        if (elementType != elementType()) {
            throw new IllegalArgumentException("Invalid element type of samples array: " + elementType +
                    ", but the specified TIFF map stores " + elementType() + " elements");
        }
        final long numberOfSamples = Math.multiplyExact(numberOfPixels, numberOfChannels());
        // - overflow impossible after checkRequestedArea
        if (numberOfSamples > maxNumberOfSamplesInArray()) {
            throw new IllegalArgumentException("Too large area for updating TIFF in a single operation: " +
                    sizeX + "x" + sizeY + "x" + numberOfChannels() + " exceed the limit " +
                    maxNumberOfSamplesInArray());
        }
        final byte[] samples = TiffSampleType.bytes(samplesArray, numberOfSamples, byteOrder());
        return updateSamples(samples, fromX, fromY, sizeX, sizeY);
    }

    public List<TiffTile> updateMatrix(Matrix<? extends PArray> matrix, int fromX, int fromY) {
        Objects.requireNonNull(matrix, "Null matrix");
        final boolean sourceInterleaved = isConsideredInterleaved();
        final Class<?> elementType = matrix.elementType();
        if (elementType != elementType()) {
            throw new IllegalArgumentException("Invalid element type of the matrix: " + elementType +
                    ", although the specified TIFF map stores " + elementType() + " elements");
        }
        if (matrix.dimCount() != 3 && !(matrix.dimCount() == 2 && numberOfChannels() == 1)) {
            throw new IllegalArgumentException("Illegal number of matrix dimensions " + matrix.dimCount() +
                    ": it must be 3-dimensional dimX*dimY*C, " +
                    "where C is the number of channels (z-dimension), " +
                    "or 3-dimensional C*dimX*dimY for interleaved case, " +
                    "or may be 2-dimensional in the case of monochrome TIFF image");
        }
        final int dimChannelsIndex = sourceInterleaved ? 0 : 2;
        final long numberOfChannels = matrix.dim(dimChannelsIndex);
        final long sizeX = matrix.dim(sourceInterleaved ? 1 : 0);
        final long sizeY = matrix.dim(sourceInterleaved ? 2 : 1);
        if (numberOfChannels != numberOfChannels()) {
            throw new IllegalArgumentException("Invalid number of channels in the matrix: " + numberOfChannels +
                    " (matrix " + matrix.dim(0) + "*" + matrix.dim(1) + "*" + matrix.dim(2) + "), " +
                    (matrix.dim(2 - dimChannelsIndex) == numberOfChannels() ?
                            "probably because of invalid interleaving mode: TIFF image is " +
                                    (sourceInterleaved ? "" : "NOT ") + "interleaved" :
                            "because the specified TIFF map stores " + numberOfChannels() + " channels"));
        }
        PArray array = matrix.array();
        if (array.length() > maxNumberOfSamplesInArray()) {
            throw new IllegalArgumentException("Too large matrix for updating TIFF in a single operation: " + matrix
                    + " (number of elements " + array.length() + " exceed the limit " +
                    maxNumberOfSamplesInArray() + ")");
        }
        final byte[] samples = TiffSampleType.bytes(array, byteOrder());
        return updateSamples(samples, fromX, fromY, sizeX, sizeY);
    }

    /**
     * Equivalent to
     * <code>{@link #updateMatrix(Matrix, int, int)
     * updateMatrix}(Matrices.mergeLayers(Arrays.SMM, channels), fromX, fromY)</code>.
     *
     * <p>Note that this method requires the {@link TiffWriter#setAutoInterleaveSource(boolean) auto-interleave}
     * mode to be set in the owning writer; otherwise an exception is thrown.
     *
     * @param channels color channels of the image (2-dimensional matrices).
     * @param fromX    starting x-coordinate for updating.
     * @param fromY    starting y-coordinate for updating.
     * @return list of TIFF tiles where were updated as a result of this operation.
     */
    public List<TiffTile> updateChannels(List<? extends Matrix<? extends PArray>> channels, int fromX, int fromY) {
        Objects.requireNonNull(channels, "Null channels");
        if (!owningWriter.isAutoInterleaveSource()) {
            throw new IllegalStateException("Cannot update image channels: autoInterleaveSource mode is not set");
        }
        return updateMatrix(Matrices.mergeLayers(net.algart.arrays.Arrays.SMM, channels), fromX, fromY);
    }

    public void writeMatrix(Matrix<? extends PArray> matrix) throws IOException {
        owningWriter.writeMatrix(this, matrix);
    }

    public void writeChannels(List<? extends Matrix<? extends PArray>> channels)
            throws IOException {
        owningWriter.writeChannels(this, channels);
    }

    public int completeWriting() throws IOException {
        return owningWriter.completeWriting(this);
    }

    public boolean isConsideredInterleaved() {
        return !isPlanarSeparated() && !owningWriter.isAutoInterleaveSource();
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ 'w';
    }

    @Override
    String mapKindName() {
        return "map-for-writing";
    }

    private static void checkRequestedAreaInArray(byte[] array, long sizeX, long sizeY, int bitsPerPixel) {
        Objects.requireNonNull(array, "Null array");
        if (bitsPerPixel <= 0) {
            throw new IllegalArgumentException("Zero or negative bitsPerPixel = " + bitsPerPixel);
        }
        final long arrayBits = (long) array.length * 8;
        TiffReader.checkRequestedArea(0, 0, sizeX, sizeY);
        if (sizeX * sizeY > arrayBits || sizeX * sizeY * (long) bitsPerPixel > arrayBits) {
            throw new IllegalArgumentException("Requested area " + sizeX + "x" + sizeY +
                    " is too large for array of " + array.length + " bytes, " + bitsPerPixel + " per pixel");
        }
    }
}
