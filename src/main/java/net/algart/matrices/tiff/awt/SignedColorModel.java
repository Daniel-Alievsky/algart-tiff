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


import java.awt.*;
import java.awt.image.*;
import java.util.Arrays;

/**
 * ColorModel that handles 8, 16 and 32 bits per channel signed data.
 */
public class SignedColorModel extends ColorModel {
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

    private final int pixelBits;

    private final int nChannels;

    private final ComponentColorModel helper;

    private final int max;

    // -- Constructors --

    public SignedColorModel(int pixelBits, int dataType, int nChannels) {
        super(pixelBits,
                makeBitArray(nChannels, pixelBits),
                AWTImages.makeColorSpace(nChannels),
                nChannels == 4,
                false,
                Transparency.TRANSLUCENT,
                dataType);

        int type = dataType;
        if (type == DataBuffer.TYPE_SHORT) {
            type = DataBuffer.TYPE_USHORT;
        }

        helper = new ComponentColorModel(AWTImages.makeColorSpace(nChannels),
                nChannels == 4, false, Transparency.TRANSLUCENT, type);

        this.pixelBits = pixelBits;
        this.nChannels = nChannels;

        max = (int) Math.pow(2, pixelBits) - 1;
    }

    // -- ColorModel API methods --

    @Override
    public synchronized Object getDataElements(int rgb, Object pixel) {
        return helper.getDataElements(rgb, pixel);
    }

    @Override
    public boolean isCompatibleRaster(Raster raster) {
        if (pixelBits == 16) {
            return raster.getTransferType() == DataBuffer.TYPE_SHORT;
        }
        return helper.isCompatibleRaster(raster);
    }

    @Override
    public WritableRaster createCompatibleWritableRaster(int w, int h) {
        if (pixelBits == 16) {
            final int[] bandOffsets = new int[nChannels];
            for (int i = 0; i < nChannels; i++)
                bandOffsets[i] = i;

            final SampleModel m = new ComponentSampleModel(DataBuffer.TYPE_SHORT, w,
                    h, nChannels, w * nChannels, bandOffsets);
            final DataBuffer db = new DataBufferShort(w * h, nChannels);
            return Raster.createWritableRaster(m, db, null);
        }
        return helper.createCompatibleWritableRaster(w, h);
    }

    @Override
    public int getAlpha(int pixel) {
        if (nChannels < 4) return 255;
        return rescale(pixel, max);
    }

    @Override
    public int getBlue(int pixel) {
        if (nChannels == 1) return getRed(pixel);
        return rescale(pixel, max);
    }

    @Override
    public int getGreen(int pixel) {
        if (nChannels == 1) return getRed(pixel);
        return rescale(pixel, max);
    }

    @Override
    public int getRed(int pixel) {
        return rescale(pixel, max);
    }

    @Override
    public int getAlpha(Object data) {
        if (data instanceof byte[] b) {
            if (b.length == 1) return getAlpha(b[0]);
            return rescale(b.length == 4 ? b[0] : max, max);
        } else if (data instanceof short[] s) {
            if (s.length == 1) return getAlpha(s[0]);
            return rescale(s.length == 4 ? s[0] : max, max);
        } else if (data instanceof int[] i) {
            if (i.length == 1) return getAlpha(i[0]);
            return rescale(i.length == 4 ? i[0] : max, max);
        }
        return 0;
    }

    @Override
    public int getRed(Object data) {
        if (data instanceof byte[] b) {
            if (b.length == 1) return getRed(b[0]);
            return rescale(b.length != 4 ? b[0] : b[1]);
        } else if (data instanceof short[] s) {
            if (s.length == 1) return getRed(s[0]);
            return rescale(s.length != 4 ? s[0] : s[1], max);
        } else if (data instanceof int[] i) {
            if (i.length == 1) return getRed(i[0]);
            return rescale(i.length != 4 ? i[0] : i[1], max);
        }
        return 0;
    }

    @Override
    public int getGreen(Object data) {
        if (data instanceof byte[] b) {
            if (b.length == 1) return getGreen(b[0]);
            return rescale(b.length != 4 ? b[1] : b[2]);
        } else if (data instanceof short[] s) {
            if (s.length == 1) return getGreen(s[0]);
            return rescale(s.length != 4 ? s[1] : s[2], max);
        } else if (data instanceof int[] i) {
            if (i.length == 1) return getGreen(i[0]);
            return rescale(i.length != 4 ? i[1] : i[2], max);
        }
        return 0;
    }

    @Override
    public int getBlue(Object data) {
        if (data instanceof byte[] b) {
            if (b.length == 1) return getBlue(b[0]);
            return rescale(b.length > 2 ? b[b.length - 1] : 0);
        } else if (data instanceof short[] s) {
            if (s.length == 1) return getBlue(s[0]);
            return rescale(s.length > 2 ? s[s.length - 1] : 0, max);
        } else if (data instanceof int[] i) {
            if (i.length == 1) return getBlue(i[0]);
            return rescale(i.length > 2 ? i[i.length - 1] : 0, max);
        }
        return 0;
    }

    // -- Helper methods --

    private int rescale(int value, int max) {
        float v = (float) value / (float) max;
        v *= 255;
        return rescale((int) v);
    }

    private int rescale(int value) {
        if (value < 128) {
            value += 128; // [0, 127] -> [128, 255]
        } else {
            value -= 128; // [128, 255] -> [0, 127]
        }
        return value;
    }

    private static int[] makeBitArray(int nChannels, int nBits) {
        final int[] bits = new int[nChannels];
        Arrays.fill(bits, nBits);
        return bits;
    }

}
