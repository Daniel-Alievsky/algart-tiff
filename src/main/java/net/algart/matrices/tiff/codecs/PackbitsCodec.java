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

import net.algart.matrices.tiff.TiffException;
import org.scijava.io.handle.BytesHandle;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.Location;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Random;

public class PackbitsCodec implements TiffCodec {
    @Override
    public byte[] compress(final byte[] data, final Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        return encodeImage(data,
                options.width,
                options.height,
                options.numberOfChannels,
                options.bitsPerSample);
    }


    /**
     * The Options parameter should have the following fields set:
     * {@link Options#getMaxSizeInBytes()}  maxBytes}
     */
    @Override
    public byte[] decompress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        byte[] result = new byte[options.maxSizeInBytes];
        unpackBytes(result, data, data.length);
        return result;
    }

    private static byte[] encodeImage(
            byte[] bytes,
            int width,
            int height,
            int numberOfChannels,
            int bitsPerSample) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        final int bitsPerPixel = numberOfChannels * bitsPerSample;
        final int bytesPerRow = (bitsPerPixel * width + 7) / 8;
        final int bufSize = (bytesPerRow + (bytesPerRow + 127) / 128);
        final byte[] buffer = new byte[bufSize];
        for (int i = 0, disp = 0; i < height; i++) {
            final int packedLength = packBytes(buffer, bytes, disp, bytesPerRow);
            disp += bytesPerRow;
            stream.write(buffer, 0, packedLength);
        }
        return stream.toByteArray();
    }

    private static int packBytes(byte[] dest, byte[] src, int srcPos, int count) {
        int destPos = 0;
        final int srcPosMax = srcPos + count - 1;
        final int srcPosMaxMinus1 = srcPosMax - 1;

        while (srcPos <= srcPosMax) {
            int run = 1;
            final byte replicate = src[srcPos];
            while (run < 127 && srcPos < srcPosMax && src[srcPos] == src[srcPos + 1]) {
                run++;
                srcPos++;
            }
            if (run > 1) {
                srcPos++;
                dest[destPos++] = (byte) (-(run - 1));
                dest[destPos++] = replicate;
            }

            run = 0;
            final int saveOffset = destPos;
            while (run < 128 &&
                    ((srcPos < srcPosMax && src[srcPos] != src[srcPos + 1])
                            || (srcPos < srcPosMaxMinus1 && src[srcPos] != src[srcPos + 2]))) {
                run++;
                dest[++destPos] = src[srcPos++];
            }
            if (run > 0) {
                dest[saveOffset] = (byte) (run - 1);
                destPos++;
            }

            if (srcPos == srcPosMax) {
                if (run > 0 && run < 128) {
                    dest[saveOffset]++;
                    dest[destPos++] = src[srcPos++];
                } else {
                    dest[destPos++] = (byte) 0;
                    dest[destPos++] = src[srcPos++];
                }
            }
        }
        return destPos;
    }

    private static int unpackBytes(byte[] dest, byte[] src, int count) {
        if (count < 0 || count > src.length) {
            throw new IllegalArgumentException("Invalid count: " + count);
        }
        int srcPos = 0;
        int destPos = 0;
        int maxSrcPos = count - 1;

        while (destPos < dest.length && srcPos < maxSrcPos) {
            final byte b = src[srcPos++];
            if (b >= 0) {
                // 0 <= b <= 127
                final int n = (int) b + 1;
                if (srcPos + n > src.length || destPos + n > dest.length) {
                    break;
                }
                System.arraycopy(src, srcPos, dest, destPos, n);
                srcPos += n;
                destPos += n;
            } else if (b != -128) {
                // -127 <= n <= -1
                final byte repeat = src[srcPos++];
                final int n = Math.min(-(int) b + 1, dest.length - destPos);
                for (int i = 0; i < n; i++) {
                    dest[destPos++] = repeat;
                }
            } else {
                // No-op, do nothing
                srcPos++;
            }
        }
        return destPos;
    }

    private static byte[] oldDecompress(byte[] bytes, int maxSizeInBytes) throws IOException {
        try (DataHandle<? extends Location> in = new BytesHandle(new BytesLocation(bytes))) {
            final long fp = in.offset();
            // Adapted from the TIFF 6.0 specification, page 42.
            final ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
            int nread = 0;
            while (output.size() < maxSizeInBytes) {
                final byte n = (byte) (in.read() & 0xff);
                nread++;
                if (n >= 0) { // 0 <= n <= 127
                    byte[] b = new byte[n + 1];
                    in.read(b);
                    nread += n + 1;
                    output.write(b);
                } else if (n != -128) { // -127 <= n <= -1
                    final int len = -n + 1;
                    final byte inp = (byte) (in.read() & 0xff);
                    nread++;
                    for (int i = 0; i < len; i++) {
                        output.write(inp);
                    }
                }
            }
            if (fp + nread < in.length()) {
                in.seek(fp + nread);
            }
            return output.toByteArray();
        }
    }

    public static void main(String[] args) throws IOException {
        Random rnd = new Random();
        for (int test = 1; test <= 1000000; test++) {
            if (test % 1000 == 0) {
                System.out.printf("\rTest #%d...\r", test);
            }
            byte[] data = new byte[1000];
            boolean useSeries = rnd.nextBoolean();
            for (int k = 0; k < data.length; ) {
                byte b = (byte) rnd.nextInt();
                if (useSeries) {
                    int len = rnd.nextInt(13) == 0 ? rnd.nextInt(300) : rnd.nextInt(2);
                    for (int i = 0; i < len && k < data.length; i++) {
                        data[k++] = b;
                    }
                } else {
                    data[k++] = b;
                }
            }
            if (test == 1) {
                System.out.printf("Some example of packed array (%s series):%n%s%n%n",
                        useSeries ? "with" : "no", Arrays.toString(data));
            }
            int count = rnd.nextInt(data.length);
            final int bufSize = (count + (count + 127) / 128);
            final byte[] packed = new byte[bufSize];
            int packedLength = packBytes(packed, data, 0, count);
            if (packedLength > bufSize) {
                throw new AssertionError(packedLength + " > " + bufSize);
            }

            // Testing actual compression/decompression
            byte[] unpacked = new byte[count];
            int unpackedLength = unpackBytes(unpacked, packed, packedLength);
            if (unpackedLength != count) {
                throw new AssertionError(unpackedLength + "!=" + count);
            }
            for (int k = 0; k < count; k++) {
                if (unpacked[k] != data[k]) {
                    throw new AssertionError("unpackBytes: " + k + ": " + unpacked[k] + "!=" + data[k]);
                }
            }
            // Testing old decompression code from SCIFIO
            byte[] old = oldDecompress(Arrays.copyOf(packed, packedLength), count);
            if (old.length != count) {
                throw new AssertionError(old.length + "!=" + count);
            }
            for (int k = 0; k < count; k++) {
                if (old[k] != data[k]) {
                    throw new AssertionError("oldDecompress: " + k + ": " + old[k] + "!=" + data[k]);
                }
            }

            unpackBytes(data, data, count);
            // - exception should not occur
        }
        System.out.println("O'k           ");
    }
}
