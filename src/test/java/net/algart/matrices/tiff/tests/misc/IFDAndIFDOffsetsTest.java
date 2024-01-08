/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2024 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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
import net.algart.matrices.tiff.TiffReader;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public class IFDAndIFDOffsetsTest {
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("    " + IFDAndIFDOffsetsTest.class.getName() + " tiff_file.tiff ifdIndex");
            return;
        }

        final Path file = Paths.get(args[0]);
        final int ifdIndex = Integer.parseInt(args[1]);
        System.out.printf("Reading IFD #%d from %s...%n", ifdIndex, file);

        TiffReader reader = new TiffReader(null, file, false);
        int n1 = reader.allMaps().size();
        int n2 = reader.readIFDOffsets().length;
        // - should not throw exception for invalid file
        if (n1 != n2) {
            throw new AssertionError();
        }
        System.out.println("Analysing...");
        reader.readFirstIFDOffset();
        // - should throw exception for invalid file
        for (int test = 1; test <= 10; test++) {
            System.out.printf("%nTest %d:%n", test);
            long t1 = System.nanoTime();
            long offset = reader.readSingleIFDOffset(ifdIndex);
            long t2 = System.nanoTime();
            System.out.printf("IFD offset #%d: %d (%.6f mcs)%n", ifdIndex, offset, (t2 - t1) * 1e-3);
            System.out.printf("  Position of last IFD offset: %d%n", reader.positionOfLastIFDOffset());

            t1 = System.nanoTime();
            long[] offsets = reader.readIFDOffsets();
            t2 = System.nanoTime();
            System.out.printf("All IFD offsets: %s (%.6f mcs)%n", Arrays.toString(offsets), (t2 - t1) * 1e-3);
            System.out.printf("  Position of last IFD offset: %d%n", reader.positionOfLastIFDOffset());

            t1 = System.nanoTime();
            List<TiffIFD> ifds = reader.allIFDs();
            t2 = System.nanoTime();
            System.out.printf("Number of IFDs: %d (%.6f mcs)%n", ifds.size(), (t2 - t1) * 1e-3);
            System.out.printf("  Position of last IFD offset: %d%n", reader.positionOfLastIFDOffset());

            t1 = System.nanoTime();
            TiffIFD firstIFD = reader.firstIFD();
            t2 = System.nanoTime();
//        IFD firstIFD = new TiffParser(new SCIFIO().getContext(), new FileLocation(file.toFile())).getFirstIFD();
            System.out.printf("First IFD: %s (%.6f mcs)%n", firstIFD, (t2 - t1) * 1e-3);
            System.out.printf("  Position of last IFD offset: %d%n", reader.positionOfLastIFDOffset());

            t1 = System.nanoTime();
            TiffIFD ifd = reader.readSingleIFD(ifdIndex);
            t2 = System.nanoTime();
            System.out.printf("IFD #%d: %s (%.6f mcs)%n", ifdIndex, ifd, (t2 - t1) * 1e-3);
            System.out.printf("  Position of last IFD offset: %d%n", reader.positionOfLastIFDOffset());
        }

        reader.close();
    }
}
