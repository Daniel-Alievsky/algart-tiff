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

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Data types in IFD entries supported by the TIFF and BigTIFF specifications.
 */
public enum TagType {
    /**
     * 8-bit unsigned integer.
     */
    BYTE(1, 1, false),

    /**
     * 8-bit byte that contains a 7-bit ASCII code; the last byte must be NUL (binary zero).
     */
    ASCII(2, 1, false),

    /**
     * 16-bit (2-byte) unsigned integer.
     */
    SHORT(3, 2, false),

    /**
     * 32-bit (4-byte) unsigned integer.
     */
    LONG(4, 4, false),

    /**
     * Two LONGs: the first represents the numerator of a fraction; the second, the denominator.
     */
    RATIONAL(5, 8, false),

    /**
     * An 8-bit signed (2's complement) integer.
     */
    SBYTE(6, 1, true),

    /**
     * An 8-bit byte that may contain anything, depending on the definition of the field.
     */
    UNDEFINED(7, 1, false),

    /**
     * A 16-bit (2-byte) signed (2's complement) integer.
     */
    SSHORT(8, 2, true),

    /**
     * A 32-bit (4-byte) signed (2's complement) integer.
     */
    SLONG(9, 4, true),

    /**
     * Two SLONGs: the first represents the numerator; the second, the denominator.
     */
    SRATIONAL(10, 8, true),

    /**
     * Single precision (4-byte) IEEE format floating point value.
     */
    FLOAT(11, 4, true),

    /**
     * Double precision (8-byte) IEEE format floating point value.
     */
    DOUBLE(12, 8, true),
    /**
     * 32-bit unsigned integer, used for storing IFD offsets.
     * Semantically equivalent to {@link #LONG}.
     */
    IFD(13, 4, false),

    /**
     * 64-bit unsigned integer (BigTIFF only).
     */
    LONG8(16, 8, false),

    /**
     * 64-bit signed integer (BigTIFF only).
     */
    SLONG8(17, 8, true),

    /**
     * 64-bit unsigned integer, used for storing IFD offsets (BigTIFF only).
     * Semantically equivalent to {@link #LONG8}.
     */
    IFD8(18, 8, false);

    private static final Map<Integer, TagType> LOOKUP =
            Arrays.stream(values()).collect(Collectors.toMap(TagType::type, v -> v));

    private final int type;
    private final int sizeOf;
    private final boolean signed;

    TagType(int id, int sizeOf, boolean signed) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (sizeOf <= 0) {
            throw  new IllegalArgumentException("sizeOf must be positive");
        }
        this.type = id;
        this.sizeOf = sizeOf;
        this.signed = signed;
    }

    public int type() {
        return type;
    }

    public int sizeOf() {
        return sizeOf;
    }

    public int bitsPerElement() {
        return 8 * sizeOf;
    }

    /**
     * Returns {@code true} for signed types: {@link #SBYTE}, {@link #SSHORT},
     * {@link #SLONG}, {@link #SLONG8}, and also for floating-point types {@link #FLOAT} and {@link #DOUBLE}.
     *
     * @return whether this type is signed.
     */
    public boolean isSigned() {
        return signed;
    }

    public static String toString(int type) {
        return fromType(type).map(Enum::name).orElseGet(() -> "Unknown type (" + type + ")");
    }

    /**
     * Returns an {@link Optional} containing the {@link TagType} with the given {@link #type()}.
     * <p>If no data kind with the specified name exists, an empty optional is returned.
     *
     * @param type the type code.
     * @return optional tag type.
     */
    public static Optional<TagType> fromType(int type) {
        return Optional.ofNullable(LOOKUP.get(type));
    }
}
