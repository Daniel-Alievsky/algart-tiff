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

package net.algart.matrices.tiff.awt;

import net.algart.matrices.tiff.tags.TagPhotometricInterpretation;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Objects;

public class JPEGEncoding {
    private JPEGEncoding() {
    }

    /**
     * Analog of <code>ImageIO.write</code> for JPEG.
     */
    public static void writeJPEG(
            BufferedImage image,
            OutputStream out,
            TagPhotometricInterpretation colorSpace,
            double quality) throws IOException {
        Objects.requireNonNull(image, "Null image");
        Objects.requireNonNull(out, "Null output stream");
        Objects.requireNonNull(colorSpace, "Null color space");
        if (colorSpace != TagPhotometricInterpretation.Y_CB_CR && colorSpace != TagPhotometricInterpretation.RGB) {
            throw new IllegalArgumentException("Unsupported color space: " + colorSpace);
        }
        final boolean enforceRGB = colorSpace == TagPhotometricInterpretation.RGB;

        final ImageOutputStream ios = ImageIO.createImageOutputStream(out);
        final ImageWriter jpegWriter = getJPEGWriter();
        jpegWriter.setOutput(ios);

        final ImageWriteParam writeParam = jpegWriter.getDefaultWriteParam();
        writeParam.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        writeParam.setCompressionType("JPEG");
        writeParam.setCompressionQuality((float) quality);
        final ImageTypeSpecifier imageTypeSpecifier = new ImageTypeSpecifier(image);
        if (enforceRGB) {
            writeParam.setDestinationType(imageTypeSpecifier);
            // - Important! It informs getDefaultImageMetadata to add Adobe and SOF markers
            // that are detected by JPEGImageWriter and leads to correct outCsType = JPEG.JCS_RGB
        }
        final IIOMetadata metadata = jpegWriter.getDefaultImageMetadata(
                enforceRGB ? null : imageTypeSpecifier,
                writeParam);
        // - Important! imageType = null necessary for RGB, in another case, setDestinationType will be ignored!

        final IIOImage iioImage = new IIOImage(image, null, metadata);
        // - metadata necessary (with necessary markers)
        try {
            jpegWriter.write(null, iioImage, writeParam);
        } finally {
            jpegWriter.dispose();
        }
    }

    public static ImageWriter getJPEGWriter() throws IIOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter result = JPEGDecoding.findAWTCodec(writers);
        if (result == null) {
            throw new IIOException("Cannot write JPEG: no necessary registered plugin");
        }
        return result;
    }

}
