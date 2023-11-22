/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2023 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import java.util.Objects;

public enum TiffSampleType {
    INT8(0, "int8", 1, byte.class, true),
    UINT8(1, "uint8", 1, byte.class, false),
    INT16(2, "int16", 2, short.class, true),
    UINT16(3, "uint16", 2, short.class, false),
    INT32(4, "int32", 4, int.class, true),
    UINT32(5, "uint32", 4, int.class, false),
    FLOAT(6, "float", 4, float.class, true),
    DOUBLE(7, "double", 8, double.class, true);

    private final int code;
    private final String prettyName;
    private final int bytesPerSample;
    private final Class<?> elementType;
    private final boolean signed;

    TiffSampleType(int code, String prettyName, int bytesPerSample, Class<?> elementType, boolean signed) {
        this.code = code;
        this.prettyName = prettyName;
        this.bytesPerSample = bytesPerSample;
        this.elementType = elementType;
        this.signed = signed;
    }

    /**
     * Returns integer code 0..7 for INT8, UINT8, INT16, UINT16, INT32, UINT32, FLOAT, DOUBLE.
     * This code is compatible with pixel type constants from SCIFIO library (<tt>FormatTools</tt> class).
     *
     * @return integer code of this sample type.
     */
    public int code() {
        return code;
    }

    public String prettyName() {
        return prettyName;
    }

    public int bytesPerSample() {
        return bytesPerSample;
    }

    public Class<?> elementType() {
        return elementType;
    }

    public boolean isSigned() {
        return signed;
    }

    public boolean isFloatingPoint() {
        return this == FLOAT || this == DOUBLE;
    }

    public static TiffSampleType valueOf(Class<?> elementType, boolean signedIntegers) {
        Objects.requireNonNull(elementType, "Null elementType");
        if (elementType == byte.class) {
            return signedIntegers ? INT8 : UINT8;
        } else if (elementType == short.class) {
            return signedIntegers ? INT16 : UINT16;
        } else if (elementType == int.class) {
            return signedIntegers ? INT32 : UINT32;
        } else if (elementType == float.class) {
            return FLOAT;
        } else if (elementType == double.class) {
            return DOUBLE;
        } else {
            throw new IllegalArgumentException("Element type " + elementType +
                    " cannot be converted to TIFF sample type");
        }
    }
}
