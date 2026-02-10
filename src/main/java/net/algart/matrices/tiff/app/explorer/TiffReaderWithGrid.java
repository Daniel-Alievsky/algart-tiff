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

import net.algart.arrays.PackedBitArraysPer8;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.matrices.tiff.tiles.TiffTileIndex;

import java.io.IOException;
import java.nio.file.Path;

class TiffReaderWithGrid extends TiffReader {
    private boolean viewTileGrid = false;

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

    @Override
    public TiffTile readTile(TiffTileIndex tileIndex) throws IOException {
        final TiffTile tile = super.readTile(tileIndex);
        if (viewTileGrid) {
            addTileBorder(tile);
        }
        return tile;
    }

    private static void addTileBorder(TiffTile tile) {
        if (!tile.isSeparated()) {
            throw new AssertionError("Tile is not separated");
        }
        byte[] decoded = tile.getDecodedData();
        final int sample = tile.bitsPerSample();
        final int sizeX = tile.getSizeX() * sample;
        final int sizeY = tile.getSizeY();
        final int sizeInBits = sizeX * sizeY;
        for (int c = 0; c < tile.samplesPerPixel(); c++) {
            int disp = c * sizeInBits;
            PackedBitArraysPer8.fillBits(decoded, disp, sizeX, false);
            PackedBitArraysPer8.fillBits(decoded, disp + sizeInBits - sizeX, sizeX, false);
            for (int k = 0; k < sizeY; k++, disp += sizeX) {
                PackedBitArraysPer8.fillBits(decoded, disp, sample, false);
                PackedBitArraysPer8.fillBits(decoded, disp + sizeX - sample, sample, false);
            }
        }
    }
}
