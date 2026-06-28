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

package net.algart.matrices.tiff.tests.io;

import net.algart.matrices.tiff.*;
import net.algart.matrices.tiff.samples.TiffSampleType;
import net.algart.matrices.tiff.tags.TagCompression;

import java.awt.*;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

public class TiffRewriteSeveralIFDTest {
    private final static boolean DAMAGE_AFTER_WRITE = true;

    public static void main(String... args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage:");
            System.out.println("    " + TiffRewriteSeveralIFDTest.class.getName() +
                    " target.tiff [number_of_images]");
            return;
        }
        final Path targetFile = Paths.get(args[0]);
        final int numberOfImages = args.length < 2 ? 5 : Integer.parseInt(args[1]);

        System.out.println("Writing TIFF " + targetFile + "...");
        try (final TiffWriter writer = new TiffWriter(targetFile, TiffCreateMode.CREATE)) {
            System.out.printf("Linkage after creating: %s%n", writer.currentIFDLinkage());

            TiffIFD[] ifds = new TiffIFD[numberOfImages];
            Random random = new Random(157);
            for (int k = 0; k < numberOfImages; k++) {
                System.out.printf("Writing image #%d...%n", k);
                final TiffIFD ifd = TiffIFD.newTiledIFD(TagCompression.JPEG)
                        .putPixelInformation(k % 2 == 0 ? 3 : 1, TiffSampleType.UINT8);
                ifds[k] = ifd;
                writer.newResizableMap(ifd).writeBlank(randomColor(random));
                System.out.printf("Linkage after write image: %s%n", writer.currentIFDLinkage());
                if (DAMAGE_AFTER_WRITE) {
                    long previous0 = k == 0 ?
                            TiffIFD.IFD_CHAIN_TERMINATOR :
                            writer.readMainIFDOffset(1);
                    damage(writer, ifd);
                    damage(writer, ifds[0]);
                    // writer.refreshIFDLinkage();
                    restore(writer, ifds[0], previous0);
                    // writer.refreshIFDLinkage();
                    restore(writer, ifd, TiffIFD.IFD_CHAIN_TERMINATOR);
                    writer.invalidateIFDLinkage();
                }
            }
        }
        System.out.println("Done");
    }

    private static void damage(TiffWriter writer, TiffIFD ifd) throws IOException {
        ifd.setNextIFDOffset(0x27777777);
        // - something wrong
        writer.writeIFD(ifd, TiffIO.IFDLinkage.UpdateMode.NONE);
        System.out.printf("Linkage after damaging IFD at %d: %s%n",
                ifd.assignedFileOffsetOfIFDForWriting(), writer.currentIFDLinkage());
    }

    private static void restore(TiffWriter writer, TiffIFD ifd, long previous) throws IOException {
        ifd.setNextIFDOffset(previous);
        writer.writeIFD(ifd, TiffIO.IFDLinkage.UpdateMode.UPDATE);
        System.out.printf("Linkage after restoring this IFD: %s%n", writer.currentIFDLinkage());
    }

    private static Color randomColor(Random rnd) {
        return new Color(
                100 + rnd.nextInt(156),
                100 + rnd.nextInt(156),
                100 + rnd.nextInt(156));
    }
}
