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

package net.algart.matrices.tiff.codecs;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffPhotometricInterpretation;
import org.scijava.util.Bytes;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.Objects;

public class JPEGTools {
    private static final boolean USE_LEGACY_DECODE_Y_CB_CR = false;
    // - Should be false for correct behaviour and better performance; necessary for debugging needs only.

    public record ImageInformation(BufferedImage bufferedImage, IIOMetadata metadata) {
    }

    private JPEGTools() {
    }

    /**
     * Analog of <tt>ImageIO.read</tt>. Actually can read any formats, not only JPEG.
     * Also reads metadata (but not thumbnails).
     */
    public static ImageInformation readJPEG(InputStream in) throws IOException {
        ImageInputStream stream = ImageIO.createImageInputStream(in);
        if (stream == null) {
            throw new IIOException("Cannot decompress JPEG tile");
        }
        ImageReader reader = JPEGTools.getImageReaderOrNull(stream);
        if (reader == null) {
            return null;
        }
        ImageReadParam param = reader.getDefaultReadParam();
        reader.setInput(stream, true, true);
        try {
            IIOMetadata imageMetadata = reader.getImageMetadata(0);
            BufferedImage image = reader.read(0, param);
            return new ImageInformation(image, imageMetadata);
        } finally {
            reader.dispose();
        }
    }

    /**
     * Analog of <tt>ImageIO.write</tt> for JPEG.
     */
    public static void writeJPEG(
            BufferedImage image,
            OutputStream out,
            TiffPhotometricInterpretation colorSpace,
            double quality) throws IOException {
        Objects.requireNonNull(image, "Null image");
        Objects.requireNonNull(out, "Null output stream");
        Objects.requireNonNull(colorSpace, "Null color space");
        if (colorSpace != TiffPhotometricInterpretation.Y_CB_CR && colorSpace != TiffPhotometricInterpretation.RGB) {
            throw new IllegalArgumentException("Unsupported color space: " + colorSpace);
        }
        final boolean enforceRGB = colorSpace == TiffPhotometricInterpretation.RGB;

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
            // - Important! It informs getDefaultImageMetadata to add Adobe and SOF markers,
            // that is detected by JPEGImageWriter and leads to correct outCsType = JPEG.JCS_RGB
        }
        final IIOMetadata metadata = jpegWriter.getDefaultImageMetadata(
                enforceRGB ? null : imageTypeSpecifier,
                writeParam);
        // - Important! imageType = null necessary for RGB, in other case setDestinationType will be ignored!

