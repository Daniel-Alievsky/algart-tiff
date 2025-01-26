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
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tiles.TiffMapForReading;
import net.algart.matrices.tiff.tiles.TiffMapForWriting;
import net.algart.matrices.tiff.tiles.TiffTile;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffOverwriteHelloWorldDemo {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 2) {
            System.out.println("Usage:");
            System.out.printf("    %s target.tiff ifdIndex [x y]%n",
                    TiffOverwriteHelloWorldDemo.class.getName());
            System.out.println("Try running this test with a very large TIFF file, like SVS 50000x50000: " +
                    "you will see that it works very quickly!");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex + 1]);
        final int x = startArgIndex + 2 < args.length ? Integer.parseInt(args[startArgIndex + 2]) : 0;
        final int y = startArgIndex + 3 < args.length ? Integer.parseInt(args[startArgIndex + 3]) : 0;

        final int imageToDrawSizeX = 200;
        final int imageToDrawSizeY = 50;
        // - estimated sizes sufficient for "Hello, world!"

        System.out.printf("Opening and rewriting TIFF %s...%n", targetFile);
        try (TiffWriter writer = new TiffWriter(targetFile)) {
            writer.openExisting();
            final TiffReader reader = writer.newReaderOfThisFile(false);
            final TiffIFD ifd = reader.readSingleIFD(ifdIndex);
            ifd.setFileOffsetForWriting(ifd.getFileOffsetForReading());
            TiffMapForReading mapForReading = reader.newMap(ifd, false);
            final BufferedImage bufferedImage = reader.readBufferedImage(mapForReading,
                    x, y, imageToDrawSizeX, imageToDrawSizeY, true);
            final TiffMapForWriting mapForWriting = writer.existingMap(ifd);
            System.out.printf("Overwriting %s...%n", mapForWriting);
            for (TiffTile existingTile : mapForReading.tiles()) {
                final TiffTile newTile = mapForWriting.get(existingTile.index());
                newTile.setDecodedData(existingTile.getDecodedData());
            }
            drawTextOnImage(bufferedImage, "Hello, world!");
            // MatrixIO.writeBufferedImage(Path.of("/tmp/test.bmp"), bufferedImage);
            writer.writeBufferedImage(mapForWriting, bufferedImage, x, y);
        }
        System.out.println("Done");
    }

    static void drawTextOnImage(BufferedImage bufferedImage, String text) {
        final Graphics graphics = bufferedImage.getGraphics();
        final Font font = new Font("SansSerif", Font.BOLD, 30);
        graphics.setFont(font);
        graphics.setColor(new Color(0x00FF00));
        graphics.drawString(text, 0, graphics.getFontMetrics().getAscent());
    }
}


