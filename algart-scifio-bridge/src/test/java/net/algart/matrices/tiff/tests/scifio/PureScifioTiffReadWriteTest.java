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

package net.algart.matrices.tiff.tests.scifio;

import io.scif.FormatException;
import io.scif.SCIFIO;
import io.scif.formats.tiff.IFD;
import io.scif.formats.tiff.IFDList;
import io.scif.formats.tiff.TiffParser;
import io.scif.formats.tiff.TiffSaver;
import io.scif.util.FormatTools;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.TiffMap;
import org.scijava.Context;
import org.scijava.io.location.FileLocation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class PureScifioTiffReadWriteTest {
    private static final int MAX_IMAGE_DIM = 5000;

    public static void main(String[] args) throws IOException, FormatException {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("    " + PureScifioTiffReadWriteTest.class.getName()
                    + " source.tif target.tif");
            return;
        }
        final Path sourceFile = Paths.get(args[0]);
        final Path targetFile = Paths.get(args[1]);

        System.out.printf("Opening %s...%n", sourceFile);

        final SCIFIO scifio = new SCIFIO();
        try (Context context = scifio.getContext()) {
            TiffParser parser = new TiffParser(context, new FileLocation(sourceFile.toFile()));
            Files.deleteIfExists(targetFile);
            // - strange, but necessary
            TiffSaver saver = new TiffSaver(context, targetFile.toString());
            saver.setWritingSequentially(true);
            saver.setLittleEndian(true);
            saver.writeHeader();
            System.out.printf("Writing %s...%n", targetFile);
            final IFDList ifdList = parser.getIFDs();
            for (int ifdIndex = 0; ifdIndex < ifdList.size(); ifdIndex++) {
                final IFD ifd = ifdList.get(ifdIndex);
                System.out.printf("Copying #%d/%d:%n%s%n", ifdIndex, ifdList.size(), ifd);
                final int w = (int) Math.min(ifd.getImageWidth(), MAX_IMAGE_DIM);
                final int h = (int) Math.min(ifd.getImageLength(), MAX_IMAGE_DIM);
                int numberOfChannels = ifd.getSamplesPerPixel();
                byte[] bytes = new byte[w * h
                        * numberOfChannels * FormatTools.getBytesPerPixel(ifd.getPixelType())];
                bytes = parser.getSamples(ifd, bytes, 0, 0, w, h);
//                bytes = TiffTools.unpackUnusualPrecisions(bytes, ifd, numberOfChannels, w * h);
                bytes = TiffMap.toInterleavedBytes(
                        bytes, numberOfChannels, FormatTools.getBytesPerPixel(ifd.getPixelType()),
                        (long) w * (long) h);
                boolean last = ifdIndex == ifdList.size() - 1;
                final IFD newIfd = removeUndesirableTags(ifd);

                saver.writeImage(bytes, newIfd, ifdIndex, ifd.getPixelType(), 0, 0, w, h, last);
//                saver.getStream().seek(saver.getStream().length());
            }
            saver.getStream().close();
        }
        System.out.println("Done");
    }

    private static IFD removeUndesirableTags(IFD ifd) {
        IFD newIFD = new IFD(ifd, null);
        for (Map.Entry<Integer, Object> entry : ifd.entrySet()) {
            switch (entry.getKey()) {
                case IFD.JPEG_TABLES, Tags.ICC_PROFILE -> {
                    System.out.println("Removing " + entry);
                    newIFD.remove(entry.getKey());
                }
            }
        }
        return newIFD;
    }
}
