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

import net.algart.arrays.Matrix;
import net.algart.arrays.UpdatablePArray;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

public final class TiffReadMap extends TiffIOMap<TiffReader> {
    private final TiffReader reader;
    // - identical to super.owner

    public TiffReadMap(TiffReader owner, TiffIFD ifd) {
        super(owner, ifd, false);
        if (!ifd.isLoadedFromFile()) {
            throw new IllegalArgumentException("IFD must be read from TIFF file");
        }
        this.reader = owner;
    }

    @Override
    public TiffReader reader() {
        return owner();
    }

    @Override
    public boolean isExisting() {
        return true;
    }

    public int numberOfImages() {
        return reader.numberOfImages();
    }

    public TiffReader.UnpackBits getAutoUnpackBits() {
        return reader.getAutoUnpackBits();
    }

    public boolean isScaleWhenIncreasingBitDepth() {
        return reader.isScaleWhenIncreasingBitDepth();
    }

    public boolean isCorrectColors() {
        return reader.isCorrectColors();
    }

    public byte[] loadSampleBytes() throws IOException {
        return loadSampleBytes(0, 0, dimX(), dimY());
    }

    public byte[] loadSampleBytes(int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return loadSampleBytes(fromX, fromY, sizeX, sizeY, reader.getUnusualPrecisions());
    }

    public byte[] loadSampleBytes(
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            TiffReader.UnusualPrecisions unusualPrecisions) throws IOException {
        return loadSampleBytes(fromX, fromY, sizeX, sizeY, unusualPrecisions, false);
    }

    public byte[] readSampleBytes() throws IOException {
        return reader.readSampleBytes(this);
    }

    public byte[] readSampleBytes(int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return reader.readSampleBytes(this, fromX, fromY, sizeX, sizeY);
    }

    public Object readJavaArray() throws IOException {
        return reader.readJavaArray(this);
    }

    public Object readJavaArray(int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return reader.readJavaArray(this, fromX, fromY, sizeX, sizeY);
    }

    public Matrix<UpdatablePArray> readMatrix() throws IOException {
        return reader.readMatrix(this);
    }

    public Matrix<UpdatablePArray> readMatrix(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return reader.readMatrix(this, fromX, fromY, sizeX, sizeY);
    }

    public Matrix<UpdatablePArray> readInterleavedMatrix() throws IOException {
        return reader.readInterleavedMatrix(this);
    }

    public Matrix<UpdatablePArray> readInterleavedMatrix(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return reader.readInterleavedMatrix(this, fromX, fromY, sizeX, sizeY);
    }

    public List<Matrix<UpdatablePArray>> readChannels() throws IOException {
        return reader.readChannels(this);
    }

    public List<Matrix<UpdatablePArray>> readChannels(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return reader.readChannels(this, fromX, fromY, sizeX, sizeY);
    }

    public BufferedImage readBufferedImage() throws IOException {
        return reader.readBufferedImage(this);
    }

    public BufferedImage readBufferedImage(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return reader.readBufferedImage(this, fromX, fromY, sizeX, sizeY);
    }

    public TiffTile readCachedTile(TiffTileIndex tileIndex) throws IOException {
        return reader.readCachedTile(tileIndex);
    }

    public TiffTile readTile(TiffTileIndex tileIndex) throws IOException {
        return reader.readTile(tileIndex);
    }

    public TiffTile readEncodedTile(TiffTileIndex tileIndex) throws IOException {
        return reader.readEncodedTile(tileIndex);
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
