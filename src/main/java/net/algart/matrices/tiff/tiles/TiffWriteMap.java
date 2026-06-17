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
import net.algart.math.IRectangularArea;
import net.algart.matrices.tiff.*;
import net.algart.matrices.tiff.tags.Tags;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

public final class TiffWriteMap extends TiffIOMap<TiffWriter> {
    private static final boolean IGNORE_WRITING_OUTSIDE_MAP = true;
    // - Should be true. The false value simulates the early versions, but this is inconvenient for using.

    private final TiffWriter owner;
    private final boolean existing;

    public TiffWriteMap(TiffWriter owner, TiffIFD ifd, boolean resizable, boolean existing) throws TiffException {
        super(owner, ifd, resizable);
        if (existing) {
            if (!ifd.isLoadedFromFile()) {
                throw new IllegalArgumentException("For existing map, IFD must be read from TIFF file");
            }
            if (resizable) {
                throw new IllegalArgumentException("Existing TIFF write map must not be resizable");
            }
        }
        this.owner = Objects.requireNonNull(owner, "Null owning writer");
        this.existing = existing;
    }

    @Override
    public TiffReader reader() {
        return owner.companionReader();
    }

    @Override
    public boolean isExistingInFile() {
        return existing;
    }


    @Override
    public TiffWriteMap setBitImageUnpackingMode(BitImageUnpackingMode bitImageUnpackingMode) {
        super.setBitImageUnpackingMode(bitImageUnpackingMode);
        return this;
    }

    @Override
    public TiffWriteMap setRarePrecisionMode(RarePrecisionMode rarePrecisionMode) {
        super.setRarePrecisionMode(rarePrecisionMode);
        return this;
    }

    @Override
    public TiffWriteMap setExtraChannelsMode(ExtraChannelsMode extraChannelsMode) {
        super.setExtraChannelsMode(extraChannelsMode);
        return this;
    }

    public byte[] readSampleBytesAndStore(int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return readSampleBytes(fromX, fromY, sizeX, sizeY, true);
    }

    public Object readJavaArrayAndStore(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return readJavaArray(fromX, fromY, sizeX, sizeY, true);
    }

    public Matrix<UpdatablePArray> readMatrixAndStore(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return readMatrix(fromX, fromY, sizeX, sizeY, true);
    }

    public Matrix<UpdatablePArray> readInterleavedMatrixAndStore(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return readInterleavedMatrix(fromX, fromY, sizeX, sizeY, true);
    }

    public List<Matrix<UpdatablePArray>> readChannelsAndStore(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY)
            throws IOException {
        return readChannels(fromX, fromY, sizeX, sizeY, true);
    }

    public BufferedImage readBufferedImageAndStore(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return readBufferedImage(fromX, fromY, sizeX, sizeY, true);
    }

    public void preloadAndStore(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY) throws IOException {
        preloadAndStore(fromX, fromY, sizeX, sizeY, true);
    }

    public void preloadAndStore(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean loadTilesFullyInsideRectangle) throws IOException {
        preloadAndStore(fromX, fromY, sizeX, sizeY, loadTilesFullyInsideRectangle, this::readCachedTile);
    }

