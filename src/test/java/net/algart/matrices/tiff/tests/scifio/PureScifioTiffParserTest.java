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

package net.algart.matrices.tiff.tests.scifio;

import io.scif.FormatException;
import io.scif.SCIFIO;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.IFDList;
import io.scif.formats.tiff.TiffParser;
import io.scif.util.FormatTools;
import net.algart.matrices.tiff.tests.TiffInfo;
import org.scijava.Context;
import org.scijava.io.location.FileLocation;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

public class PureScifioTiffParserTest {
    private static final int MAX_IMAGE_DIM = 12000;

    private static final int START_X = 0;
    // - non-zero value allows to illustrate a bug in original TiffParser.getSamples
    private static final int START_Y = 0;

    public static void main(String[] args) throws IOException, FormatException {
        if (args.length < 3) {
            System.out.println("Usage:");
            System.out.println("    " + PureScifioTiffParserTest.class.getName()
                    + " some_tiff_file.tif result.png ifd_index [number_of_tests]");
            return;
        }
        final File tiffFile = new File(args[0]);
        final File resultFile = new File(args[1]);
        final int ifdIndex = Integer.parseInt(args[2]);
        final int numberOfTests = args.length <= 3 ? 1 : Integer.parseInt(args[3]);

        System.out.printf("Opening %s...%n", tiffFile);
        Context context = new SCIFIO().getContext();
        TiffParser parser = new TiffParser(context, new FileLocation(tiffFile));
        IFDList ifDs = parser.getIFDs();
        final IFD ifd = ifDs.get(ifdIndex);
//        ifd.put(IFD.BITS_PER_SAMPLE, new int[] {8, 12, 8});
//        - will not work in new TiffParser
        final int w = (int) Math.min(ifd.getImageWidth(), MAX_IMAGE_DIM);
        final int h = (int) Math.min(ifd.getImageLength(), MAX_IMAGE_DIM);
        final int bandCount = ifd.getSamplesPerPixel();

        System.out.printf("Reading data %dx%dx%d from IFD #%d/%d:%n%s%n",
                w, h, bandCount, ifdIndex, ifDs.size(), net.algart.matrices.tiff.compatibility.TiffParser.toTiffIFD(ifd));
        final int pixelType = ifd.getPixelType();
        final int bytesPerBand = Math.max(1, FormatTools.getBytesPerPixel(pixelType));
        byte[] bytes = null;
        for (int test = 1; test <= numberOfTests; test++) {
            bytes = new byte[w * h * bytesPerBand * bandCount];
            long t1 = System.nanoTime();
            parser.getSamples(ifd, bytes, START_X, START_Y, w, h);
            long t2 = System.nanoTime();
            System.out.printf(Locale.US, "Test (SCIFIO original) #%d: %dx%d (%.3f MB) loaded in %.3f ms, " +
                            "%.3f MB/sec%n",
                    test, w, h, bytes.length / 1048576.0,
                    (t2 - t1) * 1e-6,
                    (bytes.length / 1048576.0 / ((t2 - t1) * 1e-9)));
        }

        System.out.printf("Converting data to BufferedImage...%n");
        final BufferedImage image = bytesToImage(bytes, w, h, bandCount);
        System.out.printf("Saving result image into %s...%n", resultFile);
        if (!ImageIO.write(image, TiffInfo.extension(resultFile.getName(), "bmp"), resultFile)) {
            throw new IIOException("Cannot write " + resultFile);
        }
        context.close();
        System.out.println("Done");
    }

    private static BufferedImage bytesToImage(byte[] bytes, int w, int h, int bandCount) {
        final BufferedImage result = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0, offset = 0; y < h; y++) {
            for (int x = 0; x < w; x++, offset++) {
                final byte r = bytes[offset];
                final byte g = bytes[bandCount == 1 ? offset : offset + w * h];
                final byte b = bytes[bandCount == 1 ? offset : offset + 2 * w * h];
                final int rgb = (b & 0xFF)
                        | ((g & 0xFF) << 8)
                        | ((r & 0xFF) << 16);
                result.setRGB(x, y, rgb);
            }
        }
        return result;
    }
}
