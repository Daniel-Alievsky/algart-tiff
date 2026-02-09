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

package net.algart.matrices.tiff.tests.awt;

import net.algart.io.MatrixIO;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

public class AWTSimpleWriteTest {
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("    " + AWTSimpleWriteTest.class.getName()
                    + " some_image.jpg/jp2/png/bmp/... result.jpg/jp2/png/bmp/...");
            return;
        }

        final File srcFile = new File(args[0]);
        final File resultFile = new File(args[1]);

        System.out.printf("Reading %s...%n", srcFile);
        BufferedImage bi = ImageIO.read(srcFile);
        if (bi == null) {
            throw new IIOException("Unsupported image format: " + srcFile);
        }
        final String extension = MatrixIO.extension(resultFile.getName());
        // Note: extension should be used together with getImageWritersBySuffix!
        // More simple way, ImageIO.write with "formatName" argument, will not work well
        // for some formats, for example, for "jp2" suffix (JPEG-2000)
        System.out.printf("%nRegistered writers for %s:%n", extension);
        final Iterator<ImageWriter> writers = ImageIO.getImageWritersBySuffix(extension);
        final ImageWriter writer = writers.hasNext() ? writers.next() : null;
        System.out.println(writer != null ? writer : "No writers");
        while (writers.hasNext()) {
            System.out.println(writers.next());
        }
        if (writer != null) {
            System.out.printf("%nWriting image into %s...%n", resultFile);
            ImageOutputStream ios = ImageIO.createImageOutputStream(resultFile);
            writer.setOutput(ios);
            writer.write(bi);
            ios.close();
        }
    }
}
