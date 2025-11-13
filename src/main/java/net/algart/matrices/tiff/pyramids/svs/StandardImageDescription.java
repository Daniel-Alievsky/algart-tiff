/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2017-2025 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import java.util.*;

class StandardImageDescription extends SVSImageDescription {
    private static final String MICRON_PER_PIXEL_ATTRIBUTE = "MPP";
    private static final String MAGNIFICATION_ATTRIBUTE = "AppMag";
    private static final String LEFT_ATTRIBUTE = "Left";
    private static final String TOP_ATTRIBUTE = "Top";
    private static final String ADDED_COMMON_IMAGE_INFO_ATTRIBUTE = "CommonInfo";

    private static final Set<String> IMPORTANT = Set.of("ScanScope ID", "Date", "Time");

    StandardImageDescription(String imageDescriptionTagValue) {
        if (imageDescriptionTagValue != null) {
            imageDescriptionTagValue = imageDescriptionTagValue.trim();
            if (!imageDescriptionTagValue.isEmpty()) {
                for (String line : imageDescriptionTagValue.split("\\n")) {
                    line = line.trim();
                    this.text.add(line);
                    if (line.contains("|")) {
                        final String[] records = line.split("[|]");
                        if (records.length >= 1) {
                            // check to be on the safe side
                            attributes.put(ADDED_COMMON_IMAGE_INFO_ATTRIBUTE,
                                    new Attribute(ADDED_COMMON_IMAGE_INFO_ATTRIBUTE, records[0]));
                        }
                        for (int i = 1; i < records.length; i++) {
                            final String[] record = records[i].trim().split("[=]");
                            if (record.length == 2) {
                                final String name = record[0].trim();
                                final String value = record[1].trim();
                                if (!attributes.containsKey(name)) {
                                    attributes.put(name, new Attribute(name, value));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Override
    public String subFormatTitle() {
        return "Aperio";
    }

    @Override
    public Set<String> importantAttributeNames() {
        return IMPORTANT;
    }

    @Override
    public List<String> importantTextAttributes() {
        return text.size() > 1 ? Collections.singletonList(text.get(0)) : Collections.<String>emptyList();
    }

    public boolean isPixelSizeSupported() {
        return attributes.containsKey(MICRON_PER_PIXEL_ATTRIBUTE);
    }

    public boolean isSVSDescription() {
        return isPixelSizeSupported();
    }

    public double pixelSize() throws TiffException {
        final Attribute attribute = attributes.get(MICRON_PER_PIXEL_ATTRIBUTE);
        if (attribute == null) {
            throw new TiffException("Image description does not contain \""
                    + MICRON_PER_PIXEL_ATTRIBUTE + "\" attribute");
        }
        final double result;
        try {
            result = Double.parseDouble(attribute.value());
        } catch (NumberFormatException e) {
            throw new TiffException("Image description contains invalid pixel size attribute: " + attribute, e);
        }
        if (result <= 0.0) {
            throw new TiffException("Image description contains negative pixel size attribute: " + attribute);
        }
        return result;
    }

    public boolean isMagnificationSupported() {
        return attributes.containsKey(MAGNIFICATION_ATTRIBUTE);
    }

    public double magnification() throws TiffException {
        final Attribute attribute = attributes.get(MAGNIFICATION_ATTRIBUTE);
        if (attribute == null) {
            throw new TiffException("Image description does not contain magnification attribute");
        }
        try {
            return Double.parseDouble(attribute.value());
        } catch (NumberFormatException e) {
            throw new TiffException("Image description contains invalid magnification attribute: " + attribute, e);
        }
    }

    public boolean isGeometrySupported() {
        return isPixelSizeSupported()
                // - without pixel size we have not enough information to detect image position at the whole slide
                && attributes.containsKey(LEFT_ATTRIBUTE)
                && attributes.containsKey(TOP_ATTRIBUTE);
    }

    public double imageOnSlideLeftInMicronsAxisRightward() throws TiffException {
        final Attribute attribute = attributes.get(LEFT_ATTRIBUTE);
        if (attribute == null) {
            throw new TiffException("Image description does not contain Left attribute");
        }
        try {
            return Double.parseDouble(attribute.value()) * 1000.0;
            // mm to microns
        } catch (NumberFormatException e) {
            throw new TiffException("Image description contains invalid Left attribute: " + attribute, e);
        }
    }

    public double imageOnSlideTopInMicronsAxisUpward() throws TiffException {
        final Attribute attribute = attributes.get(TOP_ATTRIBUTE);
        if (attribute == null) {
            throw new TiffException("Image description does not contain Top attribute");
        }
        try {
            return Double.parseDouble(attribute.value()) * 1000.0;
            // mm to microns
        } catch (NumberFormatException e) {
            throw new TiffException("Image description contains invalid Top attribute: " + attribute, e);
        }
    }
}
