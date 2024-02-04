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

import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffTools;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.matrices.tiff.tiles.TiffTileIndex;
import org.scijava.Context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TiffCopyTest {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean noContext = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-noContext")) {
            noContext = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.println("    " + TiffCopyTest.class.getName()
                    + " source.tif target.tif [firstIFDIndex lastIFDIndex [numberOfTests]]");
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
            try (Context context = noContext ? null : TiffTools.newSCIFIOContext()) {
                copyTiff(
                        context, sourceFile, targetFile, firstIFDIndex, lastIFDIndex,
                        false, false);
            }
        }
        System.out.println("Done");
    }

    private static void copyTiff(
            Context context,
            Path sourceFile, Path targetFile,
            int firstIFDIndex, int lastIFDIndex,
            boolean enforceBigTiff,
            boolean uncompressedTarget)
            throws IOException {
        try (TiffReader reader = new TiffReader(sourceFile, false).setContext(context)) {
            if (!reader.isValid()) {
                System.out.printf("Skipping %s: not a TIFF%n", sourceFile);
                return;
            }
            System.out.printf("Copying %s to %s...%n", sourceFile, targetFile);
            reader.setByteFiller((byte) 0xC0);
            boolean ok = false;
            try (TiffWriter writer = new TiffWriter(targetFile).setContext(context)) {
                writer.setSmartIFDCorrection(true);
                writer.setBigTiff(enforceBigTiff || reader.isBigTiff());
                writer.setLittleEndian(reader.isLittleEndian());
                // writer.setJpegInPhotometricRGB(true);
                // - should not be important for copying, when PhotometricInterpretation is already specified
//                writer.setQuality(0.3);
                writer.create();

                final List<TiffIFD> ifds = reader.allIFDs();
                lastIFDIndex = Math.min(lastIFDIndex, ifds.size() - 1);
                for (int ifdIndex = firstIFDIndex; ifdIndex <= lastIFDIndex; ifdIndex++) {
                    final TiffIFD readIFD = ifds.get(ifdIndex);
                    final TiffIFD writeIFD = new TiffIFD(readIFD);
                    if (uncompressedTarget) {
                        writeIFD.putCompression(TagCompression.UNCOMPRESSED);
                    }
                    System.out.printf("\r  Copying #%d/%d: %s%n", ifdIndex, ifds.size(), readIFD);
                    copyImage(readIFD, writeIFD, reader, writer);
                }
                ok = true;
            } finally {
                if (!ok) {
                    Files.deleteIfExists(targetFile);
                }
            }
        }
    }

    static void copyTiff(
            Context context, Path sourceFile, Path targetFile,
            boolean enforceBigTiff, boolean uncompressedTarget)
            throws IOException {
        copyTiff(context, sourceFile, targetFile, 0, Integer.MAX_VALUE, enforceBigTiff, uncompressedTarget);
    }

    static void copyImage(TiffIFD readIFD, TiffIFD writeIFD, TiffReader reader, TiffWriter writer)
            throws IOException {
        final TiffMap readMap = reader.newMap(readIFD);
        final TiffMap writeMap = writer.newMap(writeIFD, false);
        writer.writeForward(writeMap);
        int k = 0, n = readMap.size();
        for (TiffTileIndex index : readMap.indexes()) {
            TiffTile sourceTile = reader.readTile(index);
            TiffTile targetTile = writeMap.getOrNew(writeMap.copyIndex(index));
            byte[] data = sourceTile.unpackUnusualDecodedData();
            targetTile.setDecodedData(data);
            writeMap.put(targetTile);
            writer.writeTile(targetTile);
            System.out.printf("\rCopying tile %d/%d...\r", ++k, n);
        }
        writer.complete(writeMap);
    }
}
