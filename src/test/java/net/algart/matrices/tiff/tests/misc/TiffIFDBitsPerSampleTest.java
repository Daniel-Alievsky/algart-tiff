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

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.samples.TiffSampleType;
import net.algart.matrices.tiff.tags.Tags;

import java.util.Arrays;

public class TiffIFDBitsPerSampleTest {
    private static void checkSampleTYpe(
            TiffIFD ifd,
            int requiredNormalizedBitDepth,
            TiffSampleType requiredSampleType)
            throws TiffException {
        System.out.printf("BitsPerSample: %s%n", Arrays.toString(ifd.getBitsPerSample()));
        TiffSampleType sampleType = null;
        int normalizedBitDepth = -1;
        try {
            sampleType = ifd.sampleType();
            normalizedBitDepth = ifd.normalizedBitDepth();
        } catch (TiffException e) {
            if (requiredSampleType != null) {
                throw new AssertionError("Unexpected " + e);
            }
            System.out.print("  ");
            e.printStackTrace(System.out);
        }
        if (sampleType != requiredSampleType) {
            throw new AssertionError("Invalid sample type");
        }
        if (normalizedBitDepth != requiredNormalizedBitDepth) {
            throw new AssertionError("Invalid normalized depth");
        }
        System.out.printf("Sample type: %s, normalized bit depth: %d%n", sampleType, normalizedBitDepth);
        System.out.println();
    }

    private static void showToString(TiffIFD ifd, String name, boolean rarePrecision, boolean exceptionExpected)
            throws TiffException {
        System.out.printf("%s%nBrief:%n----%n%s%n----%nNormal:%n----%n%s%n----%n",
                name, ifd, ifd.toString(TiffIFD.StringFormat.NORMAL_SORTED));
        boolean exceptionOccurred = false;
        TiffSampleType sampleType = null;
        try {
            sampleType = ifd.sampleType();
            System.out.printf("Sample type: %s, %s%n", sampleType, sampleType.prettyName());
        } catch (TiffException e) {
            if (!exceptionExpected) {
                throw e;
            }
            e.printStackTrace(System.out);
            exceptionOccurred = true;
        }
        if (!exceptionOccurred) {
            boolean rare = ifd.isRarePrecision();
            System.out.printf("Rare precision: %b%n", rare);
            if (rare != rarePrecision) {
                throw new AssertionError("Invalid rare precision: " +
                        rare + " instead of " + rarePrecision);
            }
            boolean differentPrecision = sampleType.bitsPerSample() != ifd.normalizedBitDepth();
            if (rare != differentPrecision) {
                throw new AssertionError("sampleType.bitsPerSample() = " +
                        sampleType.bitsPerSample() + ", ifd.normalizedBitDepth() = " + ifd.normalizedBitDepth() +
                        ", isRarePrecision() = " + rare);
            }
        }
        try {
            System.out.printf("Bits per sample: %s%n", Arrays.toString(ifd.getBitsPerSample()));
        } catch (TiffException e) {
            if (!exceptionExpected) {
                throw e;
            }
            e.printStackTrace(System.out);
            exceptionOccurred = true;
        }
        System.out.printf("Samples per pixel: %s%n", ifd.getSamplesPerPixel());
        System.out.printf("Floating point: %s%n", ifd.isFloatingPoint());
        System.out.println();
        if (exceptionExpected && !exceptionOccurred) {
            throw new AssertionError("Exception did not occur!");
        }
    }

    public void test() throws Exception {
        main();
    }

