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

package net.algart.matrices.tiff.app.explorer;

import java.nio.ByteOrder;
import java.util.Objects;

enum UserByteOrder {
    BIG_ENDIAN(ByteOrder.BIG_ENDIAN, "Big-endian"),
    LITTLE_ENDIAN(ByteOrder.LITTLE_ENDIAN, "Little-endian");

    private final ByteOrder byteOrder;
    private final String name;

    UserByteOrder(ByteOrder byteOrder, String name) {
        this.byteOrder = byteOrder;
        this.name = name;
    }

    public static UserByteOrder ofByteOrder(ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder, "Null byte order");
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            return BIG_ENDIAN;
        } else if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
            return LITTLE_ENDIAN;
        } else {
            throw new IllegalArgumentException("Unknown byte order: " + byteOrder);
        }
    }

    public ByteOrder byteOrder() {
        return byteOrder;
    }

    @Override
    public String toString() {
        return name;
    }
}
