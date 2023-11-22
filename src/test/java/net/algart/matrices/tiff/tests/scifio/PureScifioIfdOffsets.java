/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2023 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.matrices.tiff.tests.scifio;

import io.scif.SCIFIO;
import io.scif.formats.tiff.TiffParser;
import org.scijava.io.location.FileLocation;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

public class PureScifioIfdOffsets {
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage:");
            System.out.println("    " + PureScifioIfdOffsets.class.getName() + " tiff_file.tiff");
            return;
        }

        final Path file = Paths.get(args[0]);

        TiffParser parser = new TiffParser(new SCIFIO().getContext(), new FileLocation(file.toFile()));
        parser.setUse64BitOffsets(false);
        long[] ifdOffsets = parser.getIFDOffsets();
        System.out.printf("Usual offset: %d, %s%n", ifdOffsets.length, Arrays.toString(ifdOffsets));

        parser.setUse64BitOffsets(true);
        ifdOffsets = parser.getIFDOffsets();
        System.out.printf("fakeBigTiff: %d, %s%n", ifdOffsets.length, Arrays.toString(ifdOffsets));
    }
}
