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

import net.algart.arrays.JArrays;
import net.algart.arrays.PArray;
import net.algart.arrays.PackedBitArrays;
import net.algart.arrays.PackedBitArraysPer8;

import java.lang.reflect.Array;
import java.nio.ByteOrder;
import java.util.*;

public enum TiffSampleType {
    BIT(0, "bit", 1, boolean.class, false, 1.0) {
        @Override
        public Object javaArray(byte[] bytes, ByteOrder byteOrder) {
            return PackedBitArraysPer8.toLongArray(bytes);
        }
    },
    UINT8(1, "byte", 8, byte.class, false, 255),
    INT8(0, "signed int-8", 8, byte.class, true, 127),
    UINT16(3, "uint-16", 16, short.class, false, 0xFFFF),
    INT16(2, "signed int-16", 16, short.class, true, 0x7FFF),
    UINT32(5, "uint-32", 32, int.class, false, 0xFFFFFFFFL),
    INT32(4, "signed int-32", 32, int.class, true, 0x7FFFFFFFL),
    FLOAT(6, "float", 32, float.class, true, 1.0),
    DOUBLE(7, "double", 64, double.class, true, 1.0);

    public final class Formatter {
        private boolean hexadecimal = false;
        private boolean normalized = false;
        private String decimalIntegerFormat = null;
        private String floatingPointFormat = "%.3f";
        private String normalizedFormat = "%.3f";
        private String separator = ", ";
        private int maxArrayLength = Integer.MAX_VALUE;
        private int maxStringLength = 10000;
        private Locale locale = Locale.US;

        private Formatter() {
        }

        public boolean isHexadecimal() {
            return hexadecimal;
        }

        public Formatter setHexadecimal(boolean hexadecimal) {
            this.hexadecimal = hexadecimal;
            return this;
        }

        public boolean isNormalized() {
            return normalized;
        }

        public Formatter setNormalized(boolean normalized) {
            this.normalized = normalized;
            return this;
        }

        public String getDecimalIntegerFormat() {
            return decimalIntegerFormat;
        }

        public Formatter setDecimalIntegerFormat(String decimalIntegerFormat) {
            this.decimalIntegerFormat = decimalIntegerFormat;
            return this;
        }

        public String getFloatingPointFormat() {
            return floatingPointFormat;
        }

        public Formatter setFloatingPointFormat(String floatingPointFormat) {
            this.floatingPointFormat = floatingPointFormat;
            return this;
        }

        public String getNormalizedFormat() {
            return normalizedFormat;
        }

        public Formatter setNormalizedFormat(String normalizedFormat) {
            this.normalizedFormat = normalizedFormat;
            return this;
        }

        public String getSeparator() {
            return separator;
        }

        public Formatter setSeparator(String separator) {
            this.separator = Objects.requireNonNull(separator, "Null separator");
            return this;
        }

        public int getMaxArrayLength() {
            return maxArrayLength;
        }

        public Formatter setMaxArrayLength(int maxArrayLength) {
            if (maxArrayLength < 0) {
                throw new IllegalArgumentException("maxArrayLength cannot be negative");
            }
            this.maxArrayLength = maxArrayLength;
            return this;
        }

        public int getMaxStringLength() {
            return maxStringLength;
        }

        public Formatter setMaxStringLength(int maxStringLength) {
            if (maxStringLength <= 0) {
                throw new IllegalArgumentException("maxStringLength argument must be positive");
            }
            this.maxStringLength = maxStringLength;
            return this;
        }

        public Locale getLocale() {
            return locale;
        }

        public Formatter setLocale(Locale locale) {
            this.locale = Objects.requireNonNull(locale, "Null locale");
            return this;
        }

        public String toString(PArray array) {
            return toString(array, 0, array.length32());
        }

        public String toString(PArray array, int offset, int count) {
            Objects.requireNonNull(array, "Null array");
            if (array.elementType() == long.class) {
                // - just in case: this message is better than "Invalid javaArray element type"
                // in the method javaArrayToString()
                throw new IllegalArgumentException("long[] elements are not supported as TIFF samples");
            }
            return javaArrayToString(array.ja(), offset, count);
        }

