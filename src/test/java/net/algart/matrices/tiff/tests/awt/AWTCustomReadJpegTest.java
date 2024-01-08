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

package net.algart.matrices.tiff.tests.awt;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataFormatImpl;
import javax.imageio.stream.ImageInputStream;
import javax.xml.transform.TransformerException;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

// Actually this test is not "custom": it just illustrates that we cannot customize default reading scheme,
// namely, cannot set up necessary color space
public class AWTCustomReadJpegTest {
    public static void main(String[] args) throws IOException, TransformerException {
        int startArgIndex = 0;
        boolean enforceAWT = false;
        if (args.length > startArgIndex && args[startArgIndex].equalsIgnoreCase("-enforceAWT")) {
            enforceAWT = true;
            startArgIndex++;
        }
        if (args.length < startArgIndex + 3) {
            System.out.println("Usage:");
            System.out.println("    " + AWTCustomReadJpegTest.class.getName()
                    + " [-enforceAWT] some_image.jpeg result.bmp resultRawRaster.bmp");
            return;
        }

        final File srcFile = new File(args[startArgIndex]);
        final File resultFile = new File(args[startArgIndex + 1]);
        final File resultRasterFile = new File(args[startArgIndex + 2]);

        Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("JPEG");
        while (readers.hasNext()) {
            System.out.println("JPEG reader: " + readers.next());
        }
        System.out.println();

        System.out.printf("Opening %s...%n", srcFile);
//        BufferedImage biSimple = ImageIO.read(srcFile);
        final ImageInputStream stream = ImageIO.createImageInputStream(srcFile);
        if (stream == null) {
            // - for example, non-existing file
            throw new IIOException("Can't create an ImageInputStream!");
        }
        readers = ImageIO.getImageReadersByFormatName("JPEG");
        ImageReader reader;
        if (enforceAWT) {
            reader = findAWTCodec(readers);
        } else {
            reader = readers.hasNext() ? readers.next() : null;
        }
        if (reader == null) {
            throw new IIOException("Cannot read JPEG");
        }
        System.out.println("Using JPEG reader: " + reader);

        ImageReadParam param = reader.getDefaultReadParam();
        System.out.printf("Default read parameters: %s%n", param);
        param.setSourceSubsampling(1, 1, 0, 0);

        reader.setInput(stream, false, false);
        IIOMetadata metadata = reader.getImageMetadata(0);
//        AWTCustomWriteJpegTest.correctColorSpace(metadata, "YCbCr");
        // - does not help: metadata are really changed, but this Java object is ignored
        // while reading inside JPEG inside AWT native code;
        // in TwelveMonkey library, leads to IllegalStateException: Metadata is read-only

//        ComponentColorModel colorModel = new ComponentColorModel(
//                new YCbCrColorSpace(), null, false, false,
//                Transparency.OPAQUE, 0);
//        ImageTypeSpecifier imageTypeSpecifier = new ImageTypeSpecifier(
//                colorModel, colorModel.createCompatibleSampleModel(biSimple.getWidth(), biSimple.getHeight()));
//        param.setDestinationType(imageTypeSpecifier);
        // - does not work: it is the only way to select one of (usually 2) ALREADY DETECTED image types

        BufferedImage bi = reader.read(0, param);

        System.out.printf("Successfully read: %s%n%n", bi);
        System.out.printf("Writing %s...%n", resultFile);
        resultFile.delete();
        if (!ImageIO.write(bi, AWTSimpleReadTest.extension(resultFile.getName(), "bmp"), resultFile)) {
            throw new IOException("No suitable writer");
        }
        Raster raster = reader.readRaster(0, param);
        if (raster instanceof WritableRaster writableRaster) {
            System.out.printf("Writing %s...%n", resultRasterFile);
            BufferedImage biRaster = new BufferedImage(bi.getColorModel(), writableRaster,
                    false, null);
            resultRasterFile.delete();
            if (!ImageIO.write(biRaster, AWTSimpleReadTest.extension(resultRasterFile.getName(), "bmp"),
                    resultRasterFile)) {
                throw new IOException("No suitable writer");
            }
        }

        if (false) {
            IIOMetadata imageMetadata = reader.getImageMetadata(0);
            System.out.printf("%nDefault metadata:%n%s", AWTReadMetadataTest.metadataToString(imageMetadata,
                    IIOMetadataFormatImpl.standardMetadataFormatName));
            System.out.printf("%nNative metadata:%n%s", AWTReadMetadataTest.metadataToString(imageMetadata,
                    "javax_imageio_jpeg_image_1.0"));
        }
    }

    private static <T> T findAWTCodec(Iterator<T> iterator) {
        if (!iterator.hasNext()) {
            return null;
        }
        T first = iterator.next();
        if (isProbableAWTClass(first)) {
            return first;
            // - This is maximally typical behaviour, in particularly, used in ImageIO.read/write.
            // But it can be not the desirable behaviour, when we have some additional plugins
            // like TwelveMonkeys ImageIO, which are registered as the first plugin INSTEAD of built-in
            // Java AWT plugin, because, for example, TwelveMonkeys does not guarantee the identical behaviour;
            // in this case, we should try to find the original AWT plugin.
        }
        while (iterator.hasNext()) {
            T other = iterator.next();
            if (isProbableAWTClass(other)) {
                return other;
            }
        }
        return first;
    }

    private static boolean isProbableAWTClass(Object o) {
        return o != null && o.getClass().getName().startsWith("com.sun.");
    }
}
