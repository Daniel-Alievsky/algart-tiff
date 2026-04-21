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
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Objects;

public class JPEGDecoding {
    /**
     * Start of image marker byte.
     */
    public static final int SOI_BYTE = 0xD8;

    /**
     * End of image marker byte.
     */
    public static final int EOI_BYTE = 0xD9;

    /**
     * Start of scan marker byte.
     */
    public static final int SOS_BYTE = 0xDA;

    /**
     * Define quantization table(s) marker byte.
     */
    public static final int DQT_BYTE = 0xDB;

    /**
     * Define Huffman table(s) marker byte.
     */
    public static final int DHT_BYTE = 0xC4;

    /**
     * Baseline DCT (Start of Frame 0) marker byte.
     */
    public static final int SOF0_BASELINE = 0xC0;

    /**
     * Temporary marker byte.
     */
    public static final int TEM_BYTE = 0x01;

    /**
     * First restart interval marker byte (1st of 8).
     */
    public static final int RST_FIRST = 0xD0;

    /**
     * Last restart interval marker byte (8th of 8).
     */
    public static final int RST_LAST = 0xD7;

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
    private static final boolean LOG_COLOR_SPACE_MISMATCH = Arrays.SystemSettings.getBooleanProperty(
            "net.algart.matrices.tiff.jpeg.logColorSpaceMismatch", false);
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

    private static final System.Logger LOG = System.getLogger(JPEGDecoding.class.getName());

    public record ImageData(
            IIOMetadata metadata,
            Raster raster,
            byte[][] pixelBytes,
            String colorSpaceName,
            boolean basedOnBufferedImage) {

        public ImageData {
            Objects.requireNonNull(raster, "Null raster");
            Objects.requireNonNull(pixelBytes, "Null pixelBytes");
        }
    }

    private JPEGDecoding() {
    }

    /**
     * Analog of <code>ImageIO.read</code>. Actually can read any formats, not only JPEG.
     * Also reads metadata (but not thumbnails).
     */
    public static ImageData readJPEG(
            InputStream in,
            Dimension sizes,
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
        if (sizes != null) {
            param.setSourceRegion(new Rectangle(0, 0, sizes.width, sizes.height));
        }
        try {
            final IIOMetadata metadata = retrieveMetadata(reader);
            final String colorSpace = tryToFindColorSpace(metadata);
            Raster raster = null;
            byte[][] pixelBytes = null;
            if (isDirectReadingYCbCrRasterNecessary(colorSpace, declaredColorSpace, numberOfChannels)) {
                // - AWT recognizes JPEG in the byte stream as YCbCr, but the declared color space
                // (photometric interpretation) is RGB:
                // we should use the readRaster() method to avoid conversion to RGB performed by the image.read() call
                raster = reader.readRaster(0, param);
                final Object pixels = AWTImages.getPixels(raster);
                pixelBytes = AWTImages.tryDirectPixelToBytes(pixels);
                // - mostly probable that the pixel data is stored by AWT as byte[], and then the getPixels() method
                // has separated them into byte[][]: we just use them;
                // if not (very improbable: for example, AWT prefers returning RBBA packed into int[]),
                // the method tryDirectPixelToBytes() will return null, and we will switch
                // to a usual reader.read() below (better than nothing)
                LOG.log(LOG_COLOR_SPACE_MISMATCH ? System.Logger.Level.INFO : System.Logger.Level.TRACE,
                        "RGB photometric interpretation with %s color space in JPEG: reading Raster%s".formatted(
                                colorSpace, (pixelBytes != null ? "" : " (failed)")));
            }
            final boolean basedOnBufferedImage = pixelBytes == null;
            if (basedOnBufferedImage) {
                // - mostly probable branch: when possible, we prefer using the read() method
                // as the most popular, stable and high-performance method for reading JPEG images.
                final BufferedImage image = reader.read(0, param);
                raster = image.getRaster();
                pixelBytes = AWTImages.getImagePixelBytes(image, littleEndian);
            }
            return new ImageData(metadata, raster, pixelBytes, colorSpace, basedOnBufferedImage);
        } finally {
            reader.dispose();
        }
    }