    /**
     * Preloads all tiles, containing in this map and intersecting the specified rectangle
     * <code>fromX&le;x&lt;fromX+sizeX</code>, <code>fromY&le;y&lt;fromY+sizeY</code>.
     *
     * <p>This method helps to overwrite some portion of the existing TIFF:
     * data written to TIFF using the returned map will be placed over the existing image.</p>
     *
     * <p>You can set the argument <code>loadTilesFullyInsideRectangle</code> to <code>false</code>
     * if you are going to fill the entire specified rectangle by some new data: in this case
     * there is no necessity to preload tiles that will be completely rewritten.
     * Otherwise, please set <code>loadTilesFullyInsideRectangle=true</code>.</p>
     *
     * <p>Note: zero sizes <code>sizeX</code> or <code>sizeY</code> are allowed,
     * in this case the method does not load any tiles.</p>
     *
     * @param fromX                         starting x-coordinate for preloading.
     * @param fromY                         starting y-coordinate for preloading.
     * @param sizeX                         width of the preloaded rectangle.
     * @param sizeY                         height of the preloaded rectangle.
     * @param loadTilesFullyInsideRectangle whether this method should load tiles that are completely
     *                                      inside the specified rectangle.
     * @param tileSupplier                  a supplier of tiles that will be used for loading.
     * @throws NullPointerException if the specified supplier is <code>null</code>.
     * @throws IOException          in the case of any problems with the TIFF file.
     */
    public void preloadAndStore(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean loadTilesFullyInsideRectangle,
            TileSupplier tileSupplier)
            throws IOException {
        checkRequestedArea(fromX, fromY, sizeX, sizeY);
        if (sizeX > 0 && sizeY > 0) {
            // a zero-size rectangle does not "intersect" anything
            final IRectangularArea areaToWrite = IRectangularArea.ofSize(fromX, fromY, sizeX, sizeY);
            for (TiffTile tile : tiles()) {
                if (tile.actualRectangle().intersects(areaToWrite)) {
                    if (loadTilesFullyInsideRectangle || !areaToWrite.contains(tile.actualRectangle())) {
                        final TiffTile existing = tileSupplier.getTile(tile.index());
                        tile.copyData(existing, false);
                    } else {
                        tile.unfreeze();
                        // - we declare that this tile should be rewritten by further overwriting data
                    }
                }
            }
        }
    }

    public List<TiffTile> updateSampleBytes(byte[] sampleBytes, long fromX, long fromY, long sizeX, long sizeY) {
        Objects.requireNonNull(sampleBytes, "Null sampleBytes");
        checkRequestedArea(fromX, fromY, sizeX, sizeY);
        assert fromX == (int) fromX && fromY == (int) fromY && sizeX == (int) sizeX && sizeY == (int) sizeY;
        return updateSampleBytes(sampleBytes, (int) fromX, (int) fromY, (int) sizeX, (int) sizeY);
    }

