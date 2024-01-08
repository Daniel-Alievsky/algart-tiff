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

package net.algart.matrices.tiff.tests;

import io.scif.SCIFIO;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.UnsupportedTiffFormatException;
import org.scijava.Context;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffCopyFolderTest {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean noContext = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-noContext")) {
            noContext = true;
            startArgIndex++;
        }
        boolean bigTiff = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-bigTiff")) {
            bigTiff = true;
            startArgIndex++;
        }
        boolean uncompressed = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-uncompressed")) {
            uncompressed = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.println("    " + TiffCopyFolderTest.class.getName()
                    + " source-folder target-folder");
            return;
        }
        final Path sourceFolder = Paths.get(args[startArgIndex++]);
        final Path targetFolder = Paths.get(args[startArgIndex]);
        Files.createDirectories(targetFolder);

        System.out.printf("Copying all TIFF files from %s to %s...%n", sourceFolder, targetFolder);

        final SCIFIO scifio = new SCIFIO();
        try (Context context = noContext ? null : scifio.getContext()) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(sourceFolder)) {
                for (Path file : files) {
                    if (!Files.isRegularFile(file)) {
                        continue;
                    }
                    Path fileName = file.getFileName();
                    if (fileName.toString().startsWith(".")) {
                        continue;
                    }
                    try {
                        TiffCopyTest.copyTiff(context, file, targetFolder.resolve(fileName), bigTiff, uncompressed);
                    } catch (UnsupportedTiffFormatException e) {
                        System.out.println("  Cannot copy: " + e.getMessage());
                    } catch (TiffException e) {
                        System.out.println("  Format error! " + e.getMessage());
                    }
                }
            }
        }
        System.out.println("Done");
    }
}
