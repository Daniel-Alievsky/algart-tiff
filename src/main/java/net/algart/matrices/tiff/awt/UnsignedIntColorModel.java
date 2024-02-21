/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2024 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import java.awt.*;
import java.awt.image.*;

/**
 * ColorModel that handles unsigned 32 bit data.
 */
public class UnsignedIntColorModel extends ColorModel {
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

    // -- Fields --

    private final int nChannels;

    private final ComponentColorModel helper;

    // -- Constructors --

    public UnsignedIntColorModel(int pixelBits, int dataType, int nChannels) {
        super(pixelBits,
                makeBitArray(nChannels, pixelBits),
                AWTImages.makeColorSpace(nChannels),
                nChannels == 4,
                false,
                Transparency.TRANSLUCENT,
                dataType);

        helper = new ComponentColorModel(AWTImages.makeColorSpace(nChannels),
                nChannels == 4, false, Transparency.TRANSLUCENT, dataType);

        this.nChannels = nChannels;
    }

    // -- ColorModel API methods --

    @Override
    public synchronized Object getDataElements(int rgb, Object pixel) {
        return helper.getDataElements(rgb, pixel);
    }

    @Override
    public boolean isCompatibleRaster(Raster raster) {
        return raster.getNumBands() == getNumComponents() && raster
                .getTransferType() == getTransferType();
    }

    @Override
    public WritableRaster createCompatibleWritableRaster(int w, int h) {
        final int[] bandOffsets = new int[nChannels];
        for (int i = 0; i < nChannels; i++)
            bandOffsets[i] = i;

        final SampleModel m = new ComponentSampleModel(DataBuffer.TYPE_INT, w, h,
                nChannels, w * nChannels, bandOffsets);
        final DataBuffer db = new DataBufferInt(w * h, nChannels);
        return Raster.createWritableRaster(m, db, null);
    }

    @Override
    public int getAlpha(int pixel) {
        return getComponent(pixel);
    }

    @Override
    public int getBlue(int pixel) {
        return getComponent(pixel);
    }

    @Override
    public int getGreen(int pixel) {
        return getComponent(pixel);
    }

    @Override
    public int getRed(int pixel) {
        return getComponent(pixel);
    }

    @Override
    public int getAlpha(Object data) {
        final int[] i;
        if (data instanceof int[] && (i = (int[]) data).length == 4) {
            if (i.length == 1) return getAlpha(i[0]);
            return getAlpha(i[0]);
        }
        return Integer.MAX_VALUE;
    }

    @Override
    public int getRed(Object data) {
        if (data instanceof int[]) {
            final int[] i = (int[]) data;
            if (i.length == 1) return getRed(i[0]);
            return getRed(i.length != 4 ? i[0] : i[1]);
        }
        return Integer.MAX_VALUE;
    }

    @Override
    public int getGreen(Object data) {
        if (data instanceof int[]) {
            final int[] i = (int[]) data;
            if (i.length == 1) return getGreen(i[0]);
            return getGreen(i.length != 4 ? i[1] : i[2]);
        }
        return Integer.MAX_VALUE;
    }

    @Override
    public int getBlue(Object data) {
        if (data instanceof int[]) {
            final int[] i = (int[]) data;
            if (i.length == 1) return getBlue(i[0]);
            return getBlue(i[i.length - 1]);
        }
        return Integer.MAX_VALUE;
    }

    // -- Helper methods --

    private int getComponent(int pixel) {
        final long v = pixel & 0xffffffffL;
        double f = v / (Math.pow(2, 32) - 1);
        f *= 255;
        return (int) f;
    }

    private static int[] makeBitArray(int nChannels, int nBits) {
        final int[] bits = new int[nChannels];
        for (int i = 0; i < bits.length; i++) {
            bits[i] = nBits;
        }
        return bits;
    }

}
