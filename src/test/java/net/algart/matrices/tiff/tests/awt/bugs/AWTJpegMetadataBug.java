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

package net.algart.matrices.tiff.tests.awt.bugs;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.*;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.util.Iterator;

public class AWTJpegMetadataBug {
    public static final boolean NEED_JCS_RGB = false;
    // Please set to "false" to enforce bug.
    // Please set fo "true" to write RGB-encoded JPEG file.

    // This method ALWAYS leads to a bug: just try to call it instead of correctColorSpace below
    static void correctColorSpaceDummy(IIOMetadata metadata, String colorSpace) throws IIOInvalidTreeException {
        Node tree = metadata.getAsTree("javax_imageio_1.0");
        metadata.setFromTree("javax_imageio_1.0", tree); // - leads to duplicate APP0!
    }

    // This method leads to a bug if colorSpace is actually the same
    static void correctColorSpace(IIOMetadata metadata, String colorSpace) throws IIOInvalidTreeException {
        Node tree = metadata.getAsTree("javax_imageio_1.0");
        NodeList rootNodes = tree.getChildNodes();
        for (int k = 0, n = rootNodes.getLength(); k < n; k++) {
            Node rootChild = rootNodes.item(k);
            String childName = rootChild.getNodeName();
            if ("Chroma".equalsIgnoreCase(childName)) {
                NodeList nodes = rootChild.getChildNodes();
                for (int i = 0, m = nodes.getLength(); i < m; i++) {
                    Node subChild = nodes.item(i);
                    String subChildName = subChild.getNodeName();
                    if ("ColorSpaceType".equalsIgnoreCase(subChildName)) {
                        NamedNodeMap attributes = subChild.getAttributes();
                        Node name = attributes.getNamedItem("name");
                        name.setNodeValue(colorSpace);
                    }
                }
            }
        }
        metadata.setFromTree("javax_imageio_1.0", tree);
        // !!!! BUG #1: if the tree was not actually changed and still contains "YCbCr" as ColorSpaceType,
        // !!!! setFromTree writes JFIF marker into the native metadata twice!
    }

    // Writes image either in usual YCbCr or in RGB color space
    public static void writeJpegViaMetadata(BufferedImage image, File file, boolean rgbSpace) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IIOException("Cannot write JPEG");
        }
        ImageWriter writer = writers.next();

        ImageOutputStream ios = ImageIO.createImageOutputStream(file);
        writer.setOutput(ios);

        ImageTypeSpecifier imageTypeSpecifier = new ImageTypeSpecifier(image);
        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        writeParam.setCompressionType("JPEG");

        IIOMetadata metadata = writer.getDefaultImageMetadata(imageTypeSpecifier, writeParam);
        correctColorSpace(metadata, rgbSpace ? "RGB" : "YCbCr");
        // - lead to invalid metadata (duplicate APP0) in a case YCbCr
        IIOImage iioImage = new IIOImage(image, null, metadata);
        writer.write(null, iioImage, writeParam);
        // !!!! BUG #2: even when metadata are incorrect and contain duplicated JFIF marker,
        // !!!! this "write" method works without exceptions and creates "strange" JPEG
    }

    private static String metadataToString(IIOMetadata metadata, String formatName) {
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

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("    " + AWTJpegMetadataBug.class.getName()
                    + " some_image.jpeg result.jpeg");
            return;
        }

        final File srcFile = new File(args[0]);
        final File resultFile = new File(args[1]);

        BufferedImage bi = ImageIO.read(srcFile);
        if (bi == null) {
            throw new IOException("Cannot read " + srcFile);
        }

        resultFile.delete();
        writeJpegViaMetadata(bi, resultFile, NEED_JCS_RGB);

        // Try to read metadata from the written file:
        ImageInputStream stream = ImageIO.createImageInputStream(resultFile);
        if (stream == null) {
            throw new IIOException("Can't create an ImageInputStream!");
        }
        Iterator<ImageReader> iter = ImageIO.getImageReaders(stream);
        if (!iter.hasNext()) {
            throw new IIOException("Can't read " + resultFile);
        }
        ImageReader reader = iter.next();
        reader.setInput(stream, true, true);
        IIOMetadata imageMetadata = reader.getImageMetadata(0);
        // !!!! BUG #1 becomes visible here (for YCbCr color space):
        // !!!! "javax.imageio.IIOException: JFIF APP0 must be first marker after SOI"
        System.out.printf("Successfully loaded back! Metadata:%n%s%n",
                metadataToString(imageMetadata, "javax_imageio_jpeg_image_1.0"));
    }
}
