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

import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffCopyDemo {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean append = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-append")) {
            append = true;
            startArgIndex++;
        }
        boolean repack = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-repack")) {
            repack = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.printf("    [-append] [-repack] %s source.tiff target.tiff [firstIFDIndex lastIFDIndex]%n",
                    TiffCopyDemo.class.getName());
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex++]);
        final Path targetFile = Paths.get(args[startArgIndex++]);
        final int firstIFDIndex = startArgIndex < args.length ? Integer.parseInt(args[startArgIndex]) : 0;
        int lastIFDIndex = startArgIndex + 1 < args.length ?
                Integer.parseInt(args[startArgIndex + 1]) :
                Integer.MAX_VALUE;

        System.out.printf("Copying %s to %s %s...%n",
                sourceFile, targetFile, repack ? "with recompression" : "as-is");

        System.out.printf("Writing TIFF %s...%n", targetFile);
        try (TiffReader reader = new TiffReader(sourceFile);
                TiffWriter writer = new TiffWriter(targetFile)) {
            lastIFDIndex = Math.min(lastIFDIndex, reader.numberOfImages() - 1);
            if (lastIFDIndex >= firstIFDIndex) {
                writer.create(append);
                for (int i = firstIFDIndex; i <= lastIFDIndex; i++) {
                    System.out.printf("Copying image %d...%n", i);
                    writer.copyImage(reader, i, repack);
                }
            }
        }
        System.out.println("Done");
    }
}