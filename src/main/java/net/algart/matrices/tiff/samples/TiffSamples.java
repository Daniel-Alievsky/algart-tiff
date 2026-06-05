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

package net.algart.matrices.tiff.samples;

import net.algart.arrays.*;
import net.algart.math.functions.LinearFunc;
import net.algart.matrices.tiff.TiffIFD;

import java.nio.ByteOrder;
import java.util.Objects;

public class TiffSamples {
    private TiffSamples() {
    }

    public static byte[] toInterleavedBytes(
            byte[] bytes,
            int numberOfChannels,
            int bytesPerSample,
            long numberOfPixels) {
        Objects.requireNonNull(bytes, "Null bytes");
        final int size = checkSizes(numberOfChannels, bytesPerSample, numberOfPixels);
        // - exception usually should not occur: this function is typically called after analyzing IFD
        if (bytes.length < size) {
            throw new IllegalArgumentException("Too short samples array: " + bytes.length + " < " + size);
        }
        if (numberOfChannels == 1) {
            return bytes;
        }
        final int bandSize = bytesPerSample * (int) numberOfPixels;
        final byte[] interleavedBytes = new byte[size];
        if (bytesPerSample == 1) {
            Matrix<UpdatablePArray> mI = Matrix.as(interleavedBytes, numberOfChannels, numberOfPixels);
            Matrix<UpdatablePArray> mS = Matrix.as(bytes, numberOfPixels, numberOfChannels);
            Matrices.interleave(null, mI, mS.asLayers());
//            if (numberOfChannels == 3) {
//                quickInterleave3(interleavedBytes, bytes, bandSize);
//            } else {
//                for (int i = 0, disp = 0; i < bandSize; i++) {
//                    for (int bandDisp = i, j = 0; j < numberOfChannels; j++, bandDisp += bandSize) {
            // note: we must check j, not bandDisp, because "bandDisp += bandSize" can lead to overflow
//                        interleavedBytes[disp++] = bytes[bandDisp];
//                    }
//                }
//            }
        } else {
            for (int i = 0, disp = 0; i < bandSize; i += bytesPerSample) {
                for (int bandDisp = i, j = 0; j < numberOfChannels; j++, bandDisp += bandSize) {
                    for (int k = 0; k < bytesPerSample; k++) {
                        interleavedBytes[disp++] = bytes[bandDisp + k];
                    }
                }
            }
        }
        return interleavedBytes;
    }

    public static byte[] toSeparatedBytes(
            byte[] bytes,
            int numberOfChannels,
            int bytesPerSample,
            long numberOfPixels) {
        Objects.requireNonNull(bytes, "Null bytes");
        final int size = checkSizes(numberOfChannels, bytesPerSample, numberOfPixels);
        // - exception usually should not occur: this function is typically called after analyzing IFD
        if (bytes.length < size) {
            throw new IllegalArgumentException("Too short samples array: " + bytes.length + " < " + size);
        }
        if (numberOfChannels == 1) {
            return bytes;
        }
        final int bandSize = bytesPerSample * (int) numberOfPixels;
        final byte[] separatedBytes = new byte[size];
        if (bytesPerSample == 1) {
            final Matrix<UpdatablePArray> mI = Matrix.as(bytes, numberOfChannels, numberOfPixels);
            final Matrix<UpdatablePArray> mS = Matrix.as(separatedBytes, numberOfPixels, numberOfChannels);
            Matrices.separate(null, mS.asLayers(), mI);
        } else {
            for (int i = 0, disp = 0; i < bandSize; i += bytesPerSample) {
                for (int bandDisp = i, j = 0; j < numberOfChannels; j++, bandDisp += bandSize) {
                    for (int k = 0; k < bytesPerSample; k++) {
                        separatedBytes[bandDisp + k] = bytes[disp++];
                    }
                }
            }
        }
        return separatedBytes;
    }

