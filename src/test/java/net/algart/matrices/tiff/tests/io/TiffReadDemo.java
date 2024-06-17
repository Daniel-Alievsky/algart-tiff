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

package net.algart.matrices.tiff.tests.io;

import net.algart.arrays.Matrix;
import net.algart.arrays.UpdatablePArray;
import net.algart.external.MatrixIO;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffTools;
import org.scijava.Context;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TiffReadDemo {
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage:");
            System.out.println("    " + TiffReadDemo.class.getName() +
                    " source.tiff target.jpg/png/bmp ifdIndex");
            return;
        }
        final Path sourceFile = Paths.get(args[0]);
        final Path targetFile = Paths.get(args[1]);
        final int ifdIndex = Integer.parseInt(args[2]);

        System.out.println("Reading TIFF " + sourceFile + "...");
        List<Matrix<UpdatablePArray>> image;
        try (TiffReader reader = new TiffReader(sourceFile)) {
            // reader.setContext(TiffTools.newSCIFIOContext()); // - throws exception without dependence on SCIFIO
            // reader.setInterleaveResults(true); // - slows down reading (unnecessary interleaving+separating)
            image = reader.readChannels(ifdIndex);
        }
        System.out.println("Writing " + targetFile + "...");
        MatrixIO.writeImage(targetFile, image);

        System.out.println("Done");
    }
}
