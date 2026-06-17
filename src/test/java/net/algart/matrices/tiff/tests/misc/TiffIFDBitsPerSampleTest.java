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
    private static void showIFD(TiffIFD ifd, String name, boolean rarePrecision, boolean exceptionExpected)
            throws TiffException {
        System.out.printf("%s%nBrief:%n----%n%s%n----%nNormal:%n----%n%s%n----%n%n",
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
        showIFD(ifd, "Empty", false, false);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{1});
        ifd.put(Tags.SAMPLES_PER_PIXEL, 1);
        showIFD(ifd, "Simplest BitsPerSample {1}", false, false);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{1, 8});
        ifd.put(Tags.SAMPLES_PER_PIXEL, 2);
        showIFD(ifd, "Strange BitsPerSample {1, 8}", false, false);

        ifd.put(Tags.COMPRESSION, TiffIFD.COMPRESSION_OLD_JPEG);
        showIFD(ifd, "Old JPEG", false, false);
        ifd.remove(Tags.COMPRESSION);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{8, 8, 8});
        if (ifd.getSamplesPerPixel() != 2) {
            throw new AssertionError();
        }
        showIFD(ifd, "Normal BitsPerSample {8, 8, 8}, 2 samples/pixel", false, false);

        ifd.put(Tags.SAMPLES_PER_PIXEL, 5);
        if (ifd.getSamplesPerPixel() != 5) {
            throw new AssertionError();
        }
        showIFD(ifd, "Normal BitsPerSample {8, 8, 8}, 5 samples/pixel", false, false);

        ifd.put(Tags.SAMPLES_PER_PIXEL, 4);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{1, -1, 1, 2});
        showIFD(ifd, "Negative BitsPerSample", false, true);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{1, 11, 1, 2});
        showIFD(ifd, "Different BitsPerSample", false, true);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{11, 11, 12, 15});
        showIFD(ifd, "Allowed BitsPerSample", false, false);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{20, 23, 24, 22});
        showIFD(ifd, "Rare BitsPerSample", true, false);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{111, 111, 112});
        showIFD(ifd, "Too large BitsPerSample", false, true);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{40});
        showIFD(ifd, "5-bytes BitsPerSample", false, true);


        ifd.putPixelInformation(1, byte.class);
        showIFD(ifd, "1-byte", false, false);

        ifd.put(Tags.SAMPLE_FORMAT, new int[]{1, 3, 1});
        showIFD(ifd, "Different sample format", false, true);

        ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_COMPLEX_INT);
        showIFD(ifd, "Complex", false, true);

        ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_VOID);
        showIFD(ifd, "Void", false, false);

        ifd.put(Tags.SAMPLES_PER_PIXEL, 3);
        ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_IEEEFP);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{8, 8, 8});
        showIFD(ifd, "Invalid BitsPerSample {8, 8, 8}, float", false, true);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{16, 15, 16});
        showIFD(ifd, "Invalid BitsPerSample {16, 15, 16}, float", false, true);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{24, 24, 16});
        showIFD(ifd, "Different BitsPerSample {24, 24, 16}, float", false, true);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{24, 24, 24});
        showIFD(ifd, "Correct rare 24-bit float", true, false);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{64});
        showIFD(ifd, "Correct 64-bit double", false, false);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[]{16});
        showIFD(ifd, "Correct rare 16-bit float", true, false);
        System.out.println("Ok");
    }
}
