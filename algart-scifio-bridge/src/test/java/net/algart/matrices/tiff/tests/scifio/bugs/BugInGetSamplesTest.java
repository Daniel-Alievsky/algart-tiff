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

package net.algart.matrices.tiff.tests.scifio.bugs;

import io.scif.FormatException;
import io.scif.SCIFIO;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.IFDList;
import io.scif.formats.tiff.TiffParser;
import io.scif.util.FormatTools;
import org.scijava.io.location.FileLocation;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/*
 * Please call this test with arguments
 *  CMU-1.svs 1 result.png
 * CMU-1.svs can be downloaded from https://openslide.org/demo/
 */
public class BugInGetSamplesTest {
    private static final int START_X = 350;
    // - non-zero value allows to illustrate a bug in TiffParser.getSamples
    private static final int START_Y = 0;

    public static void main(String[] args) throws IOException, FormatException {
        if (args.length < 3) {
            System.out.println("Usage:");
            System.out.println("    " + BugInGetSamplesTest.class.getName()
                + " some_tiff_file ifdIndex result.png");
            return;
        }
        File tiffFile = new File(args[0]);
        int ifdIndex = Integer.parseInt(args[1]);
        File resultFile = new File(args[2]);

        System.out.printf("Opening %s...%n", tiffFile);
        SCIFIO scifio = new SCIFIO();
        TiffParser reader = new TiffParser(scifio.getContext(), new FileLocation(tiffFile));
        IFDList ifDs = reader.getIFDs();
        IFD ifd = ifDs.get(ifdIndex);
        int w = (int) ifd.getImageWidth();
        // w--;
        // - uncomment the previous operator, and all will work normally
        int h = (int) ifd.getImageLength();
        int bandCount = ifd.getSamplesPerPixel();
        int bytesPerBand = FormatTools.getBytesPerPixel(ifd.getPixelType());

        byte[] bytes = new byte[w * h * bytesPerBand * bandCount];
        reader.getSamples(ifd, bytes, START_X, START_Y, w, h);
        int pixelType = ifd.getPixelType();

        bytes = interleaveSamples(bytes, w * h, bandCount, bytesPerBand);

        System.out.printf("Saving result image into %s...%n", resultFile);
        BufferedImage image = bytesToImage(bytes, w, h, bandCount);
        if (!ImageIO.write(image, "png", resultFile)) {
            throw new IIOException("Cannot write " + resultFile);
        }
        scifio.getContext().close();
        // - without this operator, program will not exit!!
        System.out.println("Done");
    }

    private static BufferedImage bytesToImage(byte[] bytes, int width, int height, int bandCount) {
        final BufferedImage result = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0, offset = 0; y < height; y++) {
            for (int x = 0; x < width; x++, offset += bandCount) {
                final byte r = bytes[offset];
                final byte g = bytes[bandCount == 1 ? offset : offset + 1];
                final byte b = bytes[bandCount == 1 ? offset : offset + 2];
                final int rgb = (b & 0xFF)
                    | ((g & 0xFF) << 8)
                    | ((r & 0xFF) << 16);
                result.setRGB(x, y, rgb);
            }
        }
        return result;
    }

    public static byte[] interleaveSamples(byte[] samples, int bandSizeInPixels, int bandCount, int bytesPerBand) {
        if (samples == null) {
            throw new NullPointerException("Null samples");
        }
        if (bandSizeInPixels < 0) {
            throw new IllegalArgumentException("Negative bandSizeInPixels = " + bandSizeInPixels);
        }
        if (bandCount <= 0) {
            throw new IllegalArgumentException("Zero or negative bandCount = " + bandCount);
        }
        if (bytesPerBand <= 0) {
            throw new IllegalArgumentException("Zero or negative bytesPerBand = " + bytesPerBand);
        }
        if (bandCount == 1) {
            return samples;
        }
        final int bandSize = bandSizeInPixels * bytesPerBand;
        byte[] interleavedBytes = new byte[samples.length];
        if (bytesPerBand == 1) {
            // optimization
            for (int i = 0, disp = 0; i < bandSize; i++) {
                for (int bandDisp = i; bandDisp < samples.length; bandDisp += bandSize) {
                    interleavedBytes[disp++] = samples[bandDisp];
                }
            }
        } else {
            for (int i = 0, disp = 0; i < bandSize; i += bytesPerBand) {
                for (int bandDisp = i; bandDisp < samples.length; bandDisp += bandSize) {
                    for (int k = 0; k < bytesPerBand; k++) {
                        interleavedBytes[disp++] = samples[bandDisp + k];
                    }
                }
            }
        }
        return interleavedBytes;
    }
}
