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

package net.algart.matrices.tiff.app;

import net.algart.arrays.Matrix;
import net.algart.arrays.UpdatablePArray;
import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tiles.TiffReadMap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

public class ConvertFromTiff {
    public static void main(String[] args) throws IOException {
        doMain(args, true);
    }

    static boolean doMain(String[] args, boolean printUsage) throws IOException {
        if (args.length < 3) {
            if (printUsage) {
                System.out.println("Usage:");
                System.out.printf("    %s source.tiff target.jpg/png/bmp IFDIndex%n",
                        ConvertFromTiff.class.getName());
            }
            return false;
        }
        final Path sourceFile = Paths.get(args[0]);
        final Path targetFile = Paths.get(args[1]);
        final int ifdIndex = Integer.parseInt(args[2]);

        System.out.printf("Reading TIFF %s...%n", sourceFile);
        long t1 = System.nanoTime();
        List<Matrix<UpdatablePArray>> image;
        try (TiffReader reader = new TiffReader(sourceFile)) {
            final TiffReadMap map = reader.map(ifdIndex);
            image = map.readChannels();
        }
        long t2 = System.nanoTime();
        System.out.printf("Writing %s...%n", targetFile);
        MatrixIO.writeImage(targetFile, image);
        long t3 = System.nanoTime();

        System.out.printf(Locale.US,
                "Conversion from TIFF finished: %.3f seconds reading TIFF, %.3f seconds writing.%n",
                (t2 - t1) * 1e-9, (t3 - t2) * 1e-9);
        return true;
    }
}
