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

import net.algart.matrices.tiff.tiles.TiffTile;

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

    private TiffTools() {
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
