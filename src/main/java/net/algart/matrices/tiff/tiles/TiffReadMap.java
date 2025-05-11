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
import net.algart.arrays.UpdatablePArray;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class TiffReadMap extends TiffIOMap {
    @FunctionalInterface
    public interface TileSupplier {
        TiffTile getTile(TiffTileIndex tiffTileIndex) throws IOException;
    }

    private final TiffReader owner;

    public TiffReadMap(TiffReader owner, TiffIFD ifd) {
        super(ifd, false);
        this.owner = Objects.requireNonNull(owner, "Null owning reader");
    }

    /**
     * Returns the associated owning reader, passed to the constructor.
     * Never returns <code>null</code>.
     *
     * @return the reader-owner.
     */
    public TiffReader reader() {
        return owner;
    }

    public long fileLength() {
        try {
            return owner.input().length();
        } catch (IOException e) {
            // - very improbable, it is better just to return something
            return 0;
        }
    }

    public TiffReader.UnpackBits getAutoUnpackBits() {
        return owner.getAutoUnpackBits();
    }

    public boolean isAutoScaleWhenIncreasingBitDepth() {
        return owner.isAutoScaleWhenIncreasingBitDepth();
    }

    public byte[] loadSamples() throws IOException {
        return loadSamples(0, 0, dimX(), dimY());
    }

    public byte[] loadSamples(int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return loadSamples(fromX, fromY, sizeX, sizeY, owner.getUnusualPrecisions());
    }

    public byte[] loadSamples(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            TiffReader.UnusualPrecisions unusualPrecisions) throws IOException {
        return loadSamples(fromX, fromY, sizeX, sizeY, unusualPrecisions, false);
    }

    public byte[] loadSamples(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            TiffReader.UnusualPrecisions unusualPrecisions,
            boolean storeTilesInMap)
            throws IOException {
        return loadSamples(
                owner::readCachedTile, fromX, fromY, sizeX, sizeY, unusualPrecisions, storeTilesInMap);
    }

    public byte[] loadSamples(
            TileSupplier tileSupplier,
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            TiffReader.UnusualPrecisions unusualPrecisions,
            boolean storeTilesInMap)
            throws IOException {
        Objects.requireNonNull(tileSupplier, "Null tile supplier");
        Objects.requireNonNull(unusualPrecisions, "Null unusualPrecisions");
        checkRequestedArea(fromX, fromY, sizeX, sizeY);
        final int sizeInBytes = sizeOfRegionWithPossibleNonStandardPrecisions(sizeX, sizeY);
        final long sizeInPixels = (long) sizeX * (long) sizeY;
        unusualPrecisions.throwIfDisabled(this);
        assert unusualPrecisions == TiffReader.UnusualPrecisions.NONE ||
                unusualPrecisions == TiffReader.UnusualPrecisions.UNPACK;
        final byte[] samples = new byte[sizeInBytes];

        final byte byteFiller = owner.getByteFiller();
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
        final long bitsPerSample = alignedBitsPerSample();
        // - "long" here leads to stricter requirements later on
        final int numberOfSeparatedPlanes = numberOfSeparatedPlanes();
        final int samplesPerPixel = tileSamplesPerPixel();

        final boolean cropTilesToImageBoundaries = owner.isCropTilesToImageBoundaries();
        final int toX = Math.min(fromX + sizeX, cropTilesToImageBoundaries ? dimX() : Integer.MAX_VALUE);
        final int toY = Math.min(fromY + sizeY, cropTilesToImageBoundaries ? dimY() : Integer.MAX_VALUE);
        // - crop by image sizes to avoid reading unpredictable content of the boundary tiles outside the image
        final int minXIndex = Math.max(0, divFloor(fromX, mapTileSizeX));
        final int minYIndex = Math.max(0, divFloor(fromY, mapTileSizeY));
        if (minXIndex >= gridCountX() || minYIndex >= gridCountY() || toX < fromX || toY < fromY) {
            return unusualPrecisions.unpackIfNecessary(this, samples, sizeInPixels);
        }
        final int maxXIndex = Math.min(gridCountX() - 1, divFloor(toX - 1, mapTileSizeX));
        final int maxYIndex = Math.min(gridCountY() - 1, divFloor(toY - 1, mapTileSizeY));
        if (minYIndex > maxYIndex || minXIndex > maxXIndex) {
            // - possible when fromX < 0 or fromY < 0
            return unusualPrecisions.unpackIfNecessary(this, samples, sizeInPixels);
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
                        throw new AssertionError("Illegal behavior of readTile: it returned interleaved tile!");
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
        return unusualPrecisions.unpackIfNecessary(this, samples, sizeInPixels);
    }

    public byte[] readSamples() throws IOException {
        return owner.readSamples(this);
    }

    public byte[] readSamples(int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return owner.readSamples(this, fromX, fromY, sizeX, sizeY);
    }

    public byte[] readSamples(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            TiffReader.UnusualPrecisions autoUnpackUnusualPrecisions,
            boolean storeTilesInMap) throws IOException {
        return owner.readSamples(
                this,
                fromX,
                fromY,
                sizeX,
                sizeY,
                autoUnpackUnusualPrecisions,
                storeTilesInMap);
    }

    public Object readJavaArray() throws IOException {
        return owner.readJavaArray(this);
    }

    public Object readJavaArray(int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return owner.readJavaArray(this, fromX, fromY, sizeX, sizeY);
    }

    public Object readJavaArray(int fromX, int fromY, int sizeX, int sizeY, boolean storeTilesInMap)
            throws IOException {
        return owner.readJavaArray(this, fromX, fromY, sizeX, sizeY, storeTilesInMap);
    }

    public Matrix<UpdatablePArray> readMatrix() throws IOException {
        return owner.readMatrix(this);
    }

    public Matrix<UpdatablePArray> readMatrix(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return owner.readMatrix(this, fromX, fromY, sizeX, sizeY);
    }

    public Matrix<UpdatablePArray> readMatrix(int fromX, int fromY, int sizeX, int sizeY, boolean storeTilesInMap)
            throws IOException {
        return owner.readMatrix(this, fromX, fromY, sizeX, sizeY, storeTilesInMap);
    }

    public Matrix<UpdatablePArray> readInterleavedMatrix() throws IOException {
        return owner.readInterleavedMatrix(this);
    }

    public Matrix<UpdatablePArray> readInterleavedMatrix(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return owner.readInterleavedMatrix(this, fromX, fromY, sizeX, sizeY);
    }

    public Matrix<UpdatablePArray> readInterleavedMatrix(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap)
            throws IOException {
        return owner.readInterleavedMatrix(this, fromX, fromY, sizeX, sizeY, storeTilesInMap);
    }

    public List<Matrix<UpdatablePArray>> readChannels() throws IOException {
        return owner.readChannels(this);
    }

    public List<Matrix<UpdatablePArray>> readChannels(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return owner.readChannels(this, fromX, fromY, sizeX, sizeY);
    }

    public List<Matrix<UpdatablePArray>> readChannels(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap)
            throws IOException {
        return owner.readChannels(this, fromX, fromY, sizeX, sizeY, storeTilesInMap);
    }

    public BufferedImage readBufferedImage() throws IOException {
        return owner.readBufferedImage(this);
    }

    public BufferedImage readBufferedImage(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return owner.readBufferedImage(this, fromX, fromY, sizeX, sizeY);
    }

    public BufferedImage readBufferedImage(int fromX, int fromY, int sizeX, int sizeY, boolean storeTilesInMap)
            throws IOException {
        return owner.readBufferedImage(this, fromX, fromY, sizeX, sizeY, storeTilesInMap);
    }

    public TiffTile readCachedTile(TiffTileIndex tileIndex) throws IOException {
        return owner.readCachedTile(tileIndex);
    }

    public TiffTile readTile(TiffTileIndex tileIndex) throws IOException {
        return owner.readTile(tileIndex);
    }

    public TiffTile readEncodedTile(TiffTileIndex tileIndex) throws IOException {
        return owner.readEncodedTile(tileIndex);
    }

    @Override
    public int hashCode() {
        return super.hashCode() ^ 'r';
    }

    @Override
    String mapKindName() {
        return "map-for-reading";
    }

    static int divFloor(int a, int b) {
        assert b > 0;
        return a >= 0 ? a / b : (a - b + 1) / b;
    }
}
