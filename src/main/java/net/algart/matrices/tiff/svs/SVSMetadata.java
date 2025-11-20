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

package net.algart.matrices.tiff.svs;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class SVSMetadata {
    private final List<SVSImageDescription> descriptions;
    private final SVSImageDescription mainDescription;
    private final SVSImageClassifier imageClassifier;

    private SVSMetadata(List<TiffIFD> allIFDs) throws TiffException {
        Objects.requireNonNull(allIFDs, "Null allIFDs");
        final int ifdCount = allIFDs.size();
        this.imageClassifier = SVSImageClassifier.of(allIFDs);
        this.descriptions = new ArrayList<>();
        for (int k = 0; k < ifdCount; k++) {
            final String description = allIFDs.get(k).optDescription().orElse(null);
            this.descriptions.add(SVSImageDescription.of(description));
        }
        this.mainDescription = findMainDescription(descriptions);
    }

    public static SVSMetadata of(List<TiffIFD> allIFDs) throws TiffException {
        return new SVSMetadata(allIFDs);
    }

    public static SVSMetadata of(TiffReader reader) throws IOException {
        Objects.requireNonNull(reader, "Null TIFF reader");
        return new SVSMetadata(reader.allIFDs());
    }

    public boolean isSVS() {
        return mainDescription != null;
    }

    public List<SVSImageDescription> allDescriptions() {
        return descriptions;
    }

    public SVSImageDescription mainDescription() {
        return mainDescription;
    }

    public SVSImageClassifier imageClassifier() {
        return imageClassifier;
    }

    private static SVSImageDescription findMainDescription(List<SVSImageDescription> imageDescriptions) {
        // Note: the detailed SVS specification is always included (as ImageDescription tag)
        // in the first image (#0) with maximal resolution and partially repeated in the thumbnail image (#1).
        // The label and macro images usually contain reduced ImageDescription.
        for (SVSImageDescription description : imageDescriptions) {
            if (description.isProbableMainDescription()) {
                return description;
                // - returning the first
            }
        }
        return null;
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Usage: " + SVSMetadata.class.getName() + " file1.svs file2.svs ...");
            return;
        }
        for (String arg : args) {
            final Path file = Paths.get(arg);
            try (TiffReader reader = new TiffReader(file)) {
                final var metadata = new SVSMetadata(reader.allIFDs());
                SVSImageDescription main = metadata.mainDescription();
                System.out.printf("%s:%nImportant text:%n%s%n", file,
                        main.importantTextAttributes());
                System.out.println("Main description, all attributes:");
                for (Map.Entry<String, String> e : main.attributes().entrySet()) {
                        System.out.printf("  %s = %s%n", e.getKey(), e.getValue());
                }
                System.out.printf("Image classifier:%n%s%n", metadata.imageClassifier());
                System.out.printf("%nAll descriptions");
                final List<SVSImageDescription> allDescriptions = metadata.allDescriptions();
                for (int i = 0, n = allDescriptions.size(); i < n; i++) {
                    SVSImageDescription d = allDescriptions.get(i);
                    if (d.isProbableMainDescription()) {
                        System.out.printf("Description #%d/%d, all attributes:%n", i, n);
                        for (Map.Entry<String, String> e : d.attributes().entrySet()) {
                            System.out.printf("  %s = %s%n", e.getKey(), e.getValue());
                        }
                    }
                }
            }
        }
    }
}
