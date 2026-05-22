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

package net.algart.matrices.tiff.tags;

import net.algart.matrices.tiff.*;
import org.scijava.io.handle.DataHandle;

import java.io.IOException;
import java.util.Objects;

/**
 * Represents a data value within IFD entries, which can be accessed via
 * {@link TiffIFD#get(int)}, {@link TiffIFD#put(int, Object)}, and other {@link TiffIFD} methods.
 *
 * <p>Note: most value types are represented by built-in Java types, such as
 * {@code long}, {@code float}, or {@code String}.
 * This interface is intended for additional data types, like {@link Rational},
 * which do not directly map to built-in Java types.</p>
 *
 * <p>More precisely, the mapping between {@link TagType} and the tag values stored
 * in {@link TiffIFD} is defined as follows:</p>
 *
 * <ul>
 * <li><b>{@code Byte} or {@code byte[]}</b>: mapped to {@link TagType#UNDEFINED}.
 * Written as raw bytes without any interpretation.</li>
 * <li><b>{@code Short} or {@code short[]}</b>: mapped to {@link TagType#BYTE}
 * (unsigned 8-bit values in the range 0..255).</li>
 * <li><b>{@code Integer} or {@code int[]}</b>: mapped to {@link TagType#SHORT}
 * (unsigned 16-bit, 0..65535). Note: a single {@code Integer} value
 * greater than 0xFFFF is written as {@link TagType#LONG} / {@link TagType#LONG8}.</li>
 * <li><b>{@code Long} or {@code long[]}</b>: mapped to {@link TagType#LONG}
 * (unsigned 32-bit) or {@link TagType#LONG8} (unsigned 64-bit) for Big-TIFF files.</li>
 * <li><b>{@link TagValue.SByte TagValue.SByte} or <code>{@link TagValue.SByte}[]</code></b>:
 * mapped to {@link TagType#SBYTE} (signed 8-bit integers, -128..127).</li>
 * <li><b>{@link TagValue.SShort TagValue.SShort} or <code>{@link TagValue.SShort}[]</code></b>:
 * mapped to {@link TagType#SSHORT} (signed 16-bit integers, -32768..32767).</li>
 * <li><b>{@link TagValue.SLong TagValue.SLong} or <code>{@link TagValue.SLong}[]</code></b>:
 * mapped to {@link TagType#SLONG} (signed 32-bit integers).</li>
 * <li><b>{@link TagValue.SLong8 TagValue.SLong8} or <code>{@link TagValue.SLong8}[]</code></b>:
 * mapped to {@link TagType#SLONG8} (signed 64-bit integers). Supported only in
 * Big-TIFF mode; attempting to write it into a classic TIFF file causes an exception.</li>
 * <li><b>{@link TagValue.IFD TagValue.IFD} or <code>{@link TagValue.IFD}[]</code></b>:
 * mapped to {@link TagType#IFD} (32-bit unsigned offset) or {@link TagType#IFD8}
 * (64-bit unsigned offset) for Big-TIFF files.</li>
 * <li><b>{@link TagValue.Rational TagValue.Rational} or
 * <code>{@link TagValue.Rational TagValue.Rational}[]</code></b>: mapped to
 * {@link TagType#RATIONAL} (pairs of unsigned 32-bit integers).</li>
 * <li><b>{@link TagValue.SRational TagValue.SRational} or
 * <code>{@link TagValue.SRational TagValue.SRational}[]</code></b>: mapped to
 * {@link TagType#SRATIONAL} (pairs of signed 32-bit integers).</li>
 * <li><b>{@code Float} or {@code float[]}</b>: mapped to {@link TagType#FLOAT}
 * (32-bit IEEE floating point).</li>
 * <li><b>{@code Double} or {@code double[]}</b>: mapped to {@link TagType#DOUBLE}
 * (64-bit IEEE floating point).</li>
 * <li><b>{@code String} or {@code String[]}</b>:
 * mapped to {@link TagType#ASCII}. Strings are encoded using UTF-8
 * (compatible with ASCII 0..127) and are zero-terminated. Note: an empty
 * array ({@code String[0]}) results in a zero-length tag (value count = 0),
 * while an empty string (or {@code String[1]} array containing one empty string) results in a single
 * zero-terminator byte (value count = 1).</li>
 * </ul>
 *
 * <p>In the list above, the first option (e.g., {@code Float}) is used to represent a single
 * IFD value, while the second option (e.g., {@code float[]}) represents an array of values.
 * The only exception is {@link TagType#ASCII}: in this case, a {@code String[]} array is used to represent
 * multiple strings separated by a zero character.
 * You can use both options in the {@link TiffIFD#put(int, Object)} method
 * when writing an IFD entry that contains a single element;
 * both {@link Float} and {@code float[1]} (an array with one float) will work fine.</p>
 *
 * <p>This mapping is symmetric: {@link TiffWriter} and {@link TiffReader} use the same logic
 * to write and restore Java objects from TIFF tags.
 * For example, {@link TiffWriter} writes a {@code Float} or {@code float[]} value into a TIFF file
 * using the {@link TagType#FLOAT} type, and {@link TiffReader} reads it back as a
 * {@code Float} or {@code float[]} value (stored in the map represented by the {@link TiffIFD} class).
 * There is an obvious exception: an array consisting of 1 element ({@code float[1]}) is stored
 * as a single value (there is no difference inside a TIFF file),
 * and {@link TiffReader} always restores a single value as a single Java object &mdash;
 * {@link Float} in this case.</p>
 *
 * <p><b>Be careful:</b> {@link TagType#BYTE} corresponds to the Java <b>{@code short}</b> type,
 * {@link TagType#SHORT} corresponds to the Java <b>{@code int}</b> type, and
 * {@link TagType#LONG} corresponds to the Java <b>{@code long}</b> type.
 * This helps to ensure correct processing of unsigned values:</p>
 * <ul>
 *     <li>Any unsigned {@link TagType#BYTE} (8 bits) can be safely represented by a
 *     signed {@code short} (16 bits).</li>
 *     <li>Any unsigned {@link TagType#SHORT} (16 bits) can be safely represented by a
 *     signed {@code int} (32 bits).</li>
 *     <li>Any unsigned {@link TagType#LONG} (32 bits) can be safely represented by a
 *     signed {@code long} (64 bits).</li>
 * </ul>
 * <p>If you put values into a {@link TiffIFD} that exceed these bounds
 * (a {@code short} outside 0..255, an {@code int} outside 0..0xFFFF, or a
 * {@code long} outside 0..0xFFFFFFFF), {@link TiffWriter} will throw an exception
 * when attempting to write the IFD to a TIFF file.
 * However, there are exceptions to this rule:</p>
 * <ul>
 *     <li>For Big-TIFF files, all {@code long} values are mapped to {@link TagType#LONG8}.</li>
 *     <li>A <b>single</b> {@code int} value ({@code Integer} or {@code int[1]})
 *     that is greater than 0xFFFF is automatically promoted to {@link TagType#LONG}
 *     (or {@link TagType#LONG8} for Big-TIFF files) instead of {@link TagType#SHORT}.</li>
 * </ul>
 *
 * <p>Note: all classes implementing this interface extend {@link Number}.
 * This ensures that the values can be correctly read as numeric values.
 * Examples: {@link Tags#REFERENCE_BLACK_WHITE} is read as an array of integers in the
 * {@link net.algart.matrices.tiff.data.TiffUnpacking#separateYCbCrToRGB} method,
 * and {@link Tags#SUB_IFD} is read as a {@code long[]} array in the {@link TiffReader#allIFDs()} method.</p>
 *
 * @see TagType#javaType()
 * @see TagType#fromJavaType(Class, boolean)
 */
