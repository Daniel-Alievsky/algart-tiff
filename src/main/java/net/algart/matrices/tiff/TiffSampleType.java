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

package net.algart.matrices.tiff;

import net.algart.arrays.*;

import java.lang.reflect.Array;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;

public enum TiffSampleType {
    BIT(0, "bit", 1, boolean.class, false, 1.0) {
        @Override
        public Object javaArray(byte[] bytes, ByteOrder byteOrder) {
            return PackedBitArraysPer8.toLongArray(bytes);
        }
    },
    INT8(0, "int8", 8, byte.class, true, 127),
    UINT8(1, "uint8", 8, byte.class, false, 255),
    INT16(2, "int16", 16, short.class, true, 0x7FFF),
    UINT16(3, "uint16", 16, short.class, false, 0xFFFF),
    INT32(4, "int32", 32, int.class, true, 0x7FFFFFFFL),
    UINT32(5, "uint32", 32, int.class, false, 0xFFFFFFFFL),
    FLOAT(6, "float", 32, float.class, true, 1.0),
    DOUBLE(7, "double", 64, double.class, true, 1.0);

    private final int code;
    private final String prettyName;
    private final int bitsPerSample;
    private final Class<?> elementType;
    private final boolean signed;
    private final double maxValue;

    TiffSampleType(
            int code,
            String prettyName,
            int bitsPerSample,
            Class<?> elementType,
            boolean signed,
            double maxValue) {
        this.code = code;
        this.prettyName = prettyName;
        this.bitsPerSample = bitsPerSample;
        this.elementType = elementType;
        this.signed = signed;
        this.maxValue = maxValue;
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

    public Class<?> elementTypeOfJavaArray() {
        return this == BIT ? long.class : elementType;
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

    public boolean isSignedInteger() {
        return isSigned() && !isFloatingPoint();
    }

    public boolean isFloatingPoint() {
        return this == FLOAT || this == DOUBLE;
    }

    public double maxValue() {
        return maxValue;
    }

    public TiffSampleType unsignedVersion() {
        return switch (this) {
            case INT8 -> UINT8;
            case INT16 -> UINT16;
            case INT32 -> UINT32;
            default -> this;
        };
    }

    public Object javaArray(byte[] bytes, ByteOrder byteOrder) {
        return elementType == byte.class ? bytes : JArrays.bytesToArray(bytes, elementType, byteOrder);
    }

    public String javaArrayToString(
            Object javaArray,
            int arrayLength,
            boolean hexadecimal,
            String floatingPointFormat,
            String separator) {
        Objects.requireNonNull(javaArray, "Null javaArray");
        Objects.requireNonNull(separator, "Null separator");
        if (isFloatingPoint()) {
            Objects.requireNonNull(floatingPointFormat, "Null floatingPointFormat " +
                    "(must be specified for " + this + ")");
        }
        if (arrayLength < 0) {
            throw new IllegalArgumentException("Negative arrayLength: " + arrayLength);
        }
        final Class<?> componentType = javaArray.getClass().getComponentType();
        if (componentType == null) {
            throw new IllegalArgumentException("Invalid javaArray: it is not an array");
        }
        if (!(componentType == boolean.class && this == BIT) && componentType != elementTypeOfJavaArray()) {
            throw new IllegalArgumentException("Invalid javaArray element type: " +
                    componentType.getTypeName() + " instead of " + elementTypeOfJavaArray());
        }
        if (javaArray instanceof long[] longArray) {
            javaArray = PackedBitArrays.unpackBits(longArray, 0, arrayLength);
        }
        if (Array.getLength(javaArray) < arrayLength) {
            throw new IllegalArgumentException("Too short javaArray: " + Array.getLength(javaArray) +
                    " elements instead of the specified length " + arrayLength);
        }
        return switch (javaArray) {
            case boolean[] values -> toString(values, arrayLength, separator);
            case byte[] values -> toString(values, arrayLength, hexadecimal ? "%02X" : null, separator);
            case short[] values -> toString(values, arrayLength, hexadecimal ? "%04X" : null, separator);
            case int[] values -> toString(values, arrayLength, hexadecimal ? "%08X" : null, separator);
            case float[] values -> toString(values, arrayLength, floatingPointFormat, separator);
            case double[] values -> toString(values, arrayLength, floatingPointFormat, separator);
            default -> throw new IllegalArgumentException("Invalid javaArray type: " +
                    javaArray.getClass().getTypeName());
        };
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

    private static String toString(boolean[] array, int length, String separator) {
        if (length < array.length) {
            array = Arrays.copyOf(array, length);
        }
        return JArrays.toBinaryString(array, separator, 10000);
    }

    private String toString(byte[] array, int length, String format, String separator) {
        long[] values = new long[Math.min(length, array.length)];
        for (int k = 0; k < values.length; k++) {
            values[k] = signed ? array[k] : array[k] & 0xFF;
        }
        return longArrayToString(values, format, separator);
    }

    private String toString(short[] array, int length, String format, String separator) {
        long[] values = new long[Math.min(length, array.length)];
        for (int k = 0; k < values.length; k++) {
            values[k] = signed ? array[k] : array[k] & 0xFFFF;
        }
        return longArrayToString(values, format, separator);
    }

    private String toString(int[] array, int length, String format, String separator) {
        long[] values = new long[Math.min(length, array.length)];
        for (int k = 0; k < values.length; k++) {
            values[k] = signed ? array[k] : array[k] & 0xFFFFFFFFL;
        }
        return longArrayToString(values, format, separator);
    }

    private static String longArrayToString(long[] array, String format, String separator) {
        if (format != null) {
            return JArrays.toString(array, Locale.US, format, separator, 10000);
        } else {
            return JArrays.toString(array, separator, 10000);
        }
    }

    private static String toString(float[] array, int length, String format, String separator) {
        if (length < array.length) {
            array = Arrays.copyOf(array, length);
        }
        if (format != null) {
            return JArrays.toString(array, Locale.US, format, separator, 10000);
        } else {
            return JArrays.toString(array, separator, 10000);
        }
    }

    private static String toString(double[] array, int length, String format, String separator) {
        if (length < array.length) {
            array = Arrays.copyOf(array, length);
        }
        if (format != null) {
            return JArrays.toString(array, Locale.US, format, separator, 10000);
        } else {
            return JArrays.toString(array, separator, 10000);
        }
    }
}