        /**
         * Formats a specific range of a samples array into a string based on the current formatter settings.
         *
         * <p>Note: for {@link TiffSampleType#BIT}, this method handles both unpacked
         * {@code boolean[]} and packed {@code long[]} arrays (see {@link PackedBitArrays}).
         * When passing packed bits ({@code long[]}),
         * the {@code offset} and {@code count} parameters dictate the range in <i>bits</i>, not array elements.</p>
         *
         * <p>Note: there is no overload for processing the entire Java array (without {@code offset} and
         * {@code count} arguments), because a <i>packed bit array</i> ({@code long[]})
         * has no information about its actual length in <i>bits</i>.</p>
         *
         * @param javaArray the primitive array containing sample values.
         * @param offset    the starting index (or bit-offset for packed {@code long[]}); must be non-negative.
         * @param count     the number of elements (or bits) to process; must be non-negative.
         * @return a formatted string of the array slice, appended with "..." if truncated by
         * {@link #getMaxArrayLength()} or {@link #getMaxStringLength()}.
         * @throws IndexOutOfBoundsException if the requested range falls outside the actual array size (or available
         * packed bits).
         * @throws IllegalArgumentException  if arguments are negative, or the array type is mismatched.
         * @throws NullPointerException      if {@code javaArray} is null.
         */
        public String javaArrayToString(Object javaArray, int offset, int count) {
            Objects.requireNonNull(javaArray, "Null javaArray");
            if (offset < 0) {
                throw new IllegalArgumentException("Negative offset: " + offset);
            }
            if (count < 0) {
                throw new IllegalArgumentException("Negative count: " + count);
            }
            final Class<?> componentType = javaArray.getClass().getComponentType();
            if (componentType == null) {
                throw new IllegalArgumentException("Invalid javaArray argument: it is not an array");
            }
            if (!(componentType == boolean.class && TiffSampleType.this == BIT) &&
                    componentType != elementTypeOfJavaArray()) {
                throw new IllegalArgumentException("Invalid javaArray element type: " +
                        componentType.getTypeName() + " instead of " + elementTypeOfJavaArray());
            }
            final long[] packedBits = javaArray instanceof long[] longs ? longs : null;
            final long arrayLength = packedBits != null ?
                    PackedBitArrays.unpackedLength(packedBits) :
                    Array.getLength(javaArray);
            if (count > arrayLength - offset) {
                throw new IndexOutOfBoundsException("End position (last index + 1) = " + (offset + (long) count)
                        + " > array length = " + arrayLength);
            }
            final boolean tooLarge = count > maxArrayLength;
            if (tooLarge) {
                count = maxArrayLength;
            }
            if (packedBits != null) {
                javaArray = PackedBitArrays.unpackBits(packedBits, offset, count);
                offset = 0;
                // - should be 0 for correct processing boolean[] values below
            }
            final boolean signedDecimal = signed && (normalized || !hexadecimal);
            // - hexadecimal should be treated as unsigned, but the normalized mode overrides hexadecimal
            String result = switch (javaArray) {
                case boolean[] values -> build(values, offset, count);
                case byte[] values -> build(values, offset, count, signedDecimal, integerFormat(2));
                case short[] values -> build(values, offset, count, signedDecimal, integerFormat(4));
                case int[] values -> build(values, offset, count, signedDecimal, integerFormat(8));
                case float[] values -> build(values, offset, count, floatingPointFormat);
                case double[] values -> build(values, offset, count, floatingPointFormat);
                default -> throw new IllegalArgumentException("Invalid javaArray type: " +
                        javaArray.getClass().getTypeName());
            };
            return tooLarge && !result.endsWith("...") ? result + separator + "..." : result;
        }

        private String integerFormat(int width) {
            return !hexadecimal ? decimalIntegerFormat : switch (width) {
                case 2 -> "%02X";
                case 4 -> "%04X";
                case 8 -> "%08X";
                default -> throw new IllegalArgumentException("Invalid width: " + width);
            };
        }

        private String build(boolean[] array, int offset, int count) {
            var values = Arrays.copyOfRange(array, offset, offset + count);
            return JArrays.toBinaryString(values, separator, maxStringLength);
        }

        private String build(byte[] array, int offset, int count, boolean signed, String format) {
            long[] values = new long[count];
            for (int k = 0; k < count; k++, offset++) {
                values[k] = signed ? array[offset] : array[offset] & 0xFF;
            }
            return toString(values, format);
        }

        private String build(short[] array, int offset, int count, boolean signed, String format) {
            long[] values = new long[count];
            for (int k = 0; k < count; k++, offset++) {
                values[k] = signed ? array[offset] : array[offset] & 0xFFFF;
            }
            return toString(values, format);
        }

        private String build(int[] array, int offset, int count, boolean signed, String format) {
            long[] values = new long[count];
            for (int k = 0; k < count; k++, offset++) {
                values[k] = signed ? array[offset] : array[offset] & 0xFFFFFFFFL;
            }
            return toString(values, format);
        }

        private String build(float[] array, int offset, int count, String format) {
            var values = Arrays.copyOfRange(array, offset, offset + count);
            if (format != null) {
                return JArrays.toString(values, locale, format, separator, maxStringLength);
            } else {
                return JArrays.toString(values, separator, maxStringLength);
            }
        }

        private String build(double[] array, int offset, int count, String format) {
            var values = Arrays.copyOfRange(array, offset, offset + count);
            return toString(values, format);
        }

        private String toString(long[] array, String format) {
            if (normalized) {
                double[] values = new double[array.length];
                double scale = 1.0 / maxValue;
                for (int i = 0; i < values.length; i++) {
                    values[i] = array[i] * scale;
                }
                return toString(values, normalizedFormat);
            }
            if (format != null) {
                return JArrays.toString(array, locale, format, separator, maxStringLength);
            } else {
                return JArrays.toString(array, separator, maxStringLength);
            }
        }

        private String toString(double[] values, String format) {
            if (format != null) {
                return JArrays.toString(values, locale, format, separator, maxStringLength);
            } else {
                return JArrays.toString(values, separator, maxStringLength);
            }
        }
    }

    private static final Map<String, TiffSampleType> PRETTY_NAME_LOOKUP = new HashMap<>();
    static {
        for (TiffSampleType v : values()) {
            final String pretty = v.prettyName().toUpperCase();
            if (PRETTY_NAME_LOOKUP.containsKey(pretty)) {
                throw new AssertionError("Duplicate pretty name (ignoring case): " + pretty);
            }
            PRETTY_NAME_LOOKUP.put(pretty, v);
        }
    }
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

    public double maxUnsignedValue() {
        return unsignedVersion().maxValue();
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

    public Formatter newFormatter() {
        return new Formatter();
    }

    /**
     * Returns an {@link Optional} containing the {@link TiffSampleType} with the given {@link #prettyName()}
     * (case-insensitive).
     * <p>If no sample type with the specified pretty name exists or if the argument is {@code null},
     * an empty optional is returned.
     *
     * @param name the sample type pretty name; may be {@code null}.
     * @return optional sample type.
     */
    public static Optional<TiffSampleType> fromPrettyName(String name) {
        return Optional.ofNullable(name == null ? null : PRETTY_NAME_LOOKUP.get(name.toUpperCase()));
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
}
