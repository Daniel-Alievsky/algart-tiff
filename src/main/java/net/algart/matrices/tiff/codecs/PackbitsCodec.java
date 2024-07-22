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
import net.algart.matrices.tiff.UnsupportedTiffFormatException;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.Location;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * This class implements packbits decompression. Compression is not yet
 * implemented.
 *
 * @author Melissa Linkert
 */
public class PackbitsCodec extends AbstractCodec {
    // (It is placed here to avoid autocorrection by IntelliJ IDEA)
    /*
     * #%L
     * SCIFIO library for reading and converting scientific file formats.
     * %%
     * Copyright (C) 2011 - 2023 SCIFIO developers.
     * %%
     * Redistribution and use in source and binary forms, with or without
     * modification, are permitted provided that the following conditions are met:
     *
     * 1. Redistributions of source code must retain the above copyright notice,
     *    this list of conditions and the following disclaimer.
     * 2. Redistributions in binary form must reproduce the above copyright notice,
     *    this list of conditions and the following disclaimer in the documentation
     *    and/or other materials provided with the distribution.
     *
     * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
     * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
     * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
     * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
     * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
     * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
     * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
     * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
     * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
     * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
     * POSSIBILITY OF SUCH DAMAGE.
     * #L%
     */
    @Override
    public byte[] compress(final byte[] data, final Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        return encode(data,
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
    public byte[] decompress(final DataHandle<Location> in, Options options) throws IOException {
        Objects.requireNonNull(in, "Null input handle");
        if (options == null) {
            options = new Options();
        }
        final long fp = in.offset();
        // Adapted from the TIFF 6.0 specification, page 42.
        final ByteArrayOutputStream output = new ByteArrayOutputStream(1024);
        int nread = 0;
        while (output.size() < options.maxSizeInBytes) {
            final byte n = (byte) (in.read() & 0xff);
            nread++;
            if (n >= 0) { // 0 <= n <= 127
                byte[] b = new byte[n + 1];
                in.read(b);
                nread += n + 1;
                output.write(b);
                b = null;
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

    private static byte[] encode(
            byte[] b,
            int width,
            int height,
            int numberOfChannels,
            int bitsPerSample) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        int bitsPerPixel = numberOfChannels * bitsPerSample;
        int bytesPerRow = (bitsPerPixel * width + 7) / 8;
        int bufSize = (bytesPerRow + (bytesPerRow + 127) / 128);
        byte[] compData = new byte[bufSize];

        for (int i = 0, off = 0; i < height; i++) {
            int bytes = packBits(b, off, bytesPerRow, compData, 0);
            off += bytesPerRow;
            stream.write(compData, 0, bytes);
        }

        return stream.toByteArray();
    }

    private static int packBits(byte[] input, int inOffset, int inCount, byte[] output, int outOffset) {
        int inMax = inOffset + inCount - 1;
        int inMaxMinus1 = inMax - 1;

        while (inOffset <= inMax) {
            int run = 1;
            byte replicate = input[inOffset];
            while (run < 127 && inOffset < inMax &&
                    input[inOffset] == input[inOffset + 1]) {
                run++;
                inOffset++;
            }
            if (run > 1) {
                inOffset++;
                output[outOffset++] = (byte) (-(run - 1));
                output[outOffset++] = replicate;
            }

            run = 0;
            int saveOffset = outOffset;
            while (run < 128 &&
                    ((inOffset < inMax &&
                            input[inOffset] != input[inOffset + 1]) ||
                            (inOffset < inMaxMinus1 &&
                                    input[inOffset] != input[inOffset + 2]))) {
                run++;
                output[++outOffset] = input[inOffset++];
            }
            if (run > 0) {
                output[saveOffset] = (byte) (run - 1);
                outOffset++;
            }

            if (inOffset == inMax) {
                if (run > 0 && run < 128) {
                    output[saveOffset]++;
                    output[outOffset++] = input[inOffset++];
                } else {
                    output[outOffset++] = (byte) 0;
                    output[outOffset++] = input[inOffset++];
                }
            }
        }

        return outOffset;
    }
}
