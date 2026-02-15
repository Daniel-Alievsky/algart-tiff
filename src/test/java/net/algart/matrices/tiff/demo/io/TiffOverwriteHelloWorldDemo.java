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

public class TiffOverwriteHelloWorldDemo {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.printf("    %s target.tiff ifdIndex [x y]%n",
                    TiffOverwriteHelloWorldDemo.class.getName());
            System.out.println("Note that target.tiff will be modified!");
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
        try (TiffWriter writer = new TiffWriter(targetFile, TiffCreateMode.OPEN_EXISTING)) {
            // writer.setAlwaysWriteToFileEnd(true); // - should not affect the results
            writer.rewriteDescription(ifdIndex, "Overwritten", true);
            // - should not destroy the results despite the "true" flag
            final TiffWriteMap writeMap = writer.existingMap(ifdIndex);
            overwrite(writeMap, x, y, sizeX, sizeY);
            overwrite(writeMap, x + sizeX / 2, y + sizeY / 2, sizeX, sizeY);
            overwrite(writeMap, x + sizeX / 2, y - sizeY / 2, sizeX, sizeY);
            int m = writeMap.completeWriting();
            System.out.printf("Completed %d tile, file length: %d%n", m, writeMap.fileLength());
            // - should be 0, because all tiles were preloaded
            if (m != 0) {
                throw new AssertionError("Not all tiles were written inside overwrite");
            }
        }
        System.out.println("Done");
    }

    private static void overwrite(TiffWriteMap writeMap, int x, int y, int sizeX, int sizeY)
            throws IOException {
        final BufferedImage bufferedImage = writeMap.readBufferedImageAndStore(x, y, sizeX, sizeY);
        System.out.printf("%nOverwriting %d..%dx%d..%d in %s...%n", x, x + sizeX - 1, y, y + sizeY - 1, writeMap);
        drawTextOnImage(bufferedImage, "Hello, world!");
        // MatrixIO.writeBufferedImage(Path.of("/tmp/test.bmp"), bufferedImage);
        final List<TiffTile> tiles = writeMap.updateBufferedImage(bufferedImage, x, y);
        int m = writeMap.writeCompletedTiles(tiles);
        System.out.printf("Written %d completed tiles, file length: %d%n", m, writeMap.fileLength());
    }

    private static void drawTextOnImage(BufferedImage bufferedImage, String text) {
        final Graphics graphics = bufferedImage.getGraphics();
        final Font font = new Font("SansSerif", Font.BOLD, 40);
        graphics.setFont(font);
        graphics.setColor(new Color(0x00FF00));
        graphics.drawString(text, 0, graphics.getFontMetrics().getAscent());
    }
}