    public List<TiffTile> updateSampleBytes(
            final byte[] sampleBytes,
            final int fromX,
            final int fromY,
            final int sizeX,
            final int sizeY) {
        Objects.requireNonNull(sampleBytes, "Null sampleBytes");
        checkRequestedArea(fromX, fromY, sizeX, sizeY);
        checkRequestedAreaInArray(sampleBytes, sizeX, sizeY, combinedNormalizedBitsPerPixel());
        final List<TiffTile> updatedTiles = new ArrayList<>();
        if (sizeX == 0 || sizeY == 0) {
            // - if no pixels are updated, no need to expand the map and to check correct expansion
            return updatedTiles;
        }
        int toX = fromX + sizeX;
        int toY = fromY + sizeY;
        if (needToExpandDimensions(toX, toY)) {
            if (isResizable()) {
                expandDimensions(toX, toY);
            } else {
                if (!IGNORE_WRITING_OUTSIDE_MAP) {
                    throw new IndexOutOfBoundsException("Requested area [" + fromX + ".." + (fromX + sizeX - 1) +
                            " x " + fromY + ".." + (fromY + sizeY - 1) + "] is outside the TIFF image dimensions " +
                            dimX() + "x" + dimY() + ": this is not allowed for non-resizable tile map");
                }
                toX = Math.min(toX, dimX());
                toY = Math.min(toY, dimY());
                // Note: we MUST NOT change sizeX/sizeY, they specify the structure of the sampleBytes array
                if (toX <= fromX || toY <= fromY) {
                    // Note: below we need a guarantee that fromX < toX, fromY < toY
                    return updatedTiles;
                }
            }
        }

        final int mapTileSizeX = tileSizeX();
        final int mapTileSizeY = tileSizeY();
        final int numberOfSeparatedPlanes = numberOfSeparatedPlanes();
        final int samplesPerPixel = tileSamplesPerPixel();
        final long bitsPerSample = normalizedBitDepth();
        final long bitsPerPixel = tileNormalizedBitsPerPixel();
        // - "long" here leads to stricter requirements later on

        final int minXIndex = Math.max(0, divFloor(fromX, mapTileSizeX));
        final int minYIndex = Math.max(0, divFloor(fromY, mapTileSizeY));
        if (isResizable() && (minXIndex >= gridCountX() || minYIndex >= gridCountY())) {
            throw new AssertionError("Map was not expanded/checked properly: minimal tile index (" +
                    minXIndex + "," + minYIndex + ") is out of tile grid 0<=x<" +
                    gridCountX() + ", 0<=y<" + gridCountY() + "; map: " + this);
        }
        final int maxXIndex = Math.min(gridCountX() - 1, divFloor(toX - 1, mapTileSizeX));
        final int maxYIndex = Math.min(gridCountY() - 1, divFloor(toY - 1, mapTileSizeY));
        if (minYIndex > maxYIndex || minXIndex > maxXIndex) {
            // - possible when the area is outside the map, in particular when fromX < 0 or fromY < 0
            return updatedTiles;
        }

        final long tileChunkedRowSizeInBits = (long) mapTileSizeX * bitsPerPixel;
        final long samplesChunkedRowSizeInBits = (long) sizeX * bitsPerPixel;
        final long tileOneChannelRowSizeInBits = (long) mapTileSizeX * bitsPerSample;
        final long samplesOneChannelRowSizeInBits = (long) sizeX * bitsPerSample;

        final boolean sourceInterleaved = !isPlanarSeparated() && !AUTO_INTERLEAVE_SOURCE;
        // - actually false since 1.4.0
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

                    final TiffTile tile = getOrNew(xIndex, yIndex, p);
                    if (tile.isFrozen()) {
                        // - we cannot write to an already frozen tile: it will result in an exception
                        continue;
                    }
                    tile.checkReadyForNewDecodedData(false);
                    tile.cropStripToMap();
                    // - In a stripped image, we should correct the height of the last row.
                    // It is important for writing: without this correction, GIMP and other libtiff-based programs
                    // will report about an error (see libtiff, tif_jpeg.c, assigning segment_width/segment_height)
                    // However, if tiling is requested via TILE_WIDTH/TILE_LENGTH tags, we SHOULD NOT do this.
                    tile.fillWhenEmpty(owner.getTileInitializer());
                    final byte[] data = tile.getDecodedData();

                    final int tileSizeX = tile.getSizeX();
                    final int tileSizeY = tile.getSizeY();
                    final int sizeXInTile = Math.min(toX - tileStartX, tileSizeX - fromXInTile);
                    if (sizeXInTile <= 0) {
                        throw new AssertionError("sizeXInTile=" + sizeXInTile);
                    }
                    final int sizeYInTile = Math.min(toY - tileStartY, tileSizeY - fromYInTile);
                    if (sizeYInTile <= 0) {
                        throw new AssertionError("sizeYInTile=" + sizeYInTile);
                    }
                    tile.markNewRectangleAsSet(fromXInTile, fromYInTile, sizeXInTile, sizeYInTile);

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
                        assert !AUTO_INTERLEAVE_SOURCE : "AUTO_INTERLEAVE_SOURCE=false, but sourceInterleaved=true";
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
                            PackedBitArraysPer8.copyBitsNoSync(data, tOffset, sampleBytes, sOffset, partSizeXInBits);
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
                                PackedBitArraysPer8.copyBitsNoSync(
                                        data, tOffset, sampleBytes, sOffset, partSizeXInBits);
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
        final byte[] sampleBytes = javaArrayToBytes(samplesArray, fromX, fromY, sizeX, sizeY);
        return updateSampleBytes(sampleBytes, fromX, fromY, sizeX, sizeY);
    }

    public List<TiffTile> updateMatrix(Matrix<? extends PArray> matrix, int fromX, int fromY) {
        if (!AUTO_INTERLEAVE_SOURCE) {
            throw new IllegalStateException("Cannot update matrix: AUTO_INTERLEAVE_SOURCE is not set");
        }
        final byte[] sampleBytes = matrixToBytes(matrix);
        return updateSampleBytes(sampleBytes, fromX, fromY, matrix.dimX(), matrix.dimY());
    }

    /**
     * Equivalent to
     * <code>{@link #updateMatrix(Matrix, int, int)
     * updateMatrix}(Matrices.mergeLayers(Arrays.SMM, channels), fromX, fromY)</code>.
     *
     * @param channels color channels of the image (2-dimensional matrices).
     * @param fromX    starting x-coordinate for updating.
     * @param fromY    starting y-coordinate for updating.
     * @return list of TIFF tiles where were updated as a result of this operation.
     */
    public List<TiffTile> updateChannels(List<? extends Matrix<? extends PArray>> channels, int fromX, int fromY) {
        if (!AUTO_INTERLEAVE_SOURCE) {
            throw new IllegalStateException("Cannot update image channels: AUTO_INTERLEAVE_SOURCE is not set");
        }
        final Matrix<PArray> mergedChannels = channelsToMatrix(channels);
        return updateMatrix(mergedChannels, fromX, fromY);
    }

