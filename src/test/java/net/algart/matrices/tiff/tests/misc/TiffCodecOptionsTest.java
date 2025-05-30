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

package net.algart.matrices.tiff.tests.misc;

import net.algart.matrices.tiff.codecs.JPEG2000Codec;
import net.algart.matrices.tiff.codecs.TiffCodec;

public class TiffCodecOptionsTest {
    public static void main(String[] args) {
        TiffCodec.Options options = new JPEG2000Codec.JPEG2000Options()
//                .setCodeBlockSize(new int[] {16, 16})
                .setBitsPerSample(1)
//                .setQuality(null)
                .setSizes(2048, 1024)
                .setLosslessCompressionLevel(0.5);
        System.out.println(options);
        System.out.println("Clone:");
        System.out.println(options.clone());
        options.setCompressionQuality(null);
        options.setLosslessCompressionLevel(null);
        System.out.println("No quality/compression level:");
        System.out.println(options);
        System.out.println("Clone:");
        System.out.println(options.clone());
    }
}
