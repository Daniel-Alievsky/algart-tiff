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

import net.algart.arrays.ColorMatrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.TiffCreateMode;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.TiffWriteMap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TiffAppendDemo {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean mono = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-mono")) {
            mono = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.printf("    %s [-mono] source.jpg/png/bmp target.tiff%n",
                    TiffAppendDemo.class.getName());
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex]);
        final Path targetFile = Paths.get(args[startArgIndex + 1]);

        System.out.printf("Reading %s...%n", sourceFile);
        List<? extends Matrix<? extends PArray>> image = MatrixIO.readImage(sourceFile);
        if (mono && image.size() > 1) {
            System.out.println("Conversion color image to monochrome...");
            image = List.of(ColorMatrices.asRGBIntensity(image).clone());
        }

        System.out.printf("Writing TIFF %s...%n", targetFile);
        try (TiffWriter writer = new TiffWriter(targetFile, TiffCreateMode.OPEN_FOR_APPEND)) {
            // - for comparison, TiffCreateMode.CREATE always creates a new file
            final TiffIFD ifd = writer.newIFD()
                    .putChannelsInformation(image)
                    .putCompression(TagCompression.DEFLATE);
            final TiffWriteMap map = writer.newFixedMap(ifd);
            System.out.printf("Appending image to %s...%n", map);
            map.writeChannels(image);
        }
        System.out.println("Done");
    }
}
