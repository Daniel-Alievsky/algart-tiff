/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2025 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

/**
 * A rational number (numerator over denominator).
 *
 * @author Curtis Rueden
 */
public class TagRational extends Number implements Comparable<TagRational> {
    // (It is placed here to avoid autocorrection by IntelliJ IDEA)
    /*
     * #%L
     * SCIFIO library for reading and converting scientific file formats.
     * %%
     * Copyright (C) 2011 - 2023 SCIFIO developers.
     * %%
     * Redistribution and use in source and binary forms, with or without
     * modification, are permitted provided that the following conditions are met:
     *
     * 1. Redistributions of source code must retain the above copyright notice,
     *    this list of conditions and the following disclaimer.
     * 2. Redistributions in binary form must reproduce the above copyright notice,
     *    this list of conditions and the following disclaimer in the documentation
     *    and/or other materials provided with the distribution.
     *
     * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
     * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
     * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
     * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
     * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
     * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
     * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
     * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
     * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
     * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
     * POSSIBILITY OF SUCH DAMAGE.
     * #L%
     */

    // -- Fields --

    /**
     * Components of the rational's fractional representation.
     */
    private final long numer;
    private final long denom;

    // -- Constructor --

    /**
     * Constructs a rational number.
     */
    public TagRational(final long numer, final long denom) {
        this.numer = numer;
        this.denom = denom;
    }

    // -- TiffRational API methods --

    /**
     * Gets this number's numerator.
     */
    public long getNumerator() {
        return numer;
    }

    /**
     * Gets this number's denominator.
     */
    public long getDenominator() {
        return denom;
    }

    // -- Number API methods --

    /**
     * Returns the value of the specified number as a byte.
     */
    @Override
    public byte byteValue() {
        return (byte) longValue();
    }

    /**
     * Returns the value of the specified number as a double.
     */
    @Override
    public double doubleValue() {
        return denom == 0 ? Double.MAX_VALUE : ((double) numer / (double) denom);
    }

    /**
     * Returns the value of the specified number as a float.
     */
    @Override
    public float floatValue() {
        return (float) doubleValue();
    }

    /**
     * Returns the value of the specified number as an int.
     */
    @Override
    public int intValue() {
        return (int) longValue();
    }

    /**
     * Returns the value of the specified number as a long.
     */
    @Override
    public long longValue() {
        return denom == 0 ? Long.MAX_VALUE : (numer / denom);
    }

    /**
     * Returns the value of the specified number as a short.
     */
    @Override
    public short shortValue() {
        return (short) longValue();
    }

    // -- Object API methods --

    /**
     * Indicates whether some other object is "equal to" this one.
     */
    @Override
    public boolean equals(final Object o) {
        return o instanceof TagRational && compareTo((TagRational) o) == 0;
    }

    /**
     * Reasonable hash value for use with hashtables.
     */
    @Override
    public int hashCode() {
        return (int) (numer - denom);
    }

    /**
     * Returns a string representation of the object.
     */
    @Override
    public String toString() {
        return numer + "/" + denom;
    }

    // -- Comparable API methods --

    /**
     * Compares this object with the specified object for order. Returns a
     * negative integer, zero, or a positive integer as this object is less than,
     * equal to, or greater than the specified object.
     */
    @Override
    public int compareTo(final TagRational q) {
        long diff = (numer * q.denom - q.numer * denom);
        if (diff > Integer.MAX_VALUE) diff = Integer.MAX_VALUE;
        else if (diff < Integer.MIN_VALUE) diff = Integer.MIN_VALUE;
        return (int) diff;
    }

}
