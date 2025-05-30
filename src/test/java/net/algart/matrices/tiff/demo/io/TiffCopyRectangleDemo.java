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

package net.algart.matrices.tiff.demo.io;

import net.algart.matrices.tiff.TiffCopier;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tiles.TiffReadMap;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffCopyRectangleDemo {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean direct = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-direct")) {
            direct = true;
            startArgIndex++;
        }
        boolean smart = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-smart")) {
            smart = true;
            startArgIndex++;
        }
        ByteOrder byteOrder = null;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-le")) {
            byteOrder = ByteOrder.LITTLE_ENDIAN;
            startArgIndex++;
        } else if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-be")) {
            byteOrder = ByteOrder.BIG_ENDIAN;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 3) {
            System.out.println("Usage:");
            System.out.printf("    [-direct] [-smart] [-le|-be] %s " +
                            "source.tiff target.tiff ifdIndex [x y width height]%n",
                    TiffCopyRectangleDemo.class.getName());
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex++]);
        final Path targetFile = Paths.get(args[startArgIndex++]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex]);
        final int x = args.length <= ++startArgIndex ? 0 : Integer.parseInt(args[startArgIndex]);
        final int y = args.length <= ++startArgIndex ? 0 : Integer.parseInt(args[startArgIndex]);
        int w = args.length <= ++startArgIndex ? -1 : Integer.parseInt(args[startArgIndex]);
        int h = args.length <= ++startArgIndex ? -1 : Integer.parseInt(args[startArgIndex]);
        System.out.printf("Copying rectangle from %s to %s%s...%n",
                sourceFile, targetFile, direct ? "" : " with recompression");

        final var copier = new TiffCopier().setDirectCopy(direct);
//         copier.setIfdCorrector(ifd -> ifd.putCompression(net.algart.matrices.tiff.tags.TagCompression.NONE));
//         copier.setIfdCorrector(ifd -> ifd.putTileSizes(64, 64));
        // - possible corrections
        copier.setProgressUpdater(p ->
                System.out.printf("\r%d/%d...", p.tileIndex() + 1, p.tileCount()));
        try (var reader = new TiffReader(sourceFile); var writer = new TiffWriter(targetFile)) {
            // reader.setCaching(false); // - slows down copying when the rectangle does not consist of whole tiles
            // reader.setAutoUnpackBits(TiffReader.UnpackBits.UNPACK_TO_0_255);
            // - should not affect the result
            // reader.setUnusualPrecisions(TiffReader.UnusualPrecisions.DISABLE);
            // - should block the ability to load non-standard precisions like 16-bit float

            if (smart) {
                writer.setSmartFormatCorrection(true);
            }

            final TiffReadMap readMap = reader.newMap(ifdIndex);
            if (w < 0) {
                w = readMap.dimX() - x;
            }
            if (h < 0) {
                h = readMap.dimY() - y;
            }
            writer.setFormatLike(reader);
            // - without this operator, direct copy will be impossible for LE format
            if (byteOrder != null) {
                writer.setByteOrder(byteOrder);
            }
            writer.create();
            System.out.printf("Copying image %d, rectangle %d..%dx%d..%d%n", ifdIndex, x, x + w - 1, y, y + h - 1);
            copier.copyImage(writer, readMap, x, y, w, h);
            System.out.print("\r               \r");
        }
        System.out.printf("Done%n");
    }
}
