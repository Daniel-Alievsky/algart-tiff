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

package net.algart.matrices.tiff;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Semantic kind of image stored in a multi-image TIFF file.
 *
 * <p>This enum classifies TIFF images by their logical role in the entire TIFF.</p>
 *
 * <p>The TIFF format itself does not define such roles explicitly; they are
 * inferred from metadata, image characteristics, and format conventions.</p>
 *
 * <p>The {@link #ORDINARY} kind represents the default case: an image without
 * any special semantic role. All other kinds correspond to commonly used
 * auxiliary images such as thumbnails or overview images.</p>
 */
public enum TiffImageKind {
    /**
     * The first image in the TIFF (IFD #0).
     */
    BASE("base", null),

    /**
     * TIFF image without any special semantic role.
     *
     * <p>This includes ordinary content images such as pyramid levels,
     * single-image TIFF content, or any other images that are not classified
     * as other kinds. But the first image is recognized as {@link #BASE}.</p>
     */
    ORDINARY("ordinary", null),

    /**
     * Thumbnail image providing a small preview of the content.
     *
     * <p>Such images are typically low-resolution and are intended for quick
     * display or navigation.
     * For example, the Aperio SVS format stores thumbnails in the second IFD of the entire TIFF.</p>
     */
    THUMBNAIL("thumbnail", null),

    /**
     * Label image containing a scanned slide label or similar auxiliary information.
     *
     * <p>This image is not part of the main content or resolution pyramid and
     * usually serves documentation or identification purposes.</p>
     */
    LABEL("label", "label"),

    /**
     * Macro overview image showing the entire content at low resolution.
     *
     * <p>This image typically provides a global view of the full slide or scene
     * and is separate from the resolution pyramid. It is often referred to
     * as a "macro image" in whole-slide imaging formats.</p>
     */
    MACRO("macro", "macro");

    private final String kindName;
    private final String keyword;
    private final Pattern detectionPattern;

    public static final Pattern LABEL_DETECTION_PATTERN = LABEL.detectionPattern;
    public static final Pattern MACRO_DETECTION_PATTERN = MACRO.detectionPattern;

    TiffImageKind(String kindName, String keyword) {
        this.kindName = Objects.requireNonNull(kindName);
        this.keyword = keyword;
        this.detectionPattern = keyword == null ?
                null :
                Pattern.compile("\\b" + keyword + "\\b", Pattern.CASE_INSENSITIVE);
    }

    public String kindName() {
        return kindName;
    }

    public boolean isBase() {
        return this == BASE;
    }

    public boolean isOrdinary() {
        return this == ORDINARY || this == BASE;
    }

    public boolean isSpecial() {
        return !isOrdinary();
    }
    /**
     * Returns {@code true} if this kind is an additional image: {@link #LABEL} or {@link #MACRO}.
     *
     * @return whether this kind is an additional image.
     */
    public boolean isAdditional() {
        return this == LABEL || this == MACRO;
    }

    /**
     * Returns a keyword that may be added to {@code ImageDescription}:
     * "label" for {@link #LABEL}, "macro" for {@link #MACRO}.
     * If the image is not {@link #isAdditional() additional}, returns {@code null}.
     *
     * @return a keyword for heuristic detection of this image kind.
     */
    public String keyword() {
        return keyword;
    }

    /**
     * Returns a regular expression pattern that allows to determine whether some textual metadata
     * contains the {@link #keyword() keyword}.
     * If the image is not {@link #isAdditional() additional}, returns {@code null}.
     *
     * @return an optional detection pattern.
     */
    public Pattern detectionPattern() {
        return detectionPattern;
    }

    public static TiffImageKind ofKindName(String kindName) {
        Objects.requireNonNull(kindName, "Null kind name");
        return fromKindName(kindName).orElseThrow(
                () -> new IllegalArgumentException("Unknown kind name: \"" + kindName + "\""));
    }

    /**
     * Returns an {@link Optional} containing the {@link TiffImageKind} with the given {@link #kindName()}
     * (case-insensitive).
     * <p>If no kind with the specified name exists or if the argument is {@code null},
     * an empty optional is returned.
     *
     * @param kindName the kind name; may be {@code null}.
     * @return an optional image kind.
     */
    public static Optional<TiffImageKind> fromKindName(String kindName) {
        for (TiffImageKind kind : values()) {
            if (kind.kindName.equalsIgnoreCase(kindName)) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }
}
