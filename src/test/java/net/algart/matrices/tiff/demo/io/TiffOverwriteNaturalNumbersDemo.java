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

import net.algart.matrices.tiff.TiffCreateMode;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.matrices.tiff.tiles.TiffWriteMap;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

public class TiffOverwriteNaturalNumbersDemo {
    private static final boolean WRITE_IMMEDIATELY = true;
    private static final boolean ACCURATE_MEMORY_MEASURING = true;

    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 7) {
            System.out.println("Usage:");
            System.out.printf("    %s target.tiff ifdIndex " +
                            "start-x start-y dx dy number-of-values [increment] [number-of-repeats]%n",
                    TiffOverwriteNaturalNumbersDemo.class.getName());
            System.out.println("Note that target.tiff will be modified!");
            System.out.println("Try running this test with a very large TIFF file, like SVS 50000x50000: " +
                    "you will see that it works very quickly.");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex + 1]);
        int x0 = Integer.parseInt(args[startArgIndex + 2]);
        int y0 = Integer.parseInt(args[startArgIndex + 3]);
        final int dx = Integer.parseInt(args[startArgIndex + 4]);
        final int dy = Integer.parseInt(args[startArgIndex + 5]);
        final int numberOfValues = Integer.parseInt(args[startArgIndex + 6]);
        final int increment = args.length > startArgIndex + 7 ? Integer.parseInt(args[startArgIndex + 7]) : 1;
        final int numberOfRepeats = args.length > startArgIndex + 8 ? Integer.parseInt(args[startArgIndex + 8]) : 1;

        System.out.printf("Opening and rewriting TIFF %s...%n", targetFile);
        final int sizeX = 50;
        final int sizeY = 30;
        // - estimated sizes sufficient for integer number like "151"
        try (TiffWriter writer = new TiffWriter(targetFile, TiffCreateMode.OPEN_EXISTING)) {
            // writer.setAlwaysWriteToFileEnd(true); // - should not affect the results
            final TiffWriteMap writeMap = writer.existingMap(ifdIndex);
            System.out.printf("Overwriting %s...%n", writeMap);
            long t1 = System.nanoTime();
            for (int repeat = 0; repeat < numberOfRepeats; repeat++) {
                final long initialFileLength = writeMap.fileLength();
                long maxLength = initialFileLength;
                System.out.printf("%nRepetition #%d/%d...%n", repeat + 1, numberOfRepeats);
                int x = x0;
                int y = y0;
                for (int k = 0, value = 0; k < numberOfValues; k++) {
                    System.out.printf("Writing %d at %d..%dx%d..%d...  ", value, x, x + sizeX - 1, y, y + sizeY - 1);
                    int m = overwrite(writeMap, x, y, sizeX, sizeY, value);
                    if (m >= 0) {
                        System.out.printf("written %d completed tiles ", m);
                    }
                    x += dx;
                    y += dy;
                    value += increment;
                    if (ACCURATE_MEMORY_MEASURING) {
                        System.gc();
                    }
                    System.out.print(memory());
                    if (writeMap.fileLength() > maxLength) {
                        System.out.printf("  [file length increased from to %d]", writeMap.fileLength());
                        maxLength = writeMap.fileLength();
                    }
                    System.out.println();
                }
                if (maxLength == initialFileLength) {
                    System.out.println("The file length has not increased");
                }
            }
            long t2 = System.nanoTime();
            int m = writeMap.completeWriting();
            long t3 = System.nanoTime();
            System.out.printf("Completed %d tile, file length: %d%n", m, writeMap.fileLength());
            System.out.printf(Locale.US, "Writing time: %.3f ms + %.3f ms for completion%n",
                    (t2 - t1) * 1e-6, (t3 - t2) * 1e-6);
            if (WRITE_IMMEDIATELY && m != 0) {
                // - should be 0, because all tiles were preloaded
                throw new AssertionError("Not all tiles were written inside overwrite");
            }
        }
        System.out.println("Done");
    }

    private static int overwrite(TiffWriteMap writeMap, int x, int y, int sizeX, int sizeY, int value)
            throws IOException {
        final BufferedImage bufferedImage = writeMap.readBufferedImageAndStore(x, y, sizeX, sizeY);
        drawNumberOnImage(bufferedImage, value);
        final List<TiffTile> tiles = writeMap.updateBufferedImage(bufferedImage, x, y);
        if (WRITE_IMMEDIATELY) {
            return writeMap.writeCompletedTiles(tiles);
        } else {
            return -1;
        }
    }

    private static void drawNumberOnImage(BufferedImage bufferedImage, int value) {
        final Graphics graphics = bufferedImage.getGraphics();
        final Font font = new Font("SansSerif", Font.BOLD, 20);
        graphics.setFont(font);
        graphics.setColor(new Color(0xFF0000 >> (8 * (value % 3))));
        graphics.drawString(String.valueOf(value), 0, graphics.getFontMetrics().getAscent());
    }

    private static String memory() {
        Runtime rt = Runtime.getRuntime();
        return String.format("%.2f/%.2f MB",
                (rt.totalMemory() - rt.freeMemory()) / 1048576.0, rt.totalMemory() / 1048576.0);
    }
}


