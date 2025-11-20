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

import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tiles.TiffReadMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.matrices.tiff.tiles.TiffTileIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public class TiffReadTilesTest {
    private static final int MAX_IMAGE_DIM = 8000;

    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 3) {
            System.out.println("Usage:");
            System.out.println("    " + TiffReadTilesTest.class.getName() +
                    " some_tiff_file result_folder ifdIndex " +
                    "[x y width height [number_of_tests]");
            return;
        }
        final Path tiffFile = Paths.get(args[startArgIndex++]);
        final Path resultFolder = Paths.get(args[startArgIndex++]);
        if (!Files.isDirectory(resultFolder)) {
            throw new IOException("Result folder " + resultFolder + " is not an existing folder");
        }
        final int ifdIndex = Integer.parseInt(args[startArgIndex++]);
        final int x = args.length <= startArgIndex ? 0 : Integer.parseInt(args[startArgIndex]);
        final int y = args.length <= ++startArgIndex ? 0 : Integer.parseInt(args[startArgIndex]);
        int w = args.length <= ++startArgIndex ? -1 : Integer.parseInt(args[startArgIndex]);
        int h = args.length <= ++startArgIndex ? -1 : Integer.parseInt(args[startArgIndex]);
        final int numberOfTests = args.length <= ++startArgIndex ? 1 : Integer.parseInt(args[startArgIndex]);

        try (TiffReader reader = new TiffReader(tiffFile)) {
            reader.setByteFiller((byte) 0x40);
            reader.setCropTilesToImageBoundaries(true);
            final TiffReadMap map = reader.map(ifdIndex);
            if (w < 0) {
                w = Math.min(map.dimX(), MAX_IMAGE_DIM);
            }
            if (h < 0) {
                h = Math.min(map.dimY(), MAX_IMAGE_DIM);
            }
            Matrix<? extends PArray> matrix = null;
            if (numberOfTests <= 0) {
                return;
            }
            for (int test = 1; test <= numberOfTests; test++) {
                System.out.printf("Reading image from %s...%n", tiffFile);
                long t1 = System.nanoTime();
                matrix = map.readMatrix(x, y, w, h, true, map.cachedTileSupplier());
                long t2 = System.nanoTime();
                System.out.printf(Locale.US, "Test #%d: %dx%d loaded in %.3f ms%n",
                        test, w, h, (t2 - t1) * 1e-6);
                System.gc();
            }

            final Path imageFile = resultFolder.resolve("result.bmp");
            System.out.printf("Saving result image into %s...%n", imageFile);
            writeImageFile(imageFile, matrix);
            for (TiffTile t : map.tiles()) {
                if (!t.isEmpty()) {
                    final Path tileFile = tilePath(t, resultFolder);
                    System.out.printf("Saving tile %s in %s...%n", t, tileFile);
                    Object a = t.sampleType().javaArray(t.getDecodedData(), reader.getByteOrder());
                    writeImageFile(tileFile, Matrix.as(a, t.getSizeX(), t.getSizeY(), t.samplesPerPixel()));
                }
            }
        }
    }

    private static Path tilePath(TiffTile tile, Path resultFolder) {
        final TiffTileIndex i = tile.index();
        return resultFolder.resolve("tile_x" + i.xIndex() +
                "_y" + i.yIndex() +
                (tile.isPlanarSeparated() ? "_p" + i.separatedPlaneIndex() : "") +
                "." + ((tile.ifd().isJpegOrOldJpeg()) ? "jpg" : "bmp"));
    }

    private static void writeImageFile(Path file, Matrix<? extends PArray> matrix) throws IOException {
        if (!matrix.isEmpty()) {
            // - BufferedImage cannot have zero sizes
            MatrixIO.writeImage(file, matrix.asLayers());
        }
    }
}
