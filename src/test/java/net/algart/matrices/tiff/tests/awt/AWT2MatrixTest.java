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
import net.algart.io.awt.BufferedImageToMatrix;
import net.algart.io.awt.MatrixToBufferedImage;
import net.algart.matrices.tiff.awt.AWTImages;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public class AWT2MatrixTest {
    public static void main(String[] args) throws IOException {
        if (args.length < 5) {
            System.out.println("Usage:");
            System.out.println("    " + AWT2MatrixTest.class.getName()
                    + " some_image.jpeg/png/bmp/... result_1.jpeg/png/bmp/... " +
                    "result_2.jpeg/png/bmp/... result_3.jpeg/png/bmp/... result_4.jpeg/png/bmp/... ");
            return;
        }

        final Path srcFile = Path.of(args[0]);
        final Path resultFile1 = Path.of(args[1]);
        final Path resultFile2 = Path.of(args[2]);
        final Path resultFile3 = Path.of(args[3]);
        final Path resultFile4 = Path.of(args[4]);

        System.out.printf("Reading %s...%n", srcFile);
        BufferedImage bi1 = MatrixIO.readBufferedImage(srcFile);
        System.out.printf("BufferedImage: %s%n", bi1);
        System.out.printf("Writing BufferedImage to %s...%n", resultFile1);
        MatrixIO.writeBufferedImage(resultFile1, bi1);

        final Matrix<UpdatablePArray> matrix = new BufferedImageToMatrix.ToInterleavedRGB().toMatrix(bi1);
        List<Matrix<UpdatablePArray>> matrices = Matrices.separate(null, matrix);
        final int dimX = (int) matrices.get(0).dimX();
        final int dimY = (int) matrices.get(0).dimY();
        final byte[][] bytes = new byte[matrices.size()][];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = matrices.get(i).array().jaByte();
        }
        BufferedImage bi2 = AWTImages.makeImage(bytes, dimX, dimY, false);

        System.out.printf("BufferedImage: %s%n", bi2);
        System.out.printf("Writing AWTImage conversion to %s...%n", resultFile2);
        MatrixIO.writeBufferedImage(resultFile2, bi2);

        BufferedImage bi3 = new MatrixToBufferedImage.InterleavedRGBToInterleaved().toBufferedImage(
                Matrices.interleave(null, matrices));
        System.out.printf("BufferedImage: %s%n", bi3);
        System.out.printf("Writing AlgART conversion to %s...%n", resultFile3);
        MatrixIO.writeBufferedImage(resultFile3, bi3);

        BufferedImage bi4 = new BufferedImage(dimX, dimY, BufferedImage.TYPE_INT_BGR);
        Graphics graphics = bi4.getGraphics();
        graphics.setFont(new Font("Monospaced", Font.PLAIN, 50));
        graphics.setColor(Color.GREEN);
        graphics.drawString("Hello", 100, 100);
        System.out.printf("BufferedImage: %s%n", bi4);
        System.out.printf("Writing new test image to %s...%n", resultFile4);
        MatrixIO.writeBufferedImage(resultFile4, bi4);
        // Note: JPEG2000 will be written incorrectly in jai-imageio-jpeg2000 1.4.0!
    }
}
