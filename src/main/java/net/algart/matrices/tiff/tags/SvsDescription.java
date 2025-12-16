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

package net.algart.matrices.tiff.tags;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;

import java.util.*;

public final class SvsDescription extends TagDescription {
    public static final String SVS_IMAGE_DESCRIPTION_PREFIX = "Aperio Image";

    public static final String MAGNIFICATION_ATTRIBUTE = "AppMag";
    public static final String MICRON_PER_PIXEL_ATTRIBUTE = "MPP";
    public static final String LEFT_ATTRIBUTE = "Left";
    public static final String TOP_ATTRIBUTE = "Top";

    private static final Set<String> HUMAN_READABLE_ATTRIBUTES = Set.of("ScanScope ID", "Date", "Time");

    private final List<String> text = new ArrayList<>();
    private final Map<String, String> attributes = new LinkedHashMap<>();
    private final String application;
    private final String summary;
    private final boolean main;

    SvsDescription(String description) {
        super(description);
        assert description != null && description.startsWith(SVS_IMAGE_DESCRIPTION_PREFIX) :
                getClass().getSimpleName() + " should not be constructed for " + description;
        description = description.trim();
        boolean delimiterFound = false;
        String summary = "";
        for (String line : description.split("\\n")) {
            line = line.trim();
            this.text.add(line);
            final int p = line.indexOf('|');
            final String prefix = p == -1 ? line : line.substring(0, p);
            if (!delimiterFound) {
                summary = prefix.trim();
                // - for the summary, we use the first string with the delimiter "|" or,
                // if there are NO such strings, the last among all strings
            }
            if (p > 0) {
                delimiterFound = true;
                final String[] records = line.substring(p + 1).split("\\|");
                for (String s : records) {
                    final String[] keyValue = s.trim().split("=", 2);
                    if (keyValue.length == 2) {
                        final String key = keyValue[0].trim();
                        final String value = keyValue[1].trim();
                        attributes.put(key, value);
                        // - for duplicates, we use the last one
                    }
                }
            }
        }
        this.application = !text.isEmpty() ? text.getFirst() : "";
        this.summary = summary;
        this.main = hasPixelSize();
    }

    private SvsDescription(Builder builder) {
        super(Objects.requireNonNull(builder).buildDescription());
        this.text.addAll(builder.text);
        this.attributes.putAll(builder.attributes);
        this.application = builder.application;
        this.summary = builder.summary;
        this.main = builder.main;
    }

    public static boolean isSvs(String imageDescription) {
        return imageDescription != null && imageDescription.trim().startsWith(SVS_IMAGE_DESCRIPTION_PREFIX);
    }

    public boolean isSvs() {
        return true;
    }

    public boolean isMain() {
        return main;
    }

    public List<String> text() {
        return Collections.unmodifiableList(text);
    }

    public String formatName(boolean pretty) {
        return pretty ? "SVS details" : "svs";
    }

    public boolean hasApplication() {
        return !application.isEmpty();
    }

    /**
     * Returns application header.
     * An example (from <a href="https://openslide.org/demo/">OpenSlide website</a>):
     * <pre>"Aperio Image Library v10.0.51".</pre>
     *
     * <p>This is the first line in {@code ImageDescription} tag, stored for SVS images.
     *
     * <p>This string cannot be {@code null} and is always trimmed.
     *
     * @return brief application information.
     */
    public String application() {
        // Historically, in the old API, oriented for PlanePyramidSource interface and SVS2Json utility,
        // this line was returned by importantTextAttributes() method.
        return application;
    }

    public boolean hasSummary() {
        return !summary.isEmpty();
    }

    /**
     * Returns a brief summary of the SVS image.
     * An example (from <a href="https://openslide.org/demo/">OpenSlide website</a>):
     * <pre>"46920x33014 [0,100 46000x32914] (256x256) JPEG/RGB Q=30".</pre>
     *
     * <p>This is the starting part (before the "|" character)
     * of the second line in {@code ImageDescription} tag, stored for SVS images.
     *
     * <p>This string cannot be {@code null} and is always trimmed.
     *
     * @return brief SVS image information.
     */
    public String summary() {
        return summary;
    }

    @Override
    public Map<String, String> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    @Override
    public Set<String> humanReadableAttributeNames() {
        // Historically, in the old API, oriented for PlanePyramidSource interface and SVS2Json utility,
        // this method was called by importantAttributeNames().
        return HUMAN_READABLE_ATTRIBUTES;
    }

    public boolean hasPixelSize() {
        return attributes.containsKey(MICRON_PER_PIXEL_ATTRIBUTE);
    }

    public OptionalDouble optPixelSize() {
        return optDouble(MICRON_PER_PIXEL_ATTRIBUTE);
    }

    public double pixelSize() throws TiffException {
        final double result = reqDouble(MICRON_PER_PIXEL_ATTRIBUTE);
        if (result <= 0.0) {
            throw new TiffException("SVS image description contains negative \"MPP\" attribute: " + result);
        }
        return result;
    }

    public boolean hasMagnification() {
        return attributes.containsKey(MAGNIFICATION_ATTRIBUTE);
    }

    public double magnification() throws TiffException {
        return reqDouble(MAGNIFICATION_ATTRIBUTE);
    }

