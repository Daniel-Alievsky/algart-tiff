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

import net.algart.matrices.tiff.TiffTools;

import java.util.Arrays;
import java.util.Random;

public class InterleaveSeparateTest {
    public static void main(String[] args) {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("    " + InterleaveSeparateTest.class.getName()
                    + " numberOfTests numberOfPixels");
            return;
        }
        final int numberOfTests = Integer.parseInt(args[0]);
        final int numberOfPixels = Integer.parseInt(args[1]);
        Random rnd = new Random(1425);

        for (int test = 1; test <= numberOfTests; test++) {
            System.out.printf("%nTest #%d/%d:%n", test, numberOfTests);
            for (int numberOfChannels = 1; numberOfChannels <= 5; numberOfChannels++) {
                for (int bytesPerSample = 1; bytesPerSample <= 5; bytesPerSample++) {
                    System.out.printf("  (%d) Testing %d samples per %d bytes:%n",
                            test, numberOfChannels, bytesPerSample);

                    final byte[] bytes = new byte[numberOfChannels * bytesPerSample * numberOfPixels];
                    for (int k = 0; k < bytes.length; k++) {
                        bytes[k] = (byte) rnd.nextInt(256);
                    }
                    testIS(bytes, numberOfChannels, bytesPerSample, numberOfPixels);
                    testSI(bytes, numberOfChannels, bytesPerSample, numberOfPixels);
                }
            }
        }
    }


    private static void testIS(byte[] bytes, int numberOfChannels, int bytesPerSamples, int numberOfPixels) {
        long t1 = System.nanoTime();
        byte[] interleavedSamples = TiffTools.toInterleavedBytes(
                bytes, numberOfChannels, bytesPerSamples, numberOfPixels);
        long t2 = System.nanoTime();
        byte[] separatedSamples = TiffTools.toSeparatedBytes(
                interleavedSamples, numberOfChannels, bytesPerSamples, numberOfPixels);
        long t3 = System.nanoTime();
        if (!Arrays.equals(bytes, separatedSamples)) {
            throw new AssertionError("Separated samples mismatch!");
        }
        System.out.printf("    %.3f ms interleave (%.3f MB/sec), %.3f separate (%.3f MB/sec)%n",
                (t2 - t1) * 1e-6, bytes.length / 1048576.0 / ((t2 - t1) * 1e-9),
                (t3 - t2) * 1e-6, bytes.length / 1048576.0 / ((t3 - t2) * 1e-9));
    }

    private static void testSI(byte[] bytes, int numberOfChannels, int bytesPerSample, int numberOfPixels) {
        long t1 = System.nanoTime();
        byte[] separatedSamples = TiffTools.toSeparatedBytes(
                bytes, numberOfChannels, bytesPerSample, numberOfPixels);
        long t2 = System.nanoTime();
        byte[] interleavedSamples = TiffTools.toInterleavedBytes(
                separatedSamples, numberOfChannels, bytesPerSample, numberOfPixels);
        long t3 = System.nanoTime();
        if (!Arrays.equals(bytes, interleavedSamples)) {
            throw new AssertionError("Interleaved samples mismatch!");
        }
        System.out.printf("    %.3f ms separate (%.3f MB/sec), %.3f interleave (%.3f MB/sec)%n",
                (t2 - t1) * 1e-6, bytes.length / 1048576.0 / ((t2 - t1) * 1e-9),
                (t3 - t2) * 1e-6, bytes.length / 1048576.0 / ((t3 - t2) * 1e-9));
    }
}