    public static void main(String... args) throws TiffException {
        TiffIFD ifd = TiffIFD.newInstance();
        showToString(ifd, "Empty", false, false);
        checkSampleTYpe(ifd, 1, TiffSampleType.BIT);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{1});
        ifd.put(Tags.SAMPLES_PER_PIXEL, 1);
        showToString(ifd, "Simplest BitsPerSample {1}", false, false);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{1, 8});
        ifd.put(Tags.SAMPLES_PER_PIXEL, 2);
        showToString(ifd, "Strange BitsPerSample {1, 8}", false, false);

        ifd.put(Tags.COMPRESSION, TiffIFD.COMPRESSION_OLD_JPEG);
        showToString(ifd, "Old JPEG", false, false);
        ifd.remove(Tags.COMPRESSION);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{8, 8, 8});
        if (ifd.getSamplesPerPixel() != 2) {
            throw new AssertionError();
        }
        showToString(ifd, "Normal BitsPerSample {8, 8, 8}, 2 samples/pixel",
                false, false);

        ifd.put(Tags.BITS_PER_SAMPLE, (short) 8);
        showToString(ifd, "Normal 8-bit BitsPerSample {8}, 2 samples/pixel",
                false, false);
        System.out.println(ifd.toString(TiffIFD.StringFormat.NORMAL));

        ifd.put(Tags.BITS_PER_SAMPLE, (long) 8);
        showToString(ifd, "Normal 64-bit BitsPerSample {8}, 2 samples/pixel",
                false, false);
        System.out.println(ifd.toString(TiffIFD.StringFormat.NORMAL));

        ifd.put(Tags.SAMPLES_PER_PIXEL, 5);
        if (ifd.getSamplesPerPixel() != 5) {
            throw new AssertionError();
        }
        showToString(ifd, "Normal BitsPerSample {8, 8, 8}, 5 samples/pixel",
                false, false);

        ifd.put(Tags.SAMPLES_PER_PIXEL, 4);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{8, 8, 8, 8});
        showToString(ifd, "Normal BitsPerSample {8, 8, 8, 8}", false, false);

        ifd.put(Tags.BITS_PER_SAMPLE, new short[]{8, 8, 8, 8});
        showToString(ifd, "Normal 8-bit BitsPerSample {8, 8, 8, 8}", false, false);
        System.out.println(ifd.toString(TiffIFD.StringFormat.NORMAL));

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{1, -1, 1, 2});
        showToString(ifd, "Negative BitsPerSample", false, true);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{1, 11, 1, 2});
        showToString(ifd, "Different BitsPerSample", false, true);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{11, 11, 12, 15});
        showToString(ifd, "Allowed BitsPerSample", false, false);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{20, 23, 24, 22});
        showToString(ifd, "Rare BitsPerSample", true, false);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{111, 111, 112});
        showToString(ifd, "Too large BitsPerSample", false, true);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{40});
        showToString(ifd, "5-bytes BitsPerSample", false, true);


        ifd.putPixelInformation(1, byte.class);
        showToString(ifd, "1-byte", false, false);

        ifd.put(Tags.SAMPLE_FORMAT, new int[]{1, 3, 1});
        showToString(ifd, "Different sample format", false, true);

        ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_COMPLEX_INT);
        showToString(ifd, "Complex", false, true);

        ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_VOID);
        showToString(ifd, "Void", false, false);

        ifd.put(Tags.SAMPLES_PER_PIXEL, 3);
        ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_IEEEFP);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{8, 8, 8});
        showToString(ifd, "Invalid BitsPerSample {8, 8, 8}, float", false, true);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{16, 15, 16});
        showToString(ifd, "Invalid BitsPerSample {16, 15, 16}, float", false, true);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{24, 24, 16});
        showToString(ifd, "Different BitsPerSample {24, 24, 16}, float", false, true);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{24, 24, 24});
        showToString(ifd, "Correct rare 24-bit float", true, false);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{64});
        showToString(ifd, "Correct 64-bit double", false, false);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{16});
        showToString(ifd, "Correct rare 16-bit float", true, false);

        ifd.remove(Tags.BITS_PER_SAMPLE);
        ifd.remove(Tags.SAMPLE_FORMAT);
        ifd.put(Tags.BITS_PER_SAMPLE, 2);
        checkSampleTYpe(ifd, 8, TiffSampleType.UINT8);
        ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_INT);
        checkSampleTYpe(ifd, 8, TiffSampleType.INT8);

        ifd.put(Tags.SAMPLES_PER_PIXEL, 3);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{5, 6, 5});
        checkSampleTYpe(ifd, 8, TiffSampleType.INT8);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{50, 6, 5});
        checkSampleTYpe(ifd, -1, null);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{32, 32, 32});
        checkSampleTYpe(ifd, 32, TiffSampleType.INT32);
        ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_IEEEFP);
        checkSampleTYpe(ifd, 32, TiffSampleType.FLOAT);
        ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_UINT);

        ifd.put(Tags.SAMPLES_PER_PIXEL, 1);
        ifd.put(Tags.BITS_PER_SAMPLE, 1);
        checkSampleTYpe(ifd, 1, TiffSampleType.BIT);
        ifd.put(Tags.SAMPLES_PER_PIXEL, 3);
        checkSampleTYpe(ifd, 8, TiffSampleType.UINT8);
        System.out.println("Ok");
    }
}
