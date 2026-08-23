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
        final TagCompression c = ifd.optCompression().orElse(null);
        if (c != requiredCompression) {
            throw new AssertionError("Invalid compression " + c);
        }
        final int compressionCode = ifd.getCompressionCode();
        if (compressionCode != requiredCode) {
            throw new AssertionError("Invalid code = " + compressionCode);
        }
        if (ifd.hasTag(Tags.COMPRESSION) != requiredContains) {
            throw new AssertionError("Invalid containsKey() = " + ifd.hasTag(Tags.COMPRESSION));
        }
        if (c != null && c.isSupported() == (c.codec() == null)) {
            throw new AssertionError("isSupported: " + c.isSupported() +
                    ", but codec(): " + c.codec());
        }
        if (c != null) {
            boolean standardOrLowLevel = compressionCode <= 10 || c.isLowLevelBitsProcessing();
            if (c.isSupported() && standardOrLowLevel != (c.isLowLevelBitsProcessing() || c.isStandardOrOldJpeg())) {
                throw new AssertionError(c);
            }
            if (c.isLowLevelBitsProcessing() && c.isStandardOrOldJpeg()) {
                throw new AssertionError(c);
            }
        }
        System.out.printf("Compression: %-25s  Code: %-5d  %-45s %s%s%s%s%s%s %s%n",
                c == null ? "unknown" : c.name(),
                compressionCode,
                "\"" + ifd.compressionPrettyName() + "\"",
                c != null && c.isAWTBasedReading() ? "AWT " : "    ",
                c != null && c.isLowLevelBitsProcessing() ? "Low " : "    ",
                c != null && c.isStandardOrOldJpeg() ? "6|7 " : "    ",
                c != null && c.isJpegCodec() ? "JPEG " : "     ",
                c != null && c.isJpeg2000() ? "J2K " : "    ",
                c != null && c.isOldFormat() ? "old " : "    ",
                c == null ? "n/a" :
                        !c.isSupported() ? "NOT supported" :
                        c.isWritingSupported() ?
                        "writing supported" :
                        "writing not supported, nearest with full support: " + c.nearestWritable());
    }

    public void test() throws Exception {
        main();
    }

    public static void main(String... args) throws TiffException {
        TiffIFD ifd = TiffIFD.newInstance();
        check(ifd, null, TiffIFD.COMPRESSION_NONE, false);
        System.out.println();

        final TagCompression[] compressions = TagCompression.values();
        System.out.printf("Checking all %d compression types:%n", compressions.length);
        int lastCode = 0;
        for (TagCompression compression : compressions) {
            ifd.putCompression(compression);
            check(ifd, compression, compression.code(), true);
            if (compression.code() < lastCode) {
                System.out.printf("Violating ascending order: %d < %d%n", compression.code(), lastCode);
            }
            lastCode = compression.code();
        }
        System.out.println();
        System.out.println();

        System.out.println("**************");
        System.out.println("Custom checks:");
        ifd.putCompression(null);
        check(ifd, null, TiffIFD.COMPRESSION_NONE, false);

        ifd.putCompression(null, true);
        check(ifd, TagCompression.NONE, TiffIFD.COMPRESSION_NONE, true);

        ifd.put(Tags.COMPRESSION, 15728);
        check(ifd, null, 15728, true);

        if (TagCompression.JPEG_2000.code() != TagCompression.JPEG_2000_LOSSLESS.code()) throw new AssertionError();
        ifd.putCompression(TagCompression.JPEG_2000_LOSSLESS);
        ifd.putTileSizes(512, 512);
        // - should not remove the stored JPEG_2000_LOSSLESS
        check(ifd, TagCompression.JPEG_2000_LOSSLESS, TagCompression.JPEG_2000_LOSSLESS.code(), true);

        ifd.put(Tags.COMPRESSION, 1);
        check(ifd, TagCompression.NONE, 1, true);

        ifd.put(Tags.COMPRESSION, TagCompression.JPEG_2000.code());
        check(ifd, TagCompression.JPEG_2000_LOSSLESS, TagCompression.JPEG_2000.code(), true);

        ifd.putCompression(TagCompression.JPEG_RGB);
        ifd.putCompressionCode(TagCompression.JPEG_RGB.code());
        // - removes the stored JPEG_RGB!
        check(ifd, TagCompression.JPEG, TagCompression.JPEG.code(), true);
    }
}