        final IIOImage iioImage = new IIOImage(image, null, metadata);
        // - metadata necessary (with necessary markers)
        try {
            jpegWriter.write(null, iioImage, writeParam);
        } finally {
            jpegWriter.dispose();
        }
    }

    public static String tryToFindColorSpace(IIOMetadata metadata) {
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
                        return name.getNodeValue();
                    }
                }
            }
        }
        return null;
    }

    public static boolean completeDecodingYCbCrNecessary(
            ImageInformation imageInformation,
            TiffPhotometricInterpretation declaredColorSpace,
            int[] declaredSubsampling) {
        Objects.requireNonNull(imageInformation, "Null image information");
        Objects.requireNonNull(declaredColorSpace, "Null color space");
        Objects.requireNonNull(declaredSubsampling, "Null declared subsampling");
        final String colorSpace = tryToFindColorSpace(imageInformation.metadata);
        return "RGB".equalsIgnoreCase(colorSpace)
                && declaredColorSpace == TiffPhotometricInterpretation.Y_CB_CR
                && declaredSubsampling.length >= 2
                && declaredSubsampling[0] == 1 && declaredSubsampling[1] == 1
                && imageInformation.bufferedImage.getRaster().getNumBands() == 3;
        // Rare case: YCbCr is encoded with non-standard sub-sampling (more exactly, without sub-sampling),
        // and the JPEG is incorrectly detected as RGB; so, there is no sense to optimize this.
    }

    public static void completeDecodingYCbCr(
            byte[][] data,
            ImageInformation imageInformation,
            TiffPhotometricInterpretation declaredColorSpace,
            int[] declaredSubsampling)
            throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(imageInformation, "Null image information");
        Objects.requireNonNull(declaredColorSpace, "Null color space");
        Objects.requireNonNull(declaredSubsampling, "Null declared subsampling");
        final long bandLength = (long) imageInformation.bufferedImage.getWidth()
                * (long) imageInformation.bufferedImage.getHeight();
        if (USE_LEGACY_DECODE_Y_CB_CR) {
            decodeYCbCrLegacy(data, bandLength);
            return;
        }

        if (data[0].length != bandLength) {
            // - should not occur
            throw new TiffException("Cannot correct unpacked JPEG: number of bytes per sample in JPEG " +
                    "must be 1, but actually we have " +
                    (double) data[0].length / (double) bandLength + " bytes/sample");
        }
        for (int i = 0; i < data[0].length; i++) {
            int y = data[0][i] & 0xFF;
            int cb = data[1][i] & 0xFF;
            int cr = data[2][i] & 0xFF;

            cb -= 128;
            cr -= 128;

            double red = (y + 1.402 * cr);
            double green = (y - 0.34414 * cb - 0.71414 * cr);
            double blue = (y + 1.772 * cb);

            data[0][i] = (byte) toUnsignedByte(red);
            data[1][i] = (byte) toUnsignedByte(green);
            data[2][i] = (byte) toUnsignedByte(blue);
        }
    }

    public static byte[] quickBGRPixelBytes(BufferedImage bufferedImage) {
        Objects.requireNonNull(bufferedImage, "Null bufferedImage");
        final WritableRaster raster = bufferedImage.getRaster();
        if (!canDirectlyUseBankDataForBGRBands(raster)) {
            return null;
        }
        byte[][] bankData = ((DataBufferByte) raster.getDataBuffer()).getBankData();
        if (bankData.length != 1) {
            return null;
        }
        byte[] data = bankData[0];
        if (data.length != 3 * (long) raster.getWidth() * (long) raster.getHeight()) {
            // - should not occur
            return null;
        }
        return data;
    }

    public static byte[] separateBGR(byte[] bytes, int numberOfPixels) {
        Objects.requireNonNull(bytes, "Null bytes");
        if (bytes.length != 3 * numberOfPixels) {
            throw new IllegalArgumentException("Length of bytes array is not equal to 3 * number of pixels");
        }
        final int bandSize2 = 2 * numberOfPixels;
        final byte[] separatedBytes = new byte[bytes.length];
        for (int i = 0, disp = 0; i < numberOfPixels; i++) {
            separatedBytes[i + bandSize2] = bytes[disp++];
            separatedBytes[i + numberOfPixels] = bytes[disp++];
            separatedBytes[i] = bytes[disp++];
        }
        return separatedBytes;
    }

    private static boolean canDirectlyUseBankDataForBGRBands(final WritableRaster raster) {
        if (raster.getTransferType() != DataBuffer.TYPE_BYTE) {
            return false;
        }
        final DataBuffer buffer = raster.getDataBuffer();
        if (!(buffer instanceof DataBufferByte)) {
            return false;
        }
        if (raster.getNumBands() != 3) {
            return false;
        }
        final SampleModel model = raster.getSampleModel();
        if (!(model instanceof ComponentSampleModel componentSampleModel)) {
            return false;
        }
        final int pixelStride = componentSampleModel.getPixelStride();
        if (pixelStride != 3) {
            return false;
        }
        final int width = raster.getWidth();
        final int scanlineStride = componentSampleModel.getScanlineStride();
        if (scanlineStride != pixelStride * width) {
            return false;
        }
        final int[] bandOffsets = componentSampleModel.getBandOffsets();
        if (bandOffsets.length != 3) {
            return false;
        }
        return bandOffsets[0] == 2 && bandOffsets[1] == 1 && bandOffsets[2] == 0;
    }


    private static void decodeYCbCrLegacy(byte[][] buf, long bandLength) {
        final boolean littleEndian = false;
        // - not important for 8-bit values
        final int nBytes = (int) (buf[0].length / bandLength);
        final int mask = (int) (Math.pow(2, nBytes * 8) - 1);
        for (int i = 0; i < buf[0].length; i += nBytes) {
            final int y = Bytes.toInt(buf[0], i, nBytes, littleEndian);
            int cb = Bytes.toInt(buf[1], i, nBytes, littleEndian);
            int cr = Bytes.toInt(buf[2], i, nBytes, littleEndian);

            cb = Math.max(0, cb - 128);
            cr = Math.max(0, cr - 128);

            final int red = (int) (y + 1.402 * cr) & mask;
            final int green = (int) (y - 0.34414 * cb - 0.71414 * cr) & mask;
            final int blue = (int) (y + 1.772 * cb) & mask;

            Bytes.unpack(red, buf[0], i, nBytes, littleEndian);
            Bytes.unpack(green, buf[1], i, nBytes, littleEndian);
            Bytes.unpack(blue, buf[2], i, nBytes, littleEndian);
        }
    }

    public static ImageReader getImageReaderOrNull(Object inputStream) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(inputStream);
        return findAWTCodec(readers);
    }

    public static ImageWriter getJPEGWriter() throws IIOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter result = findAWTCodec(writers);
        if (result == null) {
            throw new IIOException("Cannot write JPEG: no necessary registered plugin");
        }
        return result;
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

    private static int toUnsignedByte(double v) {
        return v < 0.0 ? 0 : v > 255.0 ? 255 : (int) Math.round(v);
    }
}
