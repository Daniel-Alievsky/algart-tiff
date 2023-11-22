/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2023 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import io.scif.media.imageioimpl.plugins.tiff.TIFFImageReader;
import io.scif.media.imageioimpl.plugins.tiff.TIFFImageReaderSpi;
import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.external.BufferedImageToMatrixConverter;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

public class JAIReadTiffTest {
    private static final int MAX_IMAGE_DIM = 6000;

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage:");
            System.out.println("    " + JAIReadTiffTest.class.getName()
                    + " some_tiff_file.tif ifdIndex result.png");
            return;
        }
        final File tiffFile = new File(args[0]);
        final int ifdIndex = Integer.parseInt(args[1]);
        final File resultFile = new File(args[2]);

        System.out.printf("Opening %s...%n", tiffFile);

        BufferedImage bi = null;
        ImageInputStream stream = ImageIO.createImageInputStream(tiffFile);
        if (stream == null) {
            throw new IIOException("Can't create an ImageInputStream!");
        }
        TIFFImageReader r = new TIFFImageReader(new TIFFImageReaderSpi());
        r.setInput(stream);
        int w = r.getWidth(ifdIndex);
        int h = r.getHeight(ifdIndex);
        ImageReadParam param = r.getDefaultReadParam();
        Rectangle sourceRegion = new Rectangle(0, 0, Math.min(w, MAX_IMAGE_DIM), Math.min(h, MAX_IMAGE_DIM));
        param.setSourceRegion(sourceRegion);
        for (int test = 1; test <= 5; test++) {
            if (test == 1) {
                System.out.printf("Reading %s %s by %dx%d%n",
                        r.getFormatName(),
                        sourceRegion,
                        r.getTileWidth(ifdIndex), r.getTileHeight(ifdIndex));
            }
            long t1 = System.nanoTime();
            bi = r.read(ifdIndex, param);
            long t2 = System.nanoTime();
//            Matrix<? extends PArray> matrix = bufferedImageToPackedBGRA(bi);
            Matrix<? extends PArray> matrix = new BufferedImageToMatrixConverter.ToPacked3D(true)
                    .toMatrix(bi);
            long t3 = System.nanoTime();
            System.out.printf("Test #%d: %s loaded (%.3f MB) in %.3f ms + converted in %.3f ms, %.3f MB/sec%n",
                    test, matrix,
                    Matrices.sizeOf(matrix) / 1048576.0,
                    (t2 - t1) * 1e-6, (t3 - t2) * 1e-6,
                    (Matrices.sizeOf(matrix) / 1048576.0 / ((t3 - t1) * 1e-9)));
        }
        r.dispose();
        stream.close();
        System.out.printf("Successfully read %s%n", bi);

        System.out.printf("Saving result image into %s...%n", resultFile);
        if (!ImageIO.write(bi, "png", resultFile)) {
            throw new IIOException("Cannot write " + resultFile);
        }
        System.out.println("Done");
    }
}
