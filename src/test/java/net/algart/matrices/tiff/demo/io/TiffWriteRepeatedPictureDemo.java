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

package net.algart.matrices.tiff.demo.io;

import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.arrays.UpdatablePArray;
import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TiffWriteRepeatedPictureDemo {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 4) {
            System.out.println("Usage:");
            System.out.printf("    %s image-to-repeat.jpg/png/bmp target.tiff " +
                            "x-repetitions y-repetitions [compression]%n",
                    TiffWriteRepeatedPictureDemo.class.getName());
            return;
        }
        final Path patternFile = Paths.get(args[startArgIndex]);
        final Path targetFile = Paths.get(args[startArgIndex + 1]);
        final int xCount = Integer.parseInt(args[startArgIndex + 2]);
        final int yCount = Integer.parseInt(args[startArgIndex + 3]);
        final TagCompression compression = startArgIndex + 4 < args.length ?
                TagCompression.valueOf(args[startArgIndex + 4]) : TagCompression.JPEG;

        System.out.printf("Reading %s...%n", patternFile);
        List<Matrix<UpdatablePArray>> pattern = MatrixIO.readImage(patternFile);
        final long patternSizeX = pattern.get(0).dimX();
        final long patternSizeY = pattern.get(0).dimY();
        if (xCount > Integer.MAX_VALUE / patternSizeX || yCount > Integer.MAX_VALUE / patternSizeY) {
            throw new IllegalArgumentException("Too large result image (>=2^31)");
        }

        System.out.printf("Writing TIFF %s...%n", targetFile);
        try (TiffWriter writer = new TiffWriter(targetFile, true)) {
            TiffIFD ifd = writer.newIFD()
                    .putPixelInformation(pattern.size(), pattern.get(0).elementType())
                    .defaultTileSizes()
                    .putCompression(compression);

            TiffMap map = writer.newResizableMap(ifd);
            for (int y = 0; y < yCount; y++) {
                for (int x = 0; x < xCount; x++) {
                    List<TiffTile> updated = writer.updateChannels(
                            map, pattern, x * (int) patternSizeX, y * (int) patternSizeY);
                    writer.writeCompletedTiles(updated);
                    // - if you comment this operator, OutOfMemoryError will be possible for a very large TIFF
                    System.out.printf("\rBlock (%d,%d) from (%d,%d) ready (%s memory used)        \r",
                            x + 1, y + 1, xCount, yCount, memory());
                }
            }
            System.out.println();
            System.out.printf("Completing image to %s...%n", map);
            writer.complete(map);
        }
        System.out.println("Done");
    }

    private static String memory() {
        Runtime rt = Runtime.getRuntime();
        return String.format("%.2f/%.2f MB",
                (rt.totalMemory() - rt.freeMemory()) / 1048576.0, rt.totalMemory() / 1048576.0);
    }
}
