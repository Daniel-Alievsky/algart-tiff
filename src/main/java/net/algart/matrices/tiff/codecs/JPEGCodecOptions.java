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

package net.algart.matrices.tiff.codecs;

import io.scif.codec.CodecOptions;
import net.algart.matrices.tiff.TiffPhotometricInterpretation;

import java.util.Objects;

public class JPEGCodecOptions extends CodecOptions {
    /**
     * Value of TIFF tag PhotometricInterpretation (READ/WRITE).
     */
    private TiffPhotometricInterpretation photometricInterpretation = TiffPhotometricInterpretation.Y_CB_CR;
    /**
     * Value of TIFF tag YCbCrSubSampling (READ).
     */
    private int[] yCbCrSubsampling = {2, 2};

    private JPEGCodecOptions(CodecOptions options) {
        super(options);
        if (options instanceof JPEGCodecOptions extended) {
            this.yCbCrSubsampling = extended.yCbCrSubsampling == null ? null : extended.yCbCrSubsampling.clone();
        }
        if (options == null || options.quality == 0.0) {
            // - 0.0 means that it was probably not specified
            this.quality = 1.0;
            // - our JPEGCodec class sets the quality, and we MUST specify correct default value,
            // if it was not specified yet via usual CodecOptions object
            // (because default 0.0 value will lead to VERY bad quality)
        }
    }

    public static JPEGCodecOptions getDefaultOptions(CodecOptions options) {
        return new JPEGCodecOptions(options);
    }

    public TiffPhotometricInterpretation getPhotometricInterpretation() {
        return photometricInterpretation;
    }

    public JPEGCodecOptions setPhotometricInterpretation(
            TiffPhotometricInterpretation photometricInterpretation) {
        this.photometricInterpretation = Objects.requireNonNull(photometricInterpretation,
                "Null photometricInterpretation");
        return this;
    }

    public int[] getYCbCrSubsampling() {
        return yCbCrSubsampling.clone();
    }

    public JPEGCodecOptions setYCbCrSubsampling(int[] yCbCrSubsampling) {
        this.yCbCrSubsampling = Objects.requireNonNull(yCbCrSubsampling, "Null yCbCrSubsampling").clone();
        return this;
    }
}
