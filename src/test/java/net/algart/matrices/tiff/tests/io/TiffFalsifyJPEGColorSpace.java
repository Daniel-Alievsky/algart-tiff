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
import net.algart.matrices.tiff.tags.TagPhotometricInterpretation;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.TiffMap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class TiffFalsifyJPEGColorSpace {
    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        if (args.length < startArgIndex + 4) {
            System.out.println("Usage:");
            System.out.println("    " + TiffFalsifyJPEGColorSpace.class.getName()
                    + " source.tif target.tif written fake [firstIFDIndex lastIFDIndex]");
            System.out.println("where written/fake are RGB or Y_CB_CR");
            return;
        }
        final Path sourceFile = Paths.get(args[startArgIndex++]);
        final Path targetFile = Paths.get(args[startArgIndex++]);
        final TagPhotometricInterpretation before = TagPhotometricInterpretation.valueOf(args[startArgIndex++]);
        final TagPhotometricInterpretation after = TagPhotometricInterpretation.valueOf(args[startArgIndex++]);
        final int firstIFDIndex = startArgIndex < args.length ?
                Integer.parseInt(args[startArgIndex]) :
                0;
        int lastIFDIndex = startArgIndex + 1 < args.length ?
                Integer.parseInt(args[startArgIndex + 1]) :
                Integer.MAX_VALUE;

        System.out.printf("Opening %s...%n", sourceFile);

        try (TiffReader reader = new TiffReader(sourceFile);
             TiffWriter writer = new TiffWriter(targetFile)) {

            writer.setBigTiff(reader.isBigTiff());
            writer.setLittleEndian(reader.isLittleEndian());
            writer.create();

            System.out.printf("Transforming to %s...%n", targetFile);
            final List<TiffMap> maps = reader.allMaps();
            lastIFDIndex = Math.min(lastIFDIndex, maps.size() - 1);
            for (int i = firstIFDIndex; i <= lastIFDIndex; i++) {
                final TiffMap readMap = maps.get(i);
                if (readMap.compressionCode() != TagCompression.JPEG.code()) {
                    System.out.printf("\rCopying #%d/%d: %s%n", i, maps.size(), readMap.ifd());
                    writer.copyImage(reader, readMap);
                    continue;
                }
                System.out.printf("\rTransforming #%d/%d: %s%n", i, maps.size(), readMap.ifd());
                final TiffMap writeMap = writer.copyImage(reader, readMap, writeIFD -> {
                            writeIFD.putPhotometricInterpretation(before);
                            writeIFD.put(Tags.Y_CB_CR_SUB_SAMPLING,
                                    before == TagPhotometricInterpretation.RGB ? new int[]{1, 1} : new int[]{2, 2});
                            // - instruct Java AWT to store as RGB and disable subsampling
                            // (RGB are encoded without subsampling)
                        }, true);
                final TiffIFD cloneIFD = new TiffIFD(writeMap.ifd());
                // - writeMap is frozen and cannot be modified
                cloneIFD.putPhotometricInterpretation(after);
                writer.rewriteIFD(cloneIFD, false);
                // - replacing photometric interpretation in the already written IFD
            }
        }
        System.out.println("Done");
    }
}
