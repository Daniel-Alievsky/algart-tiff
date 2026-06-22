/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2026 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import io.scif.codec.CodecOptions;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.app.TiffInfo;
import net.algart.matrices.tiff.compatibility.TiffParser;
import net.algart.matrices.tiff.tiles.TiffReadMap;
import org.scijava.Context;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffParserTest {
    private static final int MAX_IMAGE_DIM = 10000;

    public static void main(String... args) throws IOException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 3) {
            System.out.println("Usage:");
            System.out.println("    " + TiffParserTest.class.getName() +
                    " some_tiff_file result.png ifdIndex " +
                    "[x y width height [number_of_tests]");
            return;
        }
        final Path tiffFile = Paths.get(args[startArgIndex++]);
        final Path resultFile = Paths.get(args[startArgIndex++]);
        int ifdIndex = Integer.parseInt(args[startArgIndex++]);
        final int x = args.length <= startArgIndex ? 0 : Integer.parseInt(args[startArgIndex]);
        final int y = args.length <= ++startArgIndex ? 0 : Integer.parseInt(args[startArgIndex]);
        int w = args.length <= ++startArgIndex ? -1 : Integer.parseInt(args[startArgIndex]);
        int h = args.length <= ++startArgIndex ? -1 : Integer.parseInt(args[startArgIndex]);
        final int numberOfTests = args.length <= ++startArgIndex ? 1 : Integer.parseInt(args[startArgIndex]);

        try (final Context context = (Context) TiffReader.newSCIFIOContext();
             TiffParser parser = new TiffParser(context, tiffFile)) {
            parser.setCaching(false);
            parser.setColorCorrection(true);
            parser.setByteFiller((byte) 0x80);
            final CodecOptions codecOptions = new CodecOptions();
            codecOptions.maxBytes = 1000;
            // - should not affect the results
            parser.setCodecOptions(codecOptions);
            System.out.printf("TiffParser context: %s%n", parser.getContext());

            // parser.setAssumeEqualStrips(true);
            final int numberOfIFDS = parser.numberOfImages();
            if (numberOfIFDS == 0) {
                System.out.println("No IFDs");
                return;
            }
            if (ifdIndex >= numberOfIFDS) {
                System.out.printf("%nNo IFD #%d, using last IFD #%d instead%n%n", ifdIndex, numberOfIFDS - 1);
                ifdIndex = numberOfIFDS - 1;
            }
            //noinspection deprecation
            final TiffReadMap map = parser.map(TiffParser.toTiffIFD(parser.getIFDs().get(ifdIndex)));
            if (w < 0) {
                w = Math.min(map.dimX(), MAX_IMAGE_DIM);
            }
            if (h < 0) {
                h = Math.min(map.dimY(), MAX_IMAGE_DIM);
            }
            final int bandCount = map.numberOfChannels();

            Matrix<? extends PArray> matrix = null;
            for (int test = 1; test <= Math.max(1, numberOfTests); test++) {
                if (test == 1) {
                    System.out.printf("Reading data %dx%dx%d from %s%n",
                            w, h, bandCount,
                            new TiffInfo().ifdInformation(parser, map.ifd(), ifdIndex));
                }
                matrix = map.readMatrix(x, y, w, h);
            }

            System.out.printf("Saving result image into %s - %s...%n", resultFile, matrix);
            MatrixIO.writeImage(resultFile, matrix.asLayers());
        }
        System.out.println("Done");
    }
}
