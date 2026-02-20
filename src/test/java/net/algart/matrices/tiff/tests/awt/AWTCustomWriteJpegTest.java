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

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.*;
import javax.imageio.metadata.IIOInvalidTreeException;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

public class AWTCustomWriteJpegTest {
    static ImageWriter getJPEGWriter() throws IIOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IIOException("Cannot write JPEG");
        }
        return writers.next();
    }

    static Node correctColorSpace(IIOMetadata metadata, String colorSpace) throws IIOInvalidTreeException {
        Node tree = metadata.getAsTree("javax_imageio_1.0");
//        metadata.setFromTree("javax_imageio_1.0", tree); // - leads to duplicate APP0!
//        if (true) return tree;
        System.out.printf("Source metadata, standard:%n%s%n",
                AWTReadMetadataTest.metadataToStringStandard(metadata));
        System.out.printf("Source metadata, native:%n%s%n",
                AWTReadMetadataTest.metadataToStringNative(metadata));
        NodeList rootNodes = tree.getChildNodes();
        for (int k = 0, n = rootNodes.getLength(); k < n; k++) {
            Node rootChild = rootNodes.item(k);
            String childName = rootChild.getNodeName();
//            System.out.println(childName);
            if ("Chroma".equalsIgnoreCase(childName)) {
                NodeList nodes = rootChild.getChildNodes();
                for (int i = 0, m = nodes.getLength(); i < m; i++) {
                    Node subChild = nodes.item(i);
                    String subChildName = subChild.getNodeName();
//                    System.out.println("  " + subChildName);
                    if ("ColorSpaceType".equalsIgnoreCase(subChildName)) {
                        NamedNodeMap attributes = subChild.getAttributes();
                        Node name = attributes.getNamedItem("name");
//                        System.out.println("    name = " + name.getNodeValue());
                        name.setNodeValue(colorSpace);
//                        System.out.println("    name (new) = " + name.getNodeValue());
                    }
                }
            }
        }
        metadata.setFromTree("javax_imageio_1.0", tree);
        System.out.printf("Corrected metadata, standard:%n%s%n",
                AWTReadMetadataTest.metadataToStringStandard(metadata));
        System.out.printf("Corrected metadata, native:%n%s%n",
                AWTReadMetadataTest.metadataToStringNative(metadata));
        return tree;
    }

    public static void writeJpegViaImageType(BufferedImage image, File file, boolean rgbSpace) throws IOException {
        ImageWriter writer = getJPEGWriter();
        System.out.printf("Registered writer is %s%n", writer.getClass());

        ImageOutputStream ios = ImageIO.createImageOutputStream(file);
        writer.setOutput(ios);

        ImageTypeSpecifier imageTypeSpecifier = new ImageTypeSpecifier(image);

        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        writeParam.setCompressionType("JPEG");
        if (rgbSpace) {
            writeParam.setDestinationType(imageTypeSpecifier);
            // - Important! It informs getDefaultImageMetadata to add Adobe and SOF markers
            // that are detected by JPEGImageWriter and leads to correct outCsType = JPEG.JCS_RGB
        }

        IIOMetadata metadata = writer.getDefaultImageMetadata(rgbSpace ? null : imageTypeSpecifier, writeParam);
        // - imageType = null, in another case, setDestinationType will be ignored!

        System.out.printf("Writing JPEG image into %s via image type %s...%n", file, imageTypeSpecifier);
        IIOImage iioImage = new IIOImage(image, null, metadata);
        writer.write(null, iioImage, writeParam);
    }

    public static void writeJpegViaMetadata(BufferedImage image, File file, boolean rgbSpace) throws IOException {
        ImageWriter writer = getJPEGWriter();
        System.out.printf("Registered writer is %s%n", writer.getClass());

        ImageOutputStream ios = ImageIO.createImageOutputStream(file);
        writer.setOutput(ios);

        ImageTypeSpecifier imageTypeSpecifier = new ImageTypeSpecifier(image);
        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        writeParam.setCompressionType("JPEG");

        IIOMetadata metadata = writer.getDefaultImageMetadata(imageTypeSpecifier, writeParam);
        correctColorSpace(metadata, rgbSpace ? "RGB" : "YCbCr");
        // - lead to invalid metadata (duplicate APP0) in the case YCbCr
        // Note: for RGB case, this method leads to results, identical to writeJpegViaImageType
        System.out.printf("Writing JPEG image into %s via metadata %s...%n", file, metadata);
        IIOImage iioImage = new IIOImage(image, null, metadata);
        writer.write(null, iioImage, writeParam);
    }

    public static void main(String[] args) throws IOException {
        System.out.printf("All installed image writers: %s%n", Arrays.toString(ImageIO.getWriterFormatNames()));
        int startArgIndex = 0;
        boolean rgb = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-rgb")) {
            rgb = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 3) {
            System.out.println("Usage:");
            System.out.println("    " + AWTCustomWriteJpegTest.class.getName()
                    + " [-rgb] method1|method2 some_image.jpeg result.jpeg");
            return;
        }

        final String method = args[startArgIndex];
        final File srcFile = new File(args[startArgIndex + 1]);
        final File resultFile = new File(args[startArgIndex + 2]);

        System.out.printf("Opening %s...%n", srcFile);
        final ImageInputStream stream = ImageIO.createImageInputStream(srcFile);
        if (stream == null) {
            throw new IIOException("Can't create an ImageInputStream!");
        }
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
        if (!readers.hasNext()) {
            throw new IIOException("Cannot read " + srcFile + ": unknown format");
        }
        ImageReader reader = readers.next();
        ImageReadParam param = reader.getDefaultReadParam();
        System.out.printf("Default read parameters: %s%n", param);
        reader.setInput(stream, true, true);
        BufferedImage bi = reader.read(0, param);
        IIOMetadata imageMetadata = reader.getImageMetadata(0);
        ImageTypeSpecifier rawImageType = reader.getRawImageType(0);
        if (bi == null) {
            throw new AssertionError("Strange null result");
        }
        System.out.printf("Image metadata: %s%n", imageMetadata);
        System.out.printf("Raw image type, model: %s%n", rawImageType.getColorModel());
        System.out.printf("Raw image type, color space: %s%n", rawImageType.getColorModel().getColorSpace().getType());
        System.out.printf("Successfully read: %s%n%n", bi);

        resultFile.delete();
        switch (method) {
            case "method1" -> writeJpegViaImageType(bi, resultFile, rgb);
            case "method2" -> writeJpegViaMetadata(bi, resultFile, rgb);
            default -> throw new IllegalArgumentException("Unknown method " + args[startArgIndex]);
        }
        System.out.printf("%n%n");
        AWTReadMetadataTest.main(resultFile.toString());
    }
}
