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

package net.algart.matrices.tiff.tests.codecs;

import net.algart.matrices.tiff.codecs.PackBitsCodec;
import org.scijava.io.handle.BytesHandle;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.Location;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Random;

public class PackBitsTest {
    // Old SCIFIO code.
    // Note: for invalid data, this algorithm can generate extra "unpacked" bytes
    // (it does not check that in.read() is -1 or in.read(b) < b.length).
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
            final int numberOfUnpackedBytes = rnd.nextInt(3) == 0 ? data.length : rnd.nextInt(data.length);
            final int bufSize = (numberOfUnpackedBytes + (numberOfUnpackedBytes + 127) / 128);
            final byte[] packed = new byte[bufSize];
            int packedLength = PackBitsCodec.packBytes(packed, data, 0, numberOfUnpackedBytes);
            if (packedLength > bufSize) {
                throw new AssertionError(packedLength + " > " + bufSize);
            }

            // Testing actual compression/decompression
            byte[] unpacked = new byte[numberOfUnpackedBytes];
            int unpackedLength = PackBitsCodec.unpackBytes(unpacked, packed, packedLength);
            if (unpackedLength != numberOfUnpackedBytes) {
                throw new AssertionError(unpackedLength + "!=" + numberOfUnpackedBytes);
            }
            for (int k = 0; k < numberOfUnpackedBytes; k++) {
                if (unpacked[k] != data[k]) {
                    throw new AssertionError("unpackBytes: " + k + ": " + unpacked[k] + "!=" + data[k]);
                }
            }
            // Testing old decompression code from SCIFIO
            byte[] old = oldDecompress(Arrays.copyOf(packed, packedLength), numberOfUnpackedBytes);
            if (old.length != numberOfUnpackedBytes) {
                throw new AssertionError(old.length + "!=" + numberOfUnpackedBytes);
            }
            for (int k = 0; k < numberOfUnpackedBytes; k++) {
                if (old[k] != data[k]) {
                    throw new AssertionError("oldDecompress: " + k + ": " + old[k] + "!=" + data[k]);
                }
            }

            // Testing invalid packed data
            final int n = rnd.nextInt(3) == 0 ? data.length : rnd.nextInt(data.length);
            unpacked = new byte[data.length];
            int newUnpackedLength = PackBitsCodec.unpackBytes(unpacked, data, n);
            // - exception should not occur
            old = oldDecompress(Arrays.copyOf(data, n), unpacked.length);
            if (old.length < newUnpackedLength) {
                // - but if is possible that old.length is too large!
                throw new AssertionError("oldDecompress: " + old.length + "!=" + newUnpackedLength);
            }
            for (int k = 0; k < newUnpackedLength; k++) {
                if (old[k] != unpacked[k]) {
                    throw new AssertionError("oldDecompress: " + k + "/" + numberOfUnpackedBytes +
                            ": " + old[k] + "!=" + unpacked[k]);
                }
            }
        }
        System.out.println("O'k           ");
    }

}
