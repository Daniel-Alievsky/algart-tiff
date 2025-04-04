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

package net.algart.matrices.tiff.data;

import net.algart.arrays.PackedBitArraysPer8;
import net.algart.arrays.TooLargeArrayException;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.util.Objects;

public class TiffPacking {
    private TiffPacking() {
    }

    public static boolean packTiffBits(TiffTile tile) {
        Objects.requireNonNull(tile);
        if (tile.bitsPerPixel() != 1) {
            return false;
        }
        assert tile.samplesPerPixel() == 1 : "> 1 channel in " + tile;
        // Note: we do not check tile.ifd().getPhotometricInterpretation().isInvertedBrightness(),
        // because WHITE_IS_ZERO photometric interpretation is not supported;
        // in any case, we do not provide any processing of non-standard photometric interpretations
        // for writing any other sample types.
        final byte[] source = tile.getDecodedData();
        final int sizeInBytes = tile.getSizeInBytes();
        if (source.length < sizeInBytes) {
            throw new IllegalArgumentException("Stored data length " + source.length +
                    " does not match tile sizes: " + tile);
        }
        final int sizeX = tile.getSizeX();
        final int sizeY = tile.getSizeY();
        assert tile.getSizeInPixels() == sizeX * sizeY;
        final long alignedLine = ((long) sizeX + 7) & ~7;
        // - skipping bits until the first bit of the next whole byte;
        // note that it may be !=sizeX only in non-tiled TIFF (when "tile width" is the width of the whole image)
        final long length = ((long) sizeY * alignedLine) >>> 3;
        if (length > Integer.MAX_VALUE) {
            // - very improbable: (sizeX*sizeY)/8 < 2^31, but (alignedLine*sizeY)/8 >= 2^31
            throw new TooLargeArrayException("Too large requested TIFF binary image " + sizeX + "x" + sizeY +
                    ": cannot fit into 2^31 bytes after aligning each line");
        }
        final byte[] result = new byte[(int) length];
        // - padded by zeros
        long sOffset = 0;
        long tOffset = 0;
        for (int yIndex = 0; yIndex < sizeY; yIndex++, sOffset += sizeX, tOffset += alignedLine) {
            PackedBitArraysPer8.copyBitsFromNormalToReverseOrderNoSync(result, tOffset, source, sOffset, sizeX);
        }
        tile.setPartiallyDecodedData(result);
        return true;
    }
}
