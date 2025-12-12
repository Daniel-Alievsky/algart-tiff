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
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class TiffPyramidMetadata {
    private final List<SvsDescription> svsDescriptions;
    private final SvsDescription mainSvsDescription;
    private final TiffPyramidImageSet pyramidImageSet;

    private TiffPyramidMetadata() {
        this.pyramidImageSet = TiffPyramidImageSet.empty();
        this.svsDescriptions = Collections.emptyList();
        this.mainSvsDescription = null;
    }

    private TiffPyramidMetadata(List<TiffIFD> allIFDs) throws TiffException {
        Objects.requireNonNull(allIFDs, "Null allIFDs");
        final int numberOfIFDs = allIFDs.size();
        this.pyramidImageSet = TiffPyramidImageSet.of(allIFDs);
        this.svsDescriptions = new ArrayList<>();
        for (int k = 0; k < numberOfIFDs; k++) {
            final String description = allIFDs.get(k).optDescription().orElse(null);
            this.svsDescriptions.add(SvsDescription.of(description));
        }
        this.mainSvsDescription = SvsDescription.findMainDescription(svsDescriptions);
    }

    public static TiffPyramidMetadata empty() {
        return new TiffPyramidMetadata();
    }

    public static TiffPyramidMetadata of(List<TiffIFD> allIFDs) throws TiffException {
        return new TiffPyramidMetadata(allIFDs);
    }

    public static TiffPyramidMetadata of(TiffReader reader) throws IOException {
        Objects.requireNonNull(reader, "Null TIFF reader");
        return new TiffPyramidMetadata(reader.allIFDs());
    }

    public boolean isSVS() {
        return mainSvsDescription != null;
    }

    public boolean isPyramid() {
        return pyramidImageSet.numberOfLayers() > 1;
    }

    public boolean isSVSCompatible() {
        return isSVS() || (isPyramid() && pyramidImageSet.hasSVSThumbnail());
    }

    public List<SvsDescription> allSvsDescriptions() {
        return svsDescriptions;
    }

    public SvsDescription svsDescription(int ifdIndex) {
        if (ifdIndex < 0) {
            throw new IllegalArgumentException("Negative IFD index " + ifdIndex);
        }
        if (ifdIndex >= svsDescriptions.size()) {
            throw new IllegalArgumentException(
                    "IFD index " + ifdIndex + " is out of bounds 0 <= index < " + svsDescriptions.size());
        }
        return svsDescriptions.get(ifdIndex);
    }

    public SvsDescription mainSvsDescription() {
        return mainSvsDescription;
    }

    public TiffPyramidImageSet pyramidImageSet() {
        return pyramidImageSet;
    }

    @Override
    public String toString() {
        return !isSVS() ? "Non-SVS" : mainSvsDescription + "; " + pyramidImageSet;
    }
}
