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

package net.algart.matrices.tiff.tests.misc;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffSampleType;
import net.algart.matrices.tiff.tags.Tags;

import java.util.Arrays;

public class IFDSampleTypeTest {
    static void showTag(TiffIFD ifd, int requiredAlignedBitDepth, TiffSampleType requiredSampleType) throws TiffException {
        System.out.printf("BitsPerSample: %s%n", Arrays.toString(ifd.getBitsPerSample()));
        TiffSampleType sampleType = null;
        int alignedBitDepth = -1;
        try {
            sampleType = ifd.sampleType();
            alignedBitDepth = ifd.alignedBitDepth();
        } catch (TiffException e) {
            if (requiredSampleType != null) {
                throw new AssertionError("Unexpected " + e);
            }
            System.out.println("  " + e);
        }
        if (sampleType != requiredSampleType) {
            throw new AssertionError("Invalid sample type");
        }
        if (alignedBitDepth != requiredAlignedBitDepth) {
            throw new AssertionError("Invalid aligned depth");
        }
        System.out.printf("  aligned bits: %d, sample type: %s%n", alignedBitDepth, sampleType);
    }

    public static void main(String[] args) throws TiffException {
        TiffIFD ifd = new TiffIFD();
        showTag(ifd, 1, TiffSampleType.BIT);
        ifd.put(Tags.BITS_PER_SAMPLE, 2);
        showTag(ifd, 8, TiffSampleType.UINT8);
        ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_INT);
        showTag(ifd, 8, TiffSampleType.INT8);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{5, 6, 5});
        showTag(ifd, 8, TiffSampleType.INT8);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{50, 6, 5});
        showTag(ifd, -1, null);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{32, 32, 32});
        showTag(ifd, 32, TiffSampleType.INT32);
        ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_IEEEFP);
        showTag(ifd, 32, TiffSampleType.FLOAT);
        ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_UINT);
        ifd.put(Tags.BITS_PER_SAMPLE, 1);
        showTag(ifd, 1, TiffSampleType.BIT);
        ifd.put(Tags.SAMPLES_PER_PIXEL, 3);
        showTag(ifd, 8, TiffSampleType.UINT8);
    }
}