    public List<TiffTile> updateBufferedImage(BufferedImage bufferedImage, int fromX, int fromY) {
        final List<Matrix<UpdatablePArray>> channels = bufferedImageToChannels(bufferedImage);
        return updateChannels(channels, fromX, fromY);
    }

    public void prewrite() throws IOException {
        owner.prewrite(this);
    }

    /**
     * Writes the matrix.
     *
     * <p>Note that the samples array in all <code>write...</code> methods is always supposed to be separated.
     * For multichannel images, this means the samples are ordered like RRR..GGG..BBB... (the standard form
     * returned by {@link TiffReader}). If the desired IFD format is chunked, i.e.,
     * {@link Tags#PLANAR_CONFIGURATION} is {@link TiffIFD#PLANAR_CONFIGURATION_CHUNKED}
     * (which is the typical usage), then the provided samples are internally re-packed into the interleaved
     * form RGBRGBRGB...</p>
     *
     * <p>Note that a second call to this method with the same map will have no effect. Example:</p>
     * <pre>
     * map.{@link #writeSampleBytes(byte[]) writeSampleBytes}(samples1);
     * map.{@link #writeSampleBytes(byte[]) writeSampleBytes}(samples2);
     * </pre>
     *
     * <p>Only {@code samples1} will be written. The reason is that writing a matrix automatically
     * {@link TiffTile#freeAndFreeze() frees and freezes} its underlying tiles. If you want to rewrite
     * the same map with new data, you need to remove all existing tiles by calling the
     * <code>map.{@link #clear()}</code> method <i>before</i> the next write operation.
     * However, typically you should not rewrite the matrix several times.
     * For updating specific elements without rewriting the entire matrix,
     * please use {@link #updateSampleBytes(byte[], int, int, int, int)} and similar methods.</p>
     *
     * @param samples the samples in a raw form.
     * @throws TiffException in the case of an invalid TIFF IFD.
     * @throws IOException   in the case of any I/O errors.
     */
    @SuppressWarnings("JavadocDeclaration")
    public void writeSampleBytes(byte[] samples) throws IOException {
        Objects.requireNonNull(samples, "Null samples");
        checkZeroDimensions();
        owner.resetTiming();
        long t1 = debugTime();
        updateSampleBytes(samples, 0, 0, dimX(), dimY());
        long t2 = debugTime();
        prewrite();
        long t3 = debugTime();
        encode();
        long t4 = debugTime();
        completeWriting();
        long t5 = debugTime();
        logMatrix(this, "byte samples",
                t2 - t1, t3 - t2, t4 - t3, t5 - t4);
    }

    public void writeJavaArray(Object samplesArray) throws IOException {
        Objects.requireNonNull(samplesArray, "Null samplesArray");
        checkZeroDimensions();
        owner.resetTiming();
        long t1 = debugTime();
        updateJavaArray(samplesArray, 0, 0, dimX(), dimY());
        long t2 = debugTime();
        prewrite();
        long t3 = debugTime();
        encode();
        long t4 = debugTime();
        completeWriting();
        long t5 = debugTime();
        logMatrix(this, "pixel array",
                t2 - t1, t3 - t2, t4 - t3, t5 - t4);
    }

