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

package net.algart.matrices.tiff.tests.io;

import io.scif.codec.CodecOptions;
import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.io.MatrixIO;
import net.algart.io.awt.MatrixToImage;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.compatibility.TiffParser;
import net.algart.matrices.tiff.executable.TiffInfo;
import net.algart.matrices.tiff.tiles.TiffReadMap;
import org.scijava.Context;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

public class TiffReaderTest {
    private static final int MAX_IMAGE_DIM = 10000;

    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean useContext = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-useContext")) {
            useContext = true;
            startArgIndex++;
        }
        boolean interleave = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-interleave")) {
            interleave = true;
            startArgIndex++;
        }
        boolean cache = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-cache")) {
            cache = true;
            startArgIndex++;
        }
        boolean tiny = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-tiny")) {
            tiny = true;
            startArgIndex++;
        }
        boolean compatibility = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-compatibility")) {
            compatibility = true;
            useContext = true;
            startArgIndex++;
        }

        if (args.length < startArgIndex + 3) {
            System.out.println("Usage:");
            System.out.println("    " + TiffReaderTest.class.getName() + " [-cache [-tiny]] " +
                    "some_tiff_file result.png ifdIndex " +
                    "[x y width height [number_of_tests] [number_of_complete_repeats]]");
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
        final int numberOfCompleteRepeats = args.length <= ++startArgIndex ? 1 : Integer.parseInt(args[startArgIndex]);

//        TiffInfo.showTiffInfo(tiffFile);

        for (int repeat = 1; repeat <= numberOfCompleteRepeats; repeat++) {
            try (final Context context = !useContext ? null : TiffReader.newSCIFIOContext()) {
                long t1 = System.nanoTime();
                final TiffReader reader = compatibility ?
                        new TiffParser(context, tiffFile) :
                        new TiffReader(tiffFile);
                if (cache) {
                    reader.setCaching(true)
                            .setMaxCachingMemory(tiny ? 1000000 : TiffReader.DEFAULT_MAX_CACHING_MEMORY);
                } else {
                    reader.setCaching(false);
                }
                reader.setContext(context);
                long t2 = System.nanoTime();
//                reader.setEnforceUseExternalCodec(true);
//                reader.setCachingIFDs(false);
//                reader.setUnusualPrecisions(TiffReader.UnusualPrecisions.NONE);
//                reader.setAutoUnpackBits(TiffReader.UnpackBits.UNPACK_TO_0_255);
//                reader.setAutoScaleWhenIncreasingBitDepth(false);
                reader.setAutoCorrectInvertedBrightness(true);
//                reader.setMissingTilesAllowed(true);
                reader.setByteFiller((byte) 0x80);
                if (reader instanceof TiffParser parser) {
                    final CodecOptions codecOptions = new CodecOptions();
                    codecOptions.maxBytes = 1000;
                    parser.setCodecOptions(codecOptions);
                    assert reader.getCodecOptions().getMaxSizeInBytes() == 1000;
                }

//                ((TiffParser) reader).setAssumeEqualStrips(true);
//                reader.setCropTilesToImageBoundaries(false);
                final long positionOfLastOffset = reader.positionOfLastIFDOffset();
                assert positionOfLastOffset == -1 : "constructor should not set positionOfLastOffset";
                final int numberOfIFDS = reader.numberOfImages();
                long t3 = System.nanoTime();
                System.out.printf(
                        "Reading %s by %s: %.3f ms opening, %.3f ms reading IFDs " +
                                "(position of first IFD offset: %d%s)%n",
                        tiffFile, reader,
                        (t2 - t1) * 1e-6,
                        (t3 - t2) * 1e-6,
                        positionOfLastOffset,
                        context == null ? "" : "; SCIFIO context " + context);
                if (numberOfIFDS == 0) {
                    System.out.println("No IFDs");
                    return;
                }
                if (ifdIndex >= numberOfIFDS) {
                    System.out.printf("%nNo IFD #%d, using last IFD #%d instead%n%n", ifdIndex, numberOfIFDS - 1);
                    ifdIndex = numberOfIFDS - 1;
                }
                final TiffReadMap map;
                if (compatibility) {
                    //noinspection deprecation
                    map = reader.newMap(TiffParser.toTiffIFD(((TiffParser) reader).getIFDs().get(ifdIndex)));
                } else {
                    map = reader.newMap(ifdIndex);
                }
                if (w < 0) {
                    w = Math.min(map.dimX(), MAX_IMAGE_DIM);
                }
                if (h < 0) {
                    h = Math.min(map.dimY(), MAX_IMAGE_DIM);
                }
                final int bandCount = map.numberOfChannels();

                Matrix<? extends PArray> matrix = null;
                for (int test = 1; test <= Math.max(1, numberOfTests); test++) {
                    if (test == 1 && repeat == 1) {
                        System.out.printf("Reading data %dx%dx%d from %s%n",
                                w, h, bandCount, new TiffInfo().ifdInfo(map.ifd(), ifdIndex, numberOfIFDS));
                    }
                    t1 = System.nanoTime();
//                    map.ifd().put(258, new double[] {3}); // - should lead to exception
                    matrix = interleave ?
                            map.readInterleavedMatrix(x, y, w, h) :
                            map.readMatrix(x, y, w, h);
                    t2 = System.nanoTime();
                    System.out.printf(Locale.US, "Test #%d: %dx%d loaded in %.3f ms%n",
                            test, w, h, (t2 - t1) * 1e-6);
                    System.gc();
                }

                System.out.printf("Saving result image into %s - %s...%n", resultFile, matrix);
                writeImageFile(resultFile, matrix, interleave);
                reader.close();
            }
            System.out.printf("Done repeat %d/%d%n%n", repeat, numberOfCompleteRepeats);
        }
    }

    private static void writeImageFile(Path file, Matrix<? extends PArray> matrix, boolean interleaved)
            throws IOException {
        if (!matrix.isEmpty()) {
            // - BufferedImage cannot have zero sizes
            final Matrix<? extends PArray> interleave = interleaved ?
                    matrix :
                    Matrices.interleave(tryToExtractRGB(matrix.asLayers()));
            final BufferedImage bi = MatrixToImage.ofInterleaved(interleave);
            MatrixIO.writeBufferedImage(file, bi);
        }
    }

    private static <T> List<T> tryToExtractRGB(List<T> image) {
        return image.size() <= 4 ? image : image.subList(0, 4);
    }
}
