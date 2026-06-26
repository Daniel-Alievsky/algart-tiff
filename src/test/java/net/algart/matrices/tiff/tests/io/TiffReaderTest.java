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

import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.arrays.UpdatablePArray;
import net.algart.io.MatrixIO;
import net.algart.io.awt.MatrixToImage;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.app.TiffInfo;
import net.algart.matrices.tiff.codecs.JPEG2000Codec;
import net.algart.matrices.tiff.tags.TagCompression;
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

    public static void main(String... args) throws IOException {
        int startArgIndex = 0;
        boolean useContext = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-useContext")) {
            useContext = true;
            startArgIndex++;
        }
        boolean external = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-external")) {
            external = true;
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
            try (final Context context = !useContext ? null : (Context) TiffReader.newSCIFIOContext()) {
                long t1 = System.nanoTime();
                final TiffReader reader = new TiffReader(tiffFile);
                if (cache) {
                    reader.setCaching(true)
                            .setMaxCacheMemory(tiny ? 1000000 : TiffReader.DEFAULT_MAX_CACHING_MEMORY);
                } else {
                    reader.setCaching(false);
                }
                reader.setContext(context);
                if (external) {
                    reader.setEnforceUseExternalCodec(true);
                }
                long t2 = System.nanoTime();
                // reader.setEnforceUseExternalCodec(true);
                // reader.setCachingIFDs(false);
                // reader.setRescaleWhenIncreasingBitDepth(false);
                reader.setColorCorrection(true);
                // reader.setMissingTilesAllowed(true);
                reader.setByteFiller((byte) 0x80);

//                ((TiffParser) reader).setAssumeEqualStrips(true);
//                reader.setCropTilesToImageBoundaries(false);
                final var offsetOfLastScannedIFDOffset = reader.offsetOfLastScannedIFDOffset();
                assert offsetOfLastScannedIFDOffset.isEmpty() :
                        "constructor should not set offsetOfLastScannedIFDOffset";
                final int numberOfIFDS = reader.numberOfImages();
                long t3 = System.nanoTime();
                System.out.printf(
                        "Reading %s by %s: %.3f ms opening, %.3f ms reading IFDs " +
                                "(position of last scanned IFD offset: %s)%n",
                        tiffFile, reader,
                        (t2 - t1) * 1e-6,
                        (t3 - t2) * 1e-6,
                        offsetOfLastScannedIFDOffset);
                if (numberOfIFDS == 0) {
                    System.out.println("No IFDs");
                    return;
                }
                if (ifdIndex >= numberOfIFDS) {
                    System.out.printf("%nNo IFD #%d, using last IFD #%d instead%n%n", ifdIndex, numberOfIFDS - 1);
                    ifdIndex = numberOfIFDS - 1;
                }
                final TiffReadMap map;
                map = reader.map(ifdIndex);
                // map.setRarePrecisionMode(TiffMap.RarePrecisionMode.KEEP_RAW);
                if (w < 0) {
                    w = Math.min(map.dimX(), MAX_IMAGE_DIM);
                }
                if (h < 0) {
                    h = Math.min(map.dimY(), MAX_IMAGE_DIM);
                }
                final int bandCount = map.numberOfChannels();
                reader.setCodecCustomizer(options -> {
                    // System.out.printf("Customizing %s...%n", options.getClass());
                    if (map.compression().orElse(TagCompression.NONE).isJpeg2000()) {
                        if (!(options instanceof JPEG2000Codec.JPEG2000Options jpeg2000Options)) {
                            throw new AssertionError("JPEG-2000 format must use JPEG2000Options");
                        }
                            jpeg2000Options.setLossless(false);
                        // - not important for reading, but can be checked under debugger
                    }
                });

                Matrix<? extends PArray> matrix = null;
                for (int test = 1; test <= Math.max(1, numberOfTests); test++) {
                    if (test == 1 && repeat == 1) {
                        System.out.printf("Reading data %dx%dx%d from %s%n",
                                w, h, bandCount,
                                new TiffInfo().ifdInformation(reader, map.ifd(), ifdIndex));
                    }
                    t1 = System.nanoTime();
//                    map.ifd().put(258, new double[] {3}); // - should lead to exception
                    matrix = interleave ?
                            map.readInterleavedMatrix(x, y, w, h) :
                            map.readMatrix(x, y, w, h);
                    t2 = System.nanoTime();
                    System.out.printf(Locale.US, "Test #%d: %dx%d loaded in %.3f ms (%s)%n",
                            test, w, h, (t2 - t1) * 1e-6, matrix);
                    if (test == 1 && !interleave &&
                            !(map.isRarePrecision() && map.getRarePrecisionMode().isKeepRaw())) {
                        // - testing alternate way to do the same thing
                        byte[] samples = map.readSampleBytes(x, y, w, h);
                        Matrix<UpdatablePArray> other = map.bytesToMatrix(samples, w, h);
                        // - will lead to exception for rare precisions in KEEP_RAW mode
                        if (!other.equals(matrix)) {
                            throw new AssertionError("Different matrices!");
                        }
                    }
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
                    Matrices.interleave(extractFirst4(matrix.asLayers()));
            final BufferedImage bi = MatrixToImage.ofInterleaved(interleave);
            MatrixIO.writeBufferedImage(file, bi);
        }
    }

    private static <T> List<T> extractFirst4(List<T> image) {
        return image.size() <= 4 ? image : image.subList(0, 4);
    }
}