    /**
     * Writes the matrix.
     * The image is specified as a 3D or 2D matrix of pixels.
     * For a 3D matrix, the first dimension ({@link Matrix#dimX()}) is the width,
     * the second ({@link Matrix#dimY()}) is the height, and
     * the third ({@link Matrix#dimZ()}) is the number of channels.
     * If the image has only {@code 1} channel, a 2D matrix (width and height) is also allowed.
     *
     * <p>Note: unlike {@link #writeJavaArray(Object)} and
     * {@link #writeSampleBytes(byte[])},
     * this method always uses the actual sizes of the passed matrix and therefore <i>does not require</i>
     * the map to have correct non-zero dimensions (a situation possible for resizable maps).</p>
     *
     * <p>Note that a second call to this method with the same map will have no effect. Example:</p>
     * <pre>
     * map.{@link #writeMatrix(Matrix) writeMatrix}(matrix1);
     * map.{@link #writeMatrix(Matrix) writeMatrix}(matrix2);
     * </pre>
     *
     * <p>Only {@code matrix1} will be written. The reason is that writing a matrix automatically
     * {@link TiffTile#freeAndFreeze() frees and freezes} its underlying tiles. If you want to rewrite
     * the same map with new data, you need to remove all existing tiles by calling the
     * <code>map.{@link #clear()}</code> method <i>before</i> the next write operation.
     * However, typically you should not rewrite the matrix several times.
     * For updating specific elements without rewriting the entire matrix,
     * please use {@link #updateMatrix(Matrix, int, int)} and similar methods.</p>
     *
     * @param matrix 3D-matrix of pixels (or 2D-matrix for 1-channel image).
     * @throws TiffException in the case of an invalid TIFF IFD.
     * @throws IOException   in the case of any I/O errors.
     */
    public void writeMatrix(Matrix<? extends PArray> matrix) throws IOException {
        Objects.requireNonNull(matrix, "Null matrix");
        owner.resetTiming();
        long t1 = debugTime();
        updateMatrix(matrix, 0, 0);
        long t2 = debugTime();
        owner.prewrite(this);
        long t3 = debugTime();
        encode();
        long t4 = debugTime();
        completeWriting();
        long t5 = debugTime();
        logMatrix(this, "matrix",
                t2 - t1, t3 - t2, t4 - t3, t5 - t4);
    }

    /**
     * Equivalent to
     * <code>{@link #writeMatrix(Matrix) writeMatrix}(Matrices.mergeLayers(channels))</code>.
     *
     * @param channels color channels of the image (2-dimensional matrices).
     * @throws TiffException in the case of invalid TIFF IFD.
     * @throws IOException   in the case of any I/O errors.
     */
    public void writeChannels(List<? extends Matrix<? extends PArray>> channels) throws IOException {
        Objects.requireNonNull(channels, "Null channels");
        if (!AUTO_INTERLEAVE_SOURCE) {
            throw new IllegalStateException("Cannot write image channels: autoInterleaveSource mode is not set");
        }
        writeMatrix(Matrices.mergeLayers(channels));
    }

    /**
     * Equivalent to
     * <code>{@link #writeChannels(List)
     * writeChannels}({@link ImageToMatrix#toChannels
     * ImageToMatrix.toChannels}(bufferedImage))</code>.
     *
     * @param bufferedImage the image.
     * @throws TiffException in the case of invalid TIFF IFD.
     * @throws IOException   in the case of any I/O errors.
     */
    public void writeBufferedImage(BufferedImage bufferedImage) throws IOException {
        Objects.requireNonNull(bufferedImage, "Null bufferedImage");
        writeChannels(ImageToMatrix.toChannels(bufferedImage));
    }

    public void writeBlank(Color fillColor) throws IOException {
        Objects.requireNonNull(fillColor, "Null fillColor");
        writeBlank(colorToChannelValues(fillColor, true));
    }

    public void writeBlank(double[] filler) throws IOException {
        Objects.requireNonNull(filler, "Null filler");
        writeBlankRepeatingTile(m -> fillByColor(m, filler));
    }

    public void writeBlankRepeatingTile(Consumer<Matrix<UpdatablePArray>> tileFiller) throws IOException {
        Objects.requireNonNull(tileFiller, "Null tileFiller");
        buildTileGrid();
        // - just in case: should be already called in TiffWriter.newMap
        final Matrix<UpdatablePArray> matrix = Matrix.newMatrix(
                elementType(), tileSizeX(), tileSizeY(), numberOfChannels());
        tileFiller.accept(matrix);
        prewrite();
        List<TiffTile> tiffTiles = updateMatrix(matrix, 0, 0);
        if (tiffTiles.size() != 1) {
            throw new AssertionError("Invalid tile count after updating 1 tile: " + tiffTiles.size());
        }
        flushCompletedTiles(tiffTiles);
        // - writing 1st tile: now it has the offset in the file
        final TiffTile first = tiffTiles.getFirst();
        int index = 0;
        for (TiffTile tile : tiles()) {
            if (tile.linearIndex() != index) {
                throw new ConcurrentModificationException("Invalid linear index of the tile " + tile +
                        ",%nprobably because of growing the map by a parallel thread:%n%s".formatted(this));
            }
            if (index > 0) {
                tile.linkAsDuplicateOf(first);
            }
            index++;
        }
        completeWriting();
    }

