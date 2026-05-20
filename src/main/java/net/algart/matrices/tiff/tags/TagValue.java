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
 * Data value in IFD entries, that can be accessed via
 * {@link TiffIFD#get(int)},
 * {@link TiffIFD#put(int, Object)} and other {@link TiffIFD} methods.
 *
 * <p>Note: most value types are represented by built-in Java types, such as
 * {@code long}, {@code float}, or {@code String}.
 * This interface is intended for additional data types like {@link Rational},
 * which do not directly map to built-in Java types.</p>
 *
 * <p>More precisely, the correspondence between {@link TagType} and tag values, stored
 * in {@link TiffIFD}, is the following. </p>
 *
 * <ul>
 *   <li><b>{@code Byte} or {@code byte[]}</b>: mapped to {@link TagType#UNDEFINED}.
 *       Written as raw bytes without any interpretation.</li>
 *   <li><b>{@code Short} or {@code short[]}</b>: mapped to {@link TagType#BYTE}.
 *       Expected to be unsigned 8-bit values in the range 0..255.</li>
 *   <li><b>{@code Integer} or {@code int[]}</b>: mapped to {@link TagType#SHORT}
 *       (unsigned 16-bit, 0..65535). Note: a single {@code Integer} value
 *       exceeding 0xFFFF is automatically promoted to {@link TagType#LONG}.</li>
 *   <li><b>{@code Long} or {@code long[]}</b>: mapped to {@link TagType#LONG}
 *       (unsigned 32-bit) or {@link TagType#LONG8} for BigTIFF files.</li>
 *   <li><b>{@link TagValue.Rational TagValue.Rational} or
 *   <code>{@link TagValue.Rational TagValue.Rational}[]</code></b>: mapped to
 *       {@link TagType#RATIONAL} (pairs of unsigned 32-bit integers).</li>
 *   <li><b>{@link TagValue.SRational TagValue.SRational} or
 *   <code>{@link TagValue.SRational TagValue.SRational}[]</code></b>:
 *   mapped to
 *       {@link TagType#SRATIONAL} (pairs of signed 32-bit integers).</li>
 *   <li><b>{@code Float} or {@code float[]}</b>: mapped to {@link TagType#FLOAT}
 *       (32-bit IEEE floating point).</li>
 *   <li><b>{@code Double} or {@code double[]}</b>: mapped to {@link TagType#DOUBLE}
 *       (64-bit IEEE floating point).</li>
 *   <li><b>{@code String} or {@code String[]}</b>:
 *       mapped to {@link TagType#ASCII}. Strings are encoded using UTF-8
 *       (compatible with ASCII 0..127) and are zero-terminated. Note: an empty
 *       array or list results in a zero-length tag (the value count = 0),
 *       while an empty string (or a list containing one empty string) results in a single
 *       zero-terminator byte (the value count = 1).</li>
 * </ul>
 *
 * <p>In the list above, the 1st option (e.g., {@code Float}) is used for representing a single
 * IFD value, and the 2nd option (e.g., {@code float[]} &mdash; for an array of values.
 * The only exception is {@link TagType#ASCII}: in this case, {@code String[]} array is used for representing
 * several strings separated by zero character.
 * You can use both options in {@link TiffIFD#put(int, Object)} method
 * for writing IFD entry containing a single element:
 * both {@link Float} and {@code float[1]} (array with 1 float) will work fine.</p>
 *
 * <p><b>Be careful:</b> {@link TagType#BYTE} corresponds to <b>{@code short}</b> Java type,
 * {@link TagType#SHORT} &mdash; to <b>{@code int}</b> Java type, and
 * {@link TagType#LONG} &mdash; to <b>{@code long}</b> Java type!
 * This helps to provide correct processing unsigned values:</p>
 * <ul>
 *     <li>any unsigned {@link TagType#BYTE} (8 bits) can be easily represented by
 *     {@code short} value (signed 16 bits),</li>
 *     <li>any unsigned {@link TagType#SHORT} (16 bits) can be easily represented by
 *     {@code int} value (signed 32 bits),</li>
 *     <li>any unsigned {@link TagType#LONG} (32 bits) can be easily represented by
 *     {@code long} value (signed 64 bits).</li>
 * </ul>
 * <p>If you try to put into {@link TiffIFD} primitive values, exceeding the corresponding limit
 * ({@code short} value out of the range 0..255, {@code int value} out of the range 0..0xFFFF,
 * {@code long} value out of the range 0..0xFFFFFFFF}) will throw an exception while an attempt to
 * write the IFD to a TIFF file by {@link TiffWriter}.
 * But there are exceptions to the last rule:</p>
 * <ul>
 *     <li>for Big-TIFF files, all {@code long} values are mapped to {@link TagType#LONG8};</li>
 *     <li>for not Big-TIFF files, a <b>single</b> {@code int} value ({@code Integer} or {@code int[1]}),
 *     which is greater than 0xFFFF, is mapped to {@link TagType#LONG} instead of {@link TagType#SHORT}.</li>
 * </ul>
 *
 * <p>Note: all classes implementing this interface extend {@link Number}.
 * This is useful to ensure correct reading of certain tags as numeric values.
 * Examples: {@link Tags#REFERENCE_BLACK_WHITE} is read as a {@code int[]} array in the
 * {@link net.algart.matrices.tiff.data.TiffUnpacking#separateYCbCrToRGB} method,
 * and {@link Tags#SUB_IFD} is read as a {@code long[]} array in the {@link TiffReader#allIFDs()} method.</p>
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
                throw new TiffException("Attempt to write too large 64-bit value as 32-bit: " +
                        Long.toUnsignedString(value));
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
            stream.writeLong(longValue());
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
