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

package net.algart.matrices.tiff.awt;

import net.algart.arrays.Arrays;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.tags.TagPhotometric;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Objects;

public class JPEGDecoding {
    static final boolean USE_MEMORY_CACHE = true;
    // - Must be true for normal performance.
    // Important: our codec is implemented for reading separate tiles, which SHOULD be not too large
    // to be located in memory. For comparison, other codecs like DeflateCodec always work in memory.
    private static final boolean IGNORE_EXCEPTION_WHILE_ATTEMPT_TO_READ_METADATA = true;
    // - Must be true for correct processing some TIFF.
    private static final boolean DIRECT_READING_Y_CB_CR_RASTER_NECESSARY = true;
    // - Should be true for better processing mismatched color spaces (YCbCr in JPEG stream, RGB in TIFF IFD).
    private static final boolean COMPLETE_DECODING_Y_CB_CR_NECESSARY = true;
    // - Should be true for better processing mismatched color spaces (RGB in JPEG stream, YCbCr in TIFF IFD).
    private static final boolean DISABLE_THIRD_PARTY_IMAGE_READER = Arrays.SystemSettings.getBooleanProperty(
            "net.algart.matrices.tiff.jpeg.disableThirdPartyImageReader", true);
    // - Should be true.
    // The false value switches to a maximally typical behavior, used, in particular, in ImageIO.read/write.
    // But it can be not the desirable behavior when we have some third-party libraries
    // like TwelveMonkeys ImageIO, which are registered as the first plugin INSTEAD of built-in
    // Java AWT plugin.
    // For example, TwelveMonkeys ImageReader does not guarantee the identical behavior:
    // OpenSlide images like "CMU-1.svs" will be decoded incorrectly.
    // When this flag is true, we will try to find and use the original AWT plugin, which works correctly.

    private static final boolean CORRECT_Y_CB_CR_WITH_SUB_SAMPLING_1X1_ONLY = false;
    // - Should be false; true value is more compatible with SCIFIO TiffParser

    public record ImageInformation(
            IIOMetadata metadata,
            byte[][] pixelBytes,
            BufferedImage bufferedImage,
            String colorSpaceName,
            boolean basedOnBufferedImage) {
    }

    private JPEGDecoding() {
    }

    /**
     * Analog of <code>ImageIO.read</code>. Actually can read any formats, not only JPEG.
     * Also reads metadata (but not thumbnails).
     */
    public static ImageInformation readJPEG(
            InputStream in,
            TagPhotometric declaredColorSpace,
            int numberOfChannels,
            boolean littleEndian) throws IOException {
        final ImageInputStream stream = USE_MEMORY_CACHE ?
                new MemoryCacheImageInputStream(in) :
                ImageIO.createImageInputStream(in);
        final ImageReader reader = getImageReaderOrNull(stream);
        if (reader == null) {
            return null;
        }
        final ImageReadParam param = reader.getDefaultReadParam();
        reader.setInput(stream, true, true);
        try {
            final IIOMetadata metadata = retrieveMetadata(reader);
            final String colorSpace = tryToFindColorSpace(metadata);
            BufferedImage image = null;
            byte[][] pixelBytes = null;
            if (isDirectReadingYCbCrRasterNecessary(colorSpace, declaredColorSpace, numberOfChannels)) {
                final Raster raster = reader.readRaster(0, param);
                final Object pixels = AWTImages.getPixels(raster);
                pixelBytes = AWTImages.tryDirectPixelToBytes(pixels);
            }
            if (pixelBytes == null) {
                image = reader.read(0, param);
                pixelBytes = AWTImages.getPixelBytes(image, littleEndian);
            }
            return new ImageInformation(metadata, pixelBytes, image, colorSpace, image != null);
        } finally {
            reader.dispose();
        }
    }

    private static IIOMetadata retrieveMetadata(ImageReader reader) throws IOException {
        IIOMetadata imageMetadata = null;
        try {
            imageMetadata = reader.getImageMetadata(0);
            // - these metadata are necessary to correctly decompress images like
            // jpeg_ycbcr_encoded_as_rgb.tiff from the demo resources
        } catch (IOException e) {
            if (!IGNORE_EXCEPTION_WHILE_ATTEMPT_TO_READ_METADATA) {
                throw e;
            }
            // Sometimes TIFF files contain JPEG data with an invalid marker sequence.
            // In such cases ImageReader.getImageMetadata() may throw an exception like
            // "JFIF APP0 must be first marker after SOI".
            // In this case, we ignore this exception and still try to read image:
            // probably the read() method (based on the native JPEG decoder) will work normally.

            // LOG.log(System.Logger.Level.DEBUG, "Cannot read metadata: " + e);
            // - usually logging is also unnecessary, but you may uncomment it for debugging.
        }
        return imageMetadata;
    }

