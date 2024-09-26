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

import io.scif.FormatException;
import io.scif.formats.tiff.IFD;
import net.algart.arrays.JArrays;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.compatibility.TiffParser;
import net.algart.matrices.tiff.tags.Tags;
import org.scijava.Context;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffParserIFDTileOffsetsTest {
    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        boolean equalStrips = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-equalStrips")) {
            equalStrips = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.println("    " + TiffParserIFDTileOffsetsTest.class.getName() +
                    " [-equalStrips] tiff_file.tiff ifdIndex");
            return;
        }

        final Path file = Paths.get(args[startArgIndex]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex + 1]);

        TiffParser reader = new TiffParser(new Context(), file);
        if (equalStrips) {
            reader.setAssumeEqualStrips(true);
        }
        long t1 = System.nanoTime();
        IFD ifd = reader.getIFDs().get(ifdIndex);
        long t2 = System.nanoTime();
        System.out.printf("IFD #%d: %s (%.6f ms)%n%n", ifdIndex, ifd, (t2 - t1) * 1e-3);

        for (int test = 1; test <= 10; test++) {
            System.out.printf("%nTest %d:%n", test);

            t1 = System.nanoTime();
            long[] offsets = ifd.getStripOffsets();
            t2 = System.nanoTime();
            System.out.printf("Tile/strip offsets, %d values: [%s] (%.3f mcs)%n",
                    offsets.length, JArrays.toString(offsets, ", ", 100), (t2 - t1) * 1e-3);

            t1 = System.nanoTime();
            long[] counts = ifd.getStripByteCounts();
            t2 = System.nanoTime();
            System.out.printf("Tile/strip byte-counts, %d values: [%s] (%.3f mcs)%n",
                    counts.length, JArrays.toString(counts, ", ", 100), (t2 - t1) * 1e-3);
        }
        reader.close();
    }
}
