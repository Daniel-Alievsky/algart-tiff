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

import net.algart.arrays.Arrays;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.Tags;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public final class SVSImageClassifier {
    public enum SpecialImageKind {
        THUMBNAIL,
        LABEL,
        MACRO
    }

    private static final int THUMBNAIL_IFD_INDEX = 1;
    private static final int MAX_PIXEL_COUNT_IN_SPECIAL_IMAGES = 2048 * 2048;
    private static final double STANDARD_MACRO_ASPECT_RATIO = 2.8846153846153846153846153846154;
    // - 75000/26000, typical value for medicine
    private static final double ALLOWED_ASPECT_RATION_DEVIATION = 0.2;
    private static final boolean ALWAYS_USE_SVS_SPECIFICATION_FOR_LABEL_AND_MACRO =
            Arrays.SystemSettings.getBooleanEnv(
                    "ALWAYS_USE_SVS_SPECIFICATION_FOR_LABEL_AND_MACRO", false);

    private static final System.Logger LOG = System.getLogger(SVSImageClassifier.class.getName());

    private final List<TiffIFD> ifds;
    private final int ifdCount;
    private int thumbnailIndex = -1;
    private int labelIndex = -1;
    private int macroIndex = -1;

    private SVSImageClassifier(List<TiffIFD> allIFDs) throws TiffException {
        this.ifds = Objects.requireNonNull(allIFDs, "Null allIFDs");
        this.ifdCount = ifds.size();
        detectThumbnail();
        if (!detectTwoLastImages()) {
            detectSingleLastImage();
        }
    }

    public static SVSImageClassifier of(List<TiffIFD> allIFD) throws TiffException {
        return new SVSImageClassifier(allIFD);
    }

    public boolean isSpecial(int ifdIndex) {
        if (ifdIndex < 0) {
            throw new IllegalArgumentException("Negative ifdIndex");
        }
        return ifdIndex == thumbnailIndex || ifdIndex == labelIndex || ifdIndex == macroIndex;
    }

    public OptionalInt specialKindIndex(SpecialImageKind kind) {
        Objects.requireNonNull(kind, "Null special image kind");
        switch (kind) {
            case LABEL -> {
                if (labelIndex != -1) {
                    return OptionalInt.of(labelIndex);
                }
            }
            case MACRO -> {
                if (macroIndex != -1) {
                    return OptionalInt.of(macroIndex);
                }
            }
            case THUMBNAIL -> {
                if (thumbnailIndex != -1) {
                    return OptionalInt.of(thumbnailIndex);
                }
            }
        }
        return OptionalInt.empty();
    }

    @Override
    public String toString() {
        return "special image positions among " + ifdCount + " total images: "
                + "thumbnail " + (thumbnailIndex == -1 ? "NOT FOUND" : "at " + thumbnailIndex)
                + ", label " + (labelIndex == -1 ? "NOT FOUND" : "at " + labelIndex)
                + ", macro " + (macroIndex == -1 ? "NOT FOUND" : "at " + macroIndex);
    }

    private void detectThumbnail() throws TiffException {
        if (ifdCount <= THUMBNAIL_IFD_INDEX) {
            return;
        }
        final TiffIFD ifd = ifds.get(THUMBNAIL_IFD_INDEX);
        if (isSmallImage(ifd)) {
            this.thumbnailIndex = THUMBNAIL_IFD_INDEX;
        }
    }

    private boolean detectTwoLastImages() throws TiffException {
        if (ifdCount <= THUMBNAIL_IFD_INDEX + 2) {
            return false;
        }
        final int index1 = ifdCount - 2;
        final int index2 = ifdCount - 1;
        final TiffIFD ifd1 = ifds.get(index1);
        final TiffIFD ifd2 = ifds.get(index2);
        if (!(isSmallImage(ifd1) && isSmallImage(ifd2))) {
            return false;
        }
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(System.Logger.Level.DEBUG, String.format(
                    "  Checking last 2 small IFDs #%d %s and #%d %s for Label and Macro...",
                    index1, sizesToString(ifd1), index2, sizesToString(ifd2)));
        }
        if (ALWAYS_USE_SVS_SPECIFICATION_FOR_LABEL_AND_MACRO) {
            final int compression1 = ifd1.getCompressionCode();
            final int compression2 = ifd2.getCompressionCode();
            boolean found = false;
            if (compression1 == TagCompression.LZW.code() && compression2 == TagCompression.JPEG.code()) {
                this.labelIndex = index1;
                this.macroIndex = index2;
                found = true;
            }
            if (compression1 == TagCompression.JPEG.code() && compression2 == TagCompression.LZW.code()) {
                this.labelIndex = index2;
                this.macroIndex = index1;
                found = true;
            }
            if (found) {
                LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                        "  Label %d / Macro %d detected by SVS specification", labelIndex, macroIndex));
                return true;
            }
        }
        final double ratio1 = ratio(ifd1);
        final double ratio2 = ratio(ifd2);
        LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                "  Last 2 IFD ratios: %.5f for %d, %.5f for %d, standard Macro %.5f",
                ratio1, index1, ratio2, index2, STANDARD_MACRO_ASPECT_RATIO));
        final double maxRatio = Math.max(ratio1, ratio2);
        if (maxRatio > STANDARD_MACRO_ASPECT_RATIO * (1.0 - ALLOWED_ASPECT_RATION_DEVIATION)
                && maxRatio < STANDARD_MACRO_ASPECT_RATIO / (1.0 - ALLOWED_ASPECT_RATION_DEVIATION)) {
            // Usually, the more extended from 2 images is Macro and the more square is Label,
            // but we use this criterion only if the more extended really looks almost as a standard Macro
            this.macroIndex = ratio1 > ratio2 ? index1 : index2;
            this.labelIndex = ratio1 > ratio2 ? index2 : index1;
            LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                    "  Label %d / Macro %d detected by form", labelIndex, macroIndex));
            return true;
        }
        // Both images have unexpected form, so, let's consider that Macro is the greatest from them
        final double area1 = area(ifd1);
        final double area2 = area(ifd2);
        this.macroIndex = area1 > area2 ? index1 : index2;
        this.labelIndex = area1 > area2 ? index2 : index1;
        LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                "  Label %d / Macro %d detected by area", labelIndex, macroIndex));
        return true;
    }

    private void detectSingleLastImage() throws TiffException {
        if (ifdCount <= THUMBNAIL_IFD_INDEX + 1) {
            return;
        }
        final int index = ifdCount - 1;
        final TiffIFD ifd = ifds.get(index);
        if (!isSmallImage(ifd)) {
            return;
        }
        final double ratio = ratio(ifd);
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(System.Logger.Level.DEBUG, String.format(
                    "  Checking last 1 small IFDs #%d %s for Label or Macro...", index, sizesToString(ifd)));
            LOG.log(System.Logger.Level.DEBUG, String.format(
                    "  Last IFD #%d, ratio: %.5f, standard Macro %.5f", index, ratio, STANDARD_MACRO_ASPECT_RATIO));
        }
        if (ratio <= STANDARD_MACRO_ASPECT_RATIO * (1.0 - ALLOWED_ASPECT_RATION_DEVIATION)) {
            if (ifd.getCompressionCode() == TagCompression.JPEG.code()) {
                // strange image, but very improbable that it is Label encoded by JPEG
                // (usually Label is compressed lossless)
                this.macroIndex = index;
                LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                        "  Macro %d with strange form detected by JPEG compression", index));
            } else {
                this.labelIndex = index;
                LOG.log(System.Logger.Level.DEBUG, () -> String.format("  Label %d detected by form", index));
            }
        } else if (ratio < STANDARD_MACRO_ASPECT_RATIO / (1.0 - ALLOWED_ASPECT_RATION_DEVIATION)) {
            this.macroIndex = index;
            LOG.log(System.Logger.Level.DEBUG, () -> String.format("  Macro %d detected by form", index));
        } else {
            LOG.log(System.Logger.Level.DEBUG, () -> String.format("  Last IFD %d is UNKNOWN", index));
        }
    }

    static boolean isSmallImage(TiffIFD ifd) throws TiffException {
        final long[] tileOffsets = ifd.getLongArray(Tags.TILE_OFFSETS);
//        System.out.println(tileOffsets == null ? "null" : tileOffsets.length);
        return tileOffsets == null &&
                (long) ifd.getImageDimX() * (long) ifd.getImageDimY() < MAX_PIXEL_COUNT_IN_SPECIAL_IMAGES;
    }

    static String sizesToString(TiffIFD ifd) throws TiffException {
        Objects.requireNonNull(ifd, "Null IFD");
        return ifd.getImageDimX() + "x" + ifd.getImageDimY();
    }

    static String compressionToString(TiffIFD ifd) {
        Objects.requireNonNull(ifd, "Null IFD");
        return String.valueOf(ifd.optCompression());
    }

    private static double area(TiffIFD ifd) throws TiffException {
        final long dimX = ifd.getImageDimX();
        final long dimY = ifd.getImageDimY();
        return (double) dimX * (double) dimY;
    }

    private static double ratio(TiffIFD ifd) throws TiffException {
        final long dimX = ifd.getImageDimX();
        final long dimY = ifd.getImageDimY();
        assert dimX > 0 && dimY > 0;
        // - was checked in checkThatIfdSizesArePositiveIntegers in the SVSPlanePyramidSource constructor
        return (double) Math.max(dimX, dimY) / (double) Math.min(dimX, dimY);
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Usage: " + SVSImageClassifier.class.getName() + " file1.svs file2.svs ...");
            return;
        }
        for (String arg : args) {
            final Path file = Paths.get(arg);
            try (TiffReader reader = new TiffReader(file)) {
                final var detector = new SVSImageClassifier(reader.allIFDs());
                System.out.printf("%s:%n%s%n%n", file, detector);
            }
        }
    }
}
