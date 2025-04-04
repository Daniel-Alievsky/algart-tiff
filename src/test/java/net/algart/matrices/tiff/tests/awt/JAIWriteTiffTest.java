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

package net.algart.matrices.tiff.tests.awt;

import com.github.jaiimageio.impl.plugins.tiff.TIFFIFD;
import com.github.jaiimageio.impl.plugins.tiff.TIFFImageMetadata;
import com.github.jaiimageio.impl.plugins.tiff.TIFFImageWriter;
import com.github.jaiimageio.impl.plugins.tiff.TIFFJPEGCompressor;
import com.github.jaiimageio.plugins.tiff.TIFFImageWriteParam;
import net.algart.matrices.tiff.tests.awt.bugs.AWTWriteJpegBug;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;

public class JAIWriteTiffTest {
    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Usage:");
            System.out.println("    " + JAIWriteTiffTest.class.getName()
                    + " some_image.bmp result.tiff");
            return;
        }

        final File srcFile = new File(args[0]);
        final File resultFile = new File(args[1]);

        System.out.printf("Opening %s...%n", srcFile);
        BufferedImage bi = ImageIO.read(srcFile);
        if (bi == null) {
            throw new IIOException("Unsupported image format: " + srcFile);
        }
        System.out.printf("%nWriting TIFF image into %s...%n", resultFile);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("tiff");
        if (!writers.hasNext()) {
            throw new IIOException("Cannot write TIFF");
        }
        ImageWriter writer = writers.next();
        System.out.printf("Registered writer is %s%n", writer.getClass());
//        com.sun.imageio.plugins.tiff.TIFFImageWriter registeredWriter =
//                (com.sun.imageio.plugins.tiff.TIFFImageWriter) writer;

        TIFFImageWriter tiffWriter = new TIFFImageWriter(null);
        resultFile.delete();
        ImageOutputStream ios = ImageIO.createImageOutputStream(resultFile);
        tiffWriter.setOutput(ios);

        ImageTypeSpecifier imageTypeSpecifier = new ImageTypeSpecifier(bi);

        ImageWriter jpegWriter = AWTWriteJpegBug.getJPEGWriter();

        ImageWriteParam jpegWriteParam = AWTWriteJpegBug.getJPEGWriteParam(jpegWriter, imageTypeSpecifier);
        TIFFJPEGCompressor compressor = new TIFFJPEGCompressor(jpegWriteParam);
        IIOMetadata jpegMetadata = jpegWriter.getDefaultImageMetadata(null, jpegWriteParam);
        compressor.setMetadata(jpegMetadata);

        TIFFImageWriteParam writeParam = (TIFFImageWriteParam) tiffWriter.getDefaultWriteParam();
        writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        writeParam.setTIFFCompressor(compressor);
        writeParam.setCompressionType("JPEG");
        writeParam.setDestinationType(imageTypeSpecifier);
        TIFFImageMetadata metadata = (TIFFImageMetadata)
                tiffWriter.getDefaultImageMetadata(imageTypeSpecifier, writeParam);
        TIFFIFD rootIFD = metadata.getRootIFD();
        System.out.printf("Default photometric: %s%n", rootIFD.getTIFFField(262).getAsInt(0));
//        rootIFD.addTIFFField(new TIFFField(
//                rootIFD.getTag(BaselineTIFFTagSet.TAG_PHOTOMETRIC_INTERPRETATION),
//                BaselineTIFFTagSet.PHOTOMETRIC_INTERPRETATION_RGB));

//        writeParam.setColorConverter(
//                new TIFFYCbCrColorConverter(metadata), BaselineTIFFTagSet.PHOTOMETRIC_INTERPRETATION_RGB);
//        System.out.printf("PhotometricInterpretation: %s%n", writeParam.getPhotometricInterpretation());

        IIOImage iioImage = new IIOImage(bi, null, metadata);
        tiffWriter.write(null, iioImage, writeParam);
//        System.out.printf("New photometric: %s%n",
//                ((TIFFImageMetadata) iioImage.getMetadata()).getRootIFD().getTIFFField(262).getAsInt(0));
        System.out.printf("Compression types: %s%n",
                Arrays.toString(writeParam.getCompressionTypes()));
    }
}
