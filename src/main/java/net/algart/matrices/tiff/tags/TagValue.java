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

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffReader;
import org.scijava.io.handle.DataHandle;

import java.io.IOException;
import java.util.Objects;


/**
 * Data value in IFD entries.
 *
 * <p>Note: most value types are represented by built-in Java types, such as
 * {@code long}, {@code float}, or {@code String}.
 * This interface is implemented by additional data types like {@link Rational},
 * which do not directly map to built-in Java types.</p>
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

    TagType type();

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
            super(TagType.IFD, raw, false);
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
        public TagType type() {
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
        public TagType type() {
            return TagType.SRATIONAL;
        }
    }
}
