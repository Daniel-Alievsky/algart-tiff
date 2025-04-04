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

package net.algart.matrices.tiff.tests.misc;

import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffSampleType;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;

public class TiffTileTest {
    private static void testMap(int numberOfChannels, TiffSampleType sampleType, int testDataLength) {
        TiffIFD ifd = new TiffIFD();
        ifd.defaultTileSizes();
        ifd.putPixelInformation(numberOfChannels, sampleType);
        System.out.printf("%s%n", ifd.toString(TiffIFD.StringFormat.NORMAL));
        TiffTile tile = new TiffMap(ifd, true).getOrNew(0, 0);

        tile.setPartiallyDecodedData(new byte[testDataLength - 1]);
        System.out.printf("%s: %s decoded length, %d estimated pixels%n",
                tile, tile.getDecodedDataLength(), tile.getEstimatedNumberOfPixels());
        try {
            tile.checkStoredNumberOfPixels();
        } catch (IllegalStateException e) {
            System.out.println(e.getMessage());
        }
        try {
            tile.checkDataLengthAlignment();
        } catch (IllegalStateException e) {
            System.out.println(e.getMessage());
        }

        tile.setDecodedData(new byte[testDataLength]);
        System.out.printf("%s: %s decoded length, %d estimated pixels%n",
                tile, tile.getDecodedDataLength(), tile.getEstimatedNumberOfPixels());
//         tile.setStoredInFileDataRange(0, 111);
        // - uncomment the previos operator to see another possible exception
        try {
            tile.checkStoredNumberOfPixels();
        } catch (IllegalStateException e) {
            System.out.println(e.getMessage());
        }
        tile.checkDataLengthAlignment();

        tile.setDecodedData(new byte[tile.getSizeInBytes()]);
        System.out.printf("%s: %s decoded length, %d estimated pixels%n",
                tile, tile.getDecodedDataLength(), tile.getEstimatedNumberOfPixels());
        tile.checkDataLengthAlignment();
        tile.checkStoredNumberOfPixels();
        System.out.println();
    }


    public static void main(String[] args) {
        testMap(1, TiffSampleType.BIT, 157);
        testMap(3, TiffSampleType.BIT, 156);
        testMap(1, TiffSampleType.UINT8, 157);
        testMap(6, TiffSampleType.UINT8, 162);
        testMap(1, TiffSampleType.UINT16, 156);
        testMap(3, TiffSampleType.UINT16, 156);
    }
}
