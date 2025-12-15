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

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.Tags;

public class TagCompressionTest {
    private static void check(
            TiffIFD ifd,
            TagCompression requiredCompression,
            int requiredCode,
            boolean requiredContains) throws TiffException {
        if (ifd.optCompression() != requiredCompression) {
            throw new AssertionError("Invalid compression " + ifd.optCompression());
        }
        if (ifd.getCompressionCode() != requiredCode) {
            throw new AssertionError("Invalid code = " + ifd.optPredictorCode());
        }
        if (ifd.containsKey(Tags.COMPRESSION) != requiredContains) {
            throw new AssertionError("Invalid containsKey() = " + ifd.containsKey(Tags.COMPRESSION));
        }
        System.out.printf("Compression: %s, code: %d, \"%s\"%n",
                ifd.optCompression(), ifd.getCompressionCode(), ifd.compressionPrettyName());
    }

    public static void main(String[] args) throws TiffException {
        TiffIFD ifd = new TiffIFD();
        check(ifd, null, TiffIFD.COMPRESSION_NONE, false);
        System.out.println();

        for (TagCompression compression : TagCompression.values()) {
            ifd.putCompression(compression);
            check(ifd, compression, compression.code(), true);
        }
        System.out.println();

        ifd.putCompression(null);
        check(ifd, null, TiffIFD.COMPRESSION_NONE, false);

        ifd.putCompression(null, true);
        check(ifd, TagCompression.NONE, TiffIFD.COMPRESSION_NONE, true);

        ifd.put(Tags.COMPRESSION, 157000);
        check(ifd, null, 157000, true);

        if (TagCompression.JPEG_2000.code() != TagCompression.JPEG_2000_LOSSLESS.code()) throw new AssertionError();
        ifd.putCompression(TagCompression.JPEG_2000_LOSSLESS);
        ifd.putTileSizes(512, 512);
        // - should not remove the stored JPEG_2000_LOSSLESS
        check(ifd, TagCompression.JPEG_2000_LOSSLESS, TagCompression.JPEG_2000_LOSSLESS.code(), true);

        ifd.put(Tags.COMPRESSION, 1);
        check(ifd, TagCompression.NONE, 1, true);

        ifd.put(Tags.COMPRESSION, TagCompression.JPEG_2000.code());
        check(ifd, TagCompression.JPEG_2000, TagCompression.JPEG_2000.code(), true);

        ifd.putCompression(TagCompression.JPEG_RGB);
        ifd.putCompressionCode(TagCompression.JPEG_RGB.code());
        // - removes the stored JPEG_RGB!
        check(ifd, TagCompression.JPEG, TagCompression.JPEG.code(), true);

    }
}
