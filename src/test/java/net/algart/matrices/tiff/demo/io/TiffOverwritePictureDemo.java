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
import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.TiffWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TiffOverwritePictureDemo {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 3) {
            System.out.println("Usage:");
            System.out.printf("    %s target.tiff image-to-draw.jpg/png/bmp ifdIndex [x y]%n",
                    TiffOverwritePictureDemo.class.getName());
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex]);
        final Path imageToDrawFile = Paths.get(args[startArgIndex + 1]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex + 2]);
        final int x = startArgIndex + 3 < args.length ? Integer.parseInt(args[startArgIndex + 3]) : 0;
        final int y = startArgIndex + 4 < args.length ? Integer.parseInt(args[startArgIndex + 4]) : 0;

        System.out.printf("Reading %s...%n", imageToDrawFile);
        List<? extends Matrix<? extends PArray>> imageToDraw = MatrixIO.readImage(imageToDrawFile);
        final int imageToDrawSizeX = imageToDraw.get(0).dimX32();
        final int imageToDrawSizeY = imageToDraw.get(0).dimY32();

        System.out.printf("Opening and rewriting TIFF %s...%n", targetFile);
        try (TiffWriter writer = new TiffWriter(targetFile)) {
            writer.openExisting();
            var map = writer.preloadExistingTiles(
                    ifdIndex, x, y, imageToDrawSizeX, imageToDrawSizeY, false);
            System.out.printf("Overwriting %s...%n", map);
            writer.writeChannels(map, imageToDraw, x, y);
        }
        System.out.println("Done");
    }
}

