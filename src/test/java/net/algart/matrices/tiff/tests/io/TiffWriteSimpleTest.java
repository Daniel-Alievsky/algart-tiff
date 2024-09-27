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

package net.algart.matrices.tiff.tests.io;

import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class TiffWriteSimpleTest {
    private final static int IMAGE_WIDTH = 2000;
    private final static int IMAGE_HEIGHT = 2000;

    private static void printReaderInfo(TiffWriter writer) {
        System.out.print("Checking file by the reader: ");
        try {
            final TiffReader reader = writer.newReaderOfThisFile(false);
            System.out.printf("%s, %s%n",
                    reader.isValid() ? "valid" : "INVALID: " + reader.openingException(),
                    reader.numberOfImages() == 0 ? "no IFD" : "#0 " + reader.ifd(0));
        } catch (IOException e) {
            e.printStackTrace(System.out);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage:");
            System.out.println("    " + TiffWriteSimpleTest.class.getName() +
                    " target.tiff");
            return;
        }
        final Path targetFile = Paths.get(args[0]);

        System.out.println("Writing TIFF " + targetFile + "...");
        try (final TiffWriter writer = new TiffWriter(targetFile)) {
            writer.setByteFiller((byte) 0);
            writer.setByteOrder(ByteOrder.LITTLE_ENDIAN);
//            writer.setSmartIFDCorrection(true);

            writer.create();
            // writer.startNewFile(); // - not a problem to call twice
            TiffIFD ifd = new TiffIFD();
            final int[] bitsPerSample = {16, 16, 16};
            int numberOfChannels = bitsPerSample.length;
            ifd.putImageDimensions(IMAGE_WIDTH, IMAGE_HEIGHT);
            ifd.putNumberOfChannels(numberOfChannels);
            ifd.putTileSizes(256, 256);
            ifd.putCompression(TagCompression.NONE);
//            ifd.putPhotometricInterpretation(TagPhotometricInterpretation.WHITE_IS_ZERO);
            ifd.put(Tags.BITS_PER_SAMPLE, bitsPerSample);
            ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_UINT);
            // - you can comment or change the options above for thorough testing
//            ifd.put(Tags.PHOTOMETRIC_INTERPRETATION, 8);
            ifd.put(15700, new String[] {});

            System.out.printf("Desired IFD:%n%s%n%n", ifd.toString(TiffIFD.StringFormat.NORMAL));

            TiffMap map = writer.newFixedMap(ifd);
            // map = writer.newMap(ifd); - will throw an exception
            System.out.printf("IFD to save:%n%s%n%n", ifd.toString(TiffIFD.StringFormat.NORMAL));

            int sizeX = map.dimX() / 2;
            int sizeY = map.dimY() / 2;
            final Object samples = Array.newInstance(
//                    String.class, // - invalid type here should lead to exception
                    map.elementType(),
                    sizeX * sizeY * numberOfChannels);
            if (samples instanceof byte[] a) {
                for (int i = 0; i < a.length; i++) {
                    a[i] = (byte) (i < a.length / numberOfChannels ? 70 : 240);
                    // - by default, source data are always separated
                }
            } else if (samples instanceof short[] a) {
                for (int i = 0; i < a.length; i++) {
                    a[i] = (short) (i < a.length / numberOfChannels ? 17070 : 41400);
                }
            } else if (samples instanceof boolean[] booleans) {
                Arrays.fill(booleans, true);
            }
            // writer.writeForward(map); // - uncomment to write IFD BEFORE image
            final List<TiffTile> updated = writer.updateJavaArray(
                    map, samples, 0, 0, sizeX, sizeY);
            // - filling only 1/4 of map
            System.out.printf("Updated:%n  %s%n%n", updated.stream().map(TiffTile::toString).collect(
                    Collectors.joining("%n  ".formatted())));
            printReaderInfo(writer);
            writer.writeCompletedTiles(updated);
            // - frees the memory (almost do not affect results)
            printReaderInfo(writer);

            writer.complete(map);
            // writer.complete(map);
            // - we can call complete twice, it will spend little time for rewriting IFD, but has no effect

//            writer.writeJavaArray(map, samples, 0, 0, sizeX, sizeY);
            // - equivalent to previous 3 TiffWriter methods
            printReaderInfo(writer);

            System.out.printf("Actually saved IFD:%n%s%n%n", ifd.toString(TiffIFD.StringFormat.DETAILED));
        }
        System.out.println("Done");
    }
}
