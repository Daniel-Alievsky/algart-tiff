/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2023 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

import io.scif.FormatException;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandleService;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

// See https://github.com/scijava/scijava-common/issues/467
public class DataHandleReadArraySpeed {
    private static void testInts(DataHandle<Location> in, DataHandle<Location> inBuffer) throws IOException {
        final int elementSize = 4;
        final int size = (int) (Math.min(in.length(), 1_000_000) / elementSize);
        int[] a = new int[size];
        int[] b = new int[size];
        long t1 = System.nanoTime();
        in.seek(0);
        for (int k = 0; k < a.length; k++) {
            a[k] = in.readInt();
        }
        long t2 = System.nanoTime();
        inBuffer.seek(0);
        for (int k = 0; k < b.length; k++) {
            b[k] = inBuffer.readInt();
        }
        long t3 = System.nanoTime();
        System.out.printf(Locale.US, "Reading %d 32-bit integers, %s: %.3f ms, %.3f MB/s%n",
                size, in.getClass(),
                (t2 - t1) * 1e-6, (size * elementSize) / 1048576.0 / ((t2 - t1) * 1e-9));
        System.out.printf(Locale.US, "Reading %d 32-bit integers, %s: %.3f ms, %.3f MB/s%n",
                size, inBuffer.getClass(),
                (t3 - t2) * 1e-6, (size * elementSize) / 1048576.0 / ((t3 - t2) * 1e-9));
    }

    private static void testBytes(DataHandle<Location> in, DataHandle<Location> inBuffer) throws IOException {
        final int elementSize = 1;
        final int size = (int) (Math.min(in.length(), 1_000_000) / elementSize);
        byte[] a = new byte[size];
        byte[] b = new byte[size];
        long t1 = System.nanoTime();
        in.seek(0);
        for (int k = 0; k < a.length; k++) {
            a[k] = in.readByte();
        }
        long t2 = System.nanoTime();
        inBuffer.seek(0);
        for (int k = 0; k < b.length; k++) {
            b[k] = inBuffer.readByte();
        }
        long t3 = System.nanoTime();
        System.out.printf(Locale.US, "Reading %d bytes, %s: %.3f ms, %.3f MB/s%n",
                size, in.getClass(),
                (t2 - t1) * 1e-6, (size * elementSize) / 1048576.0 / ((t2 - t1) * 1e-9));
        System.out.printf(Locale.US, "Reading %d bytes, %s: %.3f ms, %.3f MB/s%n",
                size, inBuffer.getClass(),
                (t3 - t2) * 1e-6, (size * elementSize) / 1048576.0 / ((t3 - t2) * 1e-9));
    }

    public static void main(String[] args) throws IOException, FormatException {
        if (args.length < 1) {
            System.out.println("Usage:");
            System.out.println("    " + DataHandleReadArraySpeed.class.getName() + " any_file");
            return;
        }

        final Location location = new FileLocation(new File(args[0]));
        for (int test = 1; test <= 5; test++) { // - helps to warm JVM
            System.out.printf("Test #%d...%n", test);
            try (Context context = new Context();
                 DataHandle<Location> in = context.getService(DataHandleService.class).create(location);
                 DataHandle<Location> inBuffer = context.getService(DataHandleService.class).readBuffer(location)) {
                testInts(in, inBuffer);
                testBytes(in, inBuffer);
            }
        }
    }
}
