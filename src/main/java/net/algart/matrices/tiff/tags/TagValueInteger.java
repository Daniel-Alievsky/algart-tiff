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

//TODO!! add other types and use it; add TagValueIFD
public abstract class TagValueInteger extends Number {
    private final long raw;
    private final boolean signed;

    private TagValueInteger(long raw, boolean signed) {
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
        final TagValueInteger that = (TagValueInteger) obj;
        return raw == that.raw && signed == that.signed;
    }

    @Override
    public int hashCode() {
        return Objects.hash(raw, signed, getClass().getName());
    }

    public static class Unsigned8Bit extends TagValueInteger {
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

    public static class Signed8Bit extends TagValueInteger {
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
}
