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

import net.algart.matrices.tiff.*;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

public class TiffIFDAndIFDOffsetsTest {
    private static void printLinkage(TiffReader reader) {
        System.out.printf("  Position of last scanned IFD offset: %s%n", reader.offsetOfLastScannedIFDOffset());
    }

    public static void main(String... args) throws IOException {
        int startArgIndex = 0;
        boolean cache = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-cache")) {
            cache = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.printf("Usage: %s tiff_file.tiff ifdIndex%n", TiffIFDAndIFDOffsetsTest.class.getName());
            return;
        }

        final Path file = Paths.get(args[startArgIndex]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex + 1]);
        System.out.printf("Reading IFD #%d from %s...%n", ifdIndex, file);

        TiffReader reader = new TiffReader(file, TiffOpenMode.ALLOW_EXISTING_NON_TIFF).setCachingIFDs(cache);
        final int n1 = reader.readMainIFDOffsets(TiffIO.LinkageUpdateMode.UPDATE, true).length;
        final int m = reader.allMaps().size();
        final int n2 = reader.mainIFDs().size();
        // - should not throw exception for an invalid file, for example, too short
        // (but error for not long non-completed file with zero first IFD offset)
        if (n1 != n2 || n1 > m) {
            throw new AssertionError(n1 + ", " + n2 + ", " + m);
        }
        System.out.printf("Number of IFDs: %d%n", n1);
        // reader.allMaps().set(0, null); // - should not be possible (result must be immutable)
        // reader.allIFDs().clear(); // - should not be possible (result must be immutable)
        reader.close();
        reader = new TiffReader(file, TiffOpenMode.NO_CHECKS).setCachingIFDs(cache);

        System.out.println("Analysing...");
        try {
            reader.readFirstIFDOffset(TiffIO.LinkageUpdateMode.UPDATE);
            // - should throw exception for an invalid file
            if (!reader.isValidTiff()) {
                throw new AssertionError();
            }
        } catch (TiffException ignored) {
        }
        for (int test = 1; test <= 16; test++) {
            System.out.printf("%nTest %d:%n", test);

            long t1 = System.nanoTime();
            OptionalLong offset = reader.readMainIFDOffsetIfPresent(ifdIndex, TiffIO.LinkageUpdateMode.UPDATE);
            long t2 = System.nanoTime();
            System.out.printf(Locale.US,
                    "readMainIFDOffsetIfPresent(%d): %s (%.6f mcs)%n", ifdIndex, offset, (t2 - t1) * 1e-3);
            printLinkage(reader);

            t1 = System.nanoTime();
            long[] offsets = reader.readMainIFDOffsets(TiffIO.LinkageUpdateMode.UPDATE, true);
            t2 = System.nanoTime();
            System.out.printf(Locale.US,
                    "readMainIFDOffsets(): %s (%.6f mcs)%n", Arrays.toString(offsets), (t2 - t1) * 1e-3);
            printLinkage(reader);

            t1 = System.nanoTime();
            int n = reader.numberOfImagesUnchecked();
            t2 = System.nanoTime();
            if (n != m) {
                throw new AssertionError();
            }
            System.out.printf(Locale.US, "numberOfIFDs(): %d (%.6f mcs)%n", n, (t2 - t1) * 1e-3);

            t1 = System.nanoTime();
            List<TiffIFD> ifds = reader.allIFDs();
            t2 = System.nanoTime();
            if (ifds.size() != m) {
                throw new AssertionError();
            }
            System.out.printf(Locale.US, "allIFDs(): %d (%.6f mcs)%n", ifds.size(), (t2 - t1) * 1e-3);
            printLinkage(reader);

            t1 = System.nanoTime();
            ifds = reader.mainIFDs();
            t2 = System.nanoTime();
            if (ifds.size() != n1) {
                throw new AssertionError();
            }
            System.out.printf(Locale.US, "mainIFDs(): %d (%.6f mcs)%n", ifds.size(), (t2 - t1) * 1e-3);
            printLinkage(reader);

            t1 = System.nanoTime();
            TiffIFD firstIFD = reader.readMainIFD(0);
            t2 = System.nanoTime();
//        IFD firstIFD = new TiffParser(new SCIFIO().getContext(), new FileLocation(file.toFile())).getFirstIFD();
            System.out.printf(Locale.US, "readMainIFD(0): %s (%.6f mcs)%n", firstIFD, (t2 - t1) * 1e-3);
            printLinkage(reader);

            t1 = System.nanoTime();
            TiffIFD ifd = reader.readMainIFD(ifdIndex);
            t2 = System.nanoTime();
            System.out.printf(Locale.US,
                    "readMainIFD(%d): %s (%.6f mcs)%n", ifdIndex, ifd, (t2 - t1) * 1e-3);
            printLinkage(reader);

            t1 = System.nanoTime();
            TiffIFD lastIfd = reader.readLastIFD();
            t2 = System.nanoTime();
            System.out.printf(Locale.US,
                    "readLastIFD(): %s (%.6f mcs)%n", lastIfd, (t2 - t1) * 1e-3);
            printLinkage(reader);
        }

        reader.close();
    }

}
