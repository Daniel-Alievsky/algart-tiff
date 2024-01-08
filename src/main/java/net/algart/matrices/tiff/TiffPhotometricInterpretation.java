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

package net.algart.matrices.tiff;

import java.util.HashMap;
import java.util.Map;

public enum TiffPhotometricInterpretation {

    WHITE_IS_ZERO(0, "White-is-zero"),
    BLACK_IS_ZERO(1, "Black-is-zero"),
	RGB(2, "RGB"),
	RGB_PALETTE(3, "RGB palette"),
	TRANSPARENCY_MASK(4, "Transparency mask"),
	CMYK(5, "CMYK"),
	Y_CB_CR(6, "YCbCr"),
	CIE_LAB(8, "CIELAB"),
	ICC_LAB(9, "ICCLAB"),
	CFA_ARRAY(32803, "Color filter array"),
    UNKNOWN(-1, "unknown");

    private int code;
    private String name;

    private static final Map<Integer, TiffPhotometricInterpretation> lookup = new HashMap<>();
    static {
        for (TiffPhotometricInterpretation v : TiffPhotometricInterpretation.values()) {
            if (v.code >= 0) {
                lookup.put(v.code, v);
            }
        }
    }

    TiffPhotometricInterpretation(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public static TiffPhotometricInterpretation valueOfCodeOrUnknown(int code) {
        return lookup.getOrDefault(code, UNKNOWN);
    }

    public int code() {
        checkUnknown();
        return code;
    }

    public String prettyName() {
        return name;
    }

    public boolean isInvertedBrightness() {
        return this == WHITE_IS_ZERO || this == CMYK;
    }

    public boolean isIndexed() {
        return this == RGB_PALETTE || this == CFA_ARRAY;
    }

    public boolean isUnknown() {
        return this == UNKNOWN;
    }

    public TiffPhotometricInterpretation checkUnknown() {
        if (this == UNKNOWN) {
            throw new IllegalArgumentException("Unknown TIFF photometric interpretation is not allowed");
        }
        return this;
    }
}
