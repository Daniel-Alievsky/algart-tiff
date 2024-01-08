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

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;

public class AWTWriteJpegBug {
    public static final boolean ENFORCE_BUG_1 = false;
    public static final boolean ENFORCE_BUG_2 = false;

    public static final boolean NEED_JCS_RGB = true;

    public static ImageWriter getJPEGWriter() throws IIOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IIOException("Cannot write JPEG");
        }
        return writers.next();
    }

    public static ImageWriteParam getJPEGWriteParam(ImageWriter writer, ImageTypeSpecifier imageTypeSpecifier) {
        ImageWriteParam writeParam = writer.getDefaultWriteParam();
        writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        writeParam.setCompressionType("JPEG");
        if (ENFORCE_BUG_1) {
            // !!!!!! BUG #1!
            // If we do not call setDestinationType below or pass null as imageTypeSpecifier,
            // then the method
            // writer.getDefaultImageMetadata(null, writeParam)
            // in main() method will throw InternalError!
            // !!!!!!
            return writeParam;
        }
        if (imageTypeSpecifier != null) {
            writeParam.setDestinationType(imageTypeSpecifier);
            // - Important! It informs getDefaultImageMetadata to add Adobe and SOF markers,
            // that is detected by JPEGImageWriter and leads to correct outCsType = JPEG.JCS_RGB
        }
        return writeParam;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("    " + AWTWriteJpegBug.class.getName()
                    + " some_image.jpeg result.jpeg");
            return;
        }

        final File srcFile = new File(args[0]);
        final File resultFile = new File(args[1]);

        System.out.printf("Opening %s...%n", srcFile);
        BufferedImage bi = ImageIO.read(srcFile);
        if (bi == null) {
            throw new IOException("Unknown format");
        }

        System.out.printf("Writing JPEG image into %s...%n", resultFile);
        resultFile.delete();
        ImageWriter writer = getJPEGWriter();
        System.out.printf("Registered writer is %s%n", writer.getClass());

        ImageOutputStream ios = ImageIO.createImageOutputStream(resultFile);
        writer.setOutput(ios);

        ImageTypeSpecifier imageTypeSpecifier = new ImageTypeSpecifier(bi);

        ImageWriteParam writeParam = getJPEGWriteParam(writer, NEED_JCS_RGB ? imageTypeSpecifier : null);
        IIOMetadata metadata;
        if (ENFORCE_BUG_2) {
            metadata = writer.getDefaultImageMetadata(imageTypeSpecifier, writeParam);
            // !!!!!! BUG #2!
            // If we shall pass imageTypeSpecifier instead of null as the 1st argument of getDefaultImageMetadata
            // (it looks  absolutely correct!),
            // then this method will IGNORE destination type, specified by writeParam.setDestinationType
            // inside getJPEGWriteParam, and we will make Y/Cb/Cr instead of desired RGB color space. Why??
            // !!!!!!
        } else {
            metadata = writer.getDefaultImageMetadata(NEED_JCS_RGB ? null : imageTypeSpecifier, writeParam);
        }


        // metadata = writer.getDefaultImageMetadata(null, null);
        // !!!!!! BUG #3!
        // If you will uncomment operator above, it will also lead to InternalError
        // instead of more suitable NullPointerException or something like this.
        // !!!!!!

        IIOImage iioImage = new IIOImage(bi, null, metadata);
        writer.write(null, iioImage, writeParam);
        ios.close();
    }
}
