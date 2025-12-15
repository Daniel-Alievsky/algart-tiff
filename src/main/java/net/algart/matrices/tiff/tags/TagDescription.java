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

public class TagDescription {
    public static final TagDescription EMPTY = new TagDescription(null);

    final String description;

    protected TagDescription(String description) {
        this.description = description;
    }

    public static TagDescription of(String imageDescription) {
        if (imageDescription == null) {
            return EMPTY;
        }
        if (SvsDescription.isSvs(imageDescription)) {
            return new SvsDescription(imageDescription);
        }
        return new TagDescription(imageDescription);
    }


    public String description() {
        return description;
    }

    public boolean isPresent() {
        return description != null;
    }

    /**
     * Returns {@code true} if and only if this description is an instance of {@link SvsDescription}.
     *
     * @return whether this description is an SVS image description.
     */
    public boolean isSvs() {
        return this instanceof SvsDescription;
    }

    public String formatName() {
        return formatName(true);
    }

    public String formatName(boolean pretty) {
        return pretty ? "Additional description" : "additional";
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
        return "Common TIFF";
    }

    public boolean hasAttributes() {
        return !attributes().isEmpty();
    }

    public Map<String, String> attributes() {
        return Collections.emptyMap();
    }

    public Set<String> humanReadableAttributeNames() {
        return Collections.emptySet();
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
     * @throws TiffException        if the attribute is missing or contains an invalid number.
     */
    public double reqDouble(String name) throws TiffException {
        Objects.requireNonNull(name, "Null attribute name");
        final String value = attributes().get(name);
        if (value == null) {
            throw new TiffException("SVS image description does not contain \"" + name + "\" attribute");
        }
        try {
            return parseJsonDouble(value);
        } catch (NumberFormatException e) {
            throw new TiffException("SVS image description contains invalid \"" + name + "\" attribute: " +
                    value + " is not a number", e);
        }
    }

    /**
     * An analog of {@link #reqDouble(String)}, which returns {@code OptionalDouble.empty()}
     * instead of throwing {@link TiffException}.
     *
     * @param name name of the SVS attribute to read (must not be {@code null}).
     * @return the parsed {@code double} value of the specified attribute or {@code OptionalDouble.empty()}.
     * @throws NullPointerException if {@code name} is {@code null}.
     */
    public OptionalDouble optDouble(String name) {
        Objects.requireNonNull(name, "Null attribute name");
        final String value = attributes().get(name);
        if (value == null) {
            return OptionalDouble.empty();
        }
        try {
            return OptionalDouble.of(parseJsonDouble(value));
        } catch (NumberFormatException e) {
            return OptionalDouble.empty();
        }
    }

    public String jsonString() {
        return "{\n" +
                "  \"application\": \"" + TiffIFD.escapeJsonString(application()) + "\"\n" +
                "}";
    }

    @Override
    public String toString() {
        return description == null ?
                "No ImageDescription tag" :
                (description.isBlank() ? "Empty" : "Common") + " unparsed TIFF image description";
    }

    public String toString(TiffIFD.StringFormat format) {
        Objects.requireNonNull(format, "Null format");
        return format.isJson() ? jsonString() : toString();
    }

    private static double parseJsonDouble(String s) throws NumberFormatException {
        double result = Double.parseDouble(s);
        if (!Double.isFinite(result)) {
            throw new NumberFormatException("unallowed value");
        }
        return result;
    }
}
