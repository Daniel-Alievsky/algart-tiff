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

import io.scif.formats.tiff.TiffCompression;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffSampleType;
import net.algart.matrices.tiff.tags.Tags;

import java.util.Arrays;

public class StrangeIFDBitsPerSampleTest {
    private static void showIFD(TiffIFD ifd, String name, boolean exceptionExpected) throws TiffException {
        System.out.printf("%s%nBrief:%n----%n%s%n----%nNormal:%n----%n%s%n----%n%n",
                name, ifd, ifd.toString(TiffIFD.StringFormat.NORMAL_SORTED));
        boolean exceptionOccurred = false;
        try {
            TiffSampleType sampleType = ifd.sampleType();
            System.out.printf("Sample type: %s, %s%n", sampleType, sampleType.prettyName());
        } catch (TiffException e) {
            if (!exceptionExpected) {
                throw e;
            }
            e.printStackTrace(System.out);
            exceptionOccurred = true;
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
        System.out.println();
        if (exceptionExpected && !exceptionOccurred) {
            throw new AssertionError("Exception did not occur!");
        }
    }

    public static void main(String[] args) throws TiffException {
        TiffIFD ifd = new TiffIFD();
        showIFD(ifd, "Empty", false);

//        ifd.put(DetailedIFD.SAMPLES_PER_PIXEL, 3); // - should not be necessary for exception on -1

        ifd.put(Tags.BITS_PER_SAMPLE, new int[] {1, 8});
        ifd.put(Tags.COMPRESSION, TiffCompression.OLD_JPEG.getCode());
        showIFD(ifd, "Old JPEG", false);

        ifd.remove(Tags.COMPRESSION);
        ifd.put(Tags.BITS_PER_SAMPLE, new int[] {1, -1, 1, 2});
        showIFD(ifd, "BitsPerSample (negative)", true);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[] {1, 11, 1, 2});
        showIFD(ifd, "BitsPerSample (different)", true);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[] {11, 11, 12, 15});
        showIFD(ifd, "BitsPerSample (normal)", false);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[] {111, 111, 112});
        showIFD(ifd, "BitsPerSample (large)", true);

        ifd.put(Tags.BITS_PER_SAMPLE, new int[] {40});
        showIFD(ifd, "BitsPerSample (5 bytes)", true);


        ifd.putPixelInformation(1, byte.class);
        showIFD(ifd, "1-byte", false);

        ifd.put(Tags.SAMPLE_FORMAT, new int[] {1, 3, 1});
        showIFD(ifd, "Different sample format", true);

        ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_COMPLEX_INT);
        showIFD(ifd, "Complex", true);

        ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_VOID);
        showIFD(ifd, "Void", false);
    }
}
