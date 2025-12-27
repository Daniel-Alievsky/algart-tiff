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
import net.algart.matrices.tiff.TiffImageKind;
import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.tags.SvsDescription;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.Tags;

import java.io.IOException;
import java.util.*;

public final class TiffPyramidMetadata {

    public static final int SVS_THUMBNAIL_INDEX = 1;

    private static final boolean DETECT_LABEL_AND_MACRO_PAIR =
            net.algart.arrays.Arrays.SystemSettings.getBooleanProperty(
                    "net.algart.matrices.tiff.pyramids.detectLabelAndMacroPair", true);
    private static final boolean CHECK_COMPRESSION_FOR_LABEL_AND_MACRO =
            net.algart.arrays.Arrays.SystemSettings.getBooleanProperty(
                    "net.algart.matrices.tiff.pyramids.checkCompressionForLabelAndMacro", true);
    private static final boolean CHECK_KEYWORDS_FOR_LABEL_AND_MACRO =
            net.algart.arrays.Arrays.SystemSettings.getBooleanProperty(
                    "net.algart.matrices.tiff.pyramids.checkKeywordsForLabelAndMacro", true);
    private static final int MAX_SPECIAL_IMAGES_SIZE = 2048;
    private static final double STANDARD_MACRO_ASPECT_RATIO = 75000.0 / 25000.0;
    // - typical value for medicine glasses
    private static final double ALLOWED_ASPECT_RATION_DEVIATION = 0.2;

    private static final System.Logger LOG = System.getLogger(TiffPyramidMetadata.class.getName());

    private final List<TiffIFD> ifds;
    private final int numberOfImages;
    private final int baseImageDimX;
    private final int baseImageDimY;
    private final boolean baseImageTiled;
    private final int numberOfLayers;
    private int pyramidScaleRatio = -1;
    private int thumbnailIndex = -1;
    private int labelIndex = -1;
    private int macroIndex = -1;

    private final SvsDescription svsDescription;

    private TiffPyramidMetadata() {
        this.ifds = Collections.emptyList();
        this.numberOfImages = this.numberOfLayers = 0;
        this.baseImageDimX = this.baseImageDimY = 0;
        this.baseImageTiled = false;
        this.svsDescription = null;
    }

