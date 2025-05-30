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

import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.matrices.tiff.tiles.TiffTileIndex;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffExtractTileContent {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean unpack = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-unpack")) {
            unpack = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 5) {
            System.out.println("Usage:");
            System.out.println("    " + TiffExtractTileContent.class.getName() +
                    " [-unpack] some_tiff_file.tiff result.jpg/png/dat ifdIndex tileCol tileRow [separatedPlaneIndex]");
            System.out.println("Note: if you do not used -unpack key, you should choose file extension " +
                    "corresponding to IFD compression format.");
            return;
        }
        final Path tiffFile = Paths.get(args[startArgIndex++]);
        final Path resultFile = Paths.get(args[startArgIndex++]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex++]);
        final int col = Integer.parseInt(args[startArgIndex++]);
        final int row = Integer.parseInt(args[startArgIndex++]);
        final int separatedPlaneIndex = startArgIndex < args.length ? Integer.parseInt(args[startArgIndex]) : 0;

        // new TiffInfo().showTiffInfo(tiffFile);

        try (var reader = new TiffReader(tiffFile)) {
            System.out.printf("Opening %s by %s...%n", tiffFile, reader);
            final var map = reader.newMap(ifdIndex);
            System.out.printf("TIFF map #%d: %s%n", ifdIndex, map);
            final TiffTileIndex tileIndex = map.index(col, row, separatedPlaneIndex);
            TiffTile tile;
            if (unpack) {
                tile = reader.readTile(tileIndex);
                System.out.printf("Decoded tile:%n    %s%n", tile);
                final var image = tile.getUnpackedMatrix().asLayers();
                MatrixIO.writeImage(resultFile, image);
                System.out.printf("Writing tile in %s%n", resultFile);
            } else {
                tile = reader.readEncodedTile(tileIndex);
                reader.prepareDecoding(tile);
                System.out.printf("Encoded tile:%n    %s%n", tile);
                if (!tile.isEmpty()) {
                    System.out.printf("    Compression format: %s%n", map.ifd().compressionPrettyName());
                    byte[] bytes = tile.getEncodedData();
                    System.out.printf("Saving tile in %s%n", resultFile);
                    Files.write(resultFile, bytes);
                    try {
                        tile = reader.readTile(tileIndex);
                        System.out.printf("Attempt to decode the same tile (for verification): %s%n", tile);
                    } catch (IOException e) {
                        System.err.printf("Cannot decode tile: %s%n", e);
                    }
                }
                System.out.println("Done");
                if (tile.compressionCode() == TiffIFD.COMPRESSION_JPEG) {
                    System.out.println();
                    System.out.println("Note: sometimes the saved JPEG will have different colors than " +
                            "the colors in the original TIFF (for example, in some SVS files).");
                    System.out.println("This is possible when the photometric interpretation (RGB or YCbCr) " +
                            "declared in the TIFF tag is not properly encoded inside the JPEG data stream.");
                }
            }
        }
    }
}
