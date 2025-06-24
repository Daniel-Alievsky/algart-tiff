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

package net.algart.matrices.tiff.tests.misc;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;

public class TiffIFDTagDescriptionTest {
    private static void testDescription(TiffIFD ifd, String s, boolean exceptionExpected) {
        boolean exceptionOccurred = false;
        try {
            ifd.putDescription(s);
        } catch (Exception e) {
            if (!exceptionExpected) {
                throw new AssertionError("Unexpected exception", e);
            }
            exceptionOccurred = true;
        }
        System.out.printf("Description: %s%n", ifd.optDescription());
        System.out.println(ifd.toString(TiffIFD.StringFormat.NORMAL));
        System.out.println();
        if (exceptionExpected && !exceptionOccurred) {
            throw new AssertionError("Exception did not occur!");
        }
    }

    public static void main(String[] args) throws TiffException {
        TiffIFD ifd = new TiffIFD();
        testDescription(ifd, "Hello", false);
        testDescription(ifd, "\u041F\u0440\u0438\u0432\u0435\u0442!", true);
        // - russian translation of "Hello"
    }
}
