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

package net.algart.matrices.tiff.demo.io;

import net.algart.arrays.ArrayContext;
import net.algart.arrays.Arrays;
import net.algart.arrays.Matrix;
import net.algart.arrays.UpdatablePArray;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tiles.TiffMap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TiffAnalyseMeanSimpleDemo {
    private static final int BLOCK_SIZE = 500;

    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean lowLevel = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-lowLevel")) {
            lowLevel = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.printf("    [-lowLevel] %s source.tiff ifdIndex%n",
                    TiffAnalyseMeanSimpleDemo.class.getName());
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex + 1]);

        System.out.printf("Analysing TIFF %s...%n", sourceFile);
        try (TiffReader reader = new TiffReader(sourceFile).setCaching(true)) {
            TiffMap map = reader.map(ifdIndex);
            long t1 = System.nanoTime();
            double[] mean = lowLevel ?
                    meanLowLevel(map) :
                    meanHighLevel(map);
            long t2 = System.nanoTime();
            System.out.printf("Mean channels values (%s): %s, calculated in %.3f ms, %.3f megapixels/sec%n",
                    lowLevel ? "low-level" : "high-level",
                    java.util.Arrays.toString(mean),
                    (t2 - t1) * 1e-6,
                    map.totalSizeInPixels() / 1048576 / ((t2 - t1) * 1e-9));
        }
        System.out.println("Done");
    }

    private static double[] meanHighLevel(TiffMap map) throws IOException {
        double[] sum = new double[map.numberOfChannels()];
        @SuppressWarnings("resource") TiffReader reader = map.owningReader();
        for (int y = 0; y < map.dimY(); y += BLOCK_SIZE) {
            if (y > 5000) {
                System.out.printf("\r%d/%d...\r", y, map.dimY());
            }
            for (int x = 0; x < map.dimX(); x += BLOCK_SIZE) {
                int sizeX = Math.min(map.dimX() - x, BLOCK_SIZE);
                int sizeY = Math.min(map.dimY() - y, BLOCK_SIZE);
                List<Matrix<UpdatablePArray>> matrices = reader.readChannels(map, x, y, sizeX, sizeY);
                for (int c = 0; c < sum.length; c++) {
                    sum[c] += Arrays.sumOf(ArrayContext.DEFAULT_SINGLE_THREAD, matrices.get(c).array());
                }
            }
        }
        for (int k = 0; k < sum.length; k++) {
            sum[k] /= map.totalSizeInPixels();
        }
        return sum;
    }

    private static double[] meanLowLevel(TiffMap map) throws IOException {
        double[] sum = new double[map.numberOfChannels()];
        @SuppressWarnings("resource") TiffReader reader = map.owningReader();
        for (int y = 0; y < map.dimY(); y += BLOCK_SIZE) {
            if (y > 5000) {
                System.out.printf("\r%d/%d...\r", y, map.dimY());
            }
            for (int x = 0; x < map.dimX(); x += BLOCK_SIZE) {
                int sizeX = Math.min(map.dimX() - x, BLOCK_SIZE);
                int sizeY = Math.min(map.dimY() - y, BLOCK_SIZE);
                Object javaArray = reader.readJavaArray(map, x, y, sizeX, sizeY);
                int length = sizeX * sizeY;
                for (int c = 0; c < sum.length; c++) {
                    sum[c] += sumLowLevel(javaArray, c * length, (c + 1) * length);
                }
            }
        }
        for (int k = 0; k < sum.length; k++) {
            sum[k] /= map.totalSizeInPixels();
        }
        return sum;
    }

    private static double sumLowLevel(Object javaArray, int from, int to) {
        if (javaArray instanceof byte[] a) {
            long sum = 0;
            for (int i = from; i < to; i++) {
                byte v = a[i];
                sum += v & 0xFF;
            }
            return sum;
        } else if (javaArray instanceof short[] a) {
            long sum = 0;
            for (int i = from; i < to; i++) {
                short v = a[i];
                sum += v & 0xFFFF;
            }
            return sum;
        } else if (javaArray instanceof int[] a) {
            long sum = 0;
            for (int i = from; i < to; i++) {
                int v = a[i];
                sum += v;
            }
            return sum;
        } else if (javaArray instanceof long[] a) {
            double sum = 0;
            for (int i = from; i < to; i++) {
                long v = a[i];
                sum += v;
            }
            return sum;
        } else if (javaArray instanceof float[] a) {
            double sum = 0;
            for (int i = from; i < to; i++) {
                float v = a[i];
                sum += v;
            }
            return sum;
        } else if (javaArray instanceof double[] a) {
            double sum = 0;
            for (int i = from; i < to; i++) {
                double v = a[i];
                sum += v;
            }
            return sum;
        } else {
            throw new IllegalArgumentException("Unsupported array type: " + javaArray.getClass().getName());
        }
    }
}
