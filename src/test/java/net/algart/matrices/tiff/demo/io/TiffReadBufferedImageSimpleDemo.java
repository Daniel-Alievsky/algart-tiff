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

import net.algart.matrices.tiff.TiffReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffReadBufferedImageSimpleDemo {
    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.out.println("Usage:");
            System.out.printf("    %s source.tiff target.jpg/png/bmp ifdIndex%n",
                    TiffReadBufferedImageSimpleDemo.class.getName());
            return;
        }
        final Path sourceFile = Paths.get(args[0]);
        final Path targetFile = Paths.get(args[1]);
        final int ifdIndex = Integer.parseInt(args[2]);

        System.out.printf("Reading TIFF %s...%n", sourceFile);
        BufferedImage image;
        try (TiffReader reader = new TiffReader(sourceFile)) {
            // reader.setEnforceUseExternalCodec(true); // - throws exception: no SCIFIO or other external codecs
            // reader.setContext(TiffTools.newSCIFIOContext()); // - throws exception without dependence on SCIFIO
            // reader.setInterleaveResults(true); // - slows down reading (unnecessary interleaving+separating)
            final var map = reader.newMap(ifdIndex);
            System.out.printf("Reading %s...%n", map);
            image = map.readBufferedImage();
        }
        System.out.printf("Writing %s...%n", targetFile);
        final String formatName = extension(targetFile.getFileName().toString());
        // - Not ideal solution: some Java AWT codecs, such as JPEG-2000, use format name different
        // from the extension. But it's very simple.
        // Better solution is using MatrixIO class.
        if (!ImageIO.write(image, formatName, targetFile.toFile())) {
            throw new IOException("Failed to write image " + targetFile);
        }
        System.out.println("Done");
    }

    static String extension(String fileName) {
        int p = fileName.lastIndexOf('.');
        if (p == -1) {
            return "bmp";
        }
        return fileName.substring(p + 1);
    }
}