    public static String tryToFindColorSpace(IIOMetadata metadata) {
        if (metadata == null) {
            return null;
        }
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

    public static boolean isDirectReadingYCbCrRasterNecessary(
            String actualColorSpace,
            TagPhotometric declaredColorSpace,
            int numberOfChannels) {
        if (!DIRECT_READING_Y_CB_CR_RASTER_NECESSARY) {
            return false;
        }
        // - Note: declaredColorSpace=null is allowed, but very improbable
        return "YCbCr".equalsIgnoreCase(actualColorSpace)
                && declaredColorSpace == TagPhotometric.RGB
                && numberOfChannels == 3;
        // Rare case: RGB is encoded directly in JPEG, but ImageReader incorrectly detected it as YCbCr
    }

    public static boolean isCompleteDecodingYCbCrNecessary(
            ImageInformation imageInformation,
            TagPhotometric declaredColorSpace,
            int[] declaredSubsampling) {
        Objects.requireNonNull(imageInformation, "Null image information");
        Objects.requireNonNull(declaredSubsampling, "Null declared subsampling");
        if (!COMPLETE_DECODING_Y_CB_CR_NECESSARY) {
            return false;
        }
        // - Note: declaredColorSpace=null is allowed, but very improbable
        final boolean suitableSubSampling = !CORRECT_Y_CB_CR_WITH_SUB_SAMPLING_1X1_ONLY ||
                (declaredSubsampling.length >= 2 && declaredSubsampling[0] == 1 && declaredSubsampling[1] == 1);
        return "RGB".equalsIgnoreCase(imageInformation.colorSpaceName)
                && declaredColorSpace == TagPhotometric.Y_CB_CR
                && suitableSubSampling
                && imageInformation.bufferedImage.getRaster().getNumBands() == 3;
        // Rare case: YCbCr is encoded with non-standard subsampling (more exactly, without subsampling),
        // and the JPEG is incorrectly detected as RGB; so, there is no sense to optimize this.
    }

    // Note: this method may be tested with the image jpeg_ycbcr_encoded_as_rgb.tiff from the demo resources
    // declaredColorSpace and declaredSubsampling are not used by the current implementation
    public static void completeDecodingYCbCr(
            byte[][] data,
            ImageInformation imageInformation,
            TagPhotometric declaredColorSpace,
            int[] declaredSubsampling)
            throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(imageInformation, "Null image information");
        checkBands(data, imageInformation);
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

    private static void checkBands(byte[][] data, ImageInformation imageInformation) throws TiffException {
        final long bandLength = (long) imageInformation.bufferedImage.getWidth()
                * (long) imageInformation.bufferedImage.getHeight();
        if (data.length < 3) {
            // - should not occur
            throw new TiffException("Cannot correct unpacked JPEG: number bands must be 3, " +
                    "but actually we have " + data.length + " bands");
        }
        for (int i = 0; i < 3; i++) {
            if (data[i].length != bandLength) {
                // - should not occur
                throw new TiffException("Cannot correct unpacked JPEG: number of bytes per sample in JPEG " +
                        "must be 1, but actually we have " +
                        (double) data[i].length / (double) bandLength + " bytes/sample in the band " + i);
            }
        }
    }

    // The following code (conversion RGB -> YCbCr) is a bad idea: if ImageReader already decoded
    // YCbCr into RGB BY A MISTAKE, the information was lost!
    // We cannot restore original bytes due to clamping by 0..255 range.
    /*
    public static void completeDecodingRGB(
            byte[][] data,
            ImageInformation imageInformation,
            TagPhotometric declaredColorSpace)
            throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(imageInformation, "Null image information");
        checkBands(data, imageInformation);
        for (int i = 0; i < data[0].length; i++) {
            int r = data[0][i] & 0xFF;
            int g = data[1][i] & 0xFF;
            int b = data[2][i] & 0xFF;

            double rAsFakeY  = 0.299 * r + 0.587 * g + 0.114 * b;
            double gAsFakeCb = -0.168736 * r - 0.331264 * g + 0.5 * b + 128.0;
            double bAsFakeCr = 0.5 * r - 0.418688 * g - 0.081312 * b + 128.0;

            data[0][i] = (byte) toUnsignedByte(rAsFakeY);
            data[1][i] = (byte) toUnsignedByte(gAsFakeCb);
            data[2][i] = (byte) toUnsignedByte(bAsFakeCr);
        }
    }
    */


    /*
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
    */

    public static ImageReader getImageReaderOrNull(Object inputStream) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(inputStream);
        return findAWTCodec(readers);
    }

    static <T> T findAWTCodec(Iterator<T> iterator) {
        if (!iterator.hasNext()) {
            return null;
        }
        T first = iterator.next();
        if (isProbableAWTClass(first)) {
            return first;
        }
        //noinspection WhileCanBeDoWhile
        while (iterator.hasNext()) {
            T other = iterator.next();
            if (isProbableAWTClass(other)) {
                return other;
            }
        }
        return first;
    }

    private static int toUnsignedByte(double v) {
        return v < 0.0 ? 0 : v > 255.0 ? 255 : (int) Math.round(v);
    }

    private static boolean isProbableAWTClass(Object o) {
        if (!DISABLE_THIRD_PARTY_IMAGE_READER) {
            return true;
        }
        return o != null && o.getClass().getName().startsWith("com.sun.");
    }
}
