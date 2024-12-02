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

package net.algart.matrices.tiff.tests.io;

import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;

public class TiffWriteHugeTiledFileTest {
    private final static int IMAGE_WIDTH = 10 * 1024;
    private final static int IMAGE_HEIGHT = 10 * 1024;

    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean bigTiff = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-bigTiff")) {
            bigTiff = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.println("    " + TiffWriteHugeTiledFileTest.class.getName() +
                    " [-bigTiff] " +
                    "target.tiff number_of_images");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex++]);
        final int numberOfImages = Integer.parseInt(args[startArgIndex]);

        System.out.println("Writing huge TIFF " + targetFile);
        try (final TiffWriter writer = new TiffWriter(targetFile)) {
            writer.setBigTiff(bigTiff);
            writer.setLittleEndian(true);
            writer.create();
            for (int k = 1; k <= numberOfImages; k++) {
                TiffIFD ifd = writer.newIFD(true);
                ifd.putImageDimensions(IMAGE_WIDTH, IMAGE_HEIGHT);
                ifd.putTileSizes(1024, 1024);
                ifd.putPixelInformation(3, byte.class);
                final var map = writer.newMap(ifd, false);

                final byte[] samples = new byte[IMAGE_WIDTH * IMAGE_HEIGHT * 3];
                Arrays.fill(samples, (byte) (10 * k));
                long t1 = System.nanoTime();

                writer.writeSamples(map, samples);
                long t2 = System.nanoTime();
                System.out.printf(Locale.US, "Image #%d/%d: %dx%d written in %.3f ms, %.3f MB/sec%n",
                        k, numberOfImages, IMAGE_WIDTH, IMAGE_HEIGHT,
                        (t2 - t1) * 1e-6, samples.length / 1048576.0 / ((t2 - t1) * 1e-9));

            }
        }
        System.out.println("Done");
    }
}