    private static int checkSizes(int numberOfChannels, int bytesPerSample, long numberOfPixels) {
        TiffIFD.checkNumberOfChannels(numberOfChannels);
        TiffIFD.checkBitsPerSample(8L * (long) bytesPerSample);
        // - so, numberOfChannels * bytesPerSample is a not-too-large value
        if (numberOfPixels < 0) {
            throw new IllegalArgumentException("Negative numberOfPixels = " + numberOfPixels);
        }
        long size;
        if (numberOfPixels > Integer.MAX_VALUE ||
                (size = numberOfPixels * (long) numberOfChannels * (long) bytesPerSample) > Integer.MAX_VALUE) {
            throw new TooLargeArrayException("Too large number of pixels " + numberOfPixels +
                    " (" + numberOfChannels + " samples/pixel, " +
                    bytesPerSample + " bytes/sample): it requires > 2 GB to store");
        }
        return (int) size;
    }

    public static byte[] toBytes(PArray array, ByteOrder byteOrder) {
        Objects.requireNonNull(array, "Null array");
        Objects.requireNonNull(byteOrder, "Null byteOrder");
        final Object javaArray = array instanceof BitArray bitArray ? bitArray.jaBit() : array.ja();
        return toBytes(javaArray, array.length(), byteOrder);
    }

    public static byte[] toBytes(Object javaArray, long numberOfElements, ByteOrder byteOrder) {
        Objects.requireNonNull(javaArray, "Null javaArray");
        Objects.requireNonNull(byteOrder, "Null byteOrder");
        return switch (javaArray) {
            case byte[] a -> {
                if (numberOfElements > a.length) {
                    throw new IllegalArgumentException("Too short array: " + a.length + "<" + numberOfElements);
                }
                yield a;
            }
            case boolean[] a -> PackedBitArraysPer8.packBits(a, 0, numberOfElements);
            case long[] a -> PackedBitArraysPer8.toByteArray(a, numberOfElements);
            default -> JArrays.arrayToBytes(javaArray, numberOfElements, byteOrder);
        };
    }

    public static boolean isBitsPerSampleSupported(int bitsPerSample) {
        return bitsPerSample == 1 || bitsPerSample == 8 || bitsPerSample == 16 ||
                bitsPerSample == 32 || bitsPerSample == 64;
    }

    public static Matrix<UpdatablePArray> asMatrix(
            Object javaArray,
            int sizeX,
            int sizeY,
            int numberOfChannels,
            boolean interleavedSamples) {
        TiffSampleType.ofJavaArray(javaArray, false);
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
        final UpdatablePArray array = javaArray instanceof long[] packedBitArray ?
                // long[] type in this library is reserved for packed bits (TIFF does not support 64-bit precision)
                BitArray.as(packedBitArray, (long) sizeX * (long) sizeY * (long) numberOfChannels) :
                // - but actually numberOfChannels > 1 is not supported by this library for binary matrices;
                // overflow (very improbable) will be well checked in the following operator
                PArray.as(javaArray);
        return interleavedSamples ?
                Matrices.matrix(array, numberOfChannels, sizeX, sizeY) :
                Matrices.matrix(array, sizeX, sizeY, numberOfChannels);
    }

    public static double maxOf(Matrix<? extends PArray> matrix) {
        Objects.requireNonNull(matrix, "Null matrix");
        return maxOf(matrix.array());
    }

    public static double maxOf(PArray array) {
        Objects.requireNonNull(array, "Null array");
        if (array instanceof IntArray intArray) {
            int[] values = intArray.jaInt();
            long max = 0;
            for (int value : values) {
                max = Math.max(max, value & 0xFFFFFFFFL);
            }
            return max;
        } else {
            return Arrays.rangeOf(array).max();
        }
    }

    public static Matrix<? extends PArray> applyLinearFunction(Matrix<? extends PArray> matrix, double a, double b) {
        Objects.requireNonNull(matrix, "Null matrix");
        return matrix.matrix(applyLinearFunction(matrix.array(), a, b));
    }

    public static PArray applyLinearFunction(PArray array, double a, double b) {
        Objects.requireNonNull(array, "Null array");
        if (array instanceof IntArray intArray) {
            int[] values = intArray.toInt();
            for (int i = 0; i < values.length; i++) {
                long value = values[i] & 0xFFFFFFFFL    ;
                long scaled = Math.round(a * value + b);
                scaled = Math.clamp(scaled, 0, 0xFFFFFFFFL);
                values[i] = (int) scaled;
            }
            return PArray.as(values);
        } else {
            return Arrays.clone(Arrays.asFuncArray(LinearFunc.getInstance(b, a), array.type(), array));
        }
    }
}
