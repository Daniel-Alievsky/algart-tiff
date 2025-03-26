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

import net.algart.arrays.JArrays;
import net.algart.arrays.PackedBitArraysPer8;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.tags.TagPredictor;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.util.Objects;

/**
 * Processing TIFF Tag Predictor.
 *
 * <p>Note that we support a non-standard case of prediction for binary images (1 sample, 1 bit/pixel).
 * Images, written with this prediction, will not be readable by usual TIFF applications.
 * We do not recommend this mode for creating new TIFF images.</p>
 */
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
            case HORIZONTAL -> subtractPrediction(tile);
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
            case HORIZONTAL -> unsubtractPrediction(tile);
            default -> throw new TiffException("Unsupported TIFF Predictor tag: " +
                    tile.ifd().optPredictorCode() + " (" + predictor.prettyName() + ")");
        }
    }

    public static void subtractPrediction(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        final byte[] data = tile.getDecodedData();
        final int bitsPerSample = tile.bitsPerSample();
        checkBitDepthForPrediction(bitsPerSample, "for writing");
        final int samplesPerPixel = tile.samplesPerPixel();
        final int xSize = tile.getSizeX();
        final int xSizeInBytes = tile.getLineSizeInBytesInsideTIFF();
        // - getRowSizeInBytesInsideTIFF, because this method is called at the last stage
        // after repacking bits according TIFF format
        final int ySize = data.length / xSizeInBytes;
        // - not tile.getSizeY(): we want to process also the ending tiles in the end image strip,
        // when they are written incorrectly and contain more or less then tile.getSizeY() lines

        switch (bitsPerSample) {
            case 1 -> {
                final int xSizeInBits = xSizeInBytes * 8;
                final boolean[] a = PackedBitArraysPer8.unpackBitsInReverseOrder(
                        data, 0, (long) xSizeInBits * ySize);
                subtractBooleanMatrix(a, xSizeInBits, ySize, samplesPerPixel);
                PackedBitArraysPer8.packBitsInReverseOrder(data, 0, a, 0, a.length);
            }
            case 8 -> subtractByteMatrix(data, xSize, ySize, samplesPerPixel);
            case 16 -> {
                final short[] a = JArrays.bytesToShortArray(data, tile.byteOrder());
                subtractShortMatrix(a, xSize, ySize, samplesPerPixel);
                JArrays.shortArrayToBytes(data, a, a.length, tile.byteOrder());
            }
            case 32 -> {
                final int[] a = JArrays.bytesToIntArray(data, tile.byteOrder());
                subtractIntMatrix(a, xSize, ySize, samplesPerPixel);
                JArrays.intArrayToBytes(data, a, a.length, tile.byteOrder());
            }
            case 64 -> {
                final long[] a = JArrays.bytesToLongArray(data, tile.byteOrder());
                subtractLongMatrix(a, xSize, ySize, samplesPerPixel);
                JArrays.longArrayToBytes(data, a, a.length, tile.byteOrder());
            }
            default -> throw new AssertionError("Must be checked in checkBitDepthForPrediction");
        }
        // Legacy solution:
//            for (int k = data.length - bytes; k >= 0; k--, xOffset--) {
//                if (xOffset < 0) {
//                    xOffset += xSizeInBytes;
//                }
//                // assert (k / len % xSize == 0) == (xOffset < bytesPerPixel);
//                if (xOffset >= bytesPerPixel) {
//                    data[k] -= data[k - bytesPerPixel];
//                }
//            }
    }

    public static void unsubtractPrediction(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        final byte[] data = tile.getDecodedData();
        final int bitsPerSample = tile.bitsPerSample();
        checkBitDepthForPrediction(bitsPerSample, "for reading");
        final int samplesPerPixel = tile.samplesPerPixel();
        final int xSize = tile.getSizeX();
        final int xSizeInBytes = tile.getLineSizeInBytesInsideTIFF();
        // - getLineSizeInBytesInsideTIFF, because this method is called at the first stage
        // before repacking bits from TIFF format (byte-aligned rows) into AlgART rules (no aligning)
        final int ySize = data.length / xSizeInBytes;

        switch (bitsPerSample) {
            case 1 -> {
                final int xSizeInBits = xSizeInBytes * 8;
                final boolean[] a = PackedBitArraysPer8.unpackBitsInReverseOrder(
                        data, 0, (long) xSizeInBits * ySize);
                unsubtractBooleanMatrix(a, xSizeInBits, ySize, samplesPerPixel);
                PackedBitArraysPer8.packBitsInReverseOrder(data, 0, a, 0, a.length);
            }
            case 8 -> unsubtractByteMatrix(data, xSize, ySize, samplesPerPixel);
            case 16 -> {
                final short[] a = JArrays.bytesToShortArray(data, tile.byteOrder());
                unsubtractShortMatrix(a, xSize, ySize, samplesPerPixel);
                JArrays.shortArrayToBytes(data, a, a.length, tile.byteOrder());
            }
            case 32 -> {
                final int[] a = JArrays.bytesToIntArray(data, tile.byteOrder());
                unsubtractIntMatrix(a, xSize, ySize, samplesPerPixel);
                JArrays.intArrayToBytes(data, a, a.length, tile.byteOrder());
            }
            case 64 -> {
                final long[] a = JArrays.bytesToLongArray(data, tile.byteOrder());
                unsubtractLongMatrix(a, xSize, ySize, samplesPerPixel);
                JArrays.longArrayToBytes(data, a, a.length, tile.byteOrder());
            }
            default -> throw new AssertionError("Must be checked in checkBitDepthForPrediction");
        }
        // Legacy solution:
//            for (int k = 0; k <= data.length - 1; k++, xOffset++) {
//                if (xOffset == xSizeInBytes) {
//                    xOffset = 0;
//                }
//                // assert (k / bytesPerPixel % xSize == 0) == (xOffset < bytesPerPixel);
//                if (xOffset >= bytesPerPixel) {
//                    data[k] += data[k - bytesPerPixel];
//                }
//            }
    }

    @SuppressWarnings("DuplicatedCode")
    private static void subtractByteMatrix(byte[] a, int xSize, int ySize, int samplesPerPixel) {
        final int xSizeInSamples = xSize * samplesPerPixel;
        for (int y = 0; y < ySize; y++) {
            final int lineOffset = y * xSizeInSamples;
            final int minOffset = lineOffset + samplesPerPixel;
            // - cannot be >Integer.MAX_VALUE: see limitation for tile sizes in TiffTile.setSizes() method
            for (int k = lineOffset + xSizeInSamples - 1; k >= minOffset; k--) {
                a[k] -= a[k - samplesPerPixel];
            }
        }
    }

    @SuppressWarnings("DuplicatedCode")
    private static void unsubtractByteMatrix(byte[] a, int xSize, int ySize, int samplesPerPixel) {
        final int xSizeInSamples = xSize * samplesPerPixel;
        for (int y = 0; y < ySize; y++) {
            final int lineOffset = y * xSizeInSamples;
            int toOffset = lineOffset + xSizeInSamples;
            // - cannot be >Integer.MAX_VALUE: see limitation for tile sizes in TiffTile.setSizes() method
            for (int k = lineOffset + samplesPerPixel; k < toOffset; k++) {
                a[k] += a[k - samplesPerPixel];
            }
        }
    }

    @SuppressWarnings("DuplicatedCode")
    private static void subtractShortMatrix(short[] a, int xSize, int ySize, int samplesPerPixel) {
        final int xSizeInSamples = xSize * samplesPerPixel;
        for (int y = 0; y < ySize; y++) {
            final int lineOffset = y * xSizeInSamples;
            final int minOffset = lineOffset + samplesPerPixel;
            // - cannot be >Integer.MAX_VALUE: see limitation for tile sizes in TiffTile.setSizes() method
            for (int k = lineOffset + xSizeInSamples - 1; k >= minOffset; k--) {
                a[k] -= a[k - samplesPerPixel];
            }
        }
    }

    @SuppressWarnings("DuplicatedCode")
    private static void unsubtractShortMatrix(short[] a, int xSize, int ySize, int samplesPerPixel) {
        final int xSizeInSamples = xSize * samplesPerPixel;
        for (int y = 0; y < ySize; y++) {
            final int lineOffset = y * xSizeInSamples;
            int toOffset = lineOffset + xSizeInSamples;
            // - cannot be >Integer.MAX_VALUE: see limitation for tile sizes in TiffTile.setSizes() method
            for (int k = lineOffset + samplesPerPixel; k < toOffset; k++) {
                a[k] += a[k - samplesPerPixel];
            }
        }
    }

    @SuppressWarnings("DuplicatedCode")
    private static void subtractIntMatrix(int[] a, int xSize, int ySize, int samplesPerPixel) {
        final int xSizeInSamples = xSize * samplesPerPixel;
        for (int y = 0; y < ySize; y++) {
            final int lineOffset = y * xSizeInSamples;
            final int minOffset = lineOffset + samplesPerPixel;
            // - cannot be >Integer.MAX_VALUE: see limitation for tile sizes in TiffTile.setSizes() method
            for (int k = lineOffset + xSizeInSamples - 1; k >= minOffset; k--) {
                a[k] -= a[k - samplesPerPixel];
            }
        }
    }

    @SuppressWarnings("DuplicatedCode")
    private static void unsubtractIntMatrix(int[] a, int xSize, int ySize, int samplesPerPixel) {
        final int xSizeInSamples = xSize * samplesPerPixel;
        for (int y = 0; y < ySize; y++) {
            final int lineOffset = y * xSizeInSamples;
            int toOffset = lineOffset + xSizeInSamples;
            // - cannot be >Integer.MAX_VALUE: see limitation for tile sizes in TiffTile.setSizes() method
            for (int k = lineOffset + samplesPerPixel; k < toOffset; k++) {
                a[k] += a[k - samplesPerPixel];
            }
        }
    }

    @SuppressWarnings("DuplicatedCode")
    private static void subtractLongMatrix(long[] a, int xSize, int ySize, int samplesPerPixel) {
        final int xSizeInSamples = xSize * samplesPerPixel;
        for (int y = 0; y < ySize; y++) {
            final int lineOffset = y * xSizeInSamples;
            final int minOffset = lineOffset + samplesPerPixel;
            // - cannot be >Integer.MAX_VALUE: see limitation for tile sizes in TiffTile.setSizes() method
            for (int k = lineOffset + xSizeInSamples - 1; k >= minOffset; k--) {
                a[k] -= a[k - samplesPerPixel];
            }
        }
    }

    @SuppressWarnings("DuplicatedCode")
    private static void unsubtractLongMatrix(long[] a, int xSize, int ySize, int samplesPerPixel) {
        final int xSizeInSamples = xSize * samplesPerPixel;
        for (int y = 0; y < ySize; y++) {
            final int lineOffset = y * xSizeInSamples;
            int toOffset = lineOffset + xSizeInSamples;
            // - cannot be >Integer.MAX_VALUE: see limitation for tile sizes in TiffTile.setSizes() method
            for (int k = lineOffset + samplesPerPixel; k < toOffset; k++) {
                a[k] += a[k - samplesPerPixel];
            }
        }
    }

    private static void subtractBooleanMatrix(boolean[] a, int xSize, int ySize, int samplesPerPixel) {
        final int xSizeInSamples = xSize * samplesPerPixel;
        for (int y = 0; y < ySize; y++) {
            final int lineOffset = y * xSizeInSamples;
            final int minOffset = lineOffset + samplesPerPixel;
            // - cannot be >Integer.MAX_VALUE: see limitation for tile sizes in TiffTile.setSizes() method
            for (int k = lineOffset + xSizeInSamples - 1; k >= minOffset; k--) {
                a[k] ^= a[k - samplesPerPixel];
            }
        }
    }

    private static void unsubtractBooleanMatrix(boolean[] a, int xSize, int ySize, int samplesPerPixel) {
        final int xSizeInSamples = xSize * samplesPerPixel;
        for (int y = 0; y < ySize; y++) {
            final int lineOffset = y * xSizeInSamples;
            int toOffset = lineOffset + xSizeInSamples;
            // - cannot be >Integer.MAX_VALUE: see limitation for tile sizes in TiffTile.setSizes() method
            for (int k = lineOffset + samplesPerPixel; k < toOffset; k++) {
                a[k] ^= a[k - samplesPerPixel];
            }
        }
    }

    private static void checkBitDepthForPrediction(int bitsPerSample, String where) throws TiffException {
        if (bitsPerSample != 1 &&
                bitsPerSample != 8 &&
                bitsPerSample != 16 &&
                bitsPerSample != 32 &&
                bitsPerSample != 64) {
            throw new TiffException("Cannot use TIFF prediction " + where +
                    " for bit depth " + bitsPerSample);
        }
    }
}
