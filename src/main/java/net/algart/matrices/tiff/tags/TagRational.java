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
 * Base class for representing types {@link TagType#RATIONAL} and {@link TagType#RATIONAL}.
 *
 * <p>Note: it extends {@link Number} class to provide correct reading some tags as
 * {@link Tags#REFERENCE_BLACK_WHITE} as numeric values
 * (this is used in {@link net.algart.matrices.tiff.data.TiffUnpacking#separateYCbCrToRGB}).
 *
 */
public abstract class TagRational extends Number {
    private final int rawNumerator;
    private final int rawDenominator;

    private TagRational(int rawNumerator, int rawDenominator) {
        this.rawNumerator = rawNumerator;
        this.rawDenominator = rawDenominator;
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
        final TagRational that = (TagRational) obj;
        return this.rawNumerator == that.rawNumerator && this.rawDenominator == that.rawDenominator;
    }

    @Override
    public int hashCode() {
        return Objects.hash(rawNumerator, rawDenominator);
    }

    public static class Unsigned extends TagRational {
        private Unsigned(int unsignedNumerator, int unsignedDenominator) {
            super(unsignedNumerator, unsignedDenominator);
        }

        public static Unsigned ofRaw(int unsignedNumerator, int unsignedDenominator) {
            return new Unsigned(unsignedNumerator, unsignedDenominator);
        }

        public static Unsigned of(long unsignedNumerator, long unsignedDenominator) {
            if (unsignedNumerator < 0 || unsignedNumerator > 0xFFFFFFFFL) {
                throw new IllegalArgumentException("Unsigned 32-bit numerator = " + unsignedNumerator +
                        " is out of range 0..2^32-1");
            }
            if (unsignedDenominator < 0 || unsignedDenominator > 0xFFFFFFFFL) {
                throw new IllegalArgumentException("Unsigned 32-bit denominator = " + unsignedDenominator +
                        " is out of range 0..2^32-1");
            }
            return new Unsigned((int) unsignedNumerator, (int) unsignedDenominator);
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
        public String toString() {
            return numerator() + "/" + denominator() + " (unsigned " + doubleValue() + ")";
        }

        @Override
        public int hashCode() {
            return super.hashCode() ^ 'U';
        }
    }

    public static class Signed extends TagRational {
        private Signed(int signedNumerator, int signedDenominator) {
            super(signedNumerator, signedDenominator);
        }

        public static Signed of(int signedNumerator, int signedDenominator) {
            return new Signed(signedNumerator, signedDenominator);
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
        public String toString() {
            return numerator() + "/" + denominator() + " (signed " + doubleValue() + ")";
        }

        @Override
        public int hashCode() {
            return super.hashCode() ^ 'S';
        }
    }
}
