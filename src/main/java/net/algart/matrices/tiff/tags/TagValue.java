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
import org.scijava.io.handle.DataHandle;

import java.io.IOException;
import java.util.Objects;

/**
 * Data value in IFD entries.
 *
 * <p>Note: most value types are represented by built-in Java types, such as
 * {@code long}, {@code float}, {@code String}.
 * This interface is implemented by additional data types like {@link Rational},
 * which do not directly map to built-in Java types.
 */
public interface TagValue {
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
                throw new TiffException("Attempt to write too large 64-bit value as 32-bit: " + value);
            }
            stream.writeInt((int) value);
        }
    }

    TagType type();

    String mathString();

    void write(DataHandle<?> stream, boolean bigTiff) throws IOException;

    abstract class RawInteger extends Number implements TagValue {
        private final TagType type;
        private final long raw;
        private final boolean signed;

        private RawInteger(TagType type, long raw, boolean signed) {
            this.type = Objects.requireNonNull(type);
            this.raw = raw;
            this.signed = signed;
        }

        public static RawInteger of(TagType type, long raw) {
            Objects.requireNonNull(type, "Null tag type");
            return switch (type) {
                case SBYTE -> SByte.of(raw);
                case SSHORT -> SShort.of(raw);
                case SLONG -> SLong.of(raw);
                case SLONG8 -> SLong8.of(raw);
                case IFD -> IFD.of(raw);
                case IFD8 -> IFD.ofUnsigned32(raw);
                default -> throw new IllegalArgumentException("RawInteger cannot be " + type);
            };
        }

        public final int intValue() {
            return (int) raw;
        }

        public final long longValue() {
            return raw;
        }

        public final float floatValue() {
            return (float) raw;
        }

        public final double doubleValue() {
            return (double) raw;
        }

        public final TagType type() {
            return type;
        }

        public String mathString() {
            return String.valueOf(raw);
        }

        @Override
        public String toString() {
            return mathString()
                    + (signed ? " (signed " : " (unsigned ") + type.bitsPerElement() + "-bits)";
        }

        @Override
        public final boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj == null || obj.getClass() != this.getClass()) {
                return false;
            }
            final RawInteger that = (RawInteger) obj;
            return raw == that.raw && signed == that.signed;
        }

        @Override
        public final int hashCode() {
            return Objects.hash(raw, signed, type);
        }
    }

    class SByte extends RawInteger {
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

    class SShort extends RawInteger {
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

    class SLong extends RawInteger {
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

    class SLong8 extends RawInteger {
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

    class IFD extends RawInteger {
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

    /**
     * Base class for representing types {@link TagType#RATIONAL} and {@link TagType#SRATIONAL}.
     *
     * <p>Note: it extends {@link Number} class to provide correct reading some tags as
     * {@link Tags#REFERENCE_BLACK_WHITE} as numeric values
     * (this is used in {@link net.algart.matrices.tiff.data.TiffUnpacking#separateYCbCrToRGB}).
     */
    abstract class RawRational extends Number implements TagValue {
        private final int rawNumerator;
        private final int rawDenominator;
        private final boolean signed;

        private RawRational(int rawNumerator, int rawDenominator, boolean signed) {
            this.rawNumerator = rawNumerator;
            this.rawDenominator = rawDenominator;
            this.signed = signed;
        }

        public static RawRational of(TagType type, int rawNumerator, int rawDenominator) {
            Objects.requireNonNull(type, "Null tag type");
            return switch (type) {
                case SRATIONAL -> new SRational(rawNumerator, rawDenominator);
                case RATIONAL -> new Rational(rawNumerator, rawDenominator);
                default -> throw new IllegalArgumentException("RawRational cannot be " + type);
            };
        }

        public int intValue() {
            return rawDenominator == 0 ? Integer.MAX_VALUE : (int) doubleValue();
        }

        public long longValue() {
            return rawDenominator == 0 ? Long.MAX_VALUE : (long) doubleValue();
        }

        public float floatValue() {
            return (float) doubleValue();
        }

        public double doubleValue() {
            return rawDenominator == 0 ? Double.MAX_VALUE : ((double) numerator() / (double) denominator());
        }

        public int rawNumerator() {
            return rawNumerator;
        }

        public int rawDenominator() {
            return rawDenominator;
        }

        public abstract long numerator();

        public abstract long denominator();

        public abstract TagType type();

        public String mathString() {
            return numerator() + "/" + denominator();
        }

        @Override
        public void write(DataHandle<?> stream, boolean bigTiff) throws IOException {
            stream.writeInt(rawNumerator);
            stream.writeInt(rawDenominator);
        }

        @Override
        public final boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj == null || obj.getClass() != this.getClass()) {
                return false;
            }
            final RawRational that = (RawRational) obj;
            return this.rawNumerator == that.rawNumerator && this.rawDenominator == that.rawDenominator;
        }

        @Override
        public final int hashCode() {
            return Objects.hash(rawNumerator, rawDenominator, signed);
        }

        @Override
        public final String toString() {
            return numerator() + "/" + denominator() + (signed ? " (signed " : " (unsigned ") + doubleValue() + ")";
        }
    }

    class Rational extends RawRational {
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

    class SRational extends RawRational {
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
