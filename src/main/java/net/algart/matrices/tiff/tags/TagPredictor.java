/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2024 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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
import java.util.Objects;
import java.util.stream.Collectors;

public enum TagPredictor {
    NONE(1, "none"),
    HORIZONTAL(2, "horizontal differencing"),
    HORIZONTAL_FLOATING_POINT(3, "floating-point horizontal differencing"),
    // - value 3 is not actually supported
    UNKNOWN(-1, "unknown");

    private static final Map<Integer, TagPredictor> LOOKUP =
            Arrays.stream(values()).filter(v -> v.code >= 0)
                    .collect(Collectors.toMap(TagPredictor::code, v -> v));

    private final int code;
    private final String name;

    TagPredictor(int code, String name) {
        this.code = code;
        this.name = Objects.requireNonNull(name);
    }

    public static TagPredictor valueOfCodeOrUnknown(int code) {
        return LOOKUP.getOrDefault(code, UNKNOWN);
    }

    public int code() {
        return code;
    }

    public String prettyName() {
        return name;
    }

    public boolean isUnknown() {
        return this == UNKNOWN;
    }
}
