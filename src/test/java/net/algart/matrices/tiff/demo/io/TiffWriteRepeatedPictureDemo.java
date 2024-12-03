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
import net.algart.arrays.UpdatablePArray;
import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TiffWriteRepeatedPictureDemo {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean append = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-append")) {
            append = true;
            startArgIndex++;
        }
        boolean bigTiff = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-bigTiff")) {
            bigTiff = true;
            startArgIndex++;
        }
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
        System.out.printf("Pattern image: %dx%d, %d channels, %s elements%n",
                patternSizeX, patternSizeY, pattern.size(), pattern.get(0).elementType());

        System.out.printf("%s TIFF %s...%n", append ? "Appending" : "Writing", targetFile);
        try (TiffWriter writer = new TiffWriter(targetFile)) {
            writer.setBigTiff(bigTiff);
            writer.create(append);
            final TiffIFD ifd = writer.newIFD(true)
                    .putPixelInformation(pattern.size(), pattern.get(0).elementType())
                    .putCompression(compression);
            final var map = writer.newResizableMap(ifd);
            System.out.printf("Creating new resizable map: %s%n", map);
            for (int y = 0; y < yCount; y++) {
                for (int x = 0; x < xCount; x++) {
                    final List<TiffTile> updated = map.copyChannelsToMap(
                            pattern, x * (int) patternSizeX, y * (int) patternSizeY);
                    final int written = map.writeCompletedTiles(updated);
                    // - if you comment this operator, OutOfMemoryError will be possible for a very large TIFF
                    System.out.printf("\rBlock (%d,%d) from (%d,%d) ready (%d written, %s memory used)        \r",
                            x + 1, y + 1, xCount, yCount, written, memory());
                }
            }
            System.out.println();
            System.out.printf("Completing image to %s...%n", map);
            map.completeWriting();
        }
        System.out.println("Done");
    }

    private static String memory() {
        Runtime rt = Runtime.getRuntime();
        return String.format("%.2f/%.2f MB",
                (rt.totalMemory() - rt.freeMemory()) / 1048576.0, rt.totalMemory() / 1048576.0);
    }
}
