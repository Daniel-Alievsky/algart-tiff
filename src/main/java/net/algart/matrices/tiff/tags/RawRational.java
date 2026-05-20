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

import org.scijava.io.handle.DataHandle;

import java.io.IOException;
import java.util.Objects;

/**
 * Base class for representing types {@link TagType#RATIONAL} and {@link TagType#SRATIONAL}.
 *
 * <p>Note: it extends {@link Number} class to provide correct reading some tags as
 * {@link Tags#REFERENCE_BLACK_WHITE} as numeric values
 * (this is used in {@link net.algart.matrices.tiff.data.TiffUnpacking#separateYCbCrToRGB}).
 */
abstract sealed class RawRational extends Number implements TagValue permits TagValue.Rational, TagValue.SRational {
    private final int rawNumerator;
    private final int rawDenominator;
    private final boolean signed;

    RawRational(int rawNumerator, int rawDenominator, boolean signed) {
        this.rawNumerator = rawNumerator;
        this.rawDenominator = rawDenominator;
        this.signed = signed;
    }

    @Override
    public int intValue() {
        return rawDenominator == 0 ? Integer.MAX_VALUE : (int) doubleValue();
    }

    @Override
    public long longValue() {
        return rawDenominator == 0 ? Long.MAX_VALUE : (long) doubleValue();
    }

    @Override
    public float floatValue() {
        return (float) doubleValue();
    }

    @Override
    public double doubleValue() {
        return rawDenominator == 0 ? Double.MAX_VALUE : ((double) numerator() / (double) denominator());
    }

    int rawNumerator() {
        return rawNumerator;
    }

    int rawDenominator() {
        return rawDenominator;
    }

    public abstract long numerator();

    public abstract long denominator();

    @Override
    public abstract TagType type(boolean bigTiff);

    @Override
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
        final net.algart.matrices.tiff.tags.RawRational that = (net.algart.matrices.tiff.tags.RawRational) obj;
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
