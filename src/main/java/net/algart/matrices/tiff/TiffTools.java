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

package net.algart.matrices.tiff;

import net.algart.arrays.*;
import net.algart.matrices.tiff.tags.*;
import net.algart.matrices.tiff.tiles.TiffTile;
import org.scijava.util.Bytes;

import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Utility methods for working with TIFF files.
 *
 * @author Daniel Alievsky
 */
public class TiffTools {
    /**
     * Maximal supported number of channels. Popular OpenCV library has the same limit.
     *
     * <p>This limit helps to avoid "crazy" or corrupted TIFF and also help to avoid arithmetic overflow.
     */
    public static final int MAX_NUMBER_OF_CHANNELS = 512;

    /**
     * Maximal supported number of bits per sample.
     *
     * <p>This limit helps to avoid "crazy" or corrupted TIFF and also help to avoid arithmetic overflow.
     */
    public static final int MAX_BITS_PER_SAMPLE = 128;

    /**
     * The number of bytes in each IFD entry.
     */
    public static final int BYTES_PER_ENTRY = 12;
    /**
     * The number of bytes in each IFD entry of a BigTIFF file.
     */
    public static final int BIG_TIFF_BYTES_PER_ENTRY = 20;
    // TIFF header constants
    public static final int FILE_USUAL_MAGIC_NUMBER = 42;
    public static final int FILE_BIG_TIFF_MAGIC_NUMBER = 43;
    public static final int FILE_PREFIX_LITTLE_ENDIAN = 0x49;
    public static final int FILE_PREFIX_BIG_ENDIAN = 0x4d;

    private TiffTools() {
    }

    public static Matrix<UpdatablePArray> asMatrix(
            Object javaArray,
            int sizeX,
            int sizeY,
            int numberOfChannels,
            boolean interleavedSamples) {
        TiffSampleType.valueOfJavaArray(javaArray, false);
        // - checks that javaArray is an array of supported primitive types
        if (sizeX < 0 || sizeY < 0) {
            throw new IllegalArgumentException("Negative sizeX = " + sizeX + " or sizeY = " + sizeY);
        }
        if (numberOfChannels <= 0) {
            throw new IllegalArgumentException("Zero or negative numberOfChannels = " + numberOfChannels);
        }
        if (javaArray instanceof boolean[]) {
            throw new IllegalArgumentException("boolean[] array cannot be converted to AlgART matrix; " +
                    "binary matrix should be packed into long[] array");
        }
        final UpdatablePArray array = javaArray instanceof long[] packedBisArray ?
                // long[] type in this library is reserved for packed bits (TIFF does not support 64-bit precision)
                BitArray.as(packedBisArray, (long) sizeX * (long) sizeY * (long) numberOfChannels) :
                // - but actually numberOfChannels > 1 is not supported by this library for binary matrices;
                // overflow (very improbable) will be well checked in the following operator
                PArray.as(javaArray);
        return interleavedSamples ?
                Matrices.matrix(array, numberOfChannels, sizeX, sizeY) :
                Matrices.matrix(array, sizeX, sizeY, numberOfChannels);
    }