    public boolean hasGeometry() {
        return hasPixelSize()
                // - without pixel size we have not enough information to detect image position at the whole slide
                && attributes.containsKey(LEFT_ATTRIBUTE)
                && attributes.containsKey(TOP_ATTRIBUTE);
    }


    /**
     * Returns the horizontal offset of the image left boundary on the slide, in microns.
     * The offset is measured along an axis directed rightward.
     *
     * <p>In SVS, the "Left" attribute stores x-coordinate of the image on the physical slide in millimeters.
     * This method returns this value multiplied by 1000.</p>
     *
     * @return the horizontal offset of the image left boundary in microns.
     * @throws TiffException if the SVS attribute cannot be read as a number.
     */
    public double imageLeftMicronsAxisRightward() throws TiffException {
        return reqDouble(LEFT_ATTRIBUTE) * 1000.0;
    }

    /**
     * Returns the vertical offset of the image top boundary on the slide, in microns.
     * Note: <i>the offset is measured along an axis directed upward</i>,
     * rather than the usual image-coordinate system where the y-axis increases downward.
     *
     * <p>In SVS, the "Top" attribute stores y-coordinate of the image on the physical slide in millimeters.
     * This method returns this value multiplied by 1000.</p>
     *
     * @return the vertical offset of the image upper boundary in microns, measured along the upward axis.
     * @throws TiffException if the SVS attribute cannot be read as a number.
     */
    public double imageTopMicronsAxisUpward() throws TiffException {
        return reqDouble(TOP_ATTRIBUTE) * 1000.0;
    }

    @Override
    public String jsonString() {
        final StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"svs\": ").append(true).append(",\n");
        sb.append("  \"main\": ").append(main).append(",\n");
        sb.append("  \"application\": \"").append(TiffIFD.escapeJsonString(application())).append("\",\n");
        sb.append("  \"summary\": \"").append(TiffIFD.escapeJsonString(summary())).append("\",\n");
        sb.append("  \"attributes\": {\n");
        for (Iterator<Map.Entry<String, String>> iterator = attributes.entrySet().iterator(); iterator.hasNext(); ) {
            Map.Entry<String, String> entry = iterator.next();
            sb.append("    \"").append(TiffIFD.escapeJsonString(entry.getKey())).append("\": ");
            sb.append("\"").append(TiffIFD.escapeJsonString(entry.getValue())).append("\"");
            if (iterator.hasNext()) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  },\n");

        sb.append("  \"rawText\": [\n");
        for (int i = 0, n = text.size(); i < n; i++) {
            sb.append("    \"").append(TiffIFD.escapeJsonString(text.get(i))).append("\"");
            if (i < n - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("  ]");
        if (hasPixelSize()) {
            sb.append(",\n  \"pixelSize\": ");
            try {
                sb.append(pixelSize());
            } catch (TiffException e) {
                sb.append("\"format error: ").append(TiffIFD.escapeJsonString(e.getMessage())).append("\"");
            }
        }
        sb.append("\n}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return toString(TiffIFD.StringFormat.BRIEF);
    }

    public String toString(TiffIFD.StringFormat format) {
        Objects.requireNonNull(format, "Null format");
        if (format.isJson()) {
            return jsonString();
        }
        StringBuilder sb = new StringBuilder();
        if (format.isBrief()) {
            sb.append(main ? "SVS" : "Additional");
            if (hasApplication()) {
                sb.append(" [").append(application()).append("]");
            }
            sb.append(": ");
            sb.append(summary());
            OptionalDouble optional = optPixelSize();
            if (optional.isPresent()) {
                sb.append(", ").append(optional.getAsDouble()).append(" microns/pixel");
            }
            return sb.toString();
        }
        sb.append(main ? "Main image" : "Additional image");
        sb.append("%nSummary:%n  %s%n".formatted(summary()));
        sb.append("Attributes:");
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            sb.append("%n  %s = %s".formatted(entry.getKey(), entry.getValue()));
        }
        if (hasPixelSize()) {
            sb.append("%nPixel size: ".formatted());
            try {
                sb.append(pixelSize());
            } catch (TiffException e) {
                sb.append("format error: ").append(e.getMessage());
            }
        }
        sb.append("%nRaw text:".formatted());
        for (String line : text) {
            // - includes information about the application
            sb.append("%n  %s".formatted(line));
        }
        return sb.toString();
    }

    public static SvsDescription findMainDescription(List<? extends TiffIFD> ifds) {
        Objects.requireNonNull(ifds, "Null IFDs");
        // Note: the detailed SVS specification is always included (as ImageDescription tag)
        // in the first image (#0) with maximal resolution and partially repeated in the thumbnail image (#1).
        // The label and macro images usually contain reduced ImageDescription.
        for (TiffIFD ifd : ifds) {
            if (ifd != null) {
                // - just in case (should not happen)
                TagDescription description = ifd.getDescription();
                if (description instanceof SvsDescription svs && svs.isMain()) {
                    return svs;
                    // - returning the first detailed SVS description (with MPP attribute)
                }
            }
        }
        return null;
    }

    //TODO!!
    public static class Builder {
        private final List<String> text = new ArrayList<>();
        private final Map<String, String> attributes = new LinkedHashMap<>();
        private String application = "";
        private String summary = "";

        private boolean main = false;

        public Builder() {
        }

        public SvsDescription build() {
            return new SvsDescription(this);
        }

        private String buildDescription() {
            return "";
        }
    }
}
