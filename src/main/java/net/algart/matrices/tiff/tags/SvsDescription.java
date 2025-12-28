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
import net.algart.matrices.tiff.TiffImageKind;
import net.algart.matrices.tiff.tiles.TiffMap;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

public final class SvsDescription extends TagDescription {
    public static final String SVS_IMAGE_DESCRIPTION_PREFIX = "Aperio Image";
    public static final String SVS_TYPICAL_APPLICATION = SVS_IMAGE_DESCRIPTION_PREFIX + " Library";

    public static final String MAGNIFICATION_ATTRIBUTE = "AppMag";
    public static final String MICRON_PER_PIXEL_ATTRIBUTE = "MPP";
    public static final String LEFT_ATTRIBUTE = "Left";
    public static final String TOP_ATTRIBUTE = "Top";
    public static final String ORIGINAL_WIDTH_ATTRIBUTE = "OriginalWidth";
    public static final String ORIGINAL_HEIGHT_ATTRIBUTE = "OriginalHeight";

    public static final String SCAN_SCOPE_ID_ATTRIBUTE = "ScanScope ID";
    public static final String DATE_ATTRIBUTE = "Date";
    public static final String TIME_ATTRIBUTE = "Time";

    private static final DateTimeFormatter SVS_DATE_FORMATTER = DateTimeFormatter.ofPattern("MM/dd/yy");
    private static final DateTimeFormatter SVS_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static final Set<String> HUMAN_READABLE_ATTRIBUTES = Set.of(
            SCAN_SCOPE_ID_ATTRIBUTE, DATE_ATTRIBUTE, TIME_ATTRIBUTE);

