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

package net.algart.matrices.tiff.tests.misc;

import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tags.SvsDescription;
import net.algart.matrices.tiff.pyramids.TiffPyramidMetadata;
import net.algart.matrices.tiff.tags.TagDescription;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class TiffPyramidMetadataTest {
    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Usage: " + TiffPyramidMetadataTest.class.getName() + " file1.svs file2.svs ...");
            return;
        }
        for (String arg : args) {
            final Path file = Paths.get(arg);
            try (TiffReader reader = new TiffReader(file)) {
                final TiffPyramidMetadata metadata = TiffPyramidMetadata.of(reader);
                if (!metadata.isSvs()) {
                    System.out.printf("%s is not SVS%n%s%n", file, metadata);
                    continue;
                }
                SvsDescription main = metadata.svsDescription();
                System.out.printf("%s:%n%s%n%nApplication:%n%s%n", file, metadata, main.application());
                System.out.println("The found main description, all attributes:");
                for (Map.Entry<String, String> e : main.attributes().entrySet()) {
                    System.out.printf("  %s = %s%n", e.getKey(), e.getValue());
                }
                System.out.println("The found main description, normal:");
                System.out.println("----------");
                System.out.println(main.toString(TiffIFD.StringFormat.NORMAL));
                System.out.println("----------");
                System.out.println("The found main description, JSON:");
                System.out.println(main.toString(TiffIFD.StringFormat.JSON));
                System.out.printf("Image set:%n%s%n", metadata);
                System.out.printf("%nAll descriptions%n");
                final List<TiffIFD> allIFDs = reader.allIFDs();
                for (int i = 0, n = allIFDs.size(); i < n; i++) {
                    TagDescription tagDescription = allIFDs.get(i).getDescription();
                    if (tagDescription instanceof SvsDescription svs) {
                        System.out.printf("%s description #%d/%d (%s)%n",
                                svs.isMain() ? "Main" : "Additional",
                                i, n, svs);
                        if (svs.hasPixelSize()) {
                            System.out.printf("  Pixel size: %s%n", svs.pixelSize());
                        }
                        if (svs.hasMagnification()) {
                            System.out.printf("  Magnification: %s%n", svs.magnification());
                        }
                        if (svs.hasGeometry()) {
                            System.out.printf("  Image left (microns, axis rightward): %f%n",
                                    svs.imageLeftMicronsAxisRightward());
                            System.out.printf("  Image top (microns, axis upward): %f%n",
                                    svs.imageTopMicronsAxisUpward());
                        }
                        if (svs.hasAttributes()) {
                            System.out.printf("  All attributes:%n");
                            for (Map.Entry<String, String> e : svs.attributes().entrySet()) {
                                System.out.printf("    %s = %s%n", e.getKey(), e.getValue());
                            }
                        }
                        if (svs.hasSummary()) {
                            System.out.printf("  Summary: <<<%s>>>%n", svs.summary());
                        }
                    }
                    System.out.printf("  Raw description:%n<<<%s>>>%n%n",
                            tagDescription.description("NONE"));
                }
            }
        }
    }
}
