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
import net.algart.matrices.tiff.TiffOpenMode;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.TiffWriteMap;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class TiffWriteMixedTest {
    private final static int IMAGE_WIDTH = 2000;
    private final static int IMAGE_HEIGHT = 2000;

    private static void printReaderInfo(TiffWriter writer) {
        System.out.print("Checking file by the reader: ");
        final TiffReader reader;
        try {
            reader = writer.newReader(TiffOpenMode.NO_CHECKS);
        } catch (IOException e) {
            throw new AssertionError("Impossible in NO_CHECKS mode", e);
        }
        try {
            final int n = reader.numberOfImages();
            System.out.printf("%s, %d bytes, %s%n",
                    reader.isValidTiff() ? "valid" : "INVALID: \"" + reader.openingException() + "\"",
                    reader.fileLength(),
                    n == 0 ? "no IFD" : "#0/" + n + ": " + reader.ifd(0));
        } catch (IOException e) {
            // - possible while reading IFD #0
            e.printStackTrace(System.out);
        }
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage:");
            System.out.println("    " + TiffWriteMixedTest.class.getName() +
                    " target.tiff");
            return;
        }
        final Path targetFile = Paths.get(args[0]);

        System.out.println("Writing TIFF " + targetFile + "...");
        try (final TiffWriter writer = new TiffWriter(targetFile)) {
//            writer.setBigTiff(true);
            writer.setByteFiller((byte) 0);
            writer.setByteOrder(ByteOrder.LITTLE_ENDIAN);
//            writer.setSmartFormatCorrection(true);

            writer.create();
            writer.create(); // - not a problem to call twice
            // writer.reader().input().setLength(0); // - throws an exception (read-only
            TiffIFD ifd = new TiffIFD();
            final int[] bitsPerSample = {8, 8, 8};
            int numberOfChannels = bitsPerSample.length;
            ifd.putImageDimensions(IMAGE_WIDTH, IMAGE_HEIGHT);
            ifd.putNumberOfChannels(numberOfChannels);
            ifd.putTileSizes(256, 256);
            ifd.putCompression(TagCompression.DEFLATE);
//            ifd.putPhotometricInterpretation(TagPhotometricInterpretation.WHITE_IS_ZERO);
            ifd.put(Tags.BITS_PER_SAMPLE, bitsPerSample);
            ifd.put(Tags.SAMPLE_FORMAT, TiffIFD.SAMPLE_FORMAT_UINT);
            // - you can comment or change the options above for thorough testing
//            ifd.put(Tags.PHOTOMETRIC_INTERPRETATION, 8);
            ifd.put(15700, new String[] {});

            System.out.printf("Desired IFD:%n%s%n%n", ifd.toString(TiffIFD.StringFormat.NORMAL));

            final TiffWriteMap map = writer.newFixedMap(ifd);
            // map = writer.newMap(ifd); - will throw an exception
            System.out.printf("IFD to save:%n%s%n%n", ifd.toString(TiffIFD.StringFormat.NORMAL));

            int sizeX = map.dimX() / 2;
            int sizeY = map.dimY() / 2;
            // writer.writeForward(map); // - uncomment to write IFD BEFORE image

            // First writing a part of image (light)
            Object samples = makeSamples(map, sizeX, sizeY, numberOfChannels, 0.3);
            List<TiffTile> updated = map.updateJavaArray(samples, 20, 20, sizeX, sizeY);
            // - filling only 1/4 of the map
            System.out.printf("1st updated %d tiles:%n  %s%n%n",
                    updated.size(),
                    updated.stream().map(TiffTile::toString).collect(Collectors.joining("%n  ".formatted())));
            printReaderInfo(writer);
            map.writeCompletedTiles(updated);
            // - frees the memory (almost do not affect results)
            printReaderInfo(writer);

            // The second writing a part of the image (dark): must not affect previously written tiles
            samples = makeSamples(map, sizeX, sizeY, numberOfChannels, 0.1);
            updated = map.updateJavaArray(samples, 500, 20, sizeX, sizeY);
            // In the version 1.3.7, fromX=500 led to a bug: the second tile was created again
            System.out.printf("2nd updated %d tiles:%n  %s%n%n",
                    updated.size(),
                    updated.stream().map(TiffTile::toString).collect(Collectors.joining("%n  ".formatted())));
            printReaderInfo(writer);
            int n = map.writeCompletedTiles(updated);
            System.out.printf("%d completed tiles written%n", n);
            // - frees the memory (almost do not affect results)
            printReaderInfo(writer);
            n = map.completeWriting();
            System.out.printf("1st: %d tiles written while the final completion%n", n);
            n = writer.completeWriting(map);
            System.out.printf("2nd: %d tiles written while the final completion%n", n);
            // - we can call complete twice, it will spend little time for rewriting IFD, but has no effect

            // writer.writeJavaArray(map, samples, 0, 0, sizeX, sizeY);
            // - equivalent to previous 3 TiffWriter methods
            printReaderInfo(writer);

            System.out.printf("Actually saved IFD:%n%s%n%n", ifd.toString(TiffIFD.StringFormat.DETAILED));
        }
        System.out.println("Done");
    }

    private static Object makeSamples(
            TiffWriteMap map,
            int sizeX,
            int sizeY,
            int numberOfChannels,
            double value) {
        final int numberOfPixels = sizeX * sizeY;
        final Object samples = Array.newInstance(
//                    String.class, // - invalid type here should lead to exception
                map.elementType(),
                numberOfPixels * numberOfChannels);
        if (samples instanceof byte[] a) {
            int v = (int) (value * 255.0);
            for (int i = 0; i < a.length; i++) {
                a[i] = (byte) (i < numberOfPixels ? v : 3 * v);
                // - by default, source data are always separated
            }
        } else if (samples instanceof short[] a) {
            int v = (int) (value * 65535.0);
            for (int i = 0; i < a.length; i++) {
                a[i] = (short) (i < numberOfPixels ? v : 3 * v);
            }
        } else if (samples instanceof boolean[] booleans) {
            Arrays.fill(booleans, value != 0.0);
        }
        return samples;
    }
}
