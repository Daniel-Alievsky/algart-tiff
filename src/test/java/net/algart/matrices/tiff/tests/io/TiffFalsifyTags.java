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

import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagPhotometricInterpretation;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffFalsifyTags {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 4) {
            System.out.println("Usage:");
            System.out.println("    " + TiffFalsifyTags.class.getName()
                    + " target.tif ifdIndex newWidth newLength [colorSpace]");
            System.out.println("newWidth/newLength are (0,0) (to avoid changing) or " +
                    "new sizes of this IFD (but you should not try to increase total number of pixels)");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex++]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex++]);
        final int newDimX = Integer.parseInt(args[startArgIndex++]);
        final int newDimY = Integer.parseInt(args[startArgIndex++]);
        TagPhotometricInterpretation photometricInterpretation = null;
        if (startArgIndex < args.length) {
            photometricInterpretation = TagPhotometricInterpretation.valueOf(args[startArgIndex]);
        }

        try (var reader = new TiffReader(targetFile);
             TiffWriter writer = new TiffWriter(targetFile)) {
            writer.openExisting();

            System.out.printf("Transforming %s...%n", targetFile);
            final TiffIFD ifd = reader.readSingleIFD(ifdIndex);
            ifd.setFileOffsetForWriting(ifd.getFileOffsetForReading());
            if (newDimX > 0 && newDimY > 0) {
                ifd.putImageDimensions(newDimX, newDimY);
            }
            if (photometricInterpretation != null) {
                ifd.putPhotometricInterpretation(photometricInterpretation);
            }
            writer.rewriteIFD(ifd, false);
            // - replacing dimensions
        }
        System.out.println("Done");
    }
}
