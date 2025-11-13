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

package net.algart.matrices.tiff.pyramids.svs;

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
    private final List<SVSImageDescription> imageDescriptions;
    private final SVSImageDescription mainImageDescription;
    private final SVSImageClassifier imageClassifier;

    public SVSMetadata(List<TiffIFD> allIFDs) throws TiffException {
        Objects.requireNonNull(allIFDs, "Null allIFDs");
        final int ifdCount = allIFDs.size();
        this.imageClassifier = new SVSImageClassifier(allIFDs);
        this.imageDescriptions = new ArrayList<>();
        for (int k = 0; k < ifdCount; k++) {
            final String description = allIFDs.get(k).optDescription().orElse(null);
            this.imageDescriptions.add(SVSImageDescription.of(description));
        }
        this.mainImageDescription = findMainImageDescription(imageDescriptions);
    }

    public List<SVSImageDescription> imageDescriptions() {
        return imageDescriptions;
    }

    public SVSImageDescription mainImageDescription() {
        return mainImageDescription;
    }

    public SVSImageClassifier imageClassifier() {
        return imageClassifier;
    }

    private static SVSImageDescription findMainImageDescription(List<SVSImageDescription> imageDescriptions) {
        for (SVSImageDescription description : imageDescriptions) {
            if (description.isSVSDescription()) {
                return description;
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
                SVSImageDescription description = metadata.mainImageDescription();
                System.out.printf("%s:%nImportant text:%n%s%n", file,
                        description.importantTextAttributes());
                System.out.println("All attributes:");
                for (Map.Entry<String, SVSImageDescription.Attribute> e : description.attributes().entrySet()) {
                        System.out.printf("  %s = %s%n", e.getKey(), e.getValue());
                }
                System.out.printf("Image classifier:%n%s%n", metadata.imageClassifier());
            }
        }
    }
}
