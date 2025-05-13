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

import net.algart.matrices.tiff.TiffCreateMode;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tiles.TiffReadMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.matrices.tiff.tiles.TiffWriteMap;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TiffOverwriteHelloWorldTest {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.printf("    %s target.tiff ifdIndex [x y]%n",
                    TiffOverwriteHelloWorldTest.class.getName());
            System.out.println("Try running this test with a very large TIFF file, like SVS 50000x50000: " +
                    "you will see that it works very quickly!");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex + 1]);
        final int x = startArgIndex + 2 < args.length ? Integer.parseInt(args[startArgIndex + 2]) : 0;
        final int y = startArgIndex + 3 < args.length ? Integer.parseInt(args[startArgIndex + 3]) : 0;

        System.out.printf("Opening and rewriting TIFF %s...%n", targetFile);
        final int sizeX = 250;
        final int sizeY = 50;
        // - estimated sizes sufficient for "Hello, world!"
        try (var writer = new TiffWriter(targetFile, TiffCreateMode.OPEN_EXISTING)) {
            TiffIFD ifd = writer.existingIFD(ifdIndex);
            TiffWriteMap writeMap = writer.existingMap(ifd);
            TiffReadMap readMap = writer.reader().newMap(ifd, false);
            overwriteUsingReadMap(writeMap, readMap, x, y, sizeX, sizeY);

            ifd = writer.existingIFD(ifdIndex);
            readMap = writer.reader().newMap(ifd, false);
            // - Note: without these calls we will use the previous reader and previous IFD,
            // so we can load tiles from the previous positions!
            overwriteUsingReadMap(writeMap, readMap, x + sizeX / 2, y + sizeY / 2, sizeX, sizeY);
            int m = writeMap.completeWriting();
            System.out.printf("Completed %d tile, file length: %d%n", m, writeMap.fileLength());
            // - should be 0, because all tiles were preloaded
            if (m != 0) {
                throw new AssertionError("Not all tiles were written inside overwrite");
            }
        }
        System.out.println("Done");
    }

    private static void overwriteUsingReadMap(
            TiffWriteMap writeMap,
            TiffReadMap readMap,
            int x, int y, int sizeX, int sizeY)
            throws IOException {
        final BufferedImage bufferedImage = readMap.readBufferedImage(
                x, y, sizeX, sizeY, true, readMap.cachedTileSupplier());
        // - the last argument "true" leads to preserving all tiles in the map:
        // this is necessary for boundary tiles that are partially covered by the image
        writeMap.copyAllData(readMap, false);
        // - TiffReadMap-style processing: using a separate TiffReadMap
        System.out.printf("%nOverwriting %d..%dx%d..%d in %s...%n", x, x + sizeX - 1, y, y + sizeY - 1, writeMap);
        drawTextOnImage(bufferedImage, "Hello, world!");
        // MatrixIO.writeBufferedImage(Path.of("/tmp/test.bmp"), bufferedImage);
        final List<TiffTile> tiles = writeMap.updateBufferedImage(bufferedImage, x, y);
        int m = writeMap.writeCompletedTiles(tiles);
//        System.out.printf("Completed tiles:%n%s%n",
//                writeMap.tiles().stream().filter(t -> !t.isEmpty() && t.isCompleted()).map(TiffTile::index)
//                        .collect(Collectors.toList()));
        readMap.freeAllData();
        // - necessary if you don't want to rewrite its tiles in the next calls
        System.out.printf("Written %d completed tiles, file length: %d%n", m, writeMap.fileLength());
    }

    static void drawTextOnImage(BufferedImage bufferedImage, String text) {
        final Graphics graphics = bufferedImage.getGraphics();
        final Font font = new Font("SansSerif", Font.BOLD, 40);
        graphics.setFont(font);
        graphics.setColor(new Color(0x00FF00));
        graphics.drawString(text, 0, graphics.getFontMetrics().getAscent());
    }
}


