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

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ImageWriterSpi;
import java.util.Arrays;
import java.util.Iterator;

public class AWTImageFormatsList {
    public static String information() {
        StringBuilder sb = new StringBuilder();
        final IIORegistry registry = IIORegistry.getDefaultInstance();
        sb.append(String.format("Available reading file extensions (suffixes):%n"));
        sb.append(String.format("    %s%n", toString(ImageIO.getReaderFileSuffixes())));
        sb.append(String.format("Available writing file extensions (suffixes):%n"));
        sb.append(String.format("    %s%n", toString(ImageIO.getWriterFileSuffixes())));
        sb.append(String.format("%n"));
        sb.append(String.format("Available reading format names (for Java developers):%n"));
        sb.append(String.format("    %s%n", toString(ImageIO.getReaderFormatNames())));
        sb.append(String.format("Available writing format names (for Java developers):%n"));
        sb.append(String.format("    %s%n", toString(ImageIO.getWriterFormatNames())));
        sb.append(String.format("%n"));
        sb.append(String.format("Available readers:%n"));
        final Iterator<ImageReaderSpi> readers = registry.getServiceProviders(ImageReaderSpi.class, true);
        while (readers.hasNext()) {
            ImageReaderSpi spi = readers.next();
            sb.append(String.format("%s: suffixes %s, format names %s%n", spi,
                    Arrays.toString(spi.getFileSuffixes()),
                    Arrays.toString(spi.getFormatNames())));
        }
        sb.append(String.format("%n"));
        sb.append(String.format("Available writers:%n"));
        Iterator<ImageWriterSpi> writers = registry.getServiceProviders(ImageWriterSpi.class, true);
        while (writers.hasNext()) {
            ImageWriterSpi spi = writers.next();
            sb.append(String.format("%s: suffixes %s, format names %s%n", spi,
                    Arrays.toString(spi.getFileSuffixes()),
                    Arrays.toString(spi.getFormatNames())));
        }
        return sb.toString();
    }

    private static String toString(String[] names) {
        names = names.clone();
        Arrays.sort(names, String::compareToIgnoreCase);
        return String.join(", ", names);
    }

    public static void main(String... args) {
        System.out.println(information());
        if (args.length < 1) {
            System.out.printf("Usage: %s format_name%n%n", AWTImageFormatsList.class.getName());
            return;
        }
        var readers = ImageIO.getImageReadersByFormatName(args[0]);
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            System.out.println("Format reader: " + reader);
        }
        var writers = ImageIO.getImageWritersByFormatName(args[0]);
        while (writers.hasNext()) {
            ImageWriter writer = writers.next();
            System.out.println("Format writer: " + writer);
        }
        System.out.println();
    }
}
