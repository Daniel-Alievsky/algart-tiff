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

package net.algart.matrices.tiff.tests;

import io.scif.SCIFIO;
import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.arrays.SimpleMemoryModel;
import net.algart.external.ExternalAlgorithmCaller;
import net.algart.external.ImageConversions;
import net.algart.matrices.tiff.CachingTiffReader;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.compatibility.TiffParser;
import net.algart.matrices.tiff.executable.TiffInfo;
import net.algart.matrices.tiff.tiles.TiffMap;
import org.scijava.Context;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TiffReaderTest {
    private static final int MAX_IMAGE_DIM = 8000;

    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean noContext = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-noContext")) {
            noContext = true;
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
            try (final Context context = noContext ? null : new SCIFIO().getContext()) {
                long t1 = System.nanoTime();
                final TiffReader reader = compatibility ?
                        new TiffParser(context, tiffFile) : cache ?
                        new CachingTiffReader(context, tiffFile)
                                .setMaxCachingMemory(tiny ? 1000000 : CachingTiffReader.DEFAULT_MAX_CACHING_MEMORY) :
                        new TiffReader(context, tiffFile);
                long t2 = System.nanoTime();
//                reader.setExtendedCodec(false);
//                reader.setCachingIFDs(false);
//                reader.setAutoUnpackUnusualPrecisions(false);
//                reader.setAutoScaleWhenIncreasingBitDepth(false);
                reader.setInterleaveResults(interleave);
                reader.setAutoCorrectInvertedBrightness(true);
                reader.setMissingTilesAllowed(true);
                reader.setByteFiller((byte) 0x80);
//                ((TiffParser) reader).setAssumeEqualStrips(true);
//                reader.setCropTilesToImageBoundaries(false);
                final long positionOfLastOffset = reader.positionOfLastIFDOffset();
                assert positionOfLastOffset == -1 : "constructor should not set positionOfLastOffset";
                final int numberOfIFDS = reader.numberOfIFDs();
                long t3 = System.nanoTime();
                System.out.printf(
                        "Opening %s by %s: %.3f ms opening, %.3f ms reading IFDs (position of first IFD offset: %d)%n",
                        tiffFile, reader,
                        (t2 - t1) * 1e-6,
                        (t3 - t2) * 1e-6,
                        positionOfLastOffset);
                if (numberOfIFDS == 0) {
                    System.out.println("No IFDs");
                    return;
                }
                if (ifdIndex >= numberOfIFDS) {
                    System.out.printf("%nNo IFD #%d, using last IFD #%d instead%n%n", ifdIndex, numberOfIFDS - 1);
                    ifdIndex = numberOfIFDS - 1;
                }
                final TiffMap map;
                if (compatibility) {
                    //noinspection deprecation
                    map = reader.newMap(TiffParser.toTiffIFD(((TiffParser) reader).getIFDs().get(ifdIndex)));
                } else {
                    map = reader.map(ifdIndex);
                }
                if (w < 0) {
                    w = Math.min(map.dimX(), MAX_IMAGE_DIM);
                }
                if (h < 0) {
                    h = Math.min(map.dimY(), MAX_IMAGE_DIM);
                }
                final int bandCount = map.numberOfChannels();

                Matrix<? extends PArray> matrix = null;
                for (int test = 1; test <= numberOfTests; test++) {
                    if (test == 1 && repeat == 1) {
                        System.out.printf("Reading data %dx%dx%d from %s%n",
                                w, h, bandCount, new TiffInfo().ifdInfo(map.ifd(), ifdIndex, numberOfIFDS));
                    }
                    t1 = System.nanoTime();
//                    map.ifd().put(258, new double[] {3}); // - should lead to exception
                    matrix = reader.readMatrix(map, x, y, w, h);
                    t2 = System.nanoTime();
                    System.out.printf(Locale.US, "Test #%d: %dx%d loaded in %.3f ms%n",
                            test, w, h, (t2 - t1) * 1e-6);
                    System.gc();
                }

                System.out.printf("Saving result image into %s...%n", resultFile);
                writeImageFile(resultFile, matrix, interleave);
                reader.close();
            }
            System.out.printf("Done repeat %d/%d%n%n", repeat, numberOfCompleteRepeats);
        }
    }

    static void writeImageFile(Path file, Matrix<? extends PArray> matrix) throws IOException {
        writeImageFile(file, matrix, false);
    }

    private static void writeImageFile(Path file, Matrix<? extends PArray> matrix, boolean interleaved)
            throws IOException {
        List<Matrix<? extends PArray>> image = interleaved ?
                interleavedToImage(matrix) :
                separatedToImage(matrix);
        if (image != null) {
            ExternalAlgorithmCaller.writeImage(file.toFile(), image);
        }
    }

    private static List<Matrix<? extends PArray>> separatedToImage(Matrix<? extends PArray> matrix) {
        if (matrix.size() == 0) {
            return null;
            // - provided for testing only (BufferedImage cannot have zero sizes)
        }
        matrix = intsToBytes(matrix);
        List<Matrix<? extends PArray>> channels = new ArrayList<>();
        for (long k = 0; k < matrix.dim(2); k++) {
            Matrix<? extends PArray> subMatrix = matrix.subMatr(0, 0, k, matrix.dimX(), matrix.dimY(), 1);
            Matrix<? extends PArray> reduced = Matrices.matrix(subMatrix.array(), matrix.dimX(), matrix.dimY());
            channels.add(reduced);
        }
        return channels;
    }

    private static List<Matrix<? extends PArray>> interleavedToImage(Matrix<? extends PArray> matrix) {
        if (matrix.size() == 0) {
            return null;
            // - provided for testing only (BufferedImage cannot have zero sizes)
        }
        return ImageConversions.unpack2DBandsFromSequentialSamples(null, intsToBytes(matrix));
    }

    private static Matrix<? extends PArray> intsToBytes(Matrix<? extends PArray> matrix) {
        if (matrix.elementType() == int.class) {
            // - standard method ExternalAlgorithmCaller.writeImage uses AlgART interpretation: 2^31 is white;
            // it is incorrect for TIFF files
            final int[] ints = new int[(int) matrix.size()];
            matrix.array().getData(0, ints);
            byte[] bytes = new byte[ints.length];
            for (int k = 0; k < bytes.length; k++) {
                bytes[k] = (byte) (ints[k] >>> 24);
            }
            return matrix.matrix(SimpleMemoryModel.asUpdatableByteArray(bytes));
        }
        return matrix;
    }

}
