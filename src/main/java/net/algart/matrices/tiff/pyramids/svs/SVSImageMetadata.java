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

public abstract class SVSImageMetadata {
    public record Attribute(String name, String value) {
        public Attribute {
            Objects.requireNonNull(name, "Null SVS attribute name");
            Objects.requireNonNull(value, "Null SVS attribute value");
        }
    }

    final List<String> text = new ArrayList<>();
    final Map<String, Attribute> attributes = new LinkedHashMap<>();

    SVSImageMetadata() {
    }

    public static SVSImageMetadata of(String imageDescriptionTagValue) {
        SVSImageMetadata result = new StandardImageMetadata(imageDescriptionTagValue);
        if (result.isSVSMetadata()) {
            return result;
        }
        return new EmptyImageMetadata(imageDescriptionTagValue);
    }

    public final List<String> getText() {
        return Collections.unmodifiableList(text);
    }

    public final Map<String, Attribute> getAttributes() {
        return Collections.unmodifiableMap(attributes);
    }

    public abstract String subFormatTitle();

    public abstract Set<String> importantAttributeNames();

    public abstract List<String> importantTextAttributes();

    public abstract boolean isSVSMetadata();

    public boolean isPixelSizeSupported() {
        return false;
    }

    public double pixelSize() throws TiffException {
        throw new UnsupportedOperationException();
    }

    public boolean isMagnificationSupported() {
        return false;
    }

    public double magnification() throws TiffException {
        throw new UnsupportedOperationException();
    }

    public boolean isGeometrySupported() {
        return false;
    }

    public double imageOnSlideLeftInMicronsAxisRightward() throws TiffException {
        throw new UnsupportedOperationException();
    }

    public double imageOnSlideTopInMicronsAxisUpward() throws TiffException {
        throw new UnsupportedOperationException();
    }

    /* TODO!! manually convert to JSON
    public final JsonObject toJson() {
        final JsonObjectBuilder builder = Json.createObjectBuilder();
        builder.add("text", Json.createArrayBuilder(text).build());
        final JsonObjectBuilder attributesBuilder = Json.createObjectBuilder();
        for (SVSAttribute attribute : attributes.values()) {
            attributesBuilder.add(attribute.name(), attribute.value());
        }
        builder.add("attributes", attributesBuilder.build());
        if (isPixelSizeSupported()) {
            try {
                Jsons.addDouble(builder, "pixelSize", pixelSize());
            } catch (TiffException e) {
                builder.add("pixelSize", "format error: " + e);
            }
        }
        return builder.build();
    }

    @Override
    public String toString() {
        return Jsons.toPrettyString(toJson());
    }

    */
}
