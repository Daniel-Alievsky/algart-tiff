/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2025 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import net.algart.arrays.Matrix;
import net.algart.arrays.UpdatablePArray;
import net.algart.io.awt.ImageToMatrix;
import net.algart.matrices.tiff.awt.AWTImages;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.stream.ImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

public class AWTImageConversionsSpeed {
    private static final boolean LITTLE_ENDIAN = false;

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("    " + AWTImageConversionsSpeed.class.getName()
                    + " some_image.jpeg/png/bmp/... result.jpeg/png/bmp/... [number_of_tests]");
            return;
        }

        final File srcFile = new File(args[0]);
        final File resultFile = new File(args[1]);
        final int numberOfTests = args.length == 2 ? 1 : Integer.parseInt(args[2]);

        System.out.printf("Opening %s...%n", srcFile);
        final ImageInputStream stream = ImageIO.createImageInputStream(srcFile);
        if (stream == null) {
            throw new IIOException("Can't create an ImageInputStream!");
        }
        BufferedImage bi = ImageIO.read(stream);
        System.out.printf("Read image %s%n", bi);
        final int dimX = bi.getWidth();
        final int dimY = bi.getHeight();
        final int tt = bi.getRaster().getTransferType();
        final boolean floatingPoint = tt == DataBuffer.TYPE_FLOAT || tt == DataBuffer.TYPE_DOUBLE;
        int bytesPerSample = -1;

        byte[][] data = null;
        for (int test = 1; test <= numberOfTests; test++) {
            System.out.printf("%nDecoding test %d%n", test);
            long t1 = System.nanoTime();
            data = AWTImages.getPixelBytes(bi, LITTLE_ENDIAN);
            long t2 = System.nanoTime();
            ImageToMatrix converter = new ImageToMatrix.ToInterleavedRGB();
            Matrix<? extends UpdatablePArray> matrix = converter.toMatrix(bi);
            long t3 = System.nanoTime();
            bytesPerSample = data[0].length / (dimX * dimY);
            final int size = data.length * data[0].length;
            System.out.printf(Locale.US,
                    "AWTImages decoding image %dx%dx%d, %d samples per %d bytes: %.3f ms (%.3f MB/sec)%n" +
                            "AlgART decoding %s: %.3f ms%n",
                    dimX, dimY, data.length, size / bytesPerSample, bytesPerSample,
                    (t2 - t1) * 1e-6, size / 1048576.0 / ((t2 - t1) * 1e-9),
                    matrix, (t3 - t2) * 1e-6);

        }

        bi = null;
        for (int test = 1; test <= numberOfTests; test++) {
            System.out.printf("%nEncoding test %d%n", test);
            long t1 = System.nanoTime();
            bi = AWTImages.makeImage(
                    data, dimX, dimY, bytesPerSample, floatingPoint, LITTLE_ENDIAN, false);
            long t2 = System.nanoTime();
            final int size = data.length * data[0].length;
            System.out.printf(Locale.US,
                    "Encoding image %dx%dx%d, %d samples per %d bytes: " +
                            "%.3f ms AWTImages (%.3f MB/sec)%n",
                    dimX, dimY, data.length, size / bytesPerSample, bytesPerSample,
                    (t2 - t1) * 1e-6, size / 1048576.0 / ((t2 - t1) * 1e-9));
        }

        System.out.printf("%nWriting %s...%n", resultFile);
        resultFile.delete();
        if (!ImageIO.write(bi, extension(resultFile.getName(), "bmp"), resultFile)) {
            throw new IOException("No suitable writer");
        }
    }

    static String extension(String fileName, String defaultExtension) {
        int p = fileName.lastIndexOf('.');
        if (p == -1) {
            return defaultExtension;
        }
        return fileName.substring(p + 1);
    }
}
