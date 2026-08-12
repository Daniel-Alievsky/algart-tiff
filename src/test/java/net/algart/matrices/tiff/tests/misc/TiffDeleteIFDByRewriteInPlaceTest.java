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

import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.function.IntPredicate;

/**
 * This test is recommended to be called for a new copy of
 * {@code src/test/resources/demo/images/tiff/libtiff/test/images/test_ifd_loop_subifd.tif}
 * to remove main IFD #2.
 * It checks whether {@link TiffWriter#writeIFD(TiffIFD, TiffIFD.Linkage.UpdateMode)},
 * {@link TiffWriter#rewriteIFDStrictlyInPlace(TiffIFD, IntPredicate, TiffIFD.Linkage.UpdateMode)}
 * and similar methods correctly call {@link TiffWriter#correctInvalidLinkageInFile()}.
 */
public class TiffDeleteIFDByRewriteInPlaceTest {
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.printf("    %s target.tiff ifd-index-to-remove%n",
                    TiffDeleteIFDByRewriteInPlaceTest.class.getName());
            return;
        }
        final Path targetFile = Paths.get(args[0]);
        final int ifdToRemove = Integer.parseInt(args[1]);
        if (ifdToRemove == 0) {
            System.out.printf("This method cannot be used for removing IFD #0%n");
            return;
        }

        System.out.printf("Rewriting %s...%n", targetFile);
        try (TiffWriter writer = new TiffWriter(targetFile, TiffWriter.OpenMode.OPEN_EXISTING)) {
            System.out.printf("Number of IFDs: %d%s%n",
                    writer.numberOfMainImages(),
                    writer.linkage().isInfiniteLoopDetected() ? " (infinite loop)" : "");
            System.out.printf("Deleting IFD %d%n", ifdToRemove);
            final TiffIFD ifd = writer.readMainIFD(ifdToRemove - 1);
            ifd.assignOriginalFileOffsetOfIFDForWriting();
            ifd.setNextIFDOffset(ifdToRemove == writer.numberOfMainImages() - 1 ?
                    TiffIFD.IFD_CHAIN_TERMINATOR :
                    writer.linkage().mainIFDOffset(ifdToRemove + 1));
            writer.rewriteIFDStrictlyInPlace(ifd, Set.of());
            System.out.printf("Number of IFDs: %d%s%n",
                    writer.numberOfMainImages(),
                    writer.linkage().isInfiniteLoopDetected() ? " (infinite loop)" : "");
        }
    }
}