    /**
     * Changes bits order inside the tile if FillOrder=2.
     *
     * @param tile TIFF tile
     * @throws TiffException in a case of error in IFD
     */
    public static void reverseFillOrderIfRequested(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        if (tile.ifd().isReversedFillOrder()) {
            PackedBitArraysPer8.reverseBitOrderInPlace(tile.getData());
        }
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

    public static void checkNumberOfChannels(long numberOfChannels) {
        if (numberOfChannels <= 0) {
            throw new IllegalArgumentException("Zero or negative numberOfChannels = " + numberOfChannels);
        }
        if (numberOfChannels > MAX_NUMBER_OF_CHANNELS) {
            throw new IllegalArgumentException("Very large number of channels " + numberOfChannels + " > " +
                    MAX_NUMBER_OF_CHANNELS + " is not supported");
        }
    }

    public static long checkRequestedArea(long fromX, long fromY, long sizeX, long sizeY) {
        if (sizeX < 0 || sizeY < 0) {
            throw new IllegalArgumentException("Negative sizeX = " + sizeX + " or sizeY = " + sizeY);
        }
        if (fromX != (int) fromX || fromY != (int) fromY) {
            throw new IllegalArgumentException("Too large absolute values of fromX = " + fromX +
                    " or fromY = " + fromY + " (out of -2^31..2^31-1 ranges)");
        }
        if (sizeX > Integer.MAX_VALUE || sizeY > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too large sizeX = " + sizeX + " or sizeY = " + sizeY + " (>2^31-1)");
        }
        if (sizeX >= Integer.MAX_VALUE - fromX || sizeY >= Integer.MAX_VALUE - fromY) {
            // - Note: >= instead of > ! This allows to use "toX = fromX + sizeX" without overflow
            throw new IllegalArgumentException("Requested area [" + fromX + ".." + (fromX + sizeX - 1) +
                    " x " + fromY + ".." + (fromY + sizeY - 1) + " is out of 0..2^31-2 ranges");
        }
        final long result = sizeX * sizeY;
        if (result > Long.MAX_VALUE / MAX_NUMBER_OF_CHANNELS) {
            throw new TooLargeArrayException("Extremely large area " + sizeX + "x" + sizeY +
                    ": number of bits may exceed the limit 2^63 for very large number of channels");
        }
        return result;
    }

    public static void checkRequestedAreaInArray(byte[] array, long sizeX, long sizeY, int bitsPerPixel) {
        Objects.requireNonNull(array, "Null array");
        if (bitsPerPixel <= 0) {
            throw new IllegalArgumentException("Zero or negative bitsPerPixel = " + bitsPerPixel);
        }
        final long arrayBits = (long) array.length * 8;
        checkRequestedArea(0, 0, sizeX, sizeY);
        if (sizeX * sizeY > arrayBits || sizeX * sizeY * (long) bitsPerPixel > arrayBits) {
            throw new IllegalArgumentException("Requested area " + sizeX + "x" + sizeY +
                    " is too large for array of " + array.length + " bytes, " + bitsPerPixel + " per pixel");
        }
    }

    static long checkedMul(
            long v1, long v2, long v3, long v4,
            String n1, String n2, String n3, String n4,
            Supplier<String> prefix,
            Supplier<String> postfix,
            long maxValue) throws TiffException {
        return checkedMul(new long[]{v1, v2, v3, v4}, new String[]{n1, n2, n3, n4}, prefix, postfix, maxValue);
    }

    public static int checkedMulNoException(
            long[] values,
            String[] names,
            Supplier<String> prefix,
            Supplier<String> postfix) {
        return (int) checkedMulNoException(values, names, prefix, postfix, Integer.MAX_VALUE);
    }

    static long checkedMulNoException(
            long[] values,
            String[] names,
            Supplier<String> prefix,
            Supplier<String> postfix,
            long maxValue) {
        try {
            return checkedMul(values, names, prefix, postfix, maxValue);
        } catch (TiffException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
    }

    public static long checkedMul(
            long[] values,
            String[] names,
            Supplier<String> prefix,
            Supplier<String> postfix,
            long maxValue) throws TiffException {
        Objects.requireNonNull(values);
        Objects.requireNonNull(prefix);
        Objects.requireNonNull(postfix);
        Objects.requireNonNull(names);
        if (values.length == 0) {
            return 1;
        }
        long result = 1L;
        double product = 1.0;
        boolean overflow = false;
        for (int i = 0; i < values.length; i++) {
            long m = values[i];
            if (m < 0) {
                throw new TiffException(prefix.get() + "negative " + names[i] + " = " + m + postfix.get());
            }
            if (m > maxValue) {
                throw new TiffException(prefix.get() + "too large " + names[i] + " = " + m + postfix.get()
                        + " > " + maxValue);
            }
            result *= m;
            product *= m;
            if (result > maxValue) {
                overflow = true;
                // - we just indicate this, but still calculate the floating-point product
            }
        }
        if (overflow) {
            throw new TooLargeTiffImageException(prefix.get() + "too large " + String.join(" * ", names) +
                    " = " + Arrays.stream(values).mapToObj(String::valueOf).collect(
                    Collectors.joining(" * ")) +
                    " = " + product + " > " + maxValue + postfix.get());
        }
        return result;
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

    private static void debugPrintBits(TiffTile tile) throws TiffException {
        if (tile.index().yIndex() != 0) {
            return;
        }
        final byte[] data = tile.getDecodedData();
        final int sizeX = tile.getSizeX();
        final int[] bitsPerSample = tile.ifd().getBitsPerSample();
        final int samplesPerPixel = tile.samplesPerPixel();
        System.out.printf("%nPacked bits %s:%n", Arrays.toString(bitsPerSample));
        for (int i = 0, bit = 0; i < sizeX; i++) {
            System.out.printf("Pixel #%d: ", i);
            for (int s = 0; s < samplesPerPixel; s++) {
                final int bits = bitsPerSample[s];
                int v = 0;
                for (int j = 0; j < bits; j++, bit++) {
                    final int bitIndex = 7 - bit % 8;
                    int b = (data[bit / 8] >> bitIndex) & 1;
                    System.out.print(b);
                    v |= b << (bits - 1 - j);
                }
                System.out.printf(" = %-6d ", v);
            }
            System.out.println();
        }
    }

    /*
    // Unnecessary since AlgART 1.4.0
    private static void quickInterleave3(byte[] interleavedBytes, byte[] bytes, int bandSize) {
        final int bandSize2 = 2 * bandSize;
        for (int i = 0, disp = 0; i < bandSize; i++) {
            interleavedBytes[disp++] = bytes[i];
            interleavedBytes[disp++] = bytes[i + bandSize];
            interleavedBytes[disp++] = bytes[i + bandSize2];
        }
    }

    private static void quickSeparate3(byte[] separatedBytes, byte[] bytes, int bandSize) {
        final int bandSize2 = 2 * bandSize;
        for (int i = 0, disp = 0; i < bandSize; i++) {
            separatedBytes[i] = bytes[disp++];
            separatedBytes[i + bandSize] = bytes[disp++];
            separatedBytes[i + bandSize2] = bytes[disp++];
        }
    }
     */

    private static int checkEnoughArrayLength(long numberOfElements, int arrayLength, int multiplier) {
        if (numberOfElements > arrayLength) {
            throw new IllegalArgumentException("Too short array length " + arrayLength +
                    ": it must contain at least " + numberOfElements + " elements");
        }
        if (numberOfElements * (long) multiplier > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Too large number of elements " + numberOfElements +
                    ": it must be less than 2^31 / " + multiplier + " = "
                    + (((long) Integer.MAX_VALUE + 1) / multiplier));
        }
        return (int) numberOfElements;
    }

}
