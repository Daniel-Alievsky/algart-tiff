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

package net.algart.matrices.tiff;

import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.Location;

import java.io.IOException;

public class TiffReaderWriterScifioTest {
    private static void checkContext(TiffReader reader) {
        long t1 = System.nanoTime();
        Object scifio = reader.scifio();
        long t2 = System.nanoTime();
        if (reader.scifio() != scifio) {
            throw new AssertionError();
        }
        long t3 = System.nanoTime();
        System.out.printf("TiffReader.scifio(): %.3f mcs, %.3f mcs: %s%n", (t2 - t1) * 1e-3, (t3 - t2) * 1e-3, scifio);
    }

    private static void checkContext(TiffWriter writer) {
        long t1 = System.nanoTime();
        Object scifio = writer.scifio();
        long t2 = System.nanoTime();
        if (writer.scifio() != scifio) {
            throw new AssertionError();
        }
        long t3 = System.nanoTime();
        System.out.printf("TiffWriter.scifio(): %.3f mcs, %.3f mcs: %s%n", (t2 - t1) * 1e-3, (t3 - t2) * 1e-3, scifio);
    }

    public static void main(String[] args) throws IOException {
        for (int test = 1; test <= 10; test++) {
            System.out.printf("%nTest %d%n", test);

            DataHandle<?> bytesHandle = TiffIO.getBytesHandle(new BytesLocation(10));
            TiffReader reader = new TiffReader(bytesHandle, TiffOpenMode.NO_CHECKS);
            checkContext(reader);
            Context context = TiffReader.newSCIFIOContext();
            reader.setContext(context);
            checkContext(reader);
            reader.setContext(context);
            checkContext(reader);
            reader.setContext(null);
            checkContext(reader);

            TiffWriter writer = new TiffWriter(bytesHandle);
            checkContext(writer);
            writer.setContext(context);
            checkContext(writer);
            writer.setContext(context);
            checkContext(writer);
            writer.setContext(null);
            checkContext(writer);
        }
    }
}
