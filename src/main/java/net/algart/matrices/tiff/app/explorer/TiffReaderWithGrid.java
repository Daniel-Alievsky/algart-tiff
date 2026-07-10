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

package net.algart.matrices.tiff.app.explorer;

import net.algart.arrays.Matrix;
import net.algart.arrays.PackedBitArraysPer8;
import net.algart.arrays.UpdatablePArray;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.matrices.tiff.tiles.TiffTileIndex;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;

class TiffReaderWithGrid extends TiffReader {
    private static final Color GRID_COLOR = new Color(0, 0, 205);

    private boolean viewTileGrid = false;
    private int tileGridThickness = 1;

    TiffReaderWithGrid(Path tiffFile) throws IOException {
        super(tiffFile);
    }

    public boolean isViewTileGrid() {
        return viewTileGrid;
    }

    public TiffReaderWithGrid setViewTileGrid(boolean viewTileGrid) {
        this.viewTileGrid = viewTileGrid;
        return this;
    }

    public int getTileGridThickness() {
        return tileGridThickness;
    }

    public TiffReaderWithGrid setTileGridThickness(int tileGridThickness) {
        if (tileGridThickness < 0) {
            throw new IllegalArgumentException("Negative tileGridThickness: " + tileGridThickness);
        }
        this.tileGridThickness = tileGridThickness;
        return this;
    }

    @Override
    public TiffTile readTile(TiffTileIndex tileIndex) throws IOException {
        final TiffTile tile = super.readTile(tileIndex);
        if (viewTileGrid && tileGridThickness > 0) {
            addTileBorder(tile);
        }
        return tile;
    }

    private void addTileBorder(TiffTile tile) {
        if (!tile.isSeparated()) {
            throw new AssertionError("Tile is not separated");
        }
        final int gap = Math.min(this.tileGridThickness, Math.min(tile.getSizeX(), tile.getSizeY()) / 2);
        if (!tile.isRarePrecision()) {
            final Matrix<UpdatablePArray> m = tile.getUnpackedMatrix();
            final double[] leftTop = tile.colorToChannelValues(GRID_COLOR, true);
            final double[] rightBottom = tile.colorToChannelValues(Color.BLACK, true);
            for (int c = 0; c < tile.samplesPerPixel(); c++) {
                m.subMatr(0, 0, c, m.dimX(), gap, 1).array().fill(leftTop[c]);
                m.subMatr(0, 0, c, gap, m.dimY(), 1).array().fill(leftTop[c]);
                m.subMatr(m.dimX() - gap, 0, c, gap, m.dimY(), 1).array().fill(rightBottom[c]);
                m.subMatr(0, m.dimY() - gap, c, m.dimX(), gap, 1).array().fill(rightBottom[c]);
            }
            tile.setUnpackedMatrix(m);
        } else {
            // - low-level algorithm for any precision (always black lines)
            final byte[] data = tile.getDecodedData();
            final int bitDepth = tile.normalizedBitDepth();
            final int sizeX = tile.getSizeX() * bitDepth;
            final int sizeY = tile.getSizeY();
            final int sizeInBits = sizeX * sizeY;
            final int filledSamples = bitDepth * gap;
            final int filledLines = sizeX * gap;
            for (int c = 0; c < Math.min(3, tile.samplesPerPixel()); c++) {
                // - channel #3 is usually alpha: do not change it
                int disp = c * sizeInBits;
                PackedBitArraysPer8.fillBits(data, disp, filledLines, false);
                PackedBitArraysPer8.fillBits(data, disp + sizeInBits - filledLines, filledLines, false);
                for (int y = 0; y < sizeY; y++, disp += sizeX) {
                    PackedBitArraysPer8.fillBits(data, disp, filledSamples, false);
                    PackedBitArraysPer8.fillBits(data, disp + sizeX - filledSamples, filledSamples, false);
                }
            }
        }
    }
}
