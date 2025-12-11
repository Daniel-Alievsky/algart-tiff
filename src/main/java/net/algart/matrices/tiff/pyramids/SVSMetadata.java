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

package net.algart.matrices.tiff.pyramids;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class SVSMetadata {
    private final List<SVSDescription> descriptions;
    private final SVSDescription mainDescription;
    private final SVSImageSet imageSet;

    private SVSMetadata(List<TiffIFD> allIFDs) throws TiffException {
        Objects.requireNonNull(allIFDs, "Null allIFDs");
        final int numberOfIFDs = allIFDs.size();
        this.imageSet = SVSImageSet.of(allIFDs);
        this.descriptions = new ArrayList<>();
        for (int k = 0; k < numberOfIFDs; k++) {
            final String description = allIFDs.get(k).optDescription().orElse(null);
            this.descriptions.add(SVSDescription.of(description));
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

    public List<SVSDescription> allDescriptions() {
        return descriptions;
    }

    public SVSDescription description(int ifdIndex) {
        if (ifdIndex < 0) {
            throw new IllegalArgumentException("Negative IFD index " + ifdIndex);
        }
        if (ifdIndex >= descriptions.size()) {
            throw new IllegalArgumentException(
                    "IFD index " + ifdIndex + " is out of bounds 0 <= index < " + descriptions.size());
        }
        return descriptions.get(ifdIndex);
    }

    public SVSDescription mainDescription() {
        return mainDescription;
    }

    public SVSImageSet imageSet() {
        return imageSet;
    }

    private static SVSDescription findMainDescription(List<SVSDescription> descriptions) {
        // Note: the detailed SVS specification is always included (as ImageDescription tag)
        // in the first image (#0) with maximal resolution and partially repeated in the thumbnail image (#1).
        // The label and macro images usually contain reduced ImageDescription.
        for (SVSDescription description : descriptions) {
            if (description.isMain()) {
                return description;
                // - returning the first
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return !isSVS() ? "Non-SVS" : mainDescription + "; " + imageSet;
    }
}
