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

package net.algart.matrices.tiff;

import net.algart.arrays.*;

import java.nio.ByteOrder;
import java.util.Objects;
import java.util.OptionalInt;

public enum TiffSampleType {
    BIT(0, "bit", 1, boolean.class, false) {
        @Override
        public Object javaArray(byte[] bytes, ByteOrder byteOrder) {
            return PackedBitArraysPer8.toLongArray(bytes);
        }
    },
    INT8(0, "int8", 8, byte.class, true),
    UINT8(1, "uint8", 8, byte.class, false),
    INT16(2, "int16", 16, short.class, true),
    UINT16(3, "uint16", 16, short.class, false),
    INT32(4, "int32", 32, int.class, true),
    UINT32(5, "uint32", 32, int.class, false),
    FLOAT(6, "float", 32, float.class, true),
    DOUBLE(7, "double", 64, double.class, true);

    private final int code;
    private final String prettyName;
    private final int bitsPerSample;
    private final Class<?> elementType;
    private final boolean signed;

    TiffSampleType(int code, String prettyName, int bitsPerSample, Class<?> elementType, boolean signed) {
        this.code = code;
        this.prettyName = prettyName;
        this.bitsPerSample = bitsPerSample;
        this.elementType = elementType;
        this.signed = signed;
    }

    /**
     * Returns integer code 0..7 for INT8, UINT8, INT16, UINT16, INT32, UINT32, FLOAT, DOUBLE.
     * This code is compatible with pixel type constants from SCIFIO library (<code>FormatTools</code> class).
     *
     * @return integer code of this sample type.
     */
    public int code() {
        return code;
    }

    public String prettyName() {
        return prettyName;
    }

    public int bitsPerSample() {
        return bitsPerSample;
    }

    public OptionalInt bytesPerSample() {
        return isWholeBytes() ? OptionalInt.of(bitsPerSample >>> 3) : OptionalInt.empty();
    }

    public Class<?> elementType() {
        return elementType;
    }

    public long maxNumberOfSamplesInArray() {
        return this == BIT ? ((long) Integer.MAX_VALUE) << 3 : Integer.MAX_VALUE;
    }

    public boolean isBinary() {
        return this == BIT;
    }

    public boolean isWholeBytes() {
        return this != BIT;
    }

    public boolean isSigned() {
        return signed;
    }

    public boolean isFloatingPoint() {
        return this == FLOAT || this == DOUBLE;
    }

    public Object javaArray(byte[] bytes, ByteOrder byteOrder) {
        return elementType == byte.class ? bytes : JArrays.bytesToArray(bytes, elementType, byteOrder);
    }

    public static byte[] bytes(PArray array, ByteOrder byteOrder) {
        Objects.requireNonNull(array, "Null array");
        Objects.requireNonNull(byteOrder, "Null byteOrder");
        final Object javaArray = array instanceof BitArray bitArray ? bitArray.jaBit() : array.ja();
        return bytes(javaArray, array.length(), byteOrder);
    }

    public static byte[] bytes(Object javaArray, long numberOfElements, ByteOrder byteOrder) {
        Objects.requireNonNull(javaArray, "Null javaArray");
        Objects.requireNonNull(byteOrder, "Null byteOrder");
        if (javaArray instanceof byte[] a) {
            if (numberOfElements > a.length) {
                throw new IllegalArgumentException("Too short array: " + a.length + "<" + numberOfElements);
            }
            return a;
        } else if (javaArray instanceof boolean[] a) {
            return PackedBitArraysPer8.packBits(a, 0, numberOfElements);
        } else if (javaArray instanceof long[] a) {
            return PackedBitArraysPer8.toByteArray(a, numberOfElements);
        } else {
            return JArrays.arrayToBytes(javaArray, numberOfElements, byteOrder);
        }
    }

    public static TiffSampleType ofJavaArray(Object javaArray, boolean signedIntegers) {
        Objects.requireNonNull(javaArray, "Null Java array");
        Class<?> elementType = javaArray.getClass().getComponentType();
        if (elementType == null) {
            throw new IllegalArgumentException("The specified javaArray is not actual an array: " +
                    "it is " + javaArray.getClass());
        }
        return javaArray instanceof long[] ?
                // - packed long[]
                TiffSampleType.BIT :
                TiffSampleType.of(elementType, signedIntegers);
    }

    public static TiffSampleType of(PArray array, boolean signedIntegers) {
        Objects.requireNonNull(array, "Null array");
        return of(array.elementType(), signedIntegers);
    }

    public static TiffSampleType of(Class<?> elementType, boolean signedIntegers) {
        Objects.requireNonNull(elementType, "Null elementType");
        if (elementType == boolean.class) {
            return BIT;
        } else if (elementType == byte.class) {
            return signedIntegers ? INT8 : UINT8;
        } else if (elementType == short.class) {
            return signedIntegers ? INT16 : UINT16;
        } else if (elementType == int.class) {
            return signedIntegers ? INT32 : UINT32;
        } else if (elementType == float.class) {
            return FLOAT;
        } else if (elementType == double.class) {
            return DOUBLE;
        } else {
            throw new IllegalArgumentException("Element type " + elementType + " is not a supported TIFF sample type");
        }
    }

    public static Matrix<UpdatablePArray> asMatrix(
            Object javaArray,
            int sizeX,
            int sizeY,
            int numberOfChannels,
            boolean interleavedSamples) {
        ofJavaArray(javaArray, false);
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

}
