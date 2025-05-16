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

import io.scif.formats.tiff.TiffCompression;
import net.algart.matrices.tiff.tags.TagCompression;

public class TiffCompressionsTest {
    public static void main(String[] args) {
        System.out.printf("%s:%n%n", TagCompression.class.getName());
        for (TagCompression v : TagCompression.values()) {
            TiffCompression compression = TiffCompression.get(v.code());
            if (v.code() != compression.getCode()) {
                throw new AssertionError();
            }
            System.out.printf("%s (%d, \"%s\", %s); corresponding SCIFIO compression:%n  %s (%d, \"%s\")%n",
                    v, v.code(), v.prettyName(), v.codec(),
                    compression, compression.getCode(), compression.getCodecName());
        }

        System.out.printf("%n%n%s:%n%n", TiffCompression.class.getName());
        for (TiffCompression v : TiffCompression.values()) {
            TagCompression compression = TagCompression.ofOrNull(v.getCode());
            System.out.printf("%s (%d, \"%s\"); corresponding compression:%n  %s%n",
                    v, v.getCode(), v.getCodecName(), compression);
        }
    }
}
