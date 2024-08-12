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
import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tiles.TiffMap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TiffCustomCodecDemo {
    public static class CustomTiffWriter extends TiffWriter {
        public CustomTiffWriter(Path file) throws IOException {
            super(file);
        }

        public CustomTiffWriter(Path file, boolean createNewFileAndOpen) throws IOException {
            super(file, createNewFileAndOpen);
        }

    }

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage:");
            System.out.println("    " + TiffCustomCodecDemo.class.getName() +
                    " source.jpg/png/bmp target.tiff target.jpg/png/bmp");
            return;
        }
        final Path sourceFile = Paths.get(args[0]);
        final Path targetFile = Paths.get(args[1]);
        final Path testFile = Paths.get(args[2]);

        System.out.println("Reading " + sourceFile + "...");
        List<Matrix<UpdatablePArray>> image = MatrixIO.readImage(sourceFile);
        ;
        System.out.println("Writing TIFF " + targetFile + "...");
        try (TiffWriter writer = new TiffWriter(targetFile, true)) {
            // writer.setAutoInterleaveSource(false); // - leads to throwing exception
            TiffIFD ifd = writer.newIFD();
            ifd.putChannelsInformation(image);
            // ifd.putCompression(TagCompression.JPEG); // - you can specify some compression
            TiffMap map = writer.newFixedMap(ifd);
            writer.writeChannels(map, image);
        }
        System.out.println("Done");
    }
}
