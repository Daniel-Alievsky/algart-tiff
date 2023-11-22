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

package net.algart.matrices.tiff.tests.awt;


import org.w3c.dom.Node;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.stream.ImageInputStream;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Iterator;

public class AWTReadMetadataTest {
    public static void main(String... args) throws IOException {
        int startArgIndex = 0;
        boolean allReaders = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-allReaders")) {
            allReaders = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 1) {
            System.out.println("Usage:");
            System.out.println("    " + AWTReadMetadataTest.class.getName()
                    + " [-allReaders] some_image.jpeg/png/bmp/");
            return;
        }

        final File srcFile = new File(args[startArgIndex]);

        System.out.printf("Opening %s...%n", srcFile);
        ImageInputStream stream = ImageIO.createImageInputStream(srcFile);
        if (stream == null) {
            throw new IIOException("Can't create an ImageInputStream!");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
        while (readers.hasNext()) {
            ImageReader reader = readers.next();
            System.out.printf("%nAnalysing by reader %s:%n%n", reader);
            Class<? extends ImageReader> c = reader.getClass();
            System.out.printf("%s%n%s%n%s%n", c, c.getPackage(), c.getProtectionDomain());

            stream = ImageIO.createImageInputStream(srcFile);
            // - reset stream (some plugins move file pointer by getStreamMetadata)
            ImageReadParam param = reader.getDefaultReadParam();
            System.out.printf("Default read parameters: %s%n", param);
            reader.setInput(stream, true, false);
            IIOMetadata imageMetadata = reader.getImageMetadata(0);
            System.out.printf("Controller: %s%n", imageMetadata.getController());
            System.out.printf("Default controller: %s%n", imageMetadata.getDefaultController());
            System.out.printf("%nDefault metadata:%n%s", metadataToStringStandard(imageMetadata));
            System.out.printf("%nNative metadata:%n%s", metadataToStringNative(imageMetadata));
            IIOMetadata streamMetadata = reader.getStreamMetadata();
            System.out.printf("%nStream metadata:%n%s", metadataToString(streamMetadata,
                    IIOMetadataFormatImpl.standardMetadataFormatName));
            System.out.println();
            if (!allReaders) {
                break;
            }
        }
    }

    static String metadataToStringStandard(IIOMetadata metadata) {
        return metadataToString(metadata, IIOMetadataFormatImpl.standardMetadataFormatName);
    }

    static String metadataToStringNative(IIOMetadata metadata) {
        try {
            return metadataToString(metadata, "javax_imageio_jpeg_image_1.0");
        } catch (Exception e) {
            return ("Cannot detect native metadata javax_imageio_jpeg_image_1.0:" + e);
        }
    }

    static String metadataToString(IIOMetadata metadata, String formatName) {
        if (metadata == null) {
            return "no metadata";
        }
        Node node = metadata.getAsTree(formatName);
        StringWriter sw = new StringWriter();
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.transform(new DOMSource(node), new StreamResult(sw));
            return sw.toString();
        } catch (TransformerException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
