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

import net.algart.matrices.tiff.TiffReader;

public class CheckRequestedAreaTest {
    private static void callCheckRequestedArea(long fromX, long fromY, long sizeX, long sizeY, boolean exception) {
        System.out.printf("%d, %d, %d x %d    ", fromX, fromY, sizeX, sizeY);
        try {
            TiffReader.checkRequestedArea(fromX, fromY, sizeX, sizeY);
            if (exception) {
                throw new AssertionError("No exception!");
            }
        } catch (IllegalArgumentException e) {
            if (!exception) {
                throw new AssertionError(e);
            }
            //noinspection ThrowablePrintedToSystemOut
            System.out.print(e);
        }
        System.out.println();
    }

    public static void main(String[] args) {
        callCheckRequestedArea(0, 0, 0, -1, true);
        callCheckRequestedArea(-1000, -1000, 10000000, 1000000, false);
        callCheckRequestedArea(1000, 1000, Integer.MAX_VALUE, Integer.MAX_VALUE, true);
        callCheckRequestedArea(-10000000000L, 1000, Integer.MAX_VALUE, Integer.MAX_VALUE, true);
        callCheckRequestedArea(0, 10000000000L, Integer.MAX_VALUE, Integer.MAX_VALUE, true);
        callCheckRequestedArea(0, 10000, Long.MAX_VALUE, Long.MAX_VALUE, true);
        callCheckRequestedArea(0, 0, -100000000000L, 0, true);
        callCheckRequestedArea(0, 0, 0, 100000000000L, true);
        callCheckRequestedArea(0, 0, Integer.MAX_VALUE - 1, Integer.MAX_VALUE, true);
    }
}
