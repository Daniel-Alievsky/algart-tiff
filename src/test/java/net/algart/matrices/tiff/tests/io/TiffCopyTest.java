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

package net.algart.matrices.tiff.tests.io;

import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffCopyTest {
    boolean useContext = false;
    boolean bigTiff = false;
    boolean rawCopy = false;
    boolean uncompress = false;

    public static void main(String[] args) throws IOException {
        TiffCopyTest copier = new TiffCopyTest();
        int startArgIndex = 0;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-rawCopy")) {
            copier.rawCopy = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.println("    " + TiffCopyTest.class.getName()
                    + " [-rawCopy] source.tif target.tif [firstIFDIndex lastIFDIndex [numberOfTests]]");
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex++]);
        final Path targetFile = Paths.get(args[startArgIndex++]);
        final int firstIFDIndex = startArgIndex < args.length ?
                Integer.parseInt(args[startArgIndex]) :
                0;
        int lastIFDIndex = startArgIndex + 1 < args.length ?
                Integer.parseInt(args[startArgIndex + 1]) :
                Integer.MAX_VALUE;
        final int numberOfTests = startArgIndex + 2 < args.length ? Integer.parseInt(args[startArgIndex + 2]) : 1;

        for (int test = 1; test <= numberOfTests; test++) {
            System.out.printf("Test #%d%n", test);
            copier.copyTiff(sourceFile, targetFile, firstIFDIndex, lastIFDIndex);
        }
        System.out.println("Done");
    }

    void copyTiff(Path targetFile, Path sourceFile) throws IOException {
        copyTiff(sourceFile, targetFile, 0, Integer.MAX_VALUE);
    }

    private void copyTiff(
            Path sourceFile, Path targetFile,
            int firstIFDIndex,
            int lastIFDIndex)
            throws IOException {
        try (TiffReader reader = new TiffReader(sourceFile, false)) {
            if (useContext) {
                reader.setContext(TiffReader.newSCIFIOContext());
            }
            if (!reader.isValid()) {
                System.out.printf("Skipping %s: not a TIFF%n", sourceFile);
                return;
            }
            System.out.printf("Copying %s to %s...%n", sourceFile, targetFile);
            reader.setByteFiller((byte) 0xC0);
            boolean ok = false;
            try (TiffWriter writer = new TiffWriter(targetFile)) {
                if (useContext) {
                    writer.setContext(TiffReader.newSCIFIOContext());
                }
                writer.setBigTiff(bigTiff || reader.isBigTiff());
                writer.setLittleEndian(reader.isLittleEndian());
                // writer.setJpegInPhotometricRGB(true);
                // - should not be important for copying, when PhotometricInterpretation is already specified
//                writer.setQuality(0.3);
                writer.create();

                final var maps = reader.allMaps();
                lastIFDIndex = Math.min(lastIFDIndex, maps.size() - 1);
                for (int ifdIndex = firstIFDIndex; ifdIndex <= lastIFDIndex; ifdIndex++) {
                    final var readMap = maps.get(ifdIndex);
                    System.out.printf("\r  Copying #%d/%d: %s%n", ifdIndex, maps.size(), readMap.ifd());
                    if (rawCopy) {
                        writer.copyImage(readMap);
                    } else {
                        writer.copyImage(readMap, writeIFD -> {
                            if (uncompress) {
                                writeIFD.putCompression(TagCompression.NONE);
                            }
                        }, true);
                    }
                }
                ok = true;
            } finally {
                if (!ok) {
                    Files.deleteIfExists(targetFile);
                }
            }
        }
    }
}
