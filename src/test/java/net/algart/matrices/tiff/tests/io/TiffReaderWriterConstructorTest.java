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

package net.algart.matrices.tiff.tests.io;

import net.algart.matrices.tiff.TiffReader;
import net.algart.matrices.tiff.TiffWriter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;

public class TiffReaderWriterConstructorTest {
    private static void test(Path fileToRead, Path fileToWrite) throws IOException {
        System.out.println("Opening " + fileToRead + "...");
        TiffReader tiffReader = new TiffReader(fileToRead);
        // - in the case of exception, file must be closed!
        System.out.println("Closing " + fileToRead + "...");
        tiffReader.close();
        System.out.println("Creating " + fileToWrite + "...");
        TiffWriter tiffWriter = new TiffWriter(fileToWrite, true);
        // - in the case of exception, file must be closed! (but this is very improbable situation)
        System.out.println("Closing " + fileToWrite + "...");
        tiffWriter.close();
    }

    @SuppressWarnings("CallToPrintStackTrace")
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("    " + TiffReaderWriterConstructorTest.class.getName() +
                    " test-for-read.tiff test-for-write.tiff");
            return;
        }

        Path fileForRead = Path.of(args[0]);
        Path fileForWrite = Path.of(args[1]);
        try {
            test(fileForRead, fileForWrite);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.out.println("Files " + fileForRead + " and " + fileForWrite + " must be closed! " +
                    "If they exist, you can try to rename or remove them right now.");
            System.out.println("Press ENTER to exit...");
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        }
    }
}
