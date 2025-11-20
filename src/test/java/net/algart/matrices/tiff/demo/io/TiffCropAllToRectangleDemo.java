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

package net.algart.matrices.tiff.demo.io;

import net.algart.matrices.tiff.TiffCopier;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tiles.TiffReadMap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

public class TiffCropAllToRectangleDemo {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean relative = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-relative")) {
            relative = true;
            startArgIndex++;
        }
        boolean wholeTiles = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-wholeTiles")) {
            wholeTiles = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 4) {
            System.out.println("Usage:");
            System.out.printf("    [-relative] [-wholeTiles] %s " +
                            "source.tiff target.tiff x y width height%n" +
                            "Rectangle coordinates are specified in the IFD #0 coordinate space.%n" +
                            "If the -relative flag is used, the values must be between 0 and 1,%n" +
                            "e.g. 0.3 0.3 0.8 0.8, representing relative position and size.",
                    TiffCropAllToRectangleDemo.class.getName());
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex]);
        final Path targetFile = Paths.get(args[++startArgIndex]);
        final double x = Double.parseDouble(args[++startArgIndex]);
        final double y = Double.parseDouble(args[++startArgIndex]);
        final Double width = args.length <= ++startArgIndex ? null : Double.parseDouble(args[startArgIndex]);
        final Double height = args.length <= ++startArgIndex ? null : Double.parseDouble(args[startArgIndex]);
        System.out.printf("Cropping from %s to %s%s...%n",
                sourceFile, targetFile, wholeTiles ? " (attempt to optimize by copying whole tiles)" : "");

        final var copier = new TiffCopier();
        if (!copier.isDirectCopy()) {
            throw new AssertionError("direct mode must be enabled by default");
        }
        copier.setProgressUpdater(TiffCopyDemo::updateProgress);
        long t1 = System.nanoTime();
        try (TiffReader reader = new TiffReader(sourceFile); var writer = new TiffWriter(targetFile)) {
            writer.setFormatLike(reader);
            writer.create();
            for (int ifdIndex = 0; ifdIndex < reader.numberOfImages(); ifdIndex++) {
                final TiffReadMap readMap = reader.map(ifdIndex);
                final int dimX = readMap.dimX();
                final int dimY = readMap.dimY();
                final double mX = relative ? dimX : ifdIndex == 0 ? 1.0 : (double) dimX / reader.dimX(0);
                final double mY = relative ? dimY : ifdIndex == 0 ? 1.0 : (double) dimY / reader.dimY(0);
                int fromX = (int) Math.round(x * mX);
                int fromY = (int) Math.round(y * mY);
                int toX = width == null ? dimX : fromX + (int) Math.round(width * mX);
                int toY = height == null ? dimY : fromY + (int) Math.round(height * mY);
                if (wholeTiles) {
                    fromX = alignDown(fromX, readMap.tileSizeX());
                    fromY = alignDown(fromY, readMap.tileSizeY());
                    toX = Math.min(dimX, alignUp(toX, readMap.tileSizeX()));
                    toY = Math.min(dimY, alignUp(toY, readMap.tileSizeY()));
                }
                System.out.printf("Copying image %d, rectangle %d..%dx%d..%d%n", ifdIndex, fromX, toX, fromY, toY);
                copier.copyImage(writer, readMap, fromX, fromY, toX - fromX, toY - fromY);
                System.out.print("\r                                 \r");
            }
        }
        long t2 = System.nanoTime();
        System.out.printf(Locale.US, "Done in %.3f seconds%n", (t2 - t1) * 1e-9);
    }

    private static int alignDown(int value, int step) {
        return value / step * step;
    }

    private static int alignUp(int value, int step) {
        int result = value / step * step;
        return result == value ? value : result + step;
    }
}
