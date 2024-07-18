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

package net.algart.matrices.tiff.tests.awt;

import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.UpdatablePArray;
import net.algart.io.MatrixIO;
import net.algart.io.awt.MatrixToBufferedImage;
import net.algart.matrices.tiff.awt.AWTImages;

import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.SampleModel;
import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

public class AWTImagesMakeBufferedImageTest {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean packed = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-packed")) {
            packed = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 6) {
            System.out.println("Usage:");
            System.out.println("    " + AWTImagesMakeBufferedImageTest.class.getName()
                    + " some_image.jpeg/png/bmp/... result_1.jpeg/png/bmp/... " +
                    "result_2.jpeg/png/bmp/... result_3.jpeg/png/bmp/... " +
                    "result_4.jpeg/png/bmp/... result_5.jpeg/png/bmp/...");
            return;
        }

        final Path srcFile = Path.of(args[startArgIndex++]);
        final Path resultFile1 = Path.of(args[startArgIndex++]);
        final Path resultFile2 = Path.of(args[startArgIndex++]);
        final Path resultFile3 = Path.of(args[startArgIndex++]);
        final Path resultFile4 = Path.of(args[startArgIndex++]);
        final Path resultFile5 = Path.of(args[startArgIndex]);

        System.out.printf("Reading %s...%n", srcFile);
        BufferedImage bi1 = MatrixIO.readBufferedImage(srcFile);
        System.out.printf("BufferedImage: %s%n", toString(bi1));
        System.out.printf("Writing to %s...%n%n", resultFile1);
        MatrixIO.writeBufferedImage(resultFile1, bi1);
        final int dimX = bi1.getWidth();
        final int dimY = bi1.getHeight();

        final List<Matrix<UpdatablePArray>> matrices1 = MatrixIO.readImage(srcFile);
        System.out.printf("AlgART matrices: %s%n", matrices1);
        System.out.printf("Writing to %s...%n%n", resultFile2);
        MatrixIO.writeImage(resultFile2, matrices1);

        if (packed) {
            bi1 = new MatrixToBufferedImage.InterleavedRGBToPacked().toBufferedImage(Matrices.interleave(matrices1));
            System.out.printf("BufferedImage in packed format: %s%n", toString(bi1));
        }

        Object pixels = AWTImages.getPixels(bi1);
        System.out.printf("All pixels: %s, %d channels%n", pixels.getClass().getSimpleName(), Array.getLength(pixels));
        // - Note: in packed mode, result type will be incorrect (int[][] instead of byte[][])!
        // This problem is not resolved in current version of AWTImages.
        System.out.println();

        // The following code will reduce precision to 8-bit samples
        final byte[][] bytes = AWTImages.getBytes(bi1);
        var matrices3 = Arrays.stream(bytes).map(b -> Matrix.as(b, dimX, dimY)).toList();
        System.out.printf("AWTImages.getBytes matrices: %s%n", matrices3);
        System.out.printf("Writing to %s...%n%n", resultFile3);
        MatrixIO.writeImage(resultFile3, matrices3);

        BufferedImage bi4 = AWTImages.makeImage(bytes, dimX, dimY, false);
        System.out.printf("AWTImages.makeImage: %s%n", toString(bi4));
        System.out.printf("Writing %s...%n%n", resultFile4);
        MatrixIO.writeBufferedImage(resultFile4, bi4);
        // Note: JPEG2000 will be written incorrectly in jai-imageio-jpeg2000 1.4.0!

        DataBuffer buffer = new DataBufferByte(bytes, bytes[0].length);
        BufferedImage bi5 = AWTImages.constructImage(bytes.length,
                DataBuffer.TYPE_BYTE,
                dimX, dimY,
                false,
                true, buffer);
        System.out.printf("AWTImages.constructImage: %s%n", toString(bi5));
        System.out.printf("Writing %s...%n%n%n", resultFile5);
        MatrixIO.writeBufferedImage(resultFile5, bi5);
    }

    static String toString(BufferedImage bi) {
        final SampleModel sm = bi.getSampleModel();
        final DataBuffer db = bi.getData().getDataBuffer();
        return bi
                + "; sample model: " + sm + " " + sm.getWidth() + "x" + sm.getHeight() + "x" + sm.getNumBands()
                + " type " + sm.getDataType()
                + "; data buffer: " + db + " type " + db.getDataType() + ", " + db.getNumBanks() + " banks";
    }
}
