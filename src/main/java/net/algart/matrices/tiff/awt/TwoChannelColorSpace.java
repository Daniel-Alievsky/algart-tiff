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

package net.algart.matrices.tiff.awt;

import java.awt.color.ColorSpace;

/**
 * ColorSpace for 2-channel images.
 *
 * @author Melissa Linkert
 */
public class TwoChannelColorSpace extends ColorSpace {
    // (It is placed here to avoid autocorrection by IntelliJ IDEA)
    /*
     * #%L
     * SCIFIO library for reading and converting scientific file formats.
     * %%
     * Copyright (C) 2011 - 2023 SCIFIO developers.
     * %%
     * Redistribution and use in source and binary forms, with or without
     * modification, are permitted provided that the following conditions are met:
     *
     * 1. Redistributions of source code must retain the above copyright notice,
     *    this list of conditions and the following disclaimer.
     * 2. Redistributions in binary form must reproduce the above copyright notice,
     *    this list of conditions and the following disclaimer in the documentation
     *    and/or other materials provided with the distribution.
     *
     * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
     * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
     * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
     * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
     * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
     * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
     * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
     * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
     * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
     * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
     * POSSIBILITY OF SUCH DAMAGE.
     * #L%
     */

    // -- Constants --

    public static final int CS_2C = -1;

    private static final int NUM_COMPONENTS = 2;

    // -- Constructor --

    protected TwoChannelColorSpace(int type, int components) {
        super(type, components);
    }

    // -- ColorSpace API methods --

    @Override
    public float[] fromCIEXYZ(float[] color) {
        final ColorSpace rgb = ColorSpace.getInstance(ColorSpace.CS_sRGB);
        return rgb.fromCIEXYZ(toRGB(color));
    }

    @Override
    public float[] fromRGB(float[] rgb) {
        return new float[]{rgb[0], rgb[1]};
    }

    public static ColorSpace getInstance(int colorSpace) {
        if (colorSpace == CS_2C) {
            return new TwoChannelColorSpace(ColorSpace.TYPE_2CLR, NUM_COMPONENTS);
        }
        return ColorSpace.getInstance(colorSpace);
    }

    @Override
    public String getName(int idx) {
        return idx == 0 ? "Red" : "Green";
    }

    @Override
    public int getNumComponents() {
        return NUM_COMPONENTS;
    }

    @Override
    public int getType() {
        return ColorSpace.TYPE_2CLR;
    }

    @Override
    public boolean isCS_sRGB() {
        return false;
    }

    @Override
    public float[] toCIEXYZ(float[] color) {
        final ColorSpace rgb = ColorSpace.getInstance(ColorSpace.CS_sRGB);
        return rgb.toCIEXYZ(toRGB(color));
    }

    @Override
    public float[] toRGB(float[] color) {
        return new float[]{color[0], color[1], 0};
    }

}