public sealed interface TagValue permits RawInteger, RawRational {
    /**
     * Write the given value to the given stream.
     * If the {@code bigTiff} flag is set, then the value will be written as an 8-byte long
     * by {@link DataHandle#writeLong(long)} method;
     * otherwise, it will be written as a 4-byte unsigned integer
     * by {@link DataHandle#writeInt(int)} method.
     *
     * @throws TiffException if {@code bigTiff} is set and the value is outside {@code 0..0xFFFFFFFFL} range.
     */
    static void writeUnsigned(DataHandle<?> stream, boolean bigTiff, long value) throws IOException {
        if (bigTiff) {
            stream.writeLong(value);
        } else {
            if (value < 0 || value > 0xFFFFFFFFL) {
                throw new TiffException("Attempt to write " + (value < 0 ? "negative" : "too large") +
                        " 64-bit value as 32-bit: " + value);
            }
            stream.writeInt((int) value);
        }
    }

    static TagValue ofInteger(TagType type, long raw) {
        Objects.requireNonNull(type, "Null tag type");
        return switch (type) {
            case SBYTE -> SByte.of(raw);
            case SSHORT -> SShort.of(raw);
            case SLONG -> SLong.of(raw);
            case SLONG8 -> SLong8.of(raw);
            case IFD -> IFD.ofUnsigned32(raw);
            case IFD8 -> IFD.of(raw);
            default -> throw new IllegalArgumentException("Integer tag type cannot be " + type);
        };
    }

    static TagValue ofRational(TagType type, int rawNumerator, int rawDenominator) {
        Objects.requireNonNull(type, "Null tag type");
        return switch (type) {
            case SRATIONAL -> new SRational(rawNumerator, rawDenominator);
            case RATIONAL -> new Rational(rawNumerator, rawDenominator);
            default -> throw new IllegalArgumentException("Rational tag type cannot be " + type);
        };
    }

    TagType type(boolean bigTiff);

    String mathString();

    void write(DataHandle<?> stream, boolean bigTiff) throws IOException;

    final class SByte extends RawInteger {
        private SByte(long raw) {
            super(TagType.SBYTE, raw, true);
        }

        public static SByte of(long value) {
            if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                throw new IllegalArgumentException("Signed 8-bit value (SBYTE) = " + value +
                        " is out of range " + Byte.MIN_VALUE + " to " + Byte.MAX_VALUE);
            }
            return new SByte(value);
        }

