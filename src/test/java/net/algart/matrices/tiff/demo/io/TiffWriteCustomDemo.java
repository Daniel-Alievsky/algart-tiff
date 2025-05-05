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

import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TiffWriteCustomDemo {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean bigTiff = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-bigTiff")) {
            bigTiff = true;
            startArgIndex++;
        }
        boolean littleEndian = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-littleEndian")) {
            littleEndian = true;
            startArgIndex++;
        }
        Double quality = null;
        if (args.length > startArgIndex && args[startArgIndex].toLowerCase().startsWith("-quality=")) {
            final String s = args[startArgIndex].toLowerCase().substring("-quality=".length());
            quality = Double.parseDouble(s);
            startArgIndex++;
        }
        Double compressionLevel = null;
        if (args.length > startArgIndex && args[startArgIndex].toLowerCase().startsWith("-compressionlevel=")) {
            final String s = args[startArgIndex].toLowerCase().substring("-compressionlevel=".length());
            compressionLevel = Double.parseDouble(s);
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.printf("    %s [-bigTiff] [-littleEndian] [-quality=0.3] [-compressionLevel=1.0] " +
                            "source.jpg/png/bmp target.tiff [compression]%n" +
                            "Possible \"compression\": NONE, LZW, DEFLATE, JPEG, JPEG_2000, ...",
                    TiffWriteCustomDemo.class.getName());
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex]);
        final Path targetFile = Paths.get(args[startArgIndex + 1]);
        final TagCompression compression = startArgIndex + 2 < args.length ?
                TagCompression.valueOf(args[startArgIndex + 2]) : null;

        System.out.printf("Reading %s...%n", sourceFile);
        final List<? extends Matrix<? extends PArray>> image = MatrixIO.readImage(sourceFile);

        System.out.printf("Writing TIFF %s...%n", targetFile);
        try (var writer = new TiffWriter(targetFile)) {
            writer.setBigTiff(bigTiff);
            writer.setLittleEndian(littleEndian);
            // - must be called BEFORE creating the new file
            writer.create();
            writer.setCompressionQuality(quality);
            writer.setLosslessCompressionLevel(compressionLevel);
            final TiffIFD ifd = writer.newIFD()
                .putChannelsInformation(image)
                .putCompression(compression);
            final var map = writer.newFixedMap(ifd);
            System.out.printf("Writing image: %s...%n", map);
            map.writeChannels(image);
        }
        System.out.println("Done");
    }
}
