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

package net.algart.matrices.tiff.demo.io;

import net.algart.arrays.Matrix;
import net.algart.arrays.UpdatablePArray;
import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tiles.TiffIOMap;
import net.algart.matrices.tiff.tiles.TiffWriteMap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TiffWriterAsReaderDemo {
    public static void main(String... args) throws IOException {
        int startArgIndex = 0;
        boolean writeToEnd = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-writeToEnd")) {
            writeToEnd = true;
            startArgIndex++;
        }
        boolean allowMissing = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-allowMissing")) {
            allowMissing = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 3) {
            System.out.println("Usage:");
            System.out.printf("    %s [-writeToEnd [-allowMissing]] image.tiff target.jpg/png/bmp ifdIndex%n",
                    TiffWriterAsReaderDemo.class.getName());
            System.out.println("""
                    -writeToEnd
                        append a shifted copy of the image #ifdIndex as a new TIFF image to the end of image.tiff.
                    -allowMissing
                        write empty tiles in the appended image as missing ("sparse", Philips TIFF style).
                    """);
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex]);
        final Path targetFile = Paths.get(args[startArgIndex + 1]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex + 2]);

        System.out.printf("Reading TIFF %s via TiffWriter, image %d...%n", sourceFile, ifdIndex);
        List<Matrix<UpdatablePArray>> image;
        try (TiffWriter writer = new TiffWriter(sourceFile, TiffWriter.OpenMode.OPEN_EXISTING)) {
            if (!writer.isTiff()) {
                throw new AssertionError();
            }
            final TiffIOMap map = writer.existingMap(ifdIndex);
            if (allowMissing) {
                writer.setMissingTilesAllowed(true);
            }
            System.out.printf("Reading %s...%n", map);
            System.out.printf("Detailed TIFF tags:%n%s%n", map.ifd().toString(TiffIFD.StringFormat.DETAILED));
            // - print detailed information stored in TIFF tags
            image = map.readChannels(false);
            final var report = map.lastCodecReport();
            if (report != null) {
                System.out.printf("Last decoding report:%n  %s%n", report);
                // - if applicable, print any additional information collected while decoding
            }
            if (writeToEnd) {
                final TiffIFD ifd = TiffIFD.newTiledIFD(map.compressionOrNone(), image);
                // - note
                final TiffWriteMap newMap = writer.newFixedMap(ifd);
                newMap.updateChannels(image, newMap.dimX() / 2, newMap.dimY() / 2);
                newMap.completeWriting();
            }
        }
        System.out.printf("Writing %s...%n", targetFile);
        MatrixIO.writeImage(targetFile, image);
        System.out.println("Done");
    }
}
