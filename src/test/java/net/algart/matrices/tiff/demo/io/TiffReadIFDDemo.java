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

package net.algart.matrices.tiff.demo.io;

import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TiffReadIFDDemo {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean json = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-json")) {
            json = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 1) {
            System.out.println("Usage:");
            System.out.printf("    %s [-json] source.tiff%n", TiffReadIFDDemo.class.getName());
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex]);

        System.out.printf("Reading TIFF %s...%n%n", sourceFile);
        try (TiffReader reader = new TiffReader(sourceFile, false)) {
            // - "false" argument helps to test also non-TIFF files
            if (reader.isValid()) {
                List<TiffIFD> ifds = reader.allIFDs();
                for (int i = 0, n = ifds.size(); i < n; i++) {
                    TiffIFD ifd = ifds.get(i);
                    String s = ifd.toString(json ? TiffIFD.StringFormat.JSON : TiffIFD.StringFormat.DETAILED);
                    System.out.printf("**** TIFF image #%d/%d IFD information ****%n%s%n%n", i + 1, n, s);
                }
            } else {
                System.out.printf("This is not a TIFF file: %s%n", sourceFile);
            }
        }
        System.out.println("Done");
    }
}