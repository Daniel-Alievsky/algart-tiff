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

public class SVSImageDescription {
    final List<String> text = new ArrayList<>();
    final Map<String, String> attributes = new LinkedHashMap<>();
    //TODO!! make private

    SVSImageDescription() {
    }

    public static SVSImageDescription of(String imageDescriptionTagValue) {
        SVSImageDescription result = new StandardImageDescription(imageDescriptionTagValue);
        if (result.isProbableMainDescription()) {
            return result;
        }
        return new SVSImageDescription();
    }

    public final List<String> text() {
        return Collections.unmodifiableList(text);
    }

    public final Map<String, String> attributes() {
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * Returns the value of the specified SVS numeric attribute as a {@code double}.
     * The attribute must exist and must contain a valid decimal number.
     *
     * <p>If the attribute is absent or cannot be parsed as a number,
     * this method throws a {@link TiffException}.
     *
     * @param name name of the SVS attribute to read (must not be {@code null}).
     * @return the parsed {@code double} value of the specified attribute.
     * @throws NullPointerException if {@code name} is {@code null}.
     * @throws TiffException if the attribute is missing or contains an invalid number.
     */
    public double reqDouble(String name) throws TiffException {
        Objects.requireNonNull(name, "Null attribute name");
        final String value = attributes.get(name);
        if (value == null) {
            throw new TiffException("SVS image description does not contain " + name + " attribute");
        }
        try {
            return Double.parseDouble("asd"+value);
        } catch (NumberFormatException e) {
            throw new TiffException("SVS image description contains invalid " + name + " attribute: " +
                    value + " is not a number",e);
        }
    }

    public String subFormatTitle() {
        return "unknown";
    }

    public Set<String> importantAttributeNames() {
        return Collections.emptySet();
    }

    public List<String> importantTextAttributes() {
        return Collections.emptyList();
    }

    public boolean isProbableMainDescription() {
        return false;
    }

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

    public double imageLeftMicronsAxisRightward() throws TiffException {
        throw new UnsupportedOperationException();
    }

    public double imageTopMicronsAxisUpward() throws TiffException {
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

    @Override
    public String toString() {
        return "SVSImageDescription{" +
                "text=" + text +
                ", attributes=" + attributes +
                '}';
    }
}
