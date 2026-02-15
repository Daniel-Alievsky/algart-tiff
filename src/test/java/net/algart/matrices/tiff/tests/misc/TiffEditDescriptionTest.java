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

import net.algart.matrices.tiff.TiffCreateMode;
import net.algart.matrices.tiff.TiffWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Random;

public class TiffEditDescriptionTest {
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.printf("    %s target.tiff number-of-tests%n", TiffEditDescriptionTest.class.getName());
            System.out.println("Note that target.tiff will be seriously changed!");
            return;
        }
        final Path targetFile = Paths.get(args[0]);
        final int numberOfTests = Integer.parseInt(args[1]);
        final Random rnd = new Random(157);
        try (TiffWriter writer = new TiffWriter(targetFile, TiffCreateMode.OPEN_EXISTING)) {
            final int numberOfExisting = writer.numberOfExistingImages();
            for (int test = 1; test <= numberOfTests; test++) {
                final int n = writer.numberOfExistingImages();
                if (n != numberOfExisting) {
                    throw new AssertionError("Number of IFDs changed: " + n + " != " + numberOfExisting);
                }
                final int ifdIndex = rnd.nextInt(n);
                final boolean enforceRelocateIFD = rnd.nextBoolean();
                Collection<Long> alreadyUsed = writer.allUsedIFDOffsets();
                System.out.printf("Test #%d/%d: modifying IFD #%d, %d used offsets...%n",
                        test, numberOfTests, ifdIndex, alreadyUsed.size());
                writer.rewriteDescription(ifdIndex,
                        "Description #" + test + " in IFD #" + ifdIndex,
                        enforceRelocateIFD);
            }
        }
    }
}
