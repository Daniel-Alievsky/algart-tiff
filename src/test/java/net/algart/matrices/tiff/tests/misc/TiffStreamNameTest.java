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

import net.algart.matrices.tiff.TiffOpenMode;
import net.algart.matrices.tiff.TiffReader;
import org.scijava.io.handle.FileHandle;
import org.scijava.io.location.FileLocation;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TiffStreamNameTest {
    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.printf("Usage: %s tiff_file.tiff%n", TiffStreamNameTest.class.getName());
            return;
        }

        final Path file = Paths.get(args[0]);
        FileHandle handle = new FileHandle(new FileLocation(file.toFile()));
        TiffReader reader = new TiffReader(handle, TiffOpenMode.ALLOW_NON_TIFF);
        System.out.printf("Reader: %s%n", reader);
        System.out.printf("Stream name: %s%n", reader.streamName());
        reader.close();
        reader = new TiffReader(file, TiffOpenMode.ALLOW_NON_TIFF);
        System.out.printf("Reader: %s%n", reader);
        System.out.printf("Stream name: %s%n", reader.streamName());
        reader.close();
    }
}