    public void writeTile(TiffTile tile, boolean freeAndFreezeAfterWriting) throws IOException {
        owner.writeTile(tile, freeAndFreezeAfterWriting);
    }

    public int writeAllTiles(Collection<TiffTile> tiles) throws IOException {
        return writeTiles(tiles, tile -> true, true);
    }

    public int flushCompletedTiles(Collection<TiffTile> tiles) throws IOException {
        return flushCompletedTiles(tiles, true);
    }

    public int flushCompletedTiles(Collection<TiffTile> tiles, boolean freeAndFreezeAfterWriting) throws IOException {
        return writeTiles(tiles, TiffTile::isCompleted, freeAndFreezeAfterWriting);
    }

    public void encode() throws TiffException {
        long t1 = debugTime();
        int count = 0;
        long sizeInBytes = 0;
        for (TiffTile tile : tiles()) {
            final boolean wasNotEncodedYet = owner.encode(tile);
            if (wasNotEncodedYet) {
                count++;
                sizeInBytes += tile.getSizeInBytes();
            }
        }
        long t2 = debugTime();
        logTiles(null, count, sizeInBytes, t1, t2);
    }

    public int writeTiles(
            Collection<TiffTile> tiles,
            Predicate<TiffTile> needToWrite,
            boolean freeAndFreezeAfterWriting)
            throws IOException {
        return owner.writeTiles(tiles, needToWrite, freeAndFreezeAfterWriting);
    }

    public int completeWriting() throws IOException {
        return owner.completeWriting(this);
    }

    /**
     * Puts the current tile positions (offsets and byte counts) from the tiles,
     * present in this map, back into the {@link #ifd() underlying IFD}.
     *
     * <p>To do this, this method loads the existing offsets and byte counts via the methods
     * {@link #ifd()}.{@link TiffIFD#cachedTileOrStripOffsets() cachedTileOrStripOffsets()} and
     * {@link #ifd()}.{@link TiffIFD#cachedTileOrStripByteCounts() cachedTileOrStripByteCounts()},
     * accesses all existing tiles via
     * {@link #get(TiffTileIndex)} to read their
     * {@link TiffTile#getStoredInFileDataOffset()} and
     * {@link TiffTile#getStoredInFileDataLength()},
     * updates the corresponding elements of these two arrays
     * and writes them into the IFD via
     * {@link #ifd()}.{@link TiffIFD#putDataPlacementInFileIgnoringFreeze(long[], long[])
     * putDataPlacementInFileIgnoringFreeze} method.
     *
     * <p>This can be useful when you need to synchronize the IFD metadata with
     * the actual state of the tiles in this map, for example, to create
     * a new map based on the same IFD.</p>
     *
     * @throws IllegalStateException if {@link #isExistingInFile()} returns {@code false}.
     * @throws TiffException         if case of problems with accessing the underlying IFD.
     */
    public void putDataPlacementInFileToUnderlyingIFD() throws TiffException {
        if (!isExistingInFile()) {
            throw new IllegalStateException("IFD can be updated only for existing maps");
        }
        if (isResizable()) {
            throw new AssertionError("Existing map cannot be resizable");
        }
        final TiffIFD ifd = ifd();
        final long[] offsets = ifd.cachedTileOrStripOffsets();
        final long[] byteCounts = ifd.cachedTileOrStripByteCounts();
        assert offsets != null;
        assert byteCounts != null;
        final int numberOfSeparatedPlanes = numberOfSeparatedPlanes();
        final int gridCountY = gridCountY();
        final int gridCountX = gridCountX();
        final int n = numberOfGridTiles();
        assert n == gridCountX * gridCountY * numberOfSeparatedPlanes;
        if (offsets.length != n || byteCounts.length != n) {
            throw new IllegalStateException("IFD offsets and byteCounts have been modified " +
                    "after creating this TIFF map: offsets.length = " + offsets.length +
                    ", byteCounts.length = " + byteCounts.length + ", but number of grids = " + n);
        }
        for (int p = 0, k = 0; p < numberOfSeparatedPlanes; p++) {
            for (int yIndex = 0; yIndex < gridCountY; yIndex++) {
                for (int xIndex = 0; xIndex < gridCountX; xIndex++, k++) {
                    final TiffTileIndex tileIndex = index(xIndex, yIndex, p);
                    final TiffTile tile = get(tileIndex);
                    if (tile != null && tile.isStoredInFile()) {
                        offsets[k] = tile.getStoredInFileDataOffset();
                        byteCounts[k] = tile.getStoredInFileDataLength();
                    }
                }
            }
        }
        ifd.putDataPlacementInFileIgnoringFreeze(offsets, byteCounts);
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ 'w';
    }

