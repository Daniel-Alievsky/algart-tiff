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

public class TiffIFDMainOffsetsTest {
    private static void printLinkage(TiffReader reader) {
        System.out.printf("  Position of last scanned IFD offset: %s%n", reader.offsetOfLastScannedIFDOffset());
    }

    private static void checkEqual(TiffIFD ifd1, TiffIFD ifd2) {
        final String detailed1 = ifd1.toString(TiffIFD.StringFormat.DETAILED);
        final String detailed2 = ifd2.toString(TiffIFD.StringFormat.DETAILED);
        if (!detailed1.equals(detailed2)) {
            throw new AssertionError("Not equal:\n\n" + detailed1 + "\n\n" + detailed2);
        }
    }

    public void test() throws Exception {
        main("src/test/resources/demo/images/tiff/openslide/CMU-1-Small-Region.svs", "3", "2");
        main("src/test/resources/demo/images/tiff/openslide/CMU-1-Small-Region.svs", "1", "2");
        main("src/test/resources/demo/images/tiff/openslide/CMU-1-Small-Region.svs", "0", "2");
        main("src/test/resources/demo/images/tiff/libtiff/test/images/tiff_with_subifd_chain.tif", "1", "2");
        main("src/test/resources/demo/images/tiff/libtiff/test/images/tiff_with_subifd_chain.tif", "0", "2");
        main("-checkIsValid", "src/test/resources/demo/images/tiff/invalid/error_non_completed.tiff", "0", "2");
        main("-checkIsValid", "src/test/resources/demo/images/tiff/invalid/error_only_header.tiff", "0", "2");
        main("src/test/resources/demo/images/tiff/invalid/error_non_completed.tiff", "0", "2");
        main("src/test/resources/demo/images/tiff/invalid/error_only_header.tiff", "0", "2");
    }

