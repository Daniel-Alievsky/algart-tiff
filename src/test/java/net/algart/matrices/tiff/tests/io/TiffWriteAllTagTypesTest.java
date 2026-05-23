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

package net.algart.matrices.tiff.tests.io;

import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.TiffWriter;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagValue;
import net.algart.matrices.tiff.tags.Tags;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffWriteAllTagTypesTest {
    private final static int SIZE_X = 2000;
    private final static int SIZE_Y = 2000;
    public static final int TAG_WITH_UNKNOWN_TYPE = 33333;

    public static void main(String[] args) throws IOException {
        int startArgIndex = 0;
        boolean bigTiff = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-bigTiff")) {
            bigTiff = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 1) {
            System.out.println("Usage:");
            System.out.println("    " + TiffWriteAllTagTypesTest.class.getName() + " [-bigTiff] target.tiff");
            return;
        }
        final Path targetFile = Paths.get(args[startArgIndex]);

        System.out.println("Writing TIFF " + targetFile + "...");
        try (final TiffWriter writer = new TiffWriter(targetFile)) {
            writer.setBigTiff(bigTiff);
            writer.create();
            final TiffIFD ifd = TiffIFD.newIFD(true);
            Matrix<? extends PArray> image = makeBytes(SIZE_X, SIZE_Y, 3);
            ifd.putMatrixInformation(image);
            ifd.putCompression(TagCompression.NONE);
            ifd.put(Tags.X_RESOLUTION, TagValue.Rational.of(72, 1));
            ifd.put(Tags.Y_RESOLUTION, TagValue.Rational.of(72, 1));
            ifd.put(14301, 123456789);
            ifd.put(14302, new int[]{123, 234});
            ifd.put(14311, bigTiff ? 123456789123L : 12345);
            ifd.put(14312, new long[]{bigTiff ? 10000000123L : 100000, bigTiff ? -256 : 2222222});
            ifd.put(15701, TagValue.SRational.of(-1, 1000));
            ifd.put(15702, TagValue.SRational.of(-100, -10));
            ifd.put(15703, TagValue.Rational.of(1, 0xFFFFFFFEL));
            ifd.put(15710, new TagValue.SRational[]{
                    TagValue.SRational.of(0, 0),
                    TagValue.SRational.of(-1111111111, -222222222)});
            ifd.put(15711, new TagValue.Rational[]{
                    TagValue.Rational.of(0, 0),
                    TagValue.Rational.of(0xFFFFFFFEL, 12)});
            ifd.put(15721, TagValue.SByte.of(123));
            ifd.put(15722, new TagValue.SByte[]{TagValue.SByte.of(0), TagValue.SByte.of(-1)});
            ifd.put(15731, TagValue.SShort.of(123));
            ifd.put(15732, new TagValue.SShort[]{TagValue.SShort.of(220), TagValue.SShort.of(-1)});
            ifd.put(15741, TagValue.SLong.of(123));
            ifd.put(15742, new TagValue.SLong[]{TagValue.SLong.of(220), TagValue.SLong.of(-1)});
            ifd.put(15751, bigTiff ? TagValue.SLong8.of(123) : TagValue.SLong.of(123));
            if (bigTiff) {
                ifd.put(15752, new TagValue.SLong8[]{
                        TagValue.SLong8.of(220),
                        TagValue.SLong8.of(Long.MAX_VALUE),
                        TagValue.SLong8.of(Long.MIN_VALUE),
                        TagValue.SLong8.of(-1)});
            }
            ifd.put(16011, new double[0]);
            ifd.put(16012, 0.11);
            ifd.put(16013, new double[]{0.11, 0.12});
            ifd.put(16020, new float[0]);
            ifd.put(16021, 1.0f);
            ifd.put(16022, new float[]{1.11f, 1.12f});
            ifd.put(16023, new float[]{1.11f, 1.12f, 3});
            ifd.put(16030, new byte[0]);
            ifd.put(16031, (byte) 12);
            ifd.put(16031, new byte[]{12, 32});
            ifd.put(16031, new byte[]{12, 32, -44});
            ifd.put(16040, new short[0]);
            ifd.put(16041, (short) 255);
            ifd.put(16042, new short[]{12, 132});
            ifd.put(16043, new short[]{12, 32, 144});
            ifd.put(16050, new int[0]);
            ifd.put(16051, 10000);
            // - note: new int[1] here will be transformed to Integer while reading
            ifd.put(16052, 1000000);
            // - unusual case: int (mapped to SHORT) contains a value >65535
            ifd.put(16053, new int[]{60000, 60001});
            ifd.put(16054, new int[]{10000, 10001, 10002});
            ifd.put(16060, new long[0]);
            ifd.put(16061, 1000000L);
            // - note: new long[1] here will be transformed to Long while reading
            ifd.put(16062, new long[]{bigTiff ? -1000000 : 0, 1000001});
            ifd.put(16063, new long[]{1000000, 1000001, 1000002});
            ifd.put(17001, TagValue.IFD.of(123));
            ifd.put(17002, TagValue.IFD.ofUnsigned32(1200000000));
            ifd.put(17003, TagValue.IFD.of(0xFFFFFFFFL));
            ifd.putMultilineDescription("Hello, world!", null, "Second line");
            // - note: null value here will be transformed into "" string
            ifd.put(TAG_WITH_UNKNOWN_TYPE, new TiffIFD.UnsupportedTypeValue(3333, 110, 0));
            // - "count" in UnsupportedTypeValue should be ignored! we don't know how to write it
            ifd.put(15700, new String[]{});
            // ifd.put(15555, false); // - will lead to TiffException
            System.out.printf("Desired IFD:%n%s%n%n", ifd.toString(TiffIFD.StringFormat.DETAILED));
            writer.newFixedMap(ifd).writeMatrix(image);

            final String savedString = stripExtra(ifd).toString(TiffIFD.StringFormat.DETAILED);
            System.out.printf("Actually saved IFD (sorted):%n%s%n%n", savedString);
            final TiffIFD back = writer.existingIFD(0, false);
            String backString = back.toString(TiffIFD.StringFormat.DETAILED);
            System.out.printf("IFD loaded back from the file:%n%s%n", backString);
            backString = stripExtra(back).toString(TiffIFD.StringFormat.DETAILED);
            System.out.printf("Without extra metadata:%n%s%n", backString);
            if (!savedString.equals(backString)) {
                final Path savedPath = Path.of(targetFile + ".saved.txt");
                final Path loadedPath = Path.of(targetFile + ".loaded.txt");
                Files.writeString(savedPath, savedString);
                Files.writeString(loadedPath, backString);
                throw new AssertionError("Saved/loaded IFD mismatch! See " +
                        savedPath + " and " + loadedPath);
            }
        }
        System.out.println("Done");
    }

    private static TiffIFD stripExtra(TiffIFD ifd) {
        TiffIFD result = ifd.sorted().cleanedOfAdditionalMetadata();
        result.remove(TAG_WITH_UNKNOWN_TYPE);
        // TAG_WITH_UNKNOWN_TYPE will be changed after reading: count=0 is always written
        return result;
    }

    @SuppressWarnings("SameParameterValue")
    private static Matrix<? extends PArray> makeBytes(int sizeX, int sizeY, int numberOfChannels) {
        byte[] bytes = new byte[sizeX * sizeY * numberOfChannels];
        for (int channel = 0, disp = 0; channel < numberOfChannels; channel++) {
            for (long y = 0; y < sizeY; y++) {
                for (long x = 0; x < sizeX; x++, disp++) {
                    bytes[disp] = (byte) (Math.sqrt(x * x + y * y) * (channel + 1));
                }
            }
        }
        return Matrix.as(bytes, sizeX, sizeY, numberOfChannels);
    }
}
