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
import net.algart.io.awt.MatrixToImage;
import net.algart.matrices.tiff.*;
import net.algart.matrices.tiff.samples.TiffSamples;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public abstract sealed class TiffIOMap<T extends TiffIO> extends TiffMap permits TiffReadMap, TiffWriteMap {
    @FunctionalInterface
    public interface TileSupplier {
        TiffTile getTile(TiffTileIndex tiffTileIndex) throws IOException;
    }

    static final boolean BUILT_IN_TIMING = TiffIO.BUILT_IN_TIMING;
    static final System.Logger LOG = System.getLogger(TiffIOMap.class.getName());
    static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);

    private final T owner;

    public TiffIOMap(T owner, TiffIFD ifd, boolean resizable) throws TiffException {
        super(ifd, resizable);
        this.owner = Objects.requireNonNull(owner, "Null owner");
    }

    public abstract TiffReader reader();

    /**
     * Returns the associated owning reader or writer, passed to the constructor.
     * Never returns <code>null</code>.
     *
     * @return the reader-owner.
     */
    public final T owner() {
        return owner;
    }

    @SuppressWarnings("resource")
    public String streamName() {
        return owner().streamName();
    }

    public abstract boolean isExistingInFile();

    public TiffIO.CodecReport lastCodecReport() {
        return owner.lastCodecReport();
    }

    @SuppressWarnings("resource")
    public TileSupplier simpleTileSupplier() {
        return reader()::readTile;
    }

    @SuppressWarnings("resource")
    public TileSupplier cachedTileSupplier() {
        return reader()::readCachedTile;
    }

    public long fileLength() {
        //noinspection resource
        return owner().fileLength();
    }

    public byte[] loadSampleBytes(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            TiffReader.UnusualPrecisions unusualPrecisions,
            boolean storeTilesInMap)
            throws IOException {
        return loadSampleBytes(
                fromX, fromY, sizeX, sizeY, unusualPrecisions, storeTilesInMap, cachedTileSupplier());
    }

    public byte[] loadSampleBytes(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            TiffReader.UnusualPrecisions unusualPrecisions,
            boolean storeTilesInMap,
            TileSupplier tileSupplier)
            throws IOException {
        Objects.requireNonNull(unusualPrecisions, "Null unusualPrecisions");
        Objects.requireNonNull(tileSupplier, "Null tileSupplier");
        checkRequestedArea(fromX, fromY, sizeX, sizeY);
        final int sizeInBytes = sizeOfRegionWithPossibleNonStandardPrecisions(sizeX, sizeY);
        final long sizeInPixels = (long) sizeX * (long) sizeY;
        if (!isExistingInFile()) {
            throw new IllegalStateException("Image data can only be read from a TIFF map for an existing IFD, " +
                    "not for a newly created map for writing new image");
        }
        unusualPrecisions.throwIfDisabled(this);
        assert unusualPrecisions == TiffReader.UnusualPrecisions.NONE ||
                unusualPrecisions == TiffReader.UnusualPrecisions.UNPACK;
        final byte[] samples = new byte[sizeInBytes];

        @SuppressWarnings("resource") final TiffReader reader = reader();
        final boolean rescaleInt24 = reader.isRescaleWhenIncreasingBitDepth();
        final byte byteFiller = reader.getByteFiller();
        if (byteFiller != 0) {
            // - Java already zero-fills samples array
            Arrays.fill(samples, 0, sizeInBytes, byteFiller);
        }
        if (sizeX == 0 || sizeY == 0) {
            // - if no pixels are updated, no need to expand the map and to check correct expansion
            return samples;
        }

        final int mapTileSizeX = tileSizeX();
        final int mapTileSizeY = tileSizeY();
        final long bitsPerSample = alignedBitDepth();
        // - "long" here leads to stricter requirements later on
        final int numberOfSeparatedPlanes = numberOfSeparatedPlanes();
        final int samplesPerPixel = tileSamplesPerPixel();

        final boolean cropTilesToImageBoundaries = reader.isCropTilesToImageBoundaries();
        final int toX = Math.min(fromX + sizeX, cropTilesToImageBoundaries ? dimX() : Integer.MAX_VALUE);
        final int toY = Math.min(fromY + sizeY, cropTilesToImageBoundaries ? dimY() : Integer.MAX_VALUE);
        // - crop by image sizes to avoid reading unpredictable content of the boundary tiles outside the image
        final int minXIndex = Math.max(0, divFloor(fromX, mapTileSizeX));
        final int minYIndex = Math.max(0, divFloor(fromY, mapTileSizeY));
        if (minXIndex >= gridCountX() || minYIndex >= gridCountY() || toX < fromX || toY < fromY) {
            return unusualPrecisions.unpackIfNecessary(this, samples, sizeInPixels, rescaleInt24);
        }
        final int maxXIndex = Math.min(gridCountX() - 1, divFloor(toX - 1, mapTileSizeX));
        final int maxYIndex = Math.min(gridCountY() - 1, divFloor(toY - 1, mapTileSizeY));
        if (minYIndex > maxYIndex || minXIndex > maxXIndex) {
            // - possible when fromX < 0 or fromY < 0
            return unusualPrecisions.unpackIfNecessary(this, samples, sizeInPixels, rescaleInt24);
        }
        final long tileOneChannelRowSizeInBits = (long) mapTileSizeX * bitsPerSample;
        final long samplesOneChannelRowSizeInBits = (long) sizeX * bitsPerSample;

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

                    final TiffTileIndex tileIndex = index(xIndex, yIndex, p);
                    final TiffTile tile = tileSupplier.getTile(tileIndex);
                    if (storeTilesInMap) {
                        put(tile);
                    }
                    if (tile.isEmpty()) {
                        continue;
                    }
                    if (!tile.isSeparated()) {
                        throw new AssertionError(
                                "Illegal behavior of readTile: it returned interleaved tile!");
                        // - theoretically possible in subclasses
                    }
                    byte[] data = tile.getDecodedData();

                    final int tileSizeX = tile.getSizeX();
                    final int tileSizeY = tile.getSizeY();
                    final int sizeXInTile = Math.min(toX - tileStartX, tileSizeX - fromXInTile);
                    assert sizeXInTile > 0 : "sizeXInTile=" + sizeXInTile;
                    final int sizeYInTile = Math.min(toY - tileStartY, tileSizeY - fromYInTile);
                    assert sizeYInTile > 0 : "sizeYInTile=" + sizeYInTile;

                    final long partSizeXInBits = (long) sizeXInTile * bitsPerSample;
                    for (int s = 0; s < samplesPerPixel; s++) {
                        long tOffset = (((s * (long) tileSizeY) + fromYInTile)
                                * (long) tileSizeX + fromXInTile) * bitsPerSample;
                        long sOffset = (((p + s) * (long) sizeY + yDiff) * (long) sizeX + xDiff) * bitsPerSample;
                        // (long) cast is important for processing large bit matrices!
                        for (int i = 0; i < sizeYInTile; i++) {
                            assert sOffset >= 0 && tOffset >= 0 : "possibly int instead of long";
                            PackedBitArraysPer8.copyBitsNoSync(samples, sOffset, data, tOffset, partSizeXInBits);
                            tOffset += tileOneChannelRowSizeInBits;
                            sOffset += samplesOneChannelRowSizeInBits;
                        }
                    }
                }
            }
        }
        return unusualPrecisions.unpackIfNecessary(this, samples, sizeInPixels, rescaleInt24);
    }

    public byte[] readSampleBytes(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            TiffReader.UnusualPrecisions unusualPrecisions,
            boolean storeTilesInMap,
            TileSupplier tileSupplier) throws IOException {
        Objects.requireNonNull(unusualPrecisions, "Null unusualPrecisions");
        Objects.requireNonNull(tileSupplier, "Null tileSupplier");
        @SuppressWarnings("resource") final TiffReader reader = reader();
        long t1 = debugTime();
        reader.resetTiming();
        checkRequestedArea(fromX, fromY, sizeX, sizeY);
        // - note: we allow this area to be outside the image

        byte[] samples = loadSampleBytes(
                fromX, fromY, sizeX, sizeY, unusualPrecisions, storeTilesInMap, tileSupplier);
        final int sizeInBytes = samples.length;
        final long sizeInPixels = (long) sizeX * (long) sizeY;
        // - can be >2^31 for bits

        long t2 = debugTime();
        // Deprecated since 1.4.0: use readInterleavedMatrix instead of this flag
        // boolean interleave = false;
        // if (interleaveResults) {
        //     byte[] newSamples = map.toInterleavedSamples(samples, numberOfChannels, sizeInPixels);
        //     interleave = newSamples != samples;
        //     samples = newSamples;
        // }
        boolean unpackingBits = false;
        if (reader.getAutoUnpackBits().isEnabled() && isBinary()) {
            unpackingBits = true;
            samples = PackedBitArraysPer8.unpackBitsToBytes(
                    samples,
                    0,
                    sizeInPixels,
                    (byte) 0,
                    reader.getAutoUnpackBits().bit1Value());
        }
        if (BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t3 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s read %dx%dx%d samples (%.3f MB) in %.3f ms = " +
                            "%.3f read/decode " +
                            "(%s) %s, %.3f MB/s",
                    getClass().getSimpleName(),
                    sizeX, sizeY, numberOfChannels(), sizeInBytes / 1048576.0,
                    (t3 - t1) * 1e-6,
                    (t2 - t1) * 1e-6,
                    reader.internalTimingReport(),
                    unpackingBits ?
                            String.format(Locale.US, " + %.3f unpacking %d-bit",
                                    (t3 - t2) * 1e-6,
                                    alignedBitDepth()) :
                            "",
                    sizeInBytes / 1048576.0 / ((t3 - t1) * 1e-9)));
        }
        return samples;
    }

    public Object readJavaArray(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap,
            TileSupplier tileSupplier)
            throws IOException {
        @SuppressWarnings("resource") final TiffReader reader = reader();
        Objects.requireNonNull(tileSupplier, "Null tileSupplier");
        final byte[] samples = readSampleBytes(
                fromX, fromY, sizeX, sizeY,
                reader.getUnusualPrecisions().unpackIfEnabled(), storeTilesInMap, tileSupplier);
        return toJavaArray(samples);
    }

    public Matrix<UpdatablePArray> readMatrix(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap,
            TileSupplier tileSupplier)
            throws IOException {
        final Object samplesArray = readJavaArray(fromX, fromY, sizeX, sizeY, storeTilesInMap, tileSupplier);
        return asMatrix(samplesArray, sizeX, sizeY);
    }

    public Matrix<UpdatablePArray> readInterleavedMatrix(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap,
            TileSupplier tileSupplier)
            throws IOException {
        final var mergedChannels = readMatrix(fromX, fromY, sizeX, sizeY, storeTilesInMap, tileSupplier);
        return Matrices.interleave(asChannels(mergedChannels));
    }

    public List<Matrix<UpdatablePArray>> readChannels(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap,
            TileSupplier tileSupplier)
            throws IOException {
        final var mergedChannels = readMatrix(fromX, fromY, sizeX, sizeY, storeTilesInMap, tileSupplier);
        return asChannels(mergedChannels);
    }

    public BufferedImage readBufferedImage(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap,
            TileSupplier tileSupplier)
            throws IOException {
        final var mergedChannels = readMatrix(fromX, fromY, sizeX, sizeY, storeTilesInMap, tileSupplier);
        return toBufferedImage(asChannels(mergedChannels));
    }

    public Object toJavaArray(byte[] samples) {
        @SuppressWarnings("resource") final TiffReader reader = reader();
        long t1 = debugTime();
        final Object samplesArray = reader.getAutoUnpackBits().isEnabled() && isBinary() ?
                samples :
                sampleType().javaArray(samples, reader.getByteOrder());
        if (BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t2 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s converted %d bytes (%.3f MB) to %s[] in %.3f ms%s",
                    getClass().getSimpleName(),
                    samples.length, samples.length / 1048576.0,
                    samplesArray.getClass().getComponentType().getSimpleName(),
                    (t2 - t1) * 1e-6,
                    samples == samplesArray ?
                            "" :
                            String.format(Locale.US, " %.3f MB/s",
                                    samples.length / 1048576.0 / ((t2 - t1) * 1e-9))));
        }
        return samplesArray;
    }

    public Matrix<UpdatablePArray> asMatrix(Object samplesArray, int sizeX, int sizeY) {
        return TiffSamples.asMatrix(samplesArray, sizeX, sizeY, numberOfChannels(), false);
    }

    public <T extends PArray> List<Matrix<T>> asChannels(Matrix<T> mergedChannels) {
        return Matrices.asLayers(mergedChannels, TiffIFD.MAX_NUMBER_OF_CHANNELS);
    }

    public BufferedImage toBufferedImage(List<? extends Matrix<? extends PArray>> channels) {
        final Matrix<? extends PArray> interleaved = Matrices.interleave(extractFirst4Channels(channels));
        return interleavedToBufferedImage(interleaved);
    }

    public BufferedImage interleavedToBufferedImage(Matrix<? extends PArray> interleaved) {
        // Note: we do not use MatrixToImage.toBufferedImage, because we need to call setUnsignedInt32
        return new MatrixToImage.InterleavedRGBToInterleaved()
                .setUnsignedInt32(true)
                .toBufferedImage(interleaved);
    }

    public <T extends Matrix<? extends PArray>> List<T> extractFirst4Channels(List<T> image) {
        //noinspection resource
        return reader().isRemoveExtraChannelsIf5OrMoreForBufferedImage() && image.size() > 4 ?
                image.subList(0, 4) :
                image;
    }

    static long debugTime() {
        return BUILT_IN_TIMING && LOGGABLE_DEBUG ? System.nanoTime() : 0;
    }

    static int divFloor(int a, int b) {
        assert b > 0;
        return a >= 0 ? a / b : (a - b + 1) / b;
    }

}

