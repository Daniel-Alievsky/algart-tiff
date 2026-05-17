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
    TagType type();

    //TODO!! add other types and use it; add TagValueIFD
    abstract class Integer extends Number implements TagValue {
        private final long raw;
        private final boolean signed;

        private Integer(long raw, boolean signed) {
            this.raw = raw;
            this.signed = signed;
        }

        public int intValue() {
            return (int) raw;
        }

        public long longValue() {
            return raw;
        }

        public float floatValue() {
            return (float) raw;
        }

        public double doubleValue() {
            return (double) raw;
        }

        public abstract TagType type();

        public String mathString() {
            return String.valueOf(raw);
        }

        @Override
        public String toString() {
            return mathString() + " (" + (signed? "signed" : "unsigned") + ")";
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (obj == null || obj.getClass() != this.getClass()) {
                return false;
            }
            final Integer that = (Integer) obj;
            return raw == that.raw && signed == that.signed;
        }

        @Override
        public int hashCode() {
            return Objects.hash(raw, signed, getClass().getName());
        }
    }

    class Unsigned8Bit extends Integer {
        private Unsigned8Bit(long raw) {
            super(raw, false);
        }

        public static Unsigned8Bit of(int value) {
            if (value < 0 || value > 0xFF) {
                throw new IllegalArgumentException("Unsigned 8-bit value = " + value +
                        " is out of range 0..255");
            }
            return new Unsigned8Bit(value);
        }

        @Override
        public TagType type() {
            return TagType.BYTE;
        }
    }

    class Signed8Bit extends Integer {
        private Signed8Bit(long raw) {
            super(raw, false);
        }

        public static Signed8Bit of(int value) {
            if (value < Byte.MIN_VALUE || value > Byte.MAX_VALUE) {
                throw new IllegalArgumentException("Signed 8-bit value = " + value +
                        " is out of range " + Byte.MIN_VALUE + " to " + Byte.MAX_VALUE);
            }
            return new Signed8Bit(value);
        }

        @Override
        public TagType type() {
            return TagType.SBYTE;
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

        private RawRational(int rawNumerator, int rawDenominator) {
            this.rawNumerator = rawNumerator;
            this.rawDenominator = rawDenominator;
        }

        public int intValue() {
            return rawDenominator == 0 ? java.lang.Integer.MAX_VALUE : (int) doubleValue();
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
        public boolean equals(Object obj) {
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
        public int hashCode() {
            return Objects.hash(rawNumerator, rawDenominator);
        }

    }

    class Rational extends RawRational {
        private Rational(int unsignedNumerator, int unsignedDenominator) {
            super(unsignedNumerator, unsignedDenominator);
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

        @Override
        public String toString() {
            return numerator() + "/" + denominator() + " (unsigned " + doubleValue() + ")";
        }

        @Override
        public int hashCode() {
            return super.hashCode() ^ 'U';
        }
    }

    class SRational extends RawRational {
        private SRational(int signedNumerator, int signedDenominator) {
            super(signedNumerator, signedDenominator);
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

        @Override
        public String toString() {
            return numerator() + "/" + denominator() + " (signed " + doubleValue() + ")";
        }

        @Override
        public int hashCode() {
            return super.hashCode() ^ 'S';
        }
    }
}
