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

import net.algart.arrays.BitArray;
import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.math.functions.AbstractFunc;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tiles.TiffMap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public class TiffWriteSimpleBitTest {
    private final static int IMAGE_WIDTH = 10 * 1024;
    private final static int IMAGE_HEIGHT = 10 * 1024;

    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean bigTiff = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-bigTiff")) {
            bigTiff = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 3) {
            System.out.println("Usage:");
            System.out.println("    " + TiffWriteSimpleBitTest.class.getName() +
                    " [-bigTiff] " +
                    "target.tiff dimX dimY [numberOfTests]");
            System.out.println("Try to set large dimX*dimY, >2 giga-pixels.");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex++]);
        final int dimX = Integer.parseInt(args[startArgIndex++]);
        final int dimY = Integer.parseInt(args[startArgIndex++]);
        final int numberOfTests = startArgIndex < args.length ? Integer.parseInt(args[startArgIndex]) : 1;

        Matrix<BitArray> m = Matrices.asCoordFuncMatrix(
                new AbstractFunc() {
                    @Override
                    public double get(double... x) {
                        return ((int) (x[0] - x[1]) & 0xFFF) < 0x800 ? 0 : 1;
                    }
                },
                BitArray.class, dimX, dimY).clone();

        System.out.println("Writing huge TIFF " + targetFile);
        try (final TiffWriter writer = new TiffWriter(targetFile)) {
            writer.setBigTiff(bigTiff);
            writer.setLittleEndian(true);
            for (int k = 1; k <= numberOfTests; k++) {
                writer.create();
                TiffIFD ifd = writer.newIFD(true);
                ifd.putMatrixInformation(m);
                ifd.removeTileInformation();
                ifd.putDefaultStripSize();
                final TiffMap map = writer.newMap(ifd, false);

                long t1 = System.nanoTime();
                writer.writeMatrix(map, m);
                long t2 = System.nanoTime();
                System.out.printf(Locale.US, "Test #%d/%d: bit matrix %dx%d (%,d elements) " +
                                "written in %.3f ms, %.3f MB/sec%n",
                        k, numberOfTests, m.dimX(), m.dimY(), m.size(),
                        (t2 - t1) * 1e-6, Matrices.sizeOf(m) / 1048576.0 / ((t2 - t1) * 1e-9));

            }
        }
        System.out.println("Done");
    }
}
