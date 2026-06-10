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
import net.algart.matrices.tiff.tags.TagType;
import net.algart.matrices.tiff.tags.TagValue;
import net.algart.matrices.tiff.tags.Tags;

public class TagValueTest {
    private static void printDescription(TiffIFD ifd) {
        System.out.println(ifd.toString(TiffIFD.StringFormat.NORMAL));
        System.out.println(ifd.jsonString());

        System.out.println();
    }

    private static TagValue check(TagValue value, TagType type) {
        if (value.type(false) != type) {
            throw new AssertionError("Invalid type: " + type);
        }
        return value;
    }

    public void test() throws Exception {
        main();
    }

    public static void main(String... args) throws TiffException {
        TiffIFD ifd = TiffIFD.newInstance();
        printDescription(ifd);
        ifd.put(Tags.X_RESOLUTION,
                check(TagValue.Rational.of(72, 1), TagType.RATIONAL));
        ifd.put(Tags.Y_RESOLUTION, TagValue.Rational.of(200, 3));
        printDescription(ifd);

        ifd.put(Tags.X_POSITION,
                check(TagValue.SRational.of(-1, 1000), TagType.SRATIONAL));
        ifd.put(Tags.Y_POSITION, TagValue.SRational.of(-10, -10));
        ifd.put(28157, new TagValue.SRational[] {
                TagValue.SRational.of(0, 0),
                TagValue.SRational.of(-1111111111, -222222222)});
        printDescription(ifd);

        ifd.put(10001, check(TagValue.SByte.of(-1), TagType.SBYTE));
        ifd.put(10002, new TagValue.SByte[] {TagValue.SByte.of(-1), TagValue.SByte.of(124)});
        ifd.put(10011, check(TagValue.SShort.of(-1), TagType.SSHORT));
        ifd.put(10012, new TagValue.SShort[] {TagValue.SShort.of(-1), TagValue.SShort.of(14444)});
        ifd.put(10021, check(TagValue.SLong.of(-100000000), TagType.SLONG));
        ifd.put(10022, new TagValue.SLong[] {TagValue.SLong.of(-1), TagValue.SLong.of(14444)});
        ifd.put(10031, check(TagValue.SLong8.of(-100000000000L), TagType.SLONG8));
        ifd.put(10032, new TagValue.SLong8[] {TagValue.SLong8.of(10000000000L), TagValue.SLong8.of(-1)});
        ifd.put(10041, check(TagValue.IFD.ofUnsigned32(0xFFFFFFFEL), TagType.IFD));
        ifd.put(10042, new TagValue.IFD[] {TagValue.IFD.ofUnsigned32(100000000), TagValue.IFD.of(0)});
        ifd.put(10051, check(TagValue.IFD.of(-100000000000L), TagType.IFD));
        ifd.put(10052, new TagValue.IFD[] {TagValue.IFD.of(10000000000L), TagValue.IFD.of(-1)});
        printDescription(ifd);
    }
}
