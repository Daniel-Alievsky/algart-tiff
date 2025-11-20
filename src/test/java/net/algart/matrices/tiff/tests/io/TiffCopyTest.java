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

import net.algart.matrices.tiff.TiffCopier;
import net.algart.matrices.tiff.TiffOpenMode;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.TiffReadMap;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TiffCopyTest {
    boolean useContext = false;
    boolean bigTiff = false;
    boolean repack = false;
    boolean smart = false;
    ByteOrder byteOrder = null;
    boolean uncompress = false;
    boolean copyRectangle = false;

    public static void main(String[] args) throws IOException {
        TiffCopyTest copyTest = new TiffCopyTest();
        int startArgIndex = 0;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-repack")) {
            copyTest.repack = true;
            startArgIndex++;
        }
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-smart")) {
            copyTest.smart = true;
            startArgIndex++;
        }
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-le")) {
            copyTest.byteOrder = ByteOrder.LITTLE_ENDIAN;
            startArgIndex++;
        } else if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-be")) {
            copyTest.byteOrder = ByteOrder.BIG_ENDIAN;
            startArgIndex++;
        }
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-copyRectangle")) {
            copyTest.copyRectangle = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.println("    " + TiffCopyTest.class.getName()
                    + " [-repack] [-smart] [-le|-be] " +
                    "source.tif target.tif [firstIFDIndex lastIFDIndex [numberOfTests]]");
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
            copyTest.copyTiff(sourceFile, targetFile, firstIFDIndex, lastIFDIndex);
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
        try (TiffReader reader = new TiffReader(sourceFile, TiffOpenMode.ALLOW_NON_TIFF)) {
            if (useContext) {
                reader.setContext(TiffReader.newSCIFIOContext());
            }
            if (!reader.isTiff()) {
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
                writer.setSmartFormatCorrection(smart);
                writer.setBigTiff(bigTiff || reader.isBigTiff());
                writer.setByteOrder(byteOrder != null ? byteOrder : reader.getByteOrder());

                // writer.setJpegInPhotometricRGB(true);
                // - should not be important for copying, when PhotometricInterpretation is already specified
                // writer.setCompressionQuality(0.3);
                writer.create();

                final List<TiffReadMap> maps = reader.allMaps();
                lastIFDIndex = Math.min(lastIFDIndex, maps.size() - 1);
                for (int ifdIndex = firstIFDIndex; ifdIndex <= lastIFDIndex; ifdIndex++) {
                    final var readMap = maps.get(ifdIndex);
                    if (copyRectangle) {
                        // - for debugging: should lead to the same results!
                        System.out.printf("\r  Copying rectangle (%s) #%d/%d: %s%n",
                                repack ? "repacking" : "raw",
                                ifdIndex, maps.size(), readMap.ifd());
                        final TiffCopier copier = getCopier();
                        copier.setDirectCopy(!repack);
                        copier.copyImage(writer, readMap, 0, 0, readMap.dimX(), readMap.dimY());
                    } else if (!repack && byteOrder == null && !uncompress) {
                        System.out.printf("\r  Direct copying #%d/%d: %s%n", ifdIndex, maps.size(), readMap.ifd());
                        // - below is an example of the simplest usage for a single IFD image:
                        new TiffCopier().copyImage(writer, readMap);
                    } else {
                        System.out.printf("\r  %s #%d/%d: %s%n",
                                repack ? "Repacking" : "Attempt of direct copying",
                                ifdIndex, maps.size(), readMap.ifd());
                        final TiffCopier copier = getCopier();
                        copier.setDirectCopy(!repack);
                        copier.copyImage(writer, readMap);
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

    private TiffCopier getCopier() {
        final TiffCopier copier = new TiffCopier();
        if (!copier.isDirectCopy()) {
            throw new AssertionError("direct-copy should be enabled by default");
        }
       copier.setProgressUpdater(
                p ->
                        System.out.printf("\r%d/%d...", p.tileIndex() + 1, p.tileCount()));
        // copier.setCancellationChecker(() -> copier.copiedTileCount() == 12);
        // - uncomment to cancel copying after 12 tiles
        if (uncompress) {
            copier.setIfdCorrector(ifd -> ifd.putCompression(TagCompression.NONE));
        }
        return copier;
    }
}
