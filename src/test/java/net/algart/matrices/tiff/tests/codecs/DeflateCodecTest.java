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

package net.algart.matrices.tiff.tests.codecs;

import net.algart.matrices.tiff.codecs.DeflateCodec;
import net.algart.matrices.tiff.codecs.TiffCodec;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

public class DeflateCodecTest {
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.printf("    %s source_file packed_file [level] [number_of_tests]%n",
                    DeflateCodecTest.class.getName());
            return;
        }
        final Path sourceFile = Path.of(args[0]);
        final Path packedFile = Path.of(args[1]);
        final Double level = args.length > 2 && !"null".equals(args[2]) ? Double.parseDouble(args[2]) : null;
        final int numberOfTests = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        DeflateCodec codec = new DeflateCodec();
        TiffCodec.Options options = new TiffCodec.Options();
        if (level != null) {
            options.setLosslessCompressionLevel(level);
        }
        for (int test = 1; test <= numberOfTests; test++) {
            System.out.printf("Test %d/%d...%n", test, numberOfTests);
            System.out.printf("Packing %s to %s%s...%n", sourceFile, packedFile,
                    level == null ? "" : " with level " + level);
            byte[] unpacked = Files.readAllBytes(sourceFile);
            long t1 = System.nanoTime();
            byte[] packed = codec.compress(unpacked, options);
            long t2 = System.nanoTime();
            byte[] unpackedToCheck = codec.decompress(packed, options);
            long t3 = System.nanoTime();
            System.out.printf("%d bytes packed to %d bytes%n", unpacked.length, packed.length);
            System.out.printf("Packing time: %.3f ms, %.3f MB/sec%n", (t2 - t1) * 1e-6,
                    unpacked.length / 1048576.0 / ((t2 - t1) * 1e-9));
            System.out.printf("Unpacking time: %.3f ms, %.3f MB/sec%n", (t3 - t2) * 1e-6,
                    unpacked.length / 1048576.0 / ((t3 - t2) * 1e-9));
            Files.write(packedFile, packed);
            if (!Arrays.equals(unpacked, unpackedToCheck)) {
                throw new AssertionError("Unpacking failed!");
            } else {
                System.out.println("OK");
            }
            System.out.println();
        }
    }
}
