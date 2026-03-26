/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2026 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import net.algart.matrices.tiff.TiffIFD;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public enum TagPhotometric {
    WHITE_IS_ZERO(TiffIFD.PHOTOMETRIC_INTERPRETATION_WHITE_IS_ZERO, "White-is-zero"),
    BLACK_IS_ZERO(TiffIFD.PHOTOMETRIC_INTERPRETATION_BLACK_IS_ZERO, "Black-is-zero"),
    RGB(TiffIFD.PHOTOMETRIC_INTERPRETATION_RGB, "RGB"),
    RGB_PALETTE(TiffIFD.PHOTOMETRIC_INTERPRETATION_RGB_PALETTE, "RGB palette"),
    TRANSPARENCY_MASK(TiffIFD.PHOTOMETRIC_INTERPRETATION_TRANSPARENCY_MASK, "Transparency mask"),
    CMYK(TiffIFD.PHOTOMETRIC_INTERPRETATION_CMYK, "CMYK"),
    Y_CB_CR(TiffIFD.PHOTOMETRIC_INTERPRETATION_Y_CB_CR, "YCbCr"),
    CIE_LAB(TiffIFD.PHOTOMETRIC_INTERPRETATION_CIE_LAB, "CIELAB"),
    ICC_LAB(TiffIFD.PHOTOMETRIC_INTERPRETATION_ICC_LAB, "ICCLAB"),
    CFA_ARRAY(32803, "Color filter array"),
    LINEAR_RAW(34892, "Linear raw");

    private final int code;
    private final String name;

    private static final Map<Integer, TagPhotometric> CODE_LOOKUP =
            Arrays.stream(values()).filter(v -> v.code >= 0)
                    .collect(Collectors.toMap(TagPhotometric::code, v -> v));

    TagPhotometric(int code, String name) {
        this.code = code;
        this.name = name;
    }

    public static Optional<TagPhotometric> fromCode(int code) {
        return Optional.ofNullable(CODE_LOOKUP.get(code));
    }

    public int code() {
        return code;
    }

    public String prettyName() {
        return name;
    }

    public boolean isInvertedBrightness() {
        return this == WHITE_IS_ZERO || this == CMYK;
    }

    public static boolean isInvertedBrightness(int code) {
        return code == TiffIFD.PHOTOMETRIC_INTERPRETATION_WHITE_IS_ZERO ||
                code == TiffIFD.PHOTOMETRIC_INTERPRETATION_CMYK;
    }

    public boolean isCMYK() {
        return this == CMYK;
    }

    /**
     * Returns true if this photometric interpretation can be read by {@link net.algart.matrices.tiff.TiffReader}
     * and then rendered directly via standard Java AWT/Swing tools.
     *
     * <p>This includes grayscale ({@link #WHITE_IS_ZERO} and {@link #BLACK_IS_ZERO}), {@link #RGB},
     * {@link #TRANSPARENCY_MASK} for bit masks and {@link #Y_CB_CR} for YCbCr color space.</p>
     *
     * <p>Note: YCbCr is included because {@link net.algart.matrices.tiff.TiffReader} usually converts
     * such TIFF images into a standard RGB form automatically.</p>
     * * <p>Note: {@link #WHITE_IS_ZERO} will be rendered correctly only if you set
     * {@link net.algart.matrices.tiff.TiffReader#setCorrectColors(boolean)} flag.</p>
     *
     * @return true if the image is ready for simple rendering.
     */
    public boolean isSimplyRenderable() {
        return switch (code) {
            case TiffIFD.PHOTOMETRIC_INTERPRETATION_WHITE_IS_ZERO,
                 TiffIFD.PHOTOMETRIC_INTERPRETATION_BLACK_IS_ZERO,
                 TiffIFD.PHOTOMETRIC_INTERPRETATION_RGB,
                 TiffIFD.PHOTOMETRIC_INTERPRETATION_TRANSPARENCY_MASK,
                 TiffIFD.PHOTOMETRIC_INTERPRETATION_Y_CB_CR -> true;
            default -> false;
        };
    }

    public boolean isIndexed() {
        return this == RGB_PALETTE || this == CFA_ARRAY;
    }

    @Override
    public String toString() {
        return "%s (%d)".formatted(name, code);
    }
}
