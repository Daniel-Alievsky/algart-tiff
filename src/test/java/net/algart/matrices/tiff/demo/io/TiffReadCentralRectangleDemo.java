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
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.awt.AWTImages;
import net.algart.matrices.tiff.tiles.TiffMap;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;

public class TiffReadCentralRectangleDemo {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean lowLevel = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-lowLevel")) {
            lowLevel = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 5) {
            System.out.println("Usage:");
            System.out.printf("    [-lowLevel] %s source.tiff target.jpg/png/bmp ifdIndex width height %n",
                    TiffReadCentralRectangleDemo.class.getName());
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex]);
        final Path targetFile = Paths.get(args[startArgIndex + 1]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex + 2]);
        final int width = Integer.parseInt(args[startArgIndex + 3]);
        final int height = Integer.parseInt(args[startArgIndex + 4]);

        System.out.printf("Reading TIFF %s and writing %s...%n", sourceFile, targetFile);
        try (TiffReader reader = new TiffReader(sourceFile)) {
            TiffMap map = reader.map(ifdIndex);
            int sizeX = Math.min(width, map.dimX());
            int sizeY = Math.min(height, map.dimY());
            int fromX = (map.dimX() - sizeX) / 2;
            int fromY = (map.dimY() - sizeY) / 2;
            if (lowLevel) {
                byte[] samples = reader.readSamples(map, fromX, fromY, sizeX, sizeY);
                BufferedImage bi = AWTImages.makeSeparatedImage(samples, sizeX, sizeY, map.numberOfChannels());
                MatrixIO.writeBufferedImage(targetFile, bi);
            } else {
                List<Matrix<UpdatablePArray>> image = reader.readChannels(map, fromX, fromY, sizeX, sizeY);
                MatrixIO.writeImage(targetFile, image);
            }
        }
        System.out.println("Done");
    }
}
