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

abstract sealed class RawInteger extends Number implements TagValue
        permits TagValue.SByte, TagValue.SShort, TagValue.SLong, TagValue.SLong8, TagValue.IFD {
    private final TagType type;
    private final long raw;
    private final boolean signed;

    RawInteger(TagType type, long raw, boolean signed) {
        this.type = Objects.requireNonNull(type);
        this.raw = raw;
        this.signed = signed;
    }

    @Override
    public final int intValue() {
        return (int) raw;
    }

    @Override
    public final long longValue() {
        return raw;
    }

    @Override
    public final float floatValue() {
        return (float) raw;
    }

    @Override
    public final double doubleValue() {
        return (double) raw;
    }

    @Override
    public final TagType type() {
        return type;
    }

    @Override
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
        final net.algart.matrices.tiff.tags.RawInteger that = (net.algart.matrices.tiff.tags.RawInteger) obj;
        return raw == that.raw && signed == that.signed;
    }

    @Override
    public final int hashCode() {
        return Objects.hash(raw, signed, type);
    }
}
