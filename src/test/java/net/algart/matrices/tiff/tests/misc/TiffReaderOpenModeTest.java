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

import net.algart.matrices.tiff.TiffReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffReaderOpenModeTest {
    private static void showFile(Path file, TiffReader.OpenMode mode) throws IOException {
        inspectFile(file, mode, null, null, null);
    }

    private static void checkFile(Path file, boolean invalidTiff) throws IOException {
        boolean exists = Files.exists(file);
        final String fileName = file.toString().toLowerCase();
        boolean tiff = exists && (fileName.endsWith(".tif") || fileName.endsWith(".tiff") || fileName.endsWith(".svs"));
        boolean validTiff = tiff && !invalidTiff;
        System.out.printf("Testing file %s: exists %s, tiff %s, invalidTiff %s...%n",
                file, exists, tiff, invalidTiff);
        inspectFile(file, TiffReader.OpenMode.NO_CHECKS, tiff, validTiff, false);
        inspectFile(file, TiffReader.OpenMode.ALLOW_NON_TIFF, tiff, validTiff, invalidTiff);
        inspectFile(file, TiffReader.OpenMode.ALLOW_EXISTING_NON_TIFF, tiff, validTiff,
                invalidTiff || !exists);
        inspectFile(file, TiffReader.OpenMode.VALID_TIFF, tiff, validTiff, !validTiff);
    }

    private static void inspectFile(
            Path file,
            TiffReader.OpenMode mode,
            Boolean requiredTiff,
            Boolean requiredValidTiff,
            Boolean exceptionExpected) throws IOException {
        System.out.printf("Opening %s in the mode %s...%n", file, mode);
        TiffReader reader;
        try {
            reader = new TiffReader(file, mode);
            if (exceptionExpected != null && exceptionExpected) {
                throw new AssertionError("Exception did not occur!");
            }
        } catch (IOException e) {
            if (exceptionExpected != null && !exceptionExpected) {
                throw new AssertionError("Unexpected exception: " + e.getMessage(), e);
            }
            System.out.println("  Exception: " + e.getMessage());
            return;
        }
        System.out.printf("  %d main IFDs found; ", reader.numberOfMainImages());
        System.out.printf("isTiff: %s; isValidTiff: %s; isBigTiff: %s%n",
                reader.isTiff(), reader.isValidTiff(), reader.isBigTiff());
        if (requiredTiff != null && reader.isTiff() != requiredTiff) {
            throw new AssertionError("Invalid isTiff: " + reader.isTiff());
        }
        if (requiredValidTiff != null && reader.isValidTiff() != requiredValidTiff) {
            throw new AssertionError("Invalid isValidTiff: " + reader.isValidTiff());
        }
        reader.close();
    }

    public void test() throws IOException {
    }

    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean invalid = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-invalid")) {
            invalid = true;
            startArgIndex++;
        }
        if (args.length == startArgIndex) {
            System.out.printf("Usage: [-invalid] %s file%n%n", TiffReaderOpenModeTest.class.getName());
            new TiffReaderOpenModeTest().test();
            return;
        }
        final Path file = Paths.get(args[startArgIndex]);
        showFile(file, TiffReader.OpenMode.NO_CHECKS);
        showFile(file, TiffReader.OpenMode.ALLOW_NON_TIFF);
        showFile(file, TiffReader.OpenMode.ALLOW_EXISTING_NON_TIFF);
        showFile(file, TiffReader.OpenMode.VALID_TIFF);
        System.out.println();
        checkFile(file, invalid);
    }
}
