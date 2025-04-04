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

package net.algart.matrices.tiff.awt;

import net.algart.arrays.JArrays;
import org.scijava.util.Bytes;

import java.lang.reflect.Array;
import java.util.Random;
import java.util.stream.IntStream;

/**
 * The test checks that AWTImages.makeArray, based on AlgART methods,
 * creates the same results as scijava Bytes.makeArray method.
 */
public class BytesMakeImageTest {
    public static void main(String[] args) {
        final Random rnd = new Random(157);
        final byte[] bytes = new byte[1011 * 1011];
        IntStream.range(0, bytes.length).forEach(i -> bytes[i] = (byte) rnd.nextInt());
        for (int bpp = 1; bpp <= 8; bpp++) {
            for (int fp = 0; fp <= 1; fp++) {
                for (int little = 0; little <= 1; little++) {
                    Object array1 = Bytes.makeArray(bytes, bpp, fp > 0, little > 0);
                    Object array2 = AWTImages.makeArray(bytes, bpp, fp > 0, little > 0);
                    if ((array1 == null) != (array2 == null)) throw new AssertionError();
                    if ((array1 == bytes) != (array2 == bytes)) throw new AssertionError();
                    if (array1 == null && little == 0) {
                        System.out.printf("Null for %d, %d%n", bpp, fp);
                    }
                    if (array1 != null && array1 != bytes) {
                        int length1 = Array.getLength(array1);
                        int length2 = Array.getLength(array2);
                        if (length1 != length2)  throw new AssertionError();
                        if (!JArrays.arrayEquals(array1, 0, array2, 0, length2)) {
                            throw new AssertionError("Different content");
                        }
                    }
                }
            }
        }

    }
}
