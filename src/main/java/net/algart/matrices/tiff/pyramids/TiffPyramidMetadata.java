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

import net.algart.arrays.Arrays;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tags.SvsDescription;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagDescription;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class TiffPyramidMetadata {
    public enum SpecialKind {
        THUMBNAIL("thumbnail"),
        LABEL("label"),
        MACRO("macro");

        private final String kindName;

        SpecialKind(String kindName) {
            this.kindName = kindName;
        }

        public String kindName() {
            return kindName;
        }
    }

    public static final int SVS_THUMBNAIL_INDEX = 1;

    private static final int MAX_SPECIAL_IMAGES_SIZE = 2048;
    private static final double STANDARD_MACRO_ASPECT_RATIO = 2.8846153846153846153846153846154;
    // - 75000/26000, typical value for medicine
    private static final double ALLOWED_ASPECT_RATION_DEVIATION = 0.2;
    private static final boolean ALWAYS_USE_SVS_SPECIFICATION_FOR_LABEL_AND_MACRO =
            Arrays.SystemSettings.getBooleanEnv(
                    "ALWAYS_USE_SVS_SPECIFICATION_FOR_LABEL_AND_MACRO", false);

    private static final System.Logger LOG = System.getLogger(TiffPyramidMetadata.class.getName());

    private final int numberOfImages;
    private final int baseImageDimX;
    private final int baseImageDimY;
    private final boolean baseImageTiled;
    private int numberOfLayers = 0;
    private int pyramidScaleRatio = -1;
    private int thumbnailIndex = -1;
    private int labelIndex = -1;
    private int macroIndex = -1;

    private final List<TagDescription> tagDescriptions;
    private final SvsDescription mainSvsDescription;

    private TiffPyramidMetadata() {
        this.numberOfImages = this.numberOfLayers = 0;
        this.baseImageDimX = this.baseImageDimY = 0;
        this.baseImageTiled = false;
        this.tagDescriptions = Collections.emptyList();
        this.mainSvsDescription = null;
    }

    private TiffPyramidMetadata(List<TiffIFD> ifds) throws TiffException {
        Objects.requireNonNull(ifds, "Null ifds");
        this.numberOfImages = ifds.size();
        if (numberOfImages == 0) {
            this.numberOfLayers = 0;
            this.baseImageDimX = this.baseImageDimY = 0;
            this.baseImageTiled = false;
        } else {
            final TiffIFD first = ifds.getFirst();
            this.baseImageDimX = first.getImageDimX();
            this.baseImageDimY = first.getImageDimY();
            this.baseImageTiled = first.hasTileInformation();
            this.numberOfLayers = detectPyramidAndThumbnail(ifds);
            // - may be corrected by further methods
            if (!detectTwoLastImages(ifds)) {
                detectSingleLastImage(ifds);
            }
        }
        this.tagDescriptions = new ArrayList<>();
        for (int k = 0; k < numberOfImages; k++) {
            final String description = ifds.get(k).optDescription().orElse(null);
            this.tagDescriptions.add(TagDescription.of(description));
        }
        this.mainSvsDescription = SvsDescription.findMainDescription(tagDescriptions);
    }

    public static TiffPyramidMetadata empty() {
        return new TiffPyramidMetadata();
    }

    public static TiffPyramidMetadata of(List<TiffIFD> ifds) throws TiffException {
        return new TiffPyramidMetadata(ifds);
    }

    public static TiffPyramidMetadata of(TiffReader reader) throws IOException {
        Objects.requireNonNull(reader, "Null TIFF reader");
        return new TiffPyramidMetadata(reader.allIFDs());
    }

    /**
     * Checks whether the dimensions of the next pyramid layer match
     * the expected downsampled size of the previous layer.
     *
     * <p>The expected size is {@code layerDim/compression}, allowing
     * a tolerance of +1 pixel in each dimension.
     *
     * @param layerDimX     width of the current layer.
     * @param layerDimY     height of the current layer.
     * @param nextLayerDimX width of the next (downsampled) layer.
     * @param nextLayerDimY height of the next (downsampled) layer.
     * @param compression   integer scale factor (must be 2 or greater).
     * @return {@code true} if the next layer size matches the expected
     * downsampled dimensions (with +1 allowed), false otherwise.
     * @throws IllegalArgumentException if compression is not 2 or greater or if one of the arguments &le;0.
     */
    public static boolean matchesDimensionRatio(
            long layerDimX,
            long layerDimY,
            long nextLayerDimX,
            long nextLayerDimY,
            int compression) {
        if (compression <= 1) {
            throw new IllegalArgumentException("Invalid compression " + compression + " (must be 2 or greater)");
        }
        if (layerDimX <= 0 || layerDimY <= 0) {
            throw new IllegalArgumentException("Zero or negative layer dimensions: " + layerDimX + "x" + layerDimY);
        }
        if (nextLayerDimX <= 0 || nextLayerDimY <= 0) {
            throw new IllegalArgumentException("Zero or negative next layer dimensions: " +
                    nextLayerDimX + "x" + nextLayerDimY);
        }
        final long predictedNextWidth = layerDimX / compression;
        final long predictedNextHeight = layerDimY / compression;
        return (nextLayerDimX == predictedNextWidth || nextLayerDimX == predictedNextWidth + 1)
                && (nextLayerDimY == predictedNextHeight || nextLayerDimY == predictedNextHeight + 1);
    }

    /**
     * Attempts to determine the integer downsampling ratio between two image layers.
     *
     * <p>The method tests integer compression factors from 2 up to 32, and returns
     * the first value for which {@link #matchesDimensionRatio(long, long, long, long, int)}
     * returns {@code true} for these arguments. This is useful for detecting the downsampling step
     * between consecutive pyramid layers in SVS-like or pyramidal TIFF images.
     *
     * <p>If no matching ratio is found within the tested range, &minus;1 is returned.
     *
     * @param layerDimX     width of the current layer.
     * @param layerDimY     height of the current layer.
     * @param nextLayerDimX width of the next (downsampled) layer.
     * @param nextLayerDimY height of the next (downsampled) layer.
     * @return the first matching integer downsampling ratio from 2..32 range, or &minus;1 if no ratio matches.
     * @throws IllegalArgumentException if one of the arguments ≤ 0.
     */
    public static int findRatio(long layerDimX, long layerDimY, long nextLayerDimX, long nextLayerDimY) {
        for (int probeCompression = 2; probeCompression <= 32; probeCompression++) {
            if (matchesDimensionRatio(layerDimX, layerDimY, nextLayerDimX, nextLayerDimY, probeCompression)) {
                return probeCompression;
            }
        }
        return -1;
    }

    public boolean isSvs() {
        return mainSvsDescription != null;
    }

    public boolean isPyramid() {
        if (numberOfLayers == 0) {
            return false;
        }
        if (numberOfLayers == 1) {
            return isSvs();
            // - if this is not SVS, i.e., we have no Aperio-like ImageDescription,
            // then we cannot determine a thumbnail and have no reasons to interpret this TIFF as a pyramid
        }
        assert numberOfLayers > 1;
        return true;
    }

    public boolean isSvsCompatible() {
        return isSvs() || (isPyramid() && hasSvsThumbnail());
    }

    public int baseImageDimX() {
        return baseImageDimX;
    }

    public int baseImageDimY() {
        return baseImageDimY;
    }

    public int numberOfImages() {
        return numberOfImages;
    }

    /**
     * Returns the number of layers in the pyramid.
     *
     * <p>The result may be 0 if the first image (IFD #0) is not tiled or
     * if there are no images ({@link #numberOfImages()}==0).
     * In all other cases, the result is &ge;1.
     *
     * @return number of pyramid layers.
     */
    public int numberOfLayers() {
        return numberOfLayers;
    }

    public int pyramidScaleRatio() {
        return pyramidScaleRatio;
    }

    /**
     * Translates a pyramid layer index to the corresponding TIFF IFD index.
     *
     * @param layerIndex index of the pyramid layer (0 = base layer, up to <code>{@link #numberOfLayers()}-1</code>).
     * @return corresponding TIFF IFD index
     * @throws IllegalArgumentException if layerIndex is negative or &ge;{@link #numberOfLayers()}.
     */
    public int layerToImage(int layerIndex) {
        if (layerIndex < 0) {
            throw new IllegalArgumentException("Negative layer index " + layerIndex);
        }
        if (layerIndex >= numberOfLayers) {
            throw new IllegalArgumentException("Too large layer index " + layerIndex + " >= " + numberOfLayers);
        }
        return !hasThumbnail() || layerIndex < thumbnailIndex ? layerIndex : layerIndex + 1;
    }

    /**
     * Translates a TIFF IFD index to the corresponding pyramid layer index.
     * If this IFD index does not correspond to a pyramid layer, the method returns &minus;1.
     *
     * @param imageIndex index of the TIFF IFD (0..<code>{@link #numberOfImages()}-1</code>).
     * @return corresponding pyramid layer index or &minus;1 if the IFD is not a pyramid layer.
     * @throws IllegalArgumentException if imageIndex is negative or &ge;{@link #numberOfImages}.
     */
    public int imageToLayer(int imageIndex) {
        if (imageIndex < 0) {
            throw new IllegalArgumentException("Negative IFD index " + imageIndex);
        }
        if (imageIndex >= numberOfImages) {
            throw new IllegalArgumentException("Too large IFD index " + imageIndex + " >= " + numberOfImages);
        }
        if (imageIndex == thumbnailIndex) {
            return -1;
        }
        final int layerIndex = imageIndex < thumbnailIndex ? imageIndex : imageIndex - 1;
        return layerIndex < numberOfLayers ? layerIndex : -1;
    }

    /**
     * Returns {@code true} if and only if this TIFF contains an SVS or SVS-compatible thumbnail image
     * at the IFD index #1 (the second image, the standard position for SVS thumbnails).
     *
     * <p>In the current version, this is the only situation when a thumbnail is recognized,
     * so this method is fully equivalent to {@link #hasThumbnail()}.
     *
     * @return whether this TIFF contains an SVS-compatible thumbnail image at the position #1.
     */
    public boolean hasSvsThumbnail() {
        return thumbnailIndex == SVS_THUMBNAIL_INDEX;
    }

    public boolean hasThumbnail() {
        return thumbnailIndex != -1;
    }

    public int thumbnailIndex() {
        return thumbnailIndex;
    }

    public boolean hasLabel() {
        return labelIndex != -1;
    }

    public int labelIndex() {
        return labelIndex;
    }

    public boolean hasMacro() {
        return macroIndex != -1;
    }

    public int macroIndex() {
        return macroIndex;
    }

    public boolean isSpecial(int ifdIndex) {
        if (ifdIndex < 0) {
            throw new IllegalArgumentException("Negative ifdIndex");
        }
        return ifdIndex == thumbnailIndex || ifdIndex == labelIndex || ifdIndex == macroIndex;
    }

    public boolean hasSpecialKinds() {
        return thumbnailIndex != -1 || labelIndex != -1 || macroIndex != -1;
    }

    public boolean hasSpecialKind(SpecialKind kind) {
        return specialKindIndex(kind) != -1;
    }

    public int specialKindIndex(SpecialKind kind) {
        Objects.requireNonNull(kind, "Null special kind");
        switch (kind) {
            case LABEL -> {
                if (labelIndex != -1) {
                    return labelIndex;
                }
            }
            case MACRO -> {
                if (macroIndex != -1) {
                    return macroIndex;
                }
            }
            case THUMBNAIL -> {
                if (thumbnailIndex != -1) {
                    return thumbnailIndex;
                }
            }
        }
        return -1;
    }

    public List<TagDescription> allDescriptions() {
        return tagDescriptions;
    }

    public TagDescription tagDescription(int ifdIndex) {
        if (ifdIndex < 0) {
            throw new IllegalArgumentException("Negative IFD index " + ifdIndex);
        }
        if (ifdIndex >= tagDescriptions.size()) {
            throw new IllegalArgumentException(
                    "IFD index " + ifdIndex + " is out of bounds 0 <= index < " + tagDescriptions.size());
        }
        return tagDescriptions.get(ifdIndex);
    }

    public SvsDescription mainSvsDescription() {
        return mainSvsDescription;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder();
        if (isPyramid()) {
            sb.append("TIFF pyramid with ").append(numberOfLayers).append(" layers ");
            if (numberOfLayers > 1) {
                sb.append(pyramidScaleRatio).append(":1 ");
            }
            sb.append("(");
            for (int i = 0; i < numberOfLayers; i++) {
                if (i > 0) {
                    sb.append("|");
                }
                sb.append(layerToImage(i));
            }
            sb.append(")");
        }
        for (SpecialKind kind : SpecialKind.values()) {
            int index = specialKindIndex(kind);
            if (index != -1) {
                if (!sb.isEmpty()) {
                    sb.append(", ");
                }
                sb.append(kind.kindName()).append(" (#").append(index).append(")");
            }
        }
        return sb.isEmpty() ? "no special images" : sb.toString();
    }

    private int detectPyramidAndThumbnail(List<TiffIFD> ifds) throws TiffException {
        thumbnailIndex = -1;
        if (!baseImageTiled) {
            return 0;
        }
        int countNonSvs = detectPyramid(ifds, 1);
        if (numberOfImages <= 1 || countNonSvs >= 3) {
            // 3 or more images 0, 1, 2, ... have the same ratio: it's obvious that the image #1 is not a thumbnail
            return countNonSvs;
        }
        if (isSmallImage(ifds.get(SVS_THUMBNAIL_INDEX))) {
            thumbnailIndex = SVS_THUMBNAIL_INDEX;
        }
        if (countNonSvs == 2 && numberOfImages == 2) {
            return thumbnailIndex == -1 ? 2 : 1;
        }
        // Now countNonSvs = 1 or 2
        return detectPyramid(ifds, SVS_THUMBNAIL_INDEX + 1);
        // If the result >= 2 and thumbnailIndex=THUMBNAIL_IFD_INDEX (small image), the image #1 is really a thumbnail.
        // If the result = 1, we have no pyramid 0-2-3-... or 0-1-2-..., so, we don't know what is #1,
        // and we still decide this based on the sizes
    }

    private int detectPyramid(List<TiffIFD> ifds, int startIndex) throws TiffException {
        int levelDimX = baseImageDimX;
        int levelDimY = baseImageDimY;
        int actualScaleRatio = -1;
        int count = 1;
        for (int k = startIndex; k < numberOfImages; k++, count++) {
            final TiffIFD ifd = ifds.get(k);
            final int nextDimX = ifd.getImageDimX();
            final int nextDimY = ifd.getImageDimY();
            if (actualScaleRatio == -1) {
                actualScaleRatio = findRatio(levelDimX, levelDimY, nextDimX, nextDimY);
                if (actualScaleRatio == -1) {
                    break;
                }
                assert actualScaleRatio >= 2;
            } else {
                if (!matchesDimensionRatio(levelDimX, levelDimY, nextDimX, nextDimY, actualScaleRatio)) {
                    final int index = k;
                    LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                            "%s found incorrect dimensions ratio at %d; skipping remaining %d IFDs",
                            getClass().getSimpleName(), index, numberOfImages - index));
                    break;
                }
            }
            levelDimX = nextDimX;
            levelDimY = nextDimY;
        }
        this.pyramidScaleRatio = actualScaleRatio;
        return count;
    }

    private boolean detectTwoLastImages(List<TiffIFD> ifds) throws TiffException {
        if (numberOfImages <= SVS_THUMBNAIL_INDEX + 2) {
            return false;
        }
        final int index1 = numberOfImages - 2;
        final int index2 = numberOfImages - 1;
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

    private void detectSingleLastImage(List<TiffIFD> ifds) throws TiffException {
        if (numberOfImages <= SVS_THUMBNAIL_INDEX + 1) {
            return;
        }
        final int index = numberOfImages - 1;
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
        // - but we do not check tiling: for non-Aperio,
        // it is possible that we will use tiling for all images without any special reason
        return ifd.getImageDimX() <= MAX_SPECIAL_IMAGES_SIZE &&
                ifd.getImageDimY() <= MAX_SPECIAL_IMAGES_SIZE;
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
        return (double) Math.max(dimX, dimY) / (double) Math.min(dimX, dimY);
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("Usage: " + TiffPyramidMetadata.class.getName() + " file1.svs file2.svs ...");
            return;
        }
        for (String arg : args) {
            final Path file = Paths.get(arg);
            try (TiffReader reader = new TiffReader(file)) {
                final var metadata = new TiffPyramidMetadata(reader.allIFDs());
                System.out.printf("%s:%n%s%n%n", file, metadata);
            }
        }
    }
}
