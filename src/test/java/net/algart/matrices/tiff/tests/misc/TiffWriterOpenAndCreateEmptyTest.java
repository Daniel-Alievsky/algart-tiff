/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2026 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.matrices.tiff.tests.misc;

import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffWriterOpenAndCreateEmptyTest {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean uncomplete = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-uncomplete")) {
            uncomplete = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 1) {
            System.out.println("Usage:");
            System.out.printf("    %s [-uncomplete] target.tiff%n", TiffWriterOpenAndCreateEmptyTest.class.getName());
            System.out.println("For special testing, please specify an existing, but invalid file.");
            return;
        }

        final Path targetFile = Paths.get(args[startArgIndex]);
        try (TiffWriter writer = new TiffWriter(targetFile)) {
            if (!writer.isTiff()) throw new AssertionError();
            if (!writer.isValidTiff()) throw new AssertionError();
            if (writer.isBigTiff()) throw new AssertionError();
            System.out.printf("Opening for writing: %s%n", targetFile);
            try {
                writer.openExisting();
            } catch (IOException e) {
                System.out.printf("Cannot open existing: %s%n", e.getMessage());
                e.printStackTrace(System.out);
                if (writer.isValidTiff()) throw new AssertionError("Cannot be valid when exception!");
            }
            System.out.printf("After openExisting(): isTiff: %s; isValidTiff: %s; isBigTiff: %s%n",
                    writer.isTiff(), writer.isValidTiff(), writer.isBigTiff());
            writer.create();
            System.out.printf("After create(): isTiff: %s; isValidTiff: %s; isBigTiff: %s%n",
                    writer.isTiff(), writer.isValidTiff(), writer.isBigTiff());
            if (!writer.isTiff()) throw new AssertionError();
            if (!writer.isValidTiff()) throw new AssertionError();
            if (writer.isBigTiff()) throw new AssertionError();
            if (!uncomplete) {
                writer.writeNewBufferedImage(
                        new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB),
                        TagCompression.JPEG);
                System.out.printf("After writeNewBufferedImage(): isTiff: %s; isValidTiff: %s; isBigTiff: %s%n",
                        writer.isTiff(), writer.isValidTiff(), writer.isBigTiff());
                if (!writer.isTiff()) throw new AssertionError();
                if (!writer.isValidTiff()) throw new AssertionError();
                if (writer.isBigTiff()) throw new AssertionError();
            }
        }
    }
}
