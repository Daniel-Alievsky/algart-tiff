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

import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.UpdatablePArray;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

public final class TiffReadMap extends TiffIOMap<TiffReader> {
    private final TiffReader reader;
    // - identical to super.owner

    public TiffReadMap(TiffReader owner, TiffIFD ifd) throws TiffException {
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
    public boolean isExistingInFile() {
        return true;
    }

    public int numberOfImagesUnchecked() {
        return reader.numberOfImagesUnchecked();
    }

    public boolean isRescaleWhenIncreasingBitDepth() {
        return reader.isRescaleWhenIncreasingBitDepth();
    }

    public boolean isColorCorrection() {
        return reader.isColorCorrection();
    }

    @Override
    public TiffReadMap setAutoUnpackBits(UnpackBits autoUnpackBits) {
        super.setAutoUnpackBits(autoUnpackBits);
        return this;
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
        return readSampleBytes(0, 0, dimX(), dimY());
    }

    public byte[] readSampleBytes(int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return readSampleBytes(
                fromX, fromY, sizeX, sizeY,
                reader.getUnusualPrecisions(),
                false,
                this::readCachedTile);
    }

    public Object readJavaArray() throws IOException {
        return readJavaArray(0, 0, dimX(), dimY());
    }

    public Object readJavaArray(int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return readJavaArray(fromX, fromY, sizeX, sizeY, false, this::readCachedTile);
    }

    /**
     * Reads the full image with the specified TIFF map.
     * The result is a 3-dimensional matrix, where each 2-dimensional {@link Matrices#asLayers(Matrix) layer}
     * contains one of color channels.
     * In other words, the samples are returned in a separated form: RRR...GGG...BBB...
     *
     * @return content of the IFD image.
     * @throws TiffException            if <code>ifdIndex</code> is too large,
     *                                  or if the file is not a correct TIFF file,
     *                                  and this was not detected while opening it.
     * @throws IOException              in the case of any problems with the input file.
     * @throws IllegalArgumentException if <code>ifdIndex&lt;0</code>.
     */
    public Matrix<UpdatablePArray> readMatrix() throws IOException {
        return readMatrix(0, 0, dimX(), dimY());
    }

    public Matrix<UpdatablePArray> readMatrix(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return readMatrix(fromX, fromY, sizeX, sizeY, false, this::readCachedTile);
    }

    public Matrix<UpdatablePArray> readInterleavedMatrix() throws IOException {
        return readInterleavedMatrix(0, 0, dimX(), dimY());
    }

    public Matrix<UpdatablePArray> readInterleavedMatrix(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return readInterleavedMatrix(fromX, fromY, sizeX, sizeY, false, this::readCachedTile);
    }

    /**
     * Reads the full image with the specified TIFF map as a list of 2-dimensional matrices containing color channels.
     * For example, for the RGB image, the result will be a list of three matrices R, G, B.
     *
     * @return content of the TIFF image.
     * @throws TiffException if the file is not a correct TIFF file,
     *                       and this was not detected while opening it.
     * @throws IOException   in the case of any other problems with the input file.
     */
    public List<Matrix<UpdatablePArray>> readChannels() throws IOException {
        return readChannels(0, 0, dimX(), dimY());
    }

    public List<Matrix<UpdatablePArray>> readChannels(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return readChannels(fromX, fromY, sizeX, sizeY, false, this::readCachedTile);
    }

    /**
     * Reads the full image with the specified TIFF map as <code>BufferedImage</code>.
     * For example, for the RGB image, the result will be a list of three matrices R, G, B.
     *
     * @return content of the TIFF image.
     * @throws TiffException if the file is not a correct TIFF file,
     *                       and this was not detected while opening it.
     * @throws IOException   in the case of any other problems with the input file.
     */
    public BufferedImage readBufferedImage() throws IOException {
        return readBufferedImage(0, 0, dimX(), dimY());
    }

    public BufferedImage readBufferedImage(int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return readBufferedImage(fromX, fromY, sizeX, sizeY, false, this::readCachedTile);
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

    public TiffTile readEncodedTile(TiffTileIndex tileIndex, boolean resolveDuplicates) throws IOException {
        return reader.readEncodedTile(tileIndex, resolveDuplicates);
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
