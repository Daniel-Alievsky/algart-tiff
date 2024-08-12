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

package net.algart.matrices.tiff.tests.scifio;

import io.scif.codec.CodecOptions;
import net.algart.matrices.tiff.codecs.JPEG2000Codec;
import net.algart.matrices.tiff.codecs.TiffCodec;

import java.awt.image.ColorModel;

public class CodecOptionsTest {
    public static void main(String[] args) {
        TiffCodec.Options options = new JPEG2000Codec.JPEG2000Options()
                .setColorModel(ColorModel.getRGBdefault())
                .setLossless(false)
                .setBitsPerSample(1)
                .setHeight(2048)
                .setInterleaved(true);
        System.out.println(options);
        final CodecOptions oldStyleOptions = options.toScifioStyleOptions(CodecOptions.class);
        System.out.println("Old-style options: " + oldStyleOptions);
        System.out.println("  width=" + oldStyleOptions.width);
        System.out.println("  height=" + oldStyleOptions.height);
        System.out.println("  channels=" + oldStyleOptions.channels);
        System.out.println("  bitsPerSample=" + oldStyleOptions.bitsPerSample);
        System.out.println("  littleEndian=" + oldStyleOptions.littleEndian);
        System.out.println("  interleaved=" + oldStyleOptions.interleaved);
        System.out.println("  signed=" + oldStyleOptions.signed);
        System.out.println("  tileWidth=" + oldStyleOptions.tileWidth);
        System.out.println("  tileHeight=" + oldStyleOptions.tileHeight);
        System.out.println("  maxBytes=" + oldStyleOptions.maxBytes);
        System.out.println("  lossless=" + oldStyleOptions.lossless);
        System.out.println("  colorModel=" + oldStyleOptions.colorModel);
        System.out.println("  quality=" + oldStyleOptions.quality);
        options = new JPEG2000Codec.JPEG2000Options();
        options.setToScifioStyleOptions(oldStyleOptions);
        System.out.println("Back to Options class");
        System.out.println(options);
    }
}