    private TiffPyramidMetadata(List<TiffIFD> ifds) throws TiffException {
        Objects.requireNonNull(ifds, "Null IFDs");
        this.ifds = List.copyOf(ifds);
        this.numberOfImages = this.ifds.size();
        this.svsDescription = SvsDescription.findMainDescription(this.ifds);
        // - used in detectLabelAndMacro()
        if (numberOfImages == 0) {
            this.numberOfLayers = 0;
            this.baseImageDimX = this.baseImageDimY = 0;
            this.baseImageTiled = false;
        } else {
            final TiffIFD first = this.ifds.getFirst();
            this.baseImageDimX = first.getImageDimX();
            this.baseImageDimY = first.getImageDimY();
            this.baseImageTiled = first.hasTileInformation();
            this.numberOfLayers = detectPyramidAndThumbnail();
            detectLabelAndMacro();
        }
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

    public static void correctForSpecialKinds(TiffIFD ifd, TiffImageKind kind) {
        Objects.requireNonNull(ifd, "Null IFD");
        Objects.requireNonNull(kind, "Null image kind");
        if (kind.isOrdinary()) {
            return;
        }
        ifd.removeTileInformation();
        ifd.defaultStripSize();
        // - note: Aperio Image Scope will not work properly if we have no both tiles and strips!
        ifd.putCompression(recommendedCompression(kind));
        switch (kind) {
            case LABEL -> ifd.put(Tags.NEW_SUBFILE_TYPE, 1);
            case MACRO -> ifd.put(Tags.NEW_SUBFILE_TYPE, 9);
            // - note: these tags are necessary for correct recognition by Aperio Image Scope
        }
    }

    public static TagCompression recommendedCompression(TiffImageKind imageKind) {
        Objects.requireNonNull(imageKind, "Null image kind");
        // From Aperio_Digital_Slides_and_Third-party_data_interchange.pdf:
        // Optionally at the end of an SVS file there may be a slide label image,
        // which is a low resolution picture taken of the slide’s label, and/or a macro camera image,
        // which is a low resolution picture taken of the entire slide.
        // The label and macro images are always stripped.
        // If present the label image is compressed with LZW compression,
        // and the macro image with JPEG compression.
        // By storing the label and macro images in the same file as the high resolution slide data
        // there is no chance of an accidental mix-up between the slide contents and the slide label information.
        return imageKind == TiffImageKind.LABEL ? TagCompression.LZW : TagCompression.JPEG_RGB;
    }

    public List<TiffIFD> ifds() {
        return ifds;
    }

    public TiffIFD ifd(int ifdIndex) {
        return ifds.get(ifdIndex);
    }

    public boolean isNonTrivial() {
        return isSvs() || isPyramid();
    }

    public boolean isSvs() {
        return svsDescription != null;
    }

    public boolean isSvsLayer0() {
        final Integer globalIndex = svsDescription != null ? svsDescription.globalIndex() : null;
        return globalIndex != null && globalIndex == 0;
    }

    public boolean isPyramid() {
        if (numberOfLayers == 0) {
            return false;
        }
        if (numberOfLayers == 1) {
            return isSvsLayer0();
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
     * Returns the unchanged {@code layerIndex} argument or {@code layerIndex+1},
     * if this layer is placed after the thumbnail (if any).
     *
     * <p>Note: if <code>layerInde &ge {@link #numberOfLayers()}-1</code>, this method still returns
     * the corresponding image index as if the pyramid were continued after the last layer.
     * For example, you may specify <code>layerIndex={@link #numberOfLayers()}</code> to retrieve
     * the index of the first image <i>after</i> the actual pyramid.
     *
     * @param layerIndex index of the pyramid layer (0 = base layer, up to <code>{@link #numberOfLayers()}-1</code>,
     *                   but greater indexes are also allowed).
     * @return corresponding TIFF IFD index
     * @throws IllegalArgumentException if layerIndex is negative.
     */
    public int layerToImage(int layerIndex) {
        if (layerIndex < 0) {
            throw new IllegalArgumentException("Negative layer index " + layerIndex);
        }
        return !hasThumbnail() || layerIndex < thumbnailIndex ? layerIndex : Math.addExact(layerIndex, 1);
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
        final int layerIndex = !hasThumbnail() || imageIndex < thumbnailIndex ? imageIndex : imageIndex - 1;
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
            throw new IllegalArgumentException("Negative IFD index = " + ifdIndex);
        }
        return ifdIndex == thumbnailIndex || ifdIndex == labelIndex || ifdIndex == macroIndex;
    }

    public boolean hasSpecialKinds() {
        return thumbnailIndex != -1 || labelIndex != -1 || macroIndex != -1;
    }

    public boolean hasSpecialKind(TiffImageKind kind) {
        return specialKindIndex(kind) != -1;
    }

    public int specialKindIndex(TiffImageKind kind) {
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

    public SvsDescription svsDescription() {
        return svsDescription;
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
        for (TiffImageKind kind : TiffImageKind.values()) {
            if (!kind.isOrdinary()) {
                int index = specialKindIndex(kind);
                if (index != -1) {
                    if (!sb.isEmpty()) {
                        sb.append(", ");
                    }
                    sb.append(kind.kindName()).append(" (#").append(index).append(")");
                }
            }
        }
        return sb.isEmpty() ? "no special images" : sb.toString();
    }

    private int detectPyramidAndThumbnail() throws TiffException {
        thumbnailIndex = -1;
        if (!baseImageTiled) {
            return 0;
        }
        int countNonSvs = detectPyramid(1);
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
        // If countNonSvs = 1, we are sure that there is no pyramid 0-1-2-...,
        // and we need to check the possible SVS-like pyramid.
        final int countSvs = detectPyramid(SVS_THUMBNAIL_INDEX + 1);
        if (countSvs < countNonSvs) {
            assert countSvs == 1;
            // so, countNonSvs == 2
            return thumbnailIndex == -1 ? 2 : 1;
        }
        return countSvs;
        // If countSvs >= countNonSvs, we prefer SVS hypotheses
    }

    private int detectPyramid(int startIndex) throws TiffException {
        assert startIndex >= 1;
        int levelDimX = baseImageDimX;
        int levelDimY = baseImageDimY;
        // - note: the base image #0 cannot be sub-IFD (it is always main)
        int actualScaleRatio = -1;
        int count = 1;
        for (int k = startIndex; k < numberOfImages; k++, count++) {
            final TiffIFD ifd = ifds.get(k);
            if (!ifd.isMainIFD() || !ifd.hasTileInformation()) {
                break;
                // - do not try to continue searching for a pyramid if we found a sub-IFD or a non-tiled image
            }
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
                    LOG.log(System.Logger.Level.DEBUG, () ->
                            "%s found incorrect dimensions ratio at %d; skipping remaining %d IFDs".formatted(
                                    getClass().getSimpleName(), index, numberOfImages - index));
                    break;
                }
            }
            levelDimX = nextDimX;
            levelDimY = nextDimY;
        }
        this.pyramidScaleRatio = actualScaleRatio;
        final int result = count;
        LOG.log(System.Logger.Level.DEBUG, () ->
                "%s checked dimension ratio for images 0, %d..%d%s (%d images in the series)".formatted(
                        getClass().getSimpleName(), startIndex, numberOfImages - 1,
                        pyramidScaleRatio < 0 ? " - not a pyramid" : " - found " + pyramidScaleRatio + ":1",
                        result));
        return result;
    }

    private void detectLabelAndMacro() throws TiffException {
        final int firstAfterPyramid = layerToImage(numberOfLayers);
        // - note: no exception here
        assert firstAfterPyramid >= numberOfLayers;
        if (!isPyramid()) {
            return;
            // - if there is no pyramid, this is a not good idea to find label/macro
        }
        assert numberOfLayers > 0 : "isPyramid()=true without layers";
        if (numberOfImages <= 2) {
            // numberOfImages=1: the only image cannot be a label/macro
            // numberOfImages=2, numberOfLayers=1: isPyramid()=true only for SVS - the image #1 is a thumbnail always
            // numberOfImages=2, numberOfLayers=2: we prefer to think that this is a 2-layer pyramid
            return;
        }
        if (!detectTwoLastImages(firstAfterPyramid)) {
            detectSingleLastImage(firstAfterPyramid);
        }
    }

    private boolean detectTwoLastImages(int firstAfterPyramid) throws TiffException {
        assert firstAfterPyramid >= 1;
        if (!DETECT_LABEL_AND_MACRO_PAIR) {
            return false;
        }
        if (numberOfImages - firstAfterPyramid < 2) {
            return false;
        }
        assert numberOfImages >= 3;

        final int index1 = numberOfImages - 2;
        final int index2 = numberOfImages - 1;
        final TiffIFD ifd1 = ifds.get(index1);
        final TiffIFD ifd2 = ifds.get(index2);
        if (!(isPossibleLabelOrMacro(ifd1) && isPossibleLabelOrMacro(ifd2))) {
            return false;
        }
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(System.Logger.Level.DEBUG, "Checking last 2 small IFDs #%d %s and #%d %s for LABEL and MACRO..."
                    .formatted(index1, sizesToString(ifd1), index2, sizesToString(ifd2)));
        }
        if (isSvs() && CHECK_COMPRESSION_FOR_LABEL_AND_MACRO) {
            boolean found = false;
            final int compression1 = ifd1.getCompressionCode();
            final int compression2 = ifd2.getCompressionCode();
            // LABEL should be LZW, MACRI should be JPEG
            if (compression1 == TiffIFD.COMPRESSION_LZW && compression2 == TiffIFD.COMPRESSION_JPEG) {
                this.labelIndex = index1;
                this.macroIndex = index2;
                found = true;
            }
            if (compression1 == TiffIFD.COMPRESSION_JPEG && compression2 == TiffIFD.COMPRESSION_LZW) {
                this.labelIndex = index2;
                this.macroIndex = index1;
                found = true;
            }
            if (found) {
                LOG.log(System.Logger.Level.DEBUG, () -> ("LABEL %d / MACRO %d detected by compression " +
                        "according to the Aperio specification").formatted(labelIndex, macroIndex));
                return true;
            }
        }
        if (CHECK_KEYWORDS_FOR_LABEL_AND_MACRO) {
            boolean found = false;
            boolean probablyLabel1 = containsLabelMarker(ifd1);
            boolean probablyMacro1 = containsMacroMarker(ifd1);
            boolean probablyLabel2 = containsLabelMarker(ifd2);
            boolean probablyMacro2 = containsMacroMarker(ifd2);
            if (probablyLabel1 && !probablyMacro1 && probablyMacro2 && !probablyLabel2) {
                this.labelIndex = index1;
                this.macroIndex = index2;
                found = true;
            }
            if (probablyMacro1 && !probablyLabel1 && probablyLabel2 && !probablyMacro2) {
                this.labelIndex = index2;
                this.macroIndex = index1;
                found = true;
            }
            if (found) {
                LOG.log(System.Logger.Level.DEBUG, () ->
                        "LABEL %d / MACRO %d detected by keywords 'label'/'macro'".formatted(labelIndex, macroIndex));
                return true;
            }
        }
        if (ifd1.isReducedImage() && ifd2.isReducedImage()) {
            // - probably they are still macro/label
            final double ratio1 = ratio(ifd1);
            final double ratio2 = ratio(ifd2);
            final double maxRatio = Math.max(ratio1, ratio2);
            LOG.log(System.Logger.Level.DEBUG, () -> "Last 2 IFD ratios: %.5f for %d, %.5f for %d, standard MACRO %.5f"
                    .formatted(ratio1, index1, ratio2, index2, STANDARD_MACRO_ASPECT_RATIO));
            if (maxRatio > STANDARD_MACRO_ASPECT_RATIO * (1.0 - ALLOWED_ASPECT_RATION_DEVIATION)
                    && maxRatio < STANDARD_MACRO_ASPECT_RATIO / (1.0 - ALLOWED_ASPECT_RATION_DEVIATION)) {
                // Usually, the more extended from 2 images is MACRO and the more square is LABEL,
                // but we use this criterion only if the more extended really looks almost as a standard MACRO
                this.macroIndex = ratio1 > ratio2 ? index1 : index2;
                this.labelIndex = ratio1 > ratio2 ? index2 : index1;
                LOG.log(System.Logger.Level.DEBUG, () ->
                        "LABEL %d / MACRO %d detected by aspect ratio".formatted(labelIndex, macroIndex));
                return true;
            }
        }
        return false;
    }

    private void detectSingleLastImage(int firstAfterPyramid) throws TiffException {
        if (numberOfImages - firstAfterPyramid < 1) {
            return;
        }
        assert numberOfImages >= 3 : "numberOfImages<=2 was checked before calling this method";
        final int index = numberOfImages - 1;
        final TiffIFD ifd = ifds.get(index);
        if (!isPossibleLabelOrMacro(ifd)) {
            return;
        }
        final double ratio = ratio(ifd);
        if (LOG.isLoggable(System.Logger.Level.DEBUG)) {
            LOG.log(System.Logger.Level.DEBUG, "Checking last 1 small IFDs #%d %s for LABEL or MACRO..."
                    .formatted(index, sizesToString(ifd)));
        }
        if (CHECK_KEYWORDS_FOR_LABEL_AND_MACRO) {
            boolean probablyLabel = containsLabelMarker(ifd);
            boolean probablyMacro = containsMacroMarker(ifd);
            if (probablyLabel && !probablyMacro) {
                this.labelIndex = index;
                LOG.log(System.Logger.Level.DEBUG, () ->"LABEL %d detected by keyword 'label'".formatted(labelIndex));
                return;
            }
            if (probablyMacro && !probablyLabel) {
                this.macroIndex = index;
                LOG.log(System.Logger.Level.DEBUG, () ->"MACRO %d detected by keyword 'macro'".formatted(macroIndex));
                return;
            }
        }
        if (ifd.isReducedImage()) {
            // - probably this is still macro/label
            LOG.log(System.Logger.Level.DEBUG, () -> String.format(
                    "Last IFD #%d, ratio: %.5f, standard Macro %.5f", index, ratio, STANDARD_MACRO_ASPECT_RATIO));
            if (ratio > STANDARD_MACRO_ASPECT_RATIO * (1.0 - ALLOWED_ASPECT_RATION_DEVIATION)
                    && ratio < STANDARD_MACRO_ASPECT_RATIO / (1.0 - ALLOWED_ASPECT_RATION_DEVIATION)) {
                this.macroIndex = index;
                LOG.log(System.Logger.Level.DEBUG, () -> "MACRO %d detected by aspect ratio".formatted(macroIndex));
            }
        }
    }

    private static boolean isSmallImage(TiffIFD ifd) throws TiffException {
        // - but we do not check tiling: for non-Aperio,
        // it is possible that we will use tiling for all images without any special reason
        return ifd.getImageDimX() <= MAX_SPECIAL_IMAGES_SIZE &&
                ifd.getImageDimY() <= MAX_SPECIAL_IMAGES_SIZE;
    }

    private static boolean isPossibleLabelOrMacro(TiffIFD ifd) throws TiffException {
        return isSmallImage(ifd) && !ifd.hasTileInformation();
        // - note: tiled images cannot be labels or macros (no reasons to do so)
    }

    private static boolean containsLabelMarker(TiffIFD ifd) throws TiffException {
        Optional<String> description = ifd.optDescription();
        return description.isPresent() && TiffImageKind.LABEL_DETECTION_PATTERN.matcher(description.get()).find();
    }

    private static boolean containsMacroMarker(TiffIFD ifd) throws TiffException {
        Optional<String> description = ifd.optDescription();
        return description.isPresent() && TiffImageKind.MACRO_DETECTION_PATTERN.matcher(description.get()).find();
    }

    private static String sizesToString(TiffIFD ifd) throws TiffException {
        Objects.requireNonNull(ifd, "Null IFD");
        return ifd.getImageDimX() + "x" + ifd.getImageDimY();
    }

    private static double ratio(TiffIFD ifd) throws TiffException {
        final long dimX = ifd.getImageDimX();
        final long dimY = ifd.getImageDimY();
        assert dimX > 0 && dimY > 0;
        return (double) Math.max(dimX, dimY) / (double) Math.min(dimX, dimY);
    }

    public static void main(String[] args) throws IOException {
        System.out.println(containsLabelMarker(new TiffIFD().putDescription("My Label is")));
        System.out.println(containsLabelMarker(new TiffIFD().putDescription("Label")));
        System.out.println(containsLabelMarker(new TiffIFD().putDescription("Labelling")));
        System.out.println(containsLabelMarker(new TiffIFD().putDescription("Label\n1")));
        System.out.println(containsLabelMarker(new TiffIFD().putDescription("Labe\n1")));
    }
}
