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
import net.algart.matrices.tiff.tags.Tags;

import java.util.Arrays;

public class TiffIFDSupportedBitDepthTest {
    static void showBitDepth(TiffIFD ifd, boolean supported) throws TiffException {
        System.out.printf("BitsPerSample: %s%n", Arrays.toString(ifd.getBitsPerSample()));
        System.out.print("  aligned bit depth: ");
        int alignedBitDepth = -1;
        try {
            alignedBitDepth = ifd.alignedBitDepth();
            System.out.printf("%d%n", alignedBitDepth);
        } catch (TiffException e) {
            System.out.printf("%s%n", e);
        }
        int bitDepth = -1;
        try {
            bitDepth = ifd.checkSupportedBitDepth();
            if (!supported) {
                throw new AssertionError("Bit depth should not be supported!");
            }
        } catch (TiffException e) {
            if (supported) {
                throw new AssertionError("Unexpected " + e, e);
            }
            System.out.printf("  %s%n", e);
        }
        if (bitDepth != -1) {
            System.out.printf("  supported bit depth: %d%n", bitDepth);
        }
        System.out.println();
    }

    public static void main(String[] args) throws TiffException {
        TiffIFD ifd = new TiffIFD();
        showBitDepth(ifd, true);
        ifd.put(Tags.BITS_PER_SAMPLE, 2);
        showBitDepth(ifd, false);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{5, 6, 5});
        showBitDepth(ifd, false);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{8, 8, 8});
        showBitDepth(ifd, true);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{8, 9, 8});
        showBitDepth(ifd, false);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{16, 16});
        showBitDepth(ifd, true);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{24});
        showBitDepth(ifd, false);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{32});
        showBitDepth(ifd, true);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{64, 64});
        showBitDepth(ifd, true);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{8, 16});
        showBitDepth(ifd, false);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{1, 1 ,1});
        showBitDepth(ifd, false);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{1});
        showBitDepth(ifd, true);
    }
}
