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

import java.lang.reflect.Array;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

public final class TiffSamplesFormatter {
    private final TiffSampleType sampleType;
    private boolean hexadecimal = false;
    private boolean normalized = false;
    private String decimalIntegerFormat = null;
    private String floatingPointFormat = "%.3f";
    private String normalizedFormat = "%.3f";
    private String separator = ", ";
    private int maxArrayLength = Integer.MAX_VALUE;
    private int maxStringLength = 10000;
    private Locale locale = Locale.US;

    public TiffSamplesFormatter(TiffSampleType sampleType) {
        this.sampleType = Objects.requireNonNull(sampleType, "Null sample type");
    }

    public boolean isHexadecimal() {
        return hexadecimal;
    }

    public TiffSamplesFormatter setHexadecimal(boolean hexadecimal) {
        this.hexadecimal = hexadecimal;
        return this;
    }

    public boolean isNormalized() {
        return normalized;
    }

    public TiffSamplesFormatter setNormalized(boolean normalized) {
        this.normalized = normalized;
        return this;
    }

    public String getDecimalIntegerFormat() {
        return decimalIntegerFormat;
    }

    public TiffSamplesFormatter setDecimalIntegerFormat(String decimalIntegerFormat) {
        this.decimalIntegerFormat = decimalIntegerFormat;
        return this;
    }

    public String getFloatingPointFormat() {
        return floatingPointFormat;
    }

    public TiffSamplesFormatter setFloatingPointFormat(String floatingPointFormat) {
        this.floatingPointFormat = floatingPointFormat;
        return this;
    }

    public String getNormalizedFormat() {
        return normalizedFormat;
    }

    public TiffSamplesFormatter setNormalizedFormat(String normalizedFormat) {
        this.normalizedFormat = normalizedFormat;
        return this;
    }

    public String getSeparator() {
        return separator;
    }

    public TiffSamplesFormatter setSeparator(String separator) {
        this.separator = Objects.requireNonNull(separator, "Null separator");
        return this;
    }

    public int getMaxArrayLength() {
        return maxArrayLength;
    }

    public TiffSamplesFormatter setMaxArrayLength(int maxArrayLength) {
        if (maxArrayLength < 0) {
            throw new IllegalArgumentException("maxArrayLength cannot be negative");
        }
        this.maxArrayLength = maxArrayLength;
        return this;
    }

    public int getMaxStringLength() {
        return maxStringLength;
    }

    public TiffSamplesFormatter setMaxStringLength(int maxStringLength) {
        if (maxStringLength <= 0) {
            throw new IllegalArgumentException("maxStringLength argument must be positive");
        }
        this.maxStringLength = maxStringLength;
        return this;
    }

    public Locale getLocale() {
        return locale;
    }

    public TiffSamplesFormatter setLocale(Locale locale) {
        this.locale = Objects.requireNonNull(locale, "Null locale");
        return this;
    }

    public String toString(PArray array) {
        return toString(array, 0, array.length32());
    }

    public String toString(PArray array, int offset, int count) {
        Objects.requireNonNull(array, "Null array");
        if (array.elementType() == long.class) {
            // - necessary for TiffSampleType.BIT: we should not pass here LongArray
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
     *                                   packed bits).
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
        if (!(componentType == boolean.class && sampleType == TiffSampleType.BIT) &&
                componentType != sampleType.elementTypeOfJavaArray()) {
            throw new IllegalArgumentException("Invalid javaArray element type: " +
                    componentType.getTypeName() + " instead of " + sampleType.elementTypeOfJavaArray());
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
        final boolean signedDecimal = sampleType.isSigned() && (normalized || !hexadecimal);
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
            double scale = 1.0 / sampleType.maxValue();
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
