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
import net.algart.arrays.UpdatablePArray;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;

import java.awt.image.BufferedImage;
import java.io.IOException;
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
    @Override
    public TiffReader owner() {
        return owner;
    }

    @Override
    public TiffReader reader() {
        return owner;
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

    public byte[] readSamples() throws IOException {
        return owner.readSamples(this);
    }

    public byte[] readSamples(int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return owner.readSamples(this, fromX, fromY, sizeX, sizeY);
    }

    public Object readJavaArray() throws IOException {
        return owner.readJavaArray(this);
    }

    public Object readJavaArray(int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return owner.readJavaArray(this, fromX, fromY, sizeX, sizeY);
    }

    public Matrix<UpdatablePArray> readMatrix() throws IOException {
        return owner.readMatrix(this);
    }

    public Matrix<UpdatablePArray> readMatrix(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return owner.readMatrix(this, fromX, fromY, sizeX, sizeY);
    }

    public Matrix<UpdatablePArray> readInterleavedMatrix() throws IOException {
        return owner.readInterleavedMatrix(this);
    }

    public Matrix<UpdatablePArray> readInterleavedMatrix(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return owner.readInterleavedMatrix(this, fromX, fromY, sizeX, sizeY);
    }

    public List<Matrix<UpdatablePArray>> readChannels() throws IOException {
        return owner.readChannels(this);
    }

    public List<Matrix<UpdatablePArray>> readChannels(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return owner.readChannels(this, fromX, fromY, sizeX, sizeY);
    }

    public BufferedImage readBufferedImage() throws IOException {
        return owner.readBufferedImage(this);
    }

    public BufferedImage readBufferedImage(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return owner.readBufferedImage(this, fromX, fromY, sizeX, sizeY);
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
}