    @Override
    String mapKindName() {
        return "map-for-writing" + (existing ? " (existing)" : " (new)");
    }

    // See also the analogous private method in TiffWriter
    private void logTiles(String stage, int count, long sizeInBytes, long t1, long t2) {
        if (BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            LOG.log(System.Logger.Level.TRACE, () ->
                    count == 0 ?
                            String.format(Locale.US,
                                    "%s encoded no tiles in %.3f ms",
                                    getClass().getSimpleName(),
                                    (t2 - t1) * 1e-6) :
                            String.format(Locale.US,
                                    "%s encoded %d tiles %dx%dx%d (%.3f MB) in %.3f ms, %.3f MB/s",
                                    getClass().getSimpleName(),
                                    count, numberOfChannels(), tileSizeX(), tileSizeY(),
                                    sizeInBytes / 1048576.0,
                                    (t2 - t1) * 1e-6,
                                    sizeInBytes / 1048576.0 / ((t2 - t1) * 1e-9)));
        }
    }

    private void logMatrix(
            TiffWriteMap map,
            String whatIsWritten,
            long updatingTime,
            long prewriteTime,
            long encodingTime,
            long completingTime) {
        Objects.requireNonNull(map, "Null TIFF map");
        if (BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            LOG.log(System.Logger.Level.DEBUG, () -> {
                final long totalTime = updatingTime + prewriteTime + encodingTime + completingTime;
                final long sizeInBytes = map.totalSizeInBytes();
                return String.format(Locale.US,
                        "%s wrote %s %dx%dx%d (%.3f MB) in %.3f ms = " +
                                "%.3f conversion/copying data%s" +
                                " + %.3f/%.3f encoding/completing " +
                                "(%s), %.3f MB/s",
                        getClass().getSimpleName(),
                        whatIsWritten,
                        map.dimX(), map.dimY(), map.numberOfChannels(),
                        sizeInBytes / 1048576.0,
                        totalTime * 1e-6,
                        updatingTime * 1e-6,
                        (owner.isLastMapPrewritten() ?
                                String.format(Locale.US, " + %.4f prewriting IFD", prewriteTime * 1e-6) :
                                ""),
                        encodingTime * 1e-6, completingTime * 1e-6,
                        owner.internalTimingReport(),
                        sizeInBytes / 1048576.0 / (totalTime * 1e-9));
            });
        }
    }

    private static void checkRequestedAreaInArray(byte[] array, long sizeX, long sizeY, int bitsPerPixel) {
        Objects.requireNonNull(array, "Null array");
        if (bitsPerPixel <= 0) {
            throw new IllegalArgumentException("Zero or negative bitsPerPixel = " + bitsPerPixel);
        }
        final long arrayBits = (long) array.length * 8;
        checkRequestedArea(0, 0, sizeX, sizeY);
        if (sizeX * sizeY > arrayBits || sizeX * sizeY * (long) bitsPerPixel > arrayBits) {
            throw new IllegalArgumentException("Requested area " + sizeX + "x" + sizeY +
                    " is too large for array of " + array.length + " bytes, " + bitsPerPixel + " per pixel");
        }
    }

    private static void fillByColor(Matrix<UpdatablePArray> matrix, double[] values) {
        final List<Matrix<UpdatablePArray>> layers = matrix.asLayers();
        for (int i = 0; i < layers.size(); i++) {
            final Matrix<UpdatablePArray> layer = layers.get(i);
            if (i < values.length) {
                if (layer.isFloatingPoint()) {
                    layer.array().fill(values[i]);
                } else {
                    layer.array().fill((long) values[i]);
                    // - override standard AlgART behavior: using long instead of (int) cast (important for 0xFFFFFFFF)
                }
            }
        }
    }
}
