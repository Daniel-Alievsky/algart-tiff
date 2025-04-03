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

public final class TiffMapForReading extends TiffMap {
    @FunctionalInterface
    public interface TileSupplier {
        TiffTile getTile(TiffTileIndex tiffTileIndex) throws IOException;
    }

    private final TiffReader owningReader;

    public TiffMapForReading(TiffReader owningReader, TiffIFD ifd) {
        super(ifd, false);
        this.owningReader = Objects.requireNonNull(owningReader, "Null owning reader");
    }

    /**
     * Returns the associated owning reader, passed to the constructor.
     * Never returns <code>null</code>.
     *
     * @return the reader-owner.
     */
    public TiffReader reader() {
        return owningReader;
    }

    public Object readJavaArray() throws IOException {
        return owningReader.readJavaArray(this);
    }

    public Object readJavaArray(int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return owningReader.readJavaArray(this, fromX, fromY, sizeX, sizeY);
    }

    public Matrix<UpdatablePArray> readMatrix() throws IOException {
        return owningReader.readMatrix(this);
    }

    public Matrix<UpdatablePArray> readMatrix(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return owningReader.readMatrix(this, fromX, fromY, sizeX, sizeY);
    }

    public List<Matrix<UpdatablePArray>> readChannels() throws IOException {
        return owningReader.readChannels(this);
    }

    public List<Matrix<UpdatablePArray>> readChannels(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return owningReader.readChannels(this, fromX, fromY, sizeX, sizeY);
    }

    public BufferedImage readBufferedImage() throws IOException {
        return owningReader.readBufferedImage(this);
    }

    public BufferedImage readBufferedImage(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return owningReader.readBufferedImage(this, fromX, fromY, sizeX, sizeY);
    }

    public byte[] loadSamples(
            TileSupplier tileSupplier,
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap)
            throws IOException {
        Objects.requireNonNull(tileSupplier, "Null tile supplier");
        TiffReader.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        final int sizeInBytes = sizeOfRegionWithPossibleNonStandardPrecisions(sizeX, sizeY);
        final byte[] samples = new byte[sizeInBytes];

        final byte byteFiller = owningReader.getByteFiller();
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

        final boolean cropTilesToImageBoundaries = owningReader.isCropTilesToImageBoundaries();
        final int toX = Math.min(fromX + sizeX, cropTilesToImageBoundaries ? dimX() : Integer.MAX_VALUE);
        final int toY = Math.min(fromY + sizeY, cropTilesToImageBoundaries ? dimY() : Integer.MAX_VALUE);
        // - crop by image sizes to avoid reading unpredictable content of the boundary tiles outside the image
        final int minXIndex = Math.max(0, divFloor(fromX, mapTileSizeX));
        final int minYIndex = Math.max(0, divFloor(fromY, mapTileSizeY));
        if (minXIndex >= gridCountX() || minYIndex >= gridCountY() || toX < fromX || toY < fromY) {
            return samples;
        }
        final int maxXIndex = Math.min(gridCountX() - 1, divFloor(toX - 1, mapTileSizeX));
        final int maxYIndex = Math.min(gridCountY() - 1, divFloor(toY - 1, mapTileSizeY));
        if (minYIndex > maxYIndex || minXIndex > maxXIndex) {
            // - possible when fromX < 0 or fromY < 0
            return samples;
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

                    final TiffTileIndex tileIndex = multiPlaneIndex(p, xIndex, yIndex);
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
        return samples;
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
