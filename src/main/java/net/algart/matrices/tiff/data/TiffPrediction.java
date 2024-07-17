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

package net.algart.matrices.tiff.data;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.tags.TagPredictor;
import net.algart.matrices.tiff.tiles.TiffTile;
import org.scijava.util.Bytes;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;

public class TiffPrediction {
    private TiffPrediction() {
    }

    // Analog of the function DefaultTiffService.difference
    public static void subtractPredictionIfRequested(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        final TagPredictor predictor = tile.ifd().optPredictor();
        switch (predictor) {
            case NONE -> {
            }
            case HORIZONTAL -> {
                subtractPrediction(tile);
            }
            default -> throw new TiffException("Unsupported TIFF Predictor tag: " +
                    tile.ifd().optPredictorCode() + " (" + predictor.prettyName() + ")");
        }
    }

    // Analog of the function DefaultTiffService.undifference
    public static void unsubtractPredictionIfRequested(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        final TagPredictor predictor = tile.ifd().optPredictor();
        switch (predictor) {
            case NONE -> {
            }
            case HORIZONTAL -> {
                unsubtractPrediction(tile);
            }
            default -> throw new TiffException("Unsupported TIFF Predictor tag: " +
                    tile.ifd().optPredictorCode() + " (" + predictor.prettyName() + ")");
        }
    }

    public static void subtractPrediction(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        final byte[] data = tile.getDecodedData();
        final int bytesPerSample = checkBitDepthForPrediction(tile, "for writing");
        final int samplesPerPixel = tile.samplesPerPixel();
        final int bytesPerPixel = bytesPerSample * samplesPerPixel;
        ByteOrder byteOrder = tile.byteOrder();
        final boolean little = byteOrder == ByteOrder.LITTLE_ENDIAN;
        final int xSize = tile.getSizeX();
        final int xSizeInSamples = xSize * samplesPerPixel;
        final int xSizeInBytes = xSize * bytesPerPixel;
        final int ySize = data.length / xSizeInBytes;
        // - not tile.getSizeY(): we want to process also the ending tiles in the end image strip,
        // when they are written incorrectly and contain more or less then tile.getSizeY() lines
        int k = data.length - bytesPerSample;
        long xOffset = k % xSizeInBytes;

        if (bytesPerSample == 1) {
            for (int y = 0; y < ySize; y++) {
                final int lineOffset = y * xSizeInSamples;
                int offset = lineOffset + xSize * samplesPerPixel - 1;
                // - cannot be >Integer.MAX_VALUE: see limitation for tile sizes in TiffTile.setSizes() method
                for (int minOffset = lineOffset + samplesPerPixel; offset >= minOffset; offset--) {
                    data[offset] -= data[offset - samplesPerPixel];
                }
            }
//        } else if (bytesPerPixel == 2) {
//            short[] a = JArrays.bytesToShortArray(data, tile.isLittleEndian());
//            for (int y = 0; y < ySize; y++) {
//                final int lineOffset = y * xSizeInSamples;
//                int offset = lineOffset + xSize * samplesPerPixel - 1;
//                 - cannot be >Integer.MAX_VALUE: see limitation for tile sizes in TiffTile.setSizes() method
//                for (int minOffset = lineOffset + samplesPerPixel; offset >= minOffset; offset--) {
//                    a[offset] -= a[offset - samplesPerPixel];
//                }
//            }
//            shortArrayToBytes(a, a.length, tile.isLittleEndian());
        } else {
            for (; k >= 0; k -= bytesPerSample) {
                if (k / bytesPerPixel % xSize == 0) {
                    continue;
                }
                int value = Bytes.toInt(data, k, bytesPerSample, little);
                value -= Bytes.toInt(data, k - bytesPerPixel, bytesPerSample, little);
                Bytes.unpack(value, data, k, bytesPerSample, little);
            }
        }
    }

    public static void unsubtractPrediction(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        final byte[] data = tile.getDecodedData();
        final int bytesPerSample = checkBitDepthForPrediction(tile, "for reading");
        final int bytesPerPixel = bytesPerSample * tile.samplesPerPixel();
        ByteOrder byteOrder = tile.byteOrder();
        final boolean little = byteOrder == ByteOrder.LITTLE_ENDIAN;
        final long xSize = tile.getSizeX();
        final long xSizeInBytes = xSize * bytesPerPixel;

        int k = 0;
        long xOffset = 0;

        if (bytesPerSample == 1) {
            for (; k <= data.length - 1; k++, xOffset++) {
                if (xOffset == xSizeInBytes) {
                    xOffset = 0;
                }
//                    assert (k / bytesPerPixel % xSize == 0) == (xOffset < bytesPerPixel);
                if (xOffset >= bytesPerPixel) {
                    data[k] += data[k - bytesPerPixel];
                }
            }
        } else {
            for (; k <= data.length - bytesPerSample; k += bytesPerSample) {
                if (k / bytesPerPixel % xSize == 0) {
                    continue;
                }
                int value = Bytes.toInt(data, k, bytesPerSample, little);
                value += Bytes.toInt(data, k - bytesPerPixel, bytesPerSample, little);
                Bytes.unpack(value, data, k, bytesPerSample, little);
            }
        }
    }

    private static int checkBitDepthForPrediction(TiffTile tile, String where) throws TiffException {
        final int bitsPerSample = tile.bitsPerSample();
        if (bitsPerSample != 8 && bitsPerSample != 16 && bitsPerSample != 32 && bitsPerSample != 64) {
            throw new TiffException("Cannot use TIFF prediction " + where +
                    " for bit depth " + bitsPerSample + ": " +
                    Arrays.toString(tile.ifd().getBitsPerSample()) + " bits/pixel");
        }
        return bitsPerSample >>> 3;
    }
}
