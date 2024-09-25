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

package net.algart.matrices.tiff.tests.misc;

import io.scif.FormatException;
import io.scif.SCIFIO;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.IFDList;
import net.algart.io.MatrixIO;
import net.algart.io.awt.MatrixToImage;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffSampleType;
import net.algart.matrices.tiff.awt.AWTImages;
import net.algart.matrices.tiff.compatibility.TiffParser;
import net.algart.matrices.tiff.demo.io.TiffExtractTileContent;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.matrices.tiff.tiles.TiffTileIndex;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffParserEqualStripsTest {
    @SuppressWarnings("deprecation")
    public static void main(String[] args) throws IOException, FormatException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 4) {
            System.out.println("Usage:");
            System.out.println("    " + TiffExtractTileContent.class.getName() +
                    " some_tiff_file.tiff result.jpg/png ifdIndex tileCol tileRow");
            System.out.println("Note: the results will be correct only for 1st tile or " +
                    "if the encoded tiles have identical length.");
            return;
        }
        final Path tiffFile = Paths.get(args[startArgIndex++]);
        final Path resultFile = Paths.get(args[startArgIndex++]);
        final int ifdIndex = Integer.parseInt(args[startArgIndex++]);
        final int col = Integer.parseInt(args[startArgIndex++]);
        final int row = Integer.parseInt(args[startArgIndex]);

        try (TiffParser parser = new TiffParser(new SCIFIO().context(), tiffFile)) {
//            parser.setAssumeEqualStrips(true);
            System.out.printf("Opening %s by %s...%n", tiffFile, parser);
            final IFDList ifdList = parser.getIFDs();
            final IFD ifd = ifdList.get(ifdIndex);
            byte[] bytes = parser.getTile(ifd, null, col, row);
            BufferedImage bi = AWTImages.makeImage(bytes,
                    (int) ifd.getTileWidth(),
                    (int) ifd.getTileLength(),
                    ifd.getSamplesPerPixel(),
                    false);
            System.out.printf("Loaded tile:%n    %s%n", bi);
            System.out.printf("Writing %s...%n", resultFile);
            MatrixIO.writeBufferedImage(resultFile, bi);
        }
        System.out.println("Done");
    }
}
