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

public class TagTypes {
    private TagTypes() {
    }

    public static final int BYTE = 1;
    public static final int ASCII = 2;
    public static final int SHORT = 3;
    public static final int LONG = 4;
    public static final int RATIONAL = 5;
    public static final int SBYTE = 6;
    public static final int UNDEFINED = 7;
    public static final int SSHORT = 8;
    public static final int SLONG = 9;
    public static final int SRATIONAL = 10;
    public static final int FLOAT = 11;
    public static final int DOUBLE = 12;
    public static final int IFD = 13;
    public static final int LONG8 = 16;
    public static final int SLONG8 = 17;
    public static final int IFD8 = 18;

    public static String typeToString(int tagType) {
        return switch (tagType) {
            case BYTE -> "BYTE";
            case ASCII -> "ASCII";
            case SHORT -> "SHORT";
            case LONG -> "LONG";
            case RATIONAL -> "RATIONAL";
            case SBYTE -> "SBYTE";
            case UNDEFINED -> "UNDEFINED";
            case SSHORT -> "SSHORT";
            case SLONG -> "SLONG";
            case SRATIONAL -> "SRATIONAL";
            case FLOAT -> "FLOAT";
            case DOUBLE -> "DOUBLE";
            case IFD -> "IFD";
            case LONG8 -> "LONG8";
            case SLONG8 -> "SLONG8";
            case IFD8 -> "IFD8";
            default -> "Unknown type (" + tagType + ")";
        };
    }

    public static int sizeOfType(int tagType) {
        return switch (tagType) {
            case BYTE, ASCII, UNDEFINED, SBYTE -> 1;
            case SHORT, SSHORT -> 2;
            case LONG, IFD, FLOAT, SLONG -> 4;
            case RATIONAL, IFD8, SLONG8, LONG8, DOUBLE, SRATIONAL -> 8;
            default -> 0;
        };
    }
}
