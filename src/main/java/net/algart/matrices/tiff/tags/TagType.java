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
    BYTE(1, 1),
    ASCII(2, 1),
    SHORT(3, 2),
    LONG(4, 4),
    RATIONAL(5, 8),
    SBYTE(6, 1),
    UNDEFINED(7, 1),
    SSHORT(8, 2),
    SLONG(9, 4),
    SRATIONAL(10, 8),
    FLOAT(11, 4),
    DOUBLE(12, 8),
    /**
     * Used for storing IFD offsets.
     */
    IFD(13, 4),
    LONG8(16, 8),
    SLONG8(17, 8),
    IFD8(18, 8);

    private static final Map<Integer, TagType> LOOKUP =
            Arrays.stream(values()).collect(Collectors.toMap(TagType::type, v -> v));

    private final int type;
    private final int sizeOf;

    TagType(int id, int sizeOf) {
        if (id <= 0) {
            throw new IllegalArgumentException("id must be positive");
        }
        if (sizeOf <= 0) {
            throw  new IllegalArgumentException("sizeOf must be positive");
        }
        this.type = id;
        this.sizeOf = sizeOf;
    }

    public int type() {
        return type;
    }

    public int sizeOf() {
        return sizeOf;
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
