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

package net.algart.matrices.tiff.tests.misc;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffOpenMode;
import net.algart.matrices.tiff.TiffReader;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public class TiffIFDAndIFDOffsetsTest {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean cache = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-cache")) {
            cache = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.println("    " + TiffIFDAndIFDOffsetsTest.class.getName() + " tiff_file.tiff ifdIndex");
            return;
        }

        final Path file = Paths.get(args[startArgIndex]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex + 1]);
        System.out.printf("Reading IFD #%d from %s...%n", ifdIndex, file);

        TiffReader reader = new TiffReader(file, TiffOpenMode.ALLOW_NON_TIFF).setCachingIFDs(cache);
        final int m = reader.allMaps().size();
        final int n1 = reader.mainIFDs().size();
        final int n2 = reader.readIFDOffsets().length;
        // - should not throw exception for invalid file
        if (n1 != n2 || n1 > m) {
            throw new AssertionError(n1 + ", " + n2 + ", " + m);
        }
        System.out.printf("Number of IFDs: %d%n", n1);
        // reader.allMaps().set(0, null); // - should not be possible (result must be immutable)
        // reader.allIFDs().clear(); // - should not be possible (result must be immutable)
        reader.close();
        reader = new TiffReader(file, TiffOpenMode.ALLOW_NON_TIFF).setCachingIFDs(cache);

        System.out.println("Analysing...");
        try {
            reader.readFirstIFDOffset();
            // - should throw exception for an invalid file
            if (!reader.isValidTiff()) {
                throw new AssertionError();
            }
        } catch (TiffException ignored) {
        }
        for (int test = 1; test <= 10; test++) {
            System.out.printf("%nTest %d:%n", test);

            long t1 = System.nanoTime();
            long[] offsets = reader.readIFDOffsets();
            long t2 = System.nanoTime();
            System.out.printf(Locale.US,
                    "readIFDOffsets(): %s (%.6f mcs)%n", Arrays.toString(offsets), (t2 - t1) * 1e-3);
            System.out.printf("  Position of last IFD offset: %d%n", reader.positionOfLastIFDOffset());

            t1 = System.nanoTime();
            long offset = reader.readSingleIFDOffset(ifdIndex);
            t2 = System.nanoTime();
            System.out.printf(Locale.US,
                    "readSingleIFDOffset(%d): %d (%.6f mcs)%n", ifdIndex, offset, (t2 - t1) * 1e-3);
            System.out.printf("  Position of last IFD offset: %d%n", reader.positionOfLastIFDOffset());

            t1 = System.nanoTime();
            int n = reader.numberOfImages();
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
            System.out.printf("  Position of last IFD offset: %d%n", reader.positionOfLastIFDOffset());

            t1 = System.nanoTime();
            ifds = reader.mainIFDs();
            t2 = System.nanoTime();
            if (ifds.size() != n1) {
                throw new AssertionError();
            }
            System.out.printf(Locale.US, "mainIFDs(): %d (%.6f mcs)%n", ifds.size(), (t2 - t1) * 1e-3);
            System.out.printf("  Position of last IFD offset: %d%n", reader.positionOfLastIFDOffset());

            t1 = System.nanoTime();
            TiffIFD firstIFD = reader.firstIFD();
            t2 = System.nanoTime();
//        IFD firstIFD = new TiffParser(new SCIFIO().getContext(), new FileLocation(file.toFile())).getFirstIFD();
            System.out.printf(Locale.US, "firstIFD(): %s (%.6f mcs)%n", firstIFD, (t2 - t1) * 1e-3);
            System.out.printf("  Position of last IFD offset: %d%n", reader.positionOfLastIFDOffset());

            t1 = System.nanoTime();
            TiffIFD ifd = reader.readSingleIFD(ifdIndex);
            t2 = System.nanoTime();
            System.out.printf(Locale.US,
                    "readSingleIFD(%d): %s (%.6f mcs)%n", ifdIndex, ifd, (t2 - t1) * 1e-3);
            System.out.printf("  Position of last IFD offset: %d%n", reader.positionOfLastIFDOffset());
        }

        reader.close();
    }
}