        @Override
        public void write(DataHandle<?> stream, boolean bigTiff) throws IOException {
            stream.writeByte(byteValue());
        }
    }

    final class SShort extends RawInteger {
        private SShort(long raw) {
            super(TagType.SSHORT, raw, true);
        }

        public static SShort of(long value) {
            if (value < Short.MIN_VALUE || value > Short.MAX_VALUE) {
                throw new IllegalArgumentException("Signed 16-bit value (SSHORT) = " + value +
                        " is out of range " + Short.MIN_VALUE + " to " + Short.MAX_VALUE);
            }
            return new SShort(value);
        }

        @Override
        public void write(DataHandle<?> stream, boolean bigTiff) throws IOException {
            stream.writeShort(shortValue());
        }
    }

    final class SLong extends RawInteger {
        private SLong(long raw) {
            super(TagType.SLONG, raw, true);
        }

        public static SLong of(long value) {
            if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Signed 32-bit value (SLONG) = " + value +
                        " is out of range " + Integer.MIN_VALUE + " to " + Integer.MAX_VALUE);
            }
            return new SLong(value);
        }

        public static TagValue of(long value, boolean bigTiff) {
            return bigTiff ? SLong8.of(value) : SLong.of(value);
        }

        @Override
        public void write(DataHandle<?> stream, boolean bigTiff) throws IOException {
            stream.writeInt(intValue());
        }
    }

    final class SLong8 extends RawInteger {
        private SLong8(long raw) {
            super(TagType.SLONG8, raw, true);
        }

        public static SLong8 of(long value) {
            return new SLong8(value);
        }

        @Override
        public void write(DataHandle<?> stream, boolean bigTiff) throws IOException {
            if (bigTiff) {
                stream.writeLong(longValue());
            } else {
                throw new TiffException("Cannot write 64-bit signed integer (SLONG8) = " + longValue() +
                        " to a classic (not Big-TIFF) file: this type is supported only in Big-TIFF");
            }
        }
    }

    /**
     * Class for representing both {@link TagType#IFD} and {@link TagType#IFD8} types.
     *
     * @see TagType#isAutomaticallyAdjustedDependingOnBigTiffMode()
     */
    final class IFD extends RawInteger {
        private IFD(long raw) {
            super(null, raw, false);
        }

        public static IFD ofUnsigned32(long value) {
            if (value < 0 || value > 0xFFFFFFFFL) {
                throw new IllegalArgumentException("Unsigned 32-bit IFD offset = " + value +
                        " is out of range 0..2^32-1");
            }
            return new IFD(value);
        }

        public static IFD of(long value) {
            // - note: attempt to throw an exception if value < 0 is not a good idea!
            // This just means that some IFD8 tag is used probably incorrectly,
            // but this is not a bug of the programmer;
            // we could throw TiffException here, but it is not the purpose of this class
            return new IFD(value);
        }

        @Override
        public TagType type(boolean bigTiff) {
            return bigTiff ? TagType.IFD8 : TagType.IFD;
        }

        @Override
        public String mathString() {
            return Long.toUnsignedString(longValue());
        }

        @Override
        public void write(DataHandle<?> stream, boolean bigTiff) throws IOException {
            writeUnsigned(stream, bigTiff, longValue());
        }

        public String toString() {
            return mathString() + " (IFD offset)";
        }
    }

    final class Rational extends RawRational {
        private Rational(int unsignedNumerator, int unsignedDenominator) {
            super(unsignedNumerator, unsignedDenominator, false);
        }

        public static Rational ofRaw(int unsignedNumerator, int unsignedDenominator) {
            return new Rational(unsignedNumerator, unsignedDenominator);
        }

        public static Rational of(long unsignedNumerator, long unsignedDenominator) {
            if (unsignedNumerator < 0 || unsignedNumerator > 0xFFFFFFFFL) {
                throw new IllegalArgumentException("Unsigned 32-bit numerator = " + unsignedNumerator +
                        " is out of range 0..2^32-1");
            }
            if (unsignedDenominator < 0 || unsignedDenominator > 0xFFFFFFFFL) {
                throw new IllegalArgumentException("Unsigned 32-bit denominator = " + unsignedDenominator +
                        " is out of range 0..2^32-1");
            }
            return new Rational((int) unsignedNumerator, (int) unsignedDenominator);
        }

        @Override
        public long numerator() {
            return rawNumerator() & 0xFFFFFFFFL;
        }

        @Override
        public long denominator() {
            return rawDenominator() & 0xFFFFFFFFL;
        }

        @Override
        public TagType type(boolean bigTiff) {
            return TagType.RATIONAL;
        }
    }

    final class SRational extends RawRational {
        private SRational(int signedNumerator, int signedDenominator) {
            super(signedNumerator, signedDenominator, true);
        }

        public static SRational of(int signedNumerator, int signedDenominator) {
            return new SRational(signedNumerator, signedDenominator);
        }

        @Override
        public long numerator() {
            return rawNumerator();
        }

        @Override
        public long denominator() {
            return rawDenominator();
        }

        @Override
        public TagType type(boolean bigTiff) {
            return TagType.SRATIONAL;
        }
    }
}