    private final List<String> raw = new ArrayList<>();
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
        final String[] lines = description.split("\\n");
        for (String line : lines) {
            line = line.trim();
            this.raw.add(line);
            final int p = line.indexOf('|');
            final String prefix = p == -1 ? line : line.substring(0, p);
            if (!delimiterFound) {
                summary = prefix.trim();
                // - for the summary, we use the first string with the delimiter "|" or,
                // if there are NO such strings, the last among all strings
            }
            if (p >= 0) {
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
        this.application = !raw.isEmpty() ? raw.getFirst() : "";
        this.summary = summary;
        this.main = hasPixelSize();
    }

    private SvsDescription(Builder builder, TiffImageKind imageKind) {
        super(Objects.requireNonNull(builder).buildDescription(imageKind));
        this.raw.add(builder.firstLine);
        this.raw.add(builder.secondLine);
        this.attributes.putAll(builder.attributes);
        this.application = builder.application;
        this.summary = builder.actualSummary;
        this.main = hasPixelSize();
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

    /**
     * Returns the raw SVS ImageDescription lines as they were parsed.
     *
     * <p>This is a normalized, line-by-line representation of the original
     * {@code ImageDescription} value: lines are trimmed and split by newline characters.
     * The returned data is intended for diagnostics and low-level inspection,
     * not for semantic processing.</p>
     */
    public List<String> raw() {
        return Collections.unmodifiableList(raw);
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
        sb.append("  \"exists\": ").append(true).append(",\n");
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

        sb.append("  \"raw\": [\n");
        for (int i = 0, n = raw.size(); i < n; i++) {
            sb.append("    \"").append(TiffIFD.escapeJsonString(raw.get(i))).append("\"");
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
        final TiffIFD ifd = getIFD();
        if (ifd != null && ifd.hasGlobalIndex()) {
            sb.append(",\n  \"globalIndex\": ").append(ifd.getGlobalIndex());
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
        for (String line : raw) {
            // - includes information about the application
            sb.append("%n  %s".formatted(line));
        }
        return sb.toString();
    }

    public static Optional<SvsDescription> fromDescriptions(Stream<? extends TagDescription> descriptions) {
        Objects.requireNonNull(descriptions, "Null descriptions");
        // Note: the detailed SVS specification is always included (as ImageDescription tag) into
        // the first image (#0) with maximal resolution and partially repeated in the thumbnail image (#1).
        // The label and macro images usually contain reduced ImageDescription.
        return descriptions
                .filter(d -> d instanceof SvsDescription svs && svs.isMain())
                .map(d -> (SvsDescription) d)
                .findFirst();
    }

    public static Optional<SvsDescription> fromIFDs(Stream<? extends TiffIFD> ifds) {
        Objects.requireNonNull(ifds, "Null ifds");
        return fromDescriptions(ifds.filter(Objects::nonNull).map(TiffIFD::getDescription));
        // - check for null just in case
    }

    public static Optional<SvsDescription> fromMaps(Stream<? extends TiffMap> maps) {
        Objects.requireNonNull(maps, "Null maps");
        return fromDescriptions(maps.filter(Objects::nonNull).map(TiffMap::description));
        // - check for null just in case
    }

    public static Optional<String> svsCompressionName(TagCompression compression) {
        Objects.requireNonNull(compression, "Null compression");
        if (compression.isJpeg2000()) {
            return Optional.of("J2K");
        } else {
            return switch (compression) {
                case JPEG_RGB -> Optional.of("JPEG/RGB");
                case JPEG -> Optional.of("JPEG/YCbCr");
                default -> Optional.empty();
            };
        }
    }

        public static class Builder {
            // Example of ImageDescription tag:
            //   Aperio Image Library v10.0.51
            //   46920x33014 [0,100 46000x32914] (256x256) JPEG/RGB Q=30|AppMag = 20|StripeWidth = 2040|...
            private final Map<String, String> attributes = new LinkedHashMap<>();
            private String application = SVS_TYPICAL_APPLICATION;
            private boolean autoGeneratedSummary = true;
            private int baseImageDimX = 0;              // 46000
            private int baseImageDimY = 0;              // 32914
            private int imageDimX = 0;
            private int imageDimY = 0;                  // (other for other levels)
            private int scanOffsetX = 0;                // 0
            private int scanOffsetY = 0;                // 100
            private int scanDimX = 0;                   // 46920
            private int scanDimY = 0;                   // 33014
            private int tileSizeX = 0;
            private int tileSizeY = 0;                  // (256x256)
            private String compressionName = null;      // JPEG/RGB
            private Integer quality = null;             // 30
            private String summary = null;

            private String actualSummary = null;
            private String firstLine = null;
            private String secondLine = null;

            public Builder() {
            }

            public Builder application(String application) {
                this.application = Objects.requireNonNull(application);
                return this;
            }

            public Builder defaultApplication() {
                return application(SVS_TYPICAL_APPLICATION);
            }

            public Builder applicationSuffix(String suffix) {
                Objects.requireNonNull(suffix);
                return application(SVS_TYPICAL_APPLICATION + " " + suffix);
            }

            public Builder autoGeneratedSummary(boolean autoSummary) {
                this.autoGeneratedSummary = autoSummary;
                return this;
            }

            public Builder baseImageDimX(int baseImageDimX) {
                if (baseImageDimX <= 0) {
                    throw new IllegalArgumentException("Zero or negative baseImageDimX: " + baseImageDimX);
                }
                this.baseImageDimX = baseImageDimX;
                return this;
            }

            public Builder baseImageDimY(int baseImageDimY) {
                if (baseImageDimY <= 0) {
                    throw new IllegalArgumentException("Zero or negative baseImageDimY: " + baseImageDimY);
                }
                this.baseImageDimY = baseImageDimY;
                return this;
            }

            public Builder imageDimX(int imageDimX) {
                if (imageDimX <= 0) {
                    throw new IllegalArgumentException("Zero or negative imageDimX: " + imageDimX);
                }
                this.imageDimX = imageDimX;
                return this;
            }

            public Builder imageDimY(int imageDimY) {
                if (imageDimY <= 0) {
                    throw new IllegalArgumentException("Zero or negative imageDimY: " + imageDimY);
                }
                this.imageDimY = imageDimY;
                return this;
            }

            public Builder scanOffsetX(int scanOffsetX) {
                this.scanOffsetX = scanOffsetX;
                return this;
            }

            public Builder scanOffsetY(int scanOffsetY) {
                this.scanOffsetY = scanOffsetY;
                return this;
            }

            public Builder scanDimX(int scanDimX) {
                if (scanDimX <= 0) {
                    throw new IllegalArgumentException("Zero or negative scanDimX: " + scanDimX);
                }
                this.scanDimX = scanDimX;
                return this;
            }

            public Builder scanDimY(int scanDimY) {
                if (scanDimY <= 0) {
                    throw new IllegalArgumentException("Zero or negative scanDimY: " + scanDimY);
                }
                this.scanDimY = scanDimY;
                return this;
            }

            public Builder tileSizeX(int tileSizeX) {
                if (tileSizeX <= 0) {
                    throw new IllegalArgumentException("Zero or negative tileSizeX: " + tileSizeX);
                }
                this.tileSizeX = tileSizeX;
                return this;
            }

            public Builder tileSizeY(int tileSizeY) {
                if (tileSizeY <= 0) {
                    throw new IllegalArgumentException("Zero or negative tileSizeY: " + tileSizeY);
                }
                this.tileSizeY = tileSizeY;
                return this;
            }

            public Builder compressionName(String compressionName) {
                this.compressionName = compressionName;
                return this;
            }

            public Builder quality(Integer quality) {
                if (quality != null && quality < 0) {
                    throw new IllegalArgumentException("Negative quality: " + quality);
                }
                this.quality = quality;
                return this;
            }

            public Builder summary(String summary) {
                if (autoGeneratedSummary) {
                    throw new IllegalStateException("Cannot set summary when autoSummary is true");
                }
                this.summary = summary;
                return this;
            }

            public Builder updateFrom(TiffIFD ifd) throws TiffException {
                return updateFrom(ifd, true);
            }

            public Builder updateFrom(TiffIFD ifd, boolean adjustScanSizes) throws TiffException {
                Objects.requireNonNull(ifd, "Null IFD");
                imageDimX(ifd.getImageDimX());
                imageDimY(ifd.getImageDimY());
                Integer globalIndex = ifd.getGlobalIndex();
                if (globalIndex != null && globalIndex == 0) {
                    baseImageDimX = imageDimX;
                    baseImageDimY = imageDimY;
                }
                adjustMinimalScanSizes(adjustScanSizes);
                if (ifd.hasTileInformation()) {
                    tileSizeX(ifd.getTileSizeX());
                    tileSizeY(ifd.getTileSizeY());
                }
                ifd.optCompression().flatMap(SvsDescription::svsCompressionName).ifPresent(this::compressionName);
                return this;
            }

            public Builder attribute(String key, String value) {
                Objects.requireNonNull(key, "Null key");
                if (value == null) {
                    this.attributes.remove(key);
                } else {
                    this.attributes.put(key, value);
                }
                return this;
            }

            public Builder addAttributes(Map<String, String> attributes) {
                Objects.requireNonNull(attributes, "Null attributes");
                this.attributes.putAll(attributes);
                return this;
            }

            public Builder removeAttributes() {
                this.attributes.clear();
                return this;
            }

            public Builder magnification(double magnification) {
                return attribute(MAGNIFICATION_ATTRIBUTE, Double.toString(magnification));
            }

            public Builder pixelSize(double pixelSize) {
                return attribute(MICRON_PER_PIXEL_ATTRIBUTE, Double.toString(pixelSize));
            }

            public Builder left(double leftMicronsAxisRightward) {
                return attribute(LEFT_ATTRIBUTE, Double.toString(leftMicronsAxisRightward));
            }

            public Builder top(double topMicronsAxisUpward) {
                return attribute(TOP_ATTRIBUTE, Double.toString(topMicronsAxisUpward));
            }

            public Builder dateTime(LocalDateTime dateTime) {
                Objects.requireNonNull(dateTime, "Null dateTime");
                attribute(DATE_ATTRIBUTE, SVS_DATE_FORMATTER.format(dateTime));
                attribute(TIME_ATTRIBUTE, SVS_TIME_FORMATTER.format(dateTime));
                return this;
            }

            public SvsDescription build() {
                return build(TiffImageKind.ORDINARY);
            }

            public SvsDescription build(TiffImageKind imageKind) {
                Objects.requireNonNull(imageKind, "Null image imageKind");
                return new SvsDescription(this, imageKind);
            }

            private String buildDescription(TiffImageKind imageKind) {
                firstLine = application;
                String summary = buildSummary(imageKind);
                StringBuilder sb = new StringBuilder(summary == null ? "" : summary);
                if (imageKind == TiffImageKind.BASE || imageKind == TiffImageKind.THUMBNAIL) {
                    // - other levels, LABEL and MACRO images do not have attributes in Aperio SVS
                    for (Map.Entry<String, String> entry : attributes.entrySet()) {
                        sb.append("|");
                        sb.append(entry.getKey()).append(" = ").append(entry.getValue());
                    }
                }
                secondLine = sb.toString();
                return firstLine + "\n" + secondLine;
            }

            private String buildSummary(TiffImageKind imageKind) {
                if (autoGeneratedSummary) {
                    final boolean baseLevel = imageDimX == baseImageDimX && imageDimY == baseImageDimY;
                    actualSummary = switch (imageKind) {
                        case BASE, ORDINARY -> "%dx%d [%d,%d %dx%d]".formatted(scanDimX, scanDimY,
                                scanOffsetX, scanOffsetY,
                                baseImageDimX, baseImageDimY) +
                                (tileSizeX > 0 && tileSizeY > 0 ? " (%dx%d)".formatted(tileSizeX, tileSizeY) : "") +
                                (baseLevel ? "" : " -> %dx%d".formatted(imageDimX, imageDimY)) +
                                (compressionName != null ? " " + compressionName : "") +
                                (quality != null ? " Q=" + quality : "");
                        case THUMBNAIL ->
                                "%dx%d -> %dx%d".formatted(baseImageDimX, baseImageDimY, imageDimX, imageDimY);
                        case LABEL, MACRO -> "%s %dx%d".formatted(imageKind.keyword(), imageDimX, imageDimY);
                        //noinspection UnnecessaryDefault
                        default -> "";
                    };
                    if (scanDimX > 0 && scanDimY > 0) {
                        attribute(ORIGINAL_WIDTH_ATTRIBUTE, String.valueOf(scanDimX));
                        attribute(ORIGINAL_HEIGHT_ATTRIBUTE, String.valueOf(scanDimY));
                    }
                } else {
                    actualSummary = summary;
                }
                return actualSummary;
            }

            private void adjustMinimalScanSizes(boolean adjustScanSizes) {
                if (adjustScanSizes) {
                    scanDimX = Math.max(scanDimX, Math.addExact(scanOffsetX, baseImageDimX));
                    scanDimY = Math.max(scanDimY, Math.addExact(scanOffsetY, baseImageDimY));
                }
            }
        }
    }