    public static void main(String... args) throws IOException {
        int startArgIndex = 0;
        boolean cache = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-cache")) {
            cache = true;
            startArgIndex++;
        }
        boolean checkIsValid = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-checkIsValid")) {
            checkIsValid = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.printf("Usage: %s tiff_file.tiff ifdIndex [numberOfTests]%n",
                    TiffIFDMainOffsetsTest.class.getName());
            return;
        }

        final Path file = Paths.get(args[startArgIndex]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex + 1]);
        final int numberOfTests = startArgIndex + 2 < args.length ? Integer.parseInt(args[startArgIndex + 2]) : 16;
        System.out.printf("Reading IFD #%d from %s...%n", ifdIndex, file);

        TiffReader reader = new TiffReader(file, TiffOpenMode.NO_CHECKS).setCachingIFDs(cache);
        final int numberOfMain = reader.readMainIFDOffsets(true).length;
        final int m = numberOfMain == 0 ? 0 : reader.allMaps().size();
        final int n2 = numberOfMain == 0 ? 0 : reader.mainIFDs().size();
        // - should not throw exception for an invalid file, for example, too short
        // (but error for not long non-completed file with zero first IFD offset)
        if (numberOfMain != n2 || numberOfMain > m) {
            throw new AssertionError(numberOfMain + ", " + n2 + ", " + m);
        }
        System.out.printf("Number of IFDs: %d%n", numberOfMain);
        // reader.allMaps().set(0, null); // - should not be possible (result must be immutable)
        // reader.allIFDs().clear(); // - should not be possible (result must be immutable)
        reader.close();
        reader = new TiffReader(file, TiffOpenMode.NO_CHECKS).setCachingIFDs(cache);

        System.out.println("Analysing...");
        if (checkIsValid) {
            try {
                reader.readFirstIFDOffset();
                // - should throw exception for an invalid file
                if (!reader.isValidTiff()) {
                    throw new AssertionError();
                }
            } catch (TiffException ignored) {
            }
        }
        for (int test = 1; test <= numberOfTests; test++) {
            System.out.printf("%nTest %d/%d:%n", test, numberOfTests);

            long t1 = System.nanoTime();
            OptionalLong offset = reader.readMainIFDOffsetIfPresent(ifdIndex);
            long t2 = System.nanoTime();
            System.out.printf(Locale.US,
                    "readMainIFDOffsetIfPresent(%d): %s (%.6f mcs)%n",
                    ifdIndex, offset, (t2 - t1) * 1e-3);
            if (reader.offsetOfLastScannedIFDOffset().isEmpty()) {
                throw new AssertionError("offsetOfLastScannedIFDOffset is not initialized");
            }
            printLinkage(reader);

            t1 = System.nanoTime();
            long[] offsets = reader.readMainIFDOffsets(true);
            t2 = System.nanoTime();
            System.out.printf(Locale.US,
                    "readMainIFDOffsets(): %s (%.6f mcs)%n", Arrays.toString(offsets), (t2 - t1) * 1e-3);
            printLinkage(reader);

            t1 = System.nanoTime();
            OptionalLong offset0 = reader.readFirstIFDOffsetIfPresent();
            t2 = System.nanoTime();
            System.out.printf(Locale.US,
                    "readFirstIFDOffsetIfPresent(): %s (%.6f mcs)%n", offset0, (t2 - t1) * 1e-3);
            if (offset0.isPresent() != offsets.length > 0) {
                throw new AssertionError(offset0 + ", " + offsets.length);
            }
            if (offset0.isPresent() && offset0.getAsLong() != offsets[0]) {
                throw new AssertionError(offset0 + ", " + offset0.getAsLong());
            }
            if (offset0.isPresent() != reader.offsetOfLastScannedIFDOffset().isPresent()) {
                System.out.printf("Empty TIFF: first offset %s, but offset of the last scanned IFD offset %s%n",
                        offset, reader.offsetOfLastScannedIFDOffset());
                // - O'k:  readMainIFDOffsets() filled offsetOfLastScannedIFDOffset even in an empty TIFF
            }
            if (reader.offsetOfLastScannedIFDOffset().getAsLong() != reader.offsetOfFirstIFDOffset()) {
                throw new AssertionError(reader.offsetOfLastScannedIFDOffset() + ", " +
                        reader.offsetOfFirstIFDOffset());
            }
            printLinkage(reader);

            t1 = System.nanoTime();
            int n = reader.numberOfImagesUnchecked();
            t2 = System.nanoTime();
            if (n != m) {
                throw new AssertionError();
            }
            System.out.printf(Locale.US, "numberOfIFDs(): %d (%.6f mcs)%n", n, (t2 - t1) * 1e-3);

            if (numberOfMain > 0) {
                t1 = System.nanoTime();
                List<TiffIFD> allIFDs = reader.allIFDs();
                t2 = System.nanoTime();
                if (allIFDs.size() != m) {
                    throw new AssertionError();
                }
                System.out.printf(Locale.US, "allIFDs(): %d (%.6f mcs)%n",
                        allIFDs.size(), (t2 - t1) * 1e-3);
                printLinkage(reader);

                t1 = System.nanoTime();
                List<TiffIFD> mainIFDS = reader.mainIFDs();
                t2 = System.nanoTime();
                if (mainIFDS.size() != numberOfMain) {
                    throw new AssertionError();
                }
                System.out.printf(Locale.US, "mainIFDs(): %d (%.6f mcs)%n",
                        mainIFDS.size(), (t2 - t1) * 1e-3);
                printLinkage(reader);

                t1 = System.nanoTime();
                TiffIFD firstIFD = reader.readMainIFD(0);
                t2 = System.nanoTime();
//        IFD firstIFD = new TiffParser(new SCIFIO().getContext(), new FileLocation(file.toFile())).getFirstIFD();
                System.out.printf(Locale.US, "readMainIFD(0): %s (%.6f mcs)%n",
                        firstIFD, (t2 - t1) * 1e-3);
                printLinkage(reader);
                final long lastScannedOffset = reader.offsetOfLastScannedIFDOffset().orElseThrow();

                t1 = System.nanoTime();
                TiffIFD.Linkage linkage = reader.readLinkage();
                t2 = System.nanoTime();
                System.out.printf(Locale.US, "readLinkage(): %s (%.6f mcs)%n", linkage, (t2 - t1) * 1e-3);
                printLinkage(reader);
                if (lastScannedOffset != reader.offsetOfLastScannedIFDOffset().orElseThrow()) {
                    throw new AssertionError("offsetOfLastScannedIFDOffset must no change in readLinkage");
                }

                var linkageCopy = new TiffIFD.Linkage(linkage.offsetOfIFDChainTerminator(), linkage.mainIFDOffsetPairs());
                if (!linkageCopy.equals(linkage)) {
                    throw new AssertionError("linkageCopy must equal linkage");
                }

                t1 = System.nanoTime();
                TiffIFD ifd = reader.readMainIFD(ifdIndex);
                t2 = System.nanoTime();
                System.out.printf(Locale.US,
                        "readMainIFD(%d): %s (%.6f mcs)%n", ifdIndex, ifd, (t2 - t1) * 1e-3);
                printLinkage(reader);
                if (ifdIndex > 0 && reader.offsetOfLastScannedIFDOffset().orElseThrow() !=
                        mainIFDS.get(ifdIndex - 1).getFileOffsetOfNextIFDOffset()) {
                    throw new AssertionError("TiffIFD.getFileOffsetOfNextIFDOffset() and " +
                            "offsetOfLastScannedIFDOffset() mismatch");
                }

                t1 = System.nanoTime();
                ifd = reader.readMainIFD(numberOfMain - 1);
                t2 = System.nanoTime();
                System.out.printf(Locale.US,
                        "readMainIFD(%d): %s (%.6f mcs)%n", numberOfMain - 1, ifd, (t2 - t1) * 1e-3);
                printLinkage(reader);
                checkEqual(ifd, mainIFDS.get(numberOfMain - 1));
            }
        }
        reader.close();
    }

}
