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

package net.algart.matrices.tiff.demo.io;

import net.algart.matrices.tiff.TiffCreateMode;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffEditDescriptionDemo {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 3) {
            System.out.println("Usage:");
            System.out.printf("    %s target.tiff ifdIndex \"Some new description (ImageDescription)\"%n",
                    TiffEditDescriptionDemo.class.getName());
            System.out.println("Note that target.tiff will be modified!");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex + 1]);
        final String description = args[startArgIndex + 2];
        try (TiffWriter writer = new TiffWriter(targetFile, TiffCreateMode.OPEN_EXISTING)) {
            final TiffIFD ifd = writer.existingIFD(ifdIndex);
            TiffIFD changedIFD = new TiffIFD(ifd);
            final String oldDescription = ifd.getDescription().description(null);
            final boolean lengthIncreased = oldDescription == null || description.length() > oldDescription.length();
            System.out.printf("IFD #%d/%d: %s image description%n\"%s\"%n%sString length %s: from %d to %d%n",
                    ifdIndex,
                    writer.numberOfExistingIFDs(),
                    oldDescription == null ? "writing new" : "overwriting",
                    description,
                    oldDescription == null ? "" : "(instead of: \"%s\")%n".formatted(oldDescription),
                    lengthIncreased ? "increased" : "not increased (overwriting possible)",
                    oldDescription == null ? 0 : oldDescription.length(),
                    description.length());
            changedIFD.putDescription(description);
            if (lengthIncreased) {
                // We must relocate IFD: overwriting in the same place will damage the further image
                long p = writer.writeIFDAt(changedIFD, null, false);
                // Note: we ignore sub-IFDs here. So, this method is not absolutely universal.
                if (ifdIndex == 0) {
                    writer.rewriteFirstIFDOffset(p);
                } else {
                    final TiffIFD previousIFD = writer.existingIFD(ifdIndex - 1);
                    previousIFD.setNextIFDOffset(p);
                    writer.rewriteIFD(previousIFD);
                    // - restoring IFD sequence
                }
            } else {
                writer.rewriteIFD(changedIFD);
            }
        }
    }
}
