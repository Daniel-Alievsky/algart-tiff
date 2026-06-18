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

package net.algart.matrices.tiff.tests.misc;

import net.algart.arrays.BitArray;
import net.algart.arrays.PArray;
import net.algart.matrices.tiff.samples.TiffSampleType;

public class TiffSamplesFormatterTest {
    private static void testFormatter(TiffSampleType sampleType, PArray array, boolean supported) {
        System.out.printf("Testing %s that formats %s%n", sampleType, array);
        try {
            String s = sampleType.newFormatter().toString(array);
            System.out.printf("  entire array:        %s%n", s);
            s = sampleType.newFormatter().toString(array, 1,2);
            System.out.printf("  2 elements #1, #2:   %s%n", s);
        } catch (Exception e) {
            if (supported) {
                throw new AssertionError("Unexpected " + e, e);
            }
            System.out.print("  ");
            e.printStackTrace(System.out);
            return;
        }
        if (!supported) {
            throw new AssertionError("Formatting should not be supported!");
        }
    }

    public void test() throws Exception {
        main();
    }

    public static void main(String... args) {
        testFormatter(TiffSampleType.BIT, PArray.as(new long[] {1, 2, 3}), false);
        testFormatter(TiffSampleType.BIT, PArray.as(new int[] {1, 2, 3}), false);
        testFormatter(TiffSampleType.BIT, BitArray.newArray(3), true);
        testFormatter(TiffSampleType.BIT, BitArray.as(new long[] {0x3333}, 5), true);
        testFormatter(TiffSampleType.UINT8, PArray.as(new byte[] {1, 2, -3}), true);
        testFormatter(TiffSampleType.INT8, PArray.as(new byte[] {1, 2, -3}), true);
        testFormatter(TiffSampleType.UINT8, PArray.as(new short[] {1, 2, -3}), false);
        testFormatter(TiffSampleType.INT32, PArray.as(new byte[] {1, 2, -3}), false);
        testFormatter(TiffSampleType.INT32, PArray.as(new int[] {1, 2, -3}), true);
        testFormatter(TiffSampleType.UINT32, PArray.as(new int[] {1, 2, -3}), true);
        testFormatter(TiffSampleType.FLOAT, PArray.as(new int[] {1, 2, -3}), false);
        testFormatter(TiffSampleType.FLOAT, PArray.as(new float[] {1, 2, -3}), true);
        testFormatter(TiffSampleType.DOUBLE, PArray.as(new float[] {1, 2, -3}), false);
        System.out.println("Ok");
    }
}
