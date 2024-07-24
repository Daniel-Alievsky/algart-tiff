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

import java.io.ByteArrayOutputStream;
import java.util.Objects;

public class PackBitsCodec implements TiffCodec {
    @Override
    public byte[] compress(final byte[] data, final Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        return packImage(data,
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

    public static byte[] packImage(byte[] data, int width, int height, int numberOfChannels, int bitsPerSample) {
        Objects.requireNonNull(data, "Null data");
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        final int bitsPerPixel = numberOfChannels * bitsPerSample;
        final int bytesPerRow = (bitsPerPixel * width + 7) / 8;
        final int bufSize = (bytesPerRow + (bytesPerRow + 127) / 128);
        final byte[] buffer = new byte[bufSize];
        for (int i = 0, disp = 0; i < height; i++) {
            final int packedLength = packBytes(buffer, data, disp, bytesPerRow);
            disp += bytesPerRow;
            stream.write(buffer, 0, packedLength);
        }
        return stream.toByteArray();
    }

    public static int packBytes(byte[] dest, byte[] src, int srcPos, int numberOfUnpackedBytes) {
        int destPos = 0;
        final int srcPosMax = srcPos + numberOfUnpackedBytes - 1;
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

    public static int unpackBytes(byte[] dest, byte[] src, int numberOfPackedBytes) {
        if (numberOfPackedBytes < 0 || numberOfPackedBytes > src.length) {
            throw new IllegalArgumentException("Invalid numberOfPackedBytes: " + numberOfPackedBytes);
        }
        int srcPos = 0;
        int destPos = 0;
        int maxSrcPos = numberOfPackedBytes - 1;

        while (destPos < dest.length && srcPos < maxSrcPos) {
            // Note: if srcPos == numberOfPackedBytes - 1 now, there is no sense to continue:
            // yes, we can read this byte, but we cannot copy anything else (b >= 0)
            // and cannot read the repeater (b < 0).
            final byte b = src[srcPos++];
            if (b >= 0) {
                // 0 <= b <= 127
                final int n = (int) b + 1;
                if (srcPos + n > numberOfPackedBytes || destPos + n > dest.length) {
                    // - probably invalid data;
                    // we do not try to copy only a portion of data, instead, we skip all this block
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
            }
            // Else we have no-operation code: 128
            // Note that some codecs, for example, TIFFPackBitsCompressor in Java API,
            // increase srcPos 2nd time in this case (and skip the next byte).
            // For comparison, TwelveMonkey library does not increase srcPos here.
        }
        return destPos;
    }
}