    // Note: this method may be tested with the image jpeg_ycbcr_encoded_as_rgb.tiff from the demo resources
    // declaredColorSpace and declaredSubsampling are not used by the current implementation
    public static void completeDecodingYCbCr(
            byte[][] data,
            ImageData imageData,
            TagPhotometric declaredColorSpace,
            int[] declaredSubsampling)
            throws TiffException {
        Objects.requireNonNull(data, "Null data");
        if (!isCompleteDecodingYCbCrNecessary(imageData, declaredColorSpace, declaredSubsampling)) {
            return;
        }
        LOG.log(LOG_COLOR_SPACE_MISMATCH ? System.Logger.Level.INFO : System.Logger.Level.TRACE,
                "RGB photometric interpretation with YCbCr color space in JPEG: additional decoding");
        checkBands(data, 3, imageData);
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

    // Note: this situation is very rare; it is possible if the JPEG is monochrome
    // and someone set incorrect photometric interpretation "White-is-zero"
    public static void completeDecodingWhiteIsZero(
            byte[][] data,
            ImageData imageData,
            TagPhotometric declaredColorSpace)
            throws TiffException {
        Objects.requireNonNull(data, "Null data");
        if (!isCompleteDecodingWhiteIsZeroNecessary(imageData, declaredColorSpace)) {
            return;
        }
        LOG.log(LOG_COLOR_SPACE_MISMATCH ? System.Logger.Level.INFO : System.Logger.Level.TRACE,
                "GRAY photometric interpretation with White-is-zero color space in JPEG: additional decoding");
        checkBands(data, 1, imageData);
        byte[] band0 = data[0];
        for (int i = 0; i < band0.length; i++) {
            band0[i] = (byte) (~band0[i]);
        }
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
            ImageData imageData,
            TagPhotometric declaredColorSpace,
            int[] declaredSubsampling) {
        Objects.requireNonNull(imageData, "Null image information");
        if (!COMPLETE_DECODING_Y_CB_CR_NECESSARY) {
            return false;
        }
        // - Note: declaredColorSpace=null is allowed, but very improbable
        final boolean suitableSubSampling = !CORRECT_Y_CB_CR_WITH_SUB_SAMPLING_1X1_ONLY ||
                (declaredSubsampling != null && declaredSubsampling.length >= 2 &&
                        declaredSubsampling[0] == 1 && declaredSubsampling[1] == 1);
        return declaredColorSpace == TagPhotometric.Y_CB_CR &&
                "RGB".equalsIgnoreCase(imageData.colorSpaceName) &&
                suitableSubSampling &&
                imageData.raster.getNumBands() == 3;
        // Rare case: YCbCr is encoded with non-standard subsampling (more exactly, without subsampling),
        // and the JPEG is incorrectly detected as RGB; so, there is no sense to optimize this.
    }

    public static boolean isCompleteDecodingWhiteIsZeroNecessary(
            ImageData imageData,
            TagPhotometric declaredColorSpace) {
        Objects.requireNonNull(imageData, "Null image information");
        // - Note: declaredColorSpace=null is allowed, but very improbable
        return declaredColorSpace == TagPhotometric.WHITE_IS_ZERO &&
                "GRAY".equalsIgnoreCase(imageData.colorSpaceName) &&
                imageData.raster.getNumBands() == 1;
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

    private static void checkBands(byte[][] data, int n, ImageData imageData) throws TiffException {
        final long bandLength = (long) imageData.raster.getWidth() * (long) imageData.raster.getHeight();
        if (data.length < n) {
            // - should not occur
            throw new TiffException("Cannot correct unpacked JPEG: number of bands must be at least " + n +
                    ", but actually we have " + data.length + " bands");
        }
        for (int i = 0; i < n; i++) {
            Objects.requireNonNull(data[i], "Null band " + i);
            if (data[i].length != bandLength) {
                // - should not occur
                throw new TiffException("Cannot correct unpacked JPEG: number of bytes per sample in JPEG " +
                        "must be 1, but actually we have " +
                        (double) data[i].length / (double) bandLength + " bytes/sample in the band " + i);
            }
        }
    }

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
