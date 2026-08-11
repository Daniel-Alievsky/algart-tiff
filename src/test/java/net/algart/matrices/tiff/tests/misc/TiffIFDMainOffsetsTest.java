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
        System.out.printf("  Current linkage: %s%n", reader.linkageIfPresent().orElse(null));
    }

    private static void checkEqual(TiffIFD ifd1, TiffIFD ifd2) {
        final String detailed1 = ifd1.toString(TiffIFD.StringFormat.DETAILED);
        final String detailed2 = ifd2.toString(TiffIFD.StringFormat.DETAILED);
        if (!detailed1.equals(detailed2)) {
            throw new AssertionError("Not equal:\n\n" + detailed1 + "\n\n" + detailed2);
        }
    }

    private static void checkOffsets(TiffIFD ifd, TiffIFD.Linkage linkage, int ifdIndex) {
        long nextOffset = ifdIndex + 1 < linkage.numberOfMainIFDs() ?
                linkage.mainIFDOffset(ifdIndex + 1) :
                TiffIFD.IFD_CHAIN_TERMINATOR;
        if ((ifdIndex < linkage.numberOfMainIFDs() - 1 || linkage.isValid())
                && ifd.getNextIFDOffset() != nextOffset) {
            throw new AssertionError("TiffIFD.getNextIFDOffset and Linkage.mainIFDOffset mismatch: " +
                    ifd.getNextIFDOffset() + ", " + nextOffset);
            // - if the linkage is invalid, the last ifd.getNextIFDOffset() is automatically corrected
        }
        if (ifd.getFileOffsetOfNextIFDOffset() != linkage.offsetOfNextIFDOffset(ifdIndex)) {
            throw new AssertionError("TiffIFD.getFileOffsetOfNextIFDOffset and " +
                    "Linkage.offsetOfNextIFDOffset mismatch");
        }
    }

    public void test() throws Exception {
        main("src/test/resources/demo/images/tiff/openslide/CMU-1-Small-Region.svs", "0..3", "2");
        main("src/test/resources/demo/images/tiff/libtiff/test/images/tiff_with_subifd_chain.tif", "0..3", "2");
        main("src/test/resources/demo/images/tiff/libtiff/test/images/test_ifd_loop_subifd.tif", "0..7", "2");
        main("src/test/resources/demo/images/tiff/libtiff/test/images/test_ifd_loop_to_first.tif", "0..2", "2");
        main("src/test/resources/demo/images/tiff/libtiff/test/images/test_ifd_loop_to_self.tif", "0..1", "2");
        main("src/test/resources/demo/images/tiff/libtiff/test/images/tiff_with_subifd_chain.tif", "0", "2");
        main("src/test/resources/demo/images/tiff/algart/bigtiff/jpeg_rgb_tiled_big_with_16bit_sizes.tiff",
                "0", "2");
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
            System.out.printf("Usage: %s tiff_file.tiff ifdIndex|ifdIndexRange [numberOfTests]%n",
                    TiffIFDMainOffsetsTest.class.getName());
            return;
        }

        final Path file = Paths.get(args[startArgIndex]);
        String range = args[startArgIndex + 1];
        final int firstIndex, lastIndex;
        if (range.contains("..")) {
            firstIndex = Integer.parseInt(range.substring(0, range.indexOf("..")));
            lastIndex = Integer.parseInt(range.substring(range.indexOf("..") + 2));
        } else {
            firstIndex = lastIndex = Integer.parseInt(range);
        }
        final int numberOfTests = startArgIndex + 2 < args.length ? Integer.parseInt(args[startArgIndex + 2]) : 16;
        System.out.printf("Reading IFD #%s from %s...%n", range, file);

        TiffReader reader = new TiffReader(file, TiffReader.OpenMode.NO_CHECKS).setCachingIFDs(cache);
        final int numberOfMain = reader.readMainIFDOffsets(true).length;
        final int numberOfAll = numberOfMain == 0 ? 0 : reader.allMaps().size();
        final int numberOfMain2 = numberOfMain == 0 ? 0 : reader.mainIFDs().size();
        final int numberOfMain3 = numberOfMain == 0 ? 0 : reader.numberOfMainImages();
        // - should not throw exception for an invalid file, for example, too short
        // (but error for not long non-completed file with zero first IFD offset)
        if (numberOfMain != numberOfMain2 || numberOfMain != numberOfMain3 || numberOfMain > numberOfAll) {
            throw new AssertionError(numberOfMain + ", " + numberOfMain2 + ", " +
                    numberOfMain3 + ", " + numberOfAll);
        }
        System.out.printf("Number of IFDs: %d%n", numberOfMain);
        // reader.allMaps().set(0, null); // - should not be possible (result must be immutable)
        // reader.allIFDs().clear(); // - should not be possible (result must be immutable)
        reader.close();

        for (int ifdIndex = firstIndex; ifdIndex <= lastIndex; ifdIndex++) {
            reader = new TiffReader(file, TiffReader.OpenMode.NO_CHECKS).setCachingIFDs(cache);

            System.out.println("Analysing...");
            System.out.printf("isTiff: %s%n", reader.isTiff());
            System.out.printf("isValidTiff: %s%n", reader.isValidTiff());
            System.out.printf("isBigTiff: %s%n", reader.isBigTiff());
            if (checkIsValid) {
                try {
                    reader.readMainIFDOffset(0);
                    // - should throw exception for an invalid file
                    if (!reader.isValidTiff()) {
                        throw new AssertionError();
                    }
                } catch (TiffException ignored) {
                }
            }
            for (int test = 1; test <= numberOfTests; test++) {
                System.out.printf("%nTest %d/%d, ifdIndex %d:%n", test, numberOfTests, ifdIndex);
                testIFD(reader, ifdIndex, numberOfAll, numberOfMain);
            }
            reader.close();
        }
    }

    private static void testIFD(TiffReader reader, int ifdIndex, int numberOfAll, int numberOfMain)
            throws IOException {
        long t1 = System.nanoTime();
        OptionalLong offset = reader.readMainIFDOffsetIfPresent(ifdIndex);
        long t2 = System.nanoTime();
        System.out.printf(Locale.ROOT,
                "readMainIFDOffsetIfPresent(%d): %s (%.6f mcs)%n",
                ifdIndex, offset, (t2 - t1) * 1e-3);
        printLinkage(reader);

        t1 = System.nanoTime();
        long[] offsets = reader.readMainIFDOffsets(true);
        t2 = System.nanoTime();
        System.out.printf(Locale.ROOT,
                "readMainIFDOffsets(): %s (%.6f mcs)%n", Arrays.toString(offsets), (t2 - t1) * 1e-3);
        printLinkage(reader);

        t1 = System.nanoTime();
        OptionalLong offset0 = reader.readMainIFDOffsetIfPresent(0);
        t2 = System.nanoTime();
        System.out.printf(Locale.ROOT,
                "readMainIFDOffsetIfPresent(0): %s (%.6f mcs)%n", offset0, (t2 - t1) * 1e-3);
        if (offset0.isPresent() != offsets.length > 0) {
            throw new AssertionError(offset0 + ", " + offsets.length);
        }
        if (offset0.isPresent() && offset0.getAsLong() != offsets[0]) {
            throw new AssertionError(offset0 + ", " + offset0.getAsLong());
        }
        printLinkage(reader);


        t1 = System.nanoTime();
        long offset0Other = -1;
        IOException offset0Exception = null;
        try {
            offset0Other = reader.readMainIFDOffset(0);
        } catch (IOException e) {
            offset0Exception = e;
            e.printStackTrace(System.out);
        }
        t2 = System.nanoTime();
        System.out.printf(Locale.ROOT,
                "readMainIFDOffset(0): %s (%.6f mcs)%n",
                offset0Other != -1 ? String.valueOf(offset0Other) : offset0Exception,
                (t2 - t1) * 1e-3);
        if (offset0.isPresent() != offset0Other > 0) {
            throw new AssertionError(offset0 + ", " + offset0Other);
        }
        if (offset0.isPresent() && offset0.getAsLong() != offset0Other) {
            throw new AssertionError(offset0 + ", " + offset0Other);
        }
        if (offset0.isEmpty() && !(offset0Exception instanceof UncompletedTiffException)) {
            throw new AssertionError(offset0 + ", " + offset0Exception);
        }
        printLinkage(reader);

        t1 = System.nanoTime();
        TiffIFD.Linkage linkage = reader.readLinkage(true);
        t2 = System.nanoTime();
        System.out.printf(Locale.ROOT, "readLinkage(true): %s (%.6f mcs)%n",
                linkage, (t2 - t1) * 1e-3);
        if (offset0.isPresent() == linkage.isEmpty() ||
                (offset0.isPresent() && offset0.getAsLong() != linkage.mainIFDOffset(0))) {
            throw new AssertionError(offset0 + ", " + linkage);
        }
        printLinkage(reader);

        var linkageCopy = new TiffIFD.Linkage(linkage.offsetOfChainTerminator(), linkage.mainIFDOffsetPairs());
        if (linkage.isValid() && !linkageCopy.equals(linkage)) {
            throw new AssertionError("linkageCopy must equal linkage");
        }

        t1 = System.nanoTime();
        int n = reader.numberOfImagesUnchecked();
        t2 = System.nanoTime();
        if (n != numberOfAll) {
            throw new AssertionError();
        }
        System.out.printf(Locale.ROOT, "numberOfIFDs(): %d (%.6f mcs)%n", n, (t2 - t1) * 1e-3);

        if (ifdIndex < numberOfMain) {
            // - numberOfMain must be positive: allIFDs() can lead to an exception even when reader.isValidTiff(),
            // for example, for error_non_completed.tiff
            t1 = System.nanoTime();
            List<TiffIFD> allIFDs = reader.allIFDs();
            t2 = System.nanoTime();
            if (allIFDs.size() != numberOfAll) {
                throw new AssertionError();
            }
            if (reader.linkageIfPresent().isEmpty()) {
                throw new AssertionError("linkage is not initialized");
            }
            System.out.printf(Locale.ROOT, "allIFDs(): %d (%.6f mcs)%n",
                    allIFDs.size(), (t2 - t1) * 1e-3);
            printLinkage(reader);

            t1 = System.nanoTime();
            List<TiffIFD> mainIFDS = reader.mainIFDs();
            t2 = System.nanoTime();
            if (mainIFDS.size() != numberOfMain) {
                throw new AssertionError();
            }
            System.out.printf(Locale.ROOT, "mainIFDs(): %d (%.6f mcs)%n",
                    mainIFDS.size(), (t2 - t1) * 1e-3);
            printLinkage(reader);


            t1 = System.nanoTime();
            TiffIFD firstIFD = reader.readMainIFD(0);
            t2 = System.nanoTime();
//        IFD firstIFD = new TiffParser(new SCIFIO().getContext(), new FileLocation(file.toFile())).getFirstIFD();
            System.out.printf(Locale.ROOT, "readMainIFD(0): %s (%.6f mcs)%n",
                    firstIFD, (t2 - t1) * 1e-3);
            printLinkage(reader);

            t1 = System.nanoTime();
            TiffIFD ifd = reader.readMainIFD(ifdIndex);
            t2 = System.nanoTime();
            System.out.printf(Locale.ROOT,
                    "readMainIFD(%d): %s (%.6f mcs)%n", ifdIndex, ifd, (t2 - t1) * 1e-3);
            printLinkage(reader);
            checkOffsets(ifd, linkage, ifdIndex);
            checkEqual(ifd, mainIFDS.get(ifdIndex));

            t1 = System.nanoTime();
            ifd = reader.readIFDAt(reader.readMainIFDOffset(ifdIndex), TiffIO.ReadIFDMode.SKIP_IFD_ENTRIES);
            t2 = System.nanoTime();
            System.out.printf(Locale.ROOT,
                    "readIFDAt for %d, no entries: %s (%.6f mcs)%n", ifdIndex, ifd, (t2 - t1) * 1e-3);
            printLinkage(reader);
            System.out.printf("Mini-IFD: ''''%s'''%n", ifd.toString(TiffIFD.StringFormat.DETAILED));
            if (ifd.numberOfEntries() > 0 || !ifd.map().isEmpty()) {
                throw new AssertionError("Entries map must be empty");
            }
            checkOffsets(ifd, linkage, ifdIndex);

            t1 = System.nanoTime();
            ifd = reader.readIFDAt(reader.readMainIFDOffset(ifdIndex), TiffIO.ReadIFDMode.SKIP_NEXT_IFD_OFFSET);
            t2 = System.nanoTime();
            System.out.printf(Locale.ROOT,
                    "readIFDA for %d, no next IFD: %s (%.6f mcs)%n", ifdIndex, ifd, (t2 - t1) * 1e-3);
            printLinkage(reader);
            if (ifd.hasNextIFDOffset() || ifd.hasFileOffsetOfNextIFDOffset()) {
                throw new AssertionError();
            }

            t1 = System.nanoTime();
            ifd = reader.readMainIFD(numberOfMain - 1);
            t2 = System.nanoTime();
            System.out.printf(Locale.ROOT,
                    "readMainIFD(%d): %s (%.6f mcs)%n", numberOfMain - 1, ifd, (t2 - t1) * 1e-3);
            printLinkage(reader);
            checkEqual(ifd, mainIFDS.get(numberOfMain - 1));
            checkOffsets(ifd, linkage, numberOfMain - 1);
            if (ifd.getNextIFDOffset() != TiffIFD.IFD_CHAIN_TERMINATOR) {
                throw new AssertionError("Invalid last IFD: " + ifd);
            }
        }
    }
}
