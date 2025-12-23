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

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.tags.TagPhotometricInterpretation;
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
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Objects;

public class JPEGDecoding {
    private static final System.Logger LOG = System.getLogger(JPEGDecoding.class.getName());

    static final boolean USE_MEMORY_CACHE = true;
    // - Must be true for normal performance.
    // Important: our codec is implemented for reading separate tiles, which SHOULD be not too large
    // to be located in memory. For comparison, other codecs like DeflateCodec always work in memory.

    public record ImageInformation(BufferedImage bufferedImage, IIOMetadata metadata) {
    }

    private JPEGDecoding() {
    }

    /**
     * Analog of <code>ImageIO.read</code>. Actually can read any formats, not only JPEG.
     * Also reads metadata (but not thumbnails).
     */
    public static ImageInformation readJPEG(InputStream in) throws IOException {
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
            IIOMetadata imageMetadata = null;
            try {
                imageMetadata = reader.getImageMetadata(0);
            } catch (IOException e) {
                // it is better to ignore this exception than block reading all the image
//                LOG.log(System.Logger.Level.DEBUG, "Cannot read metadata: " + e);
            }
            final BufferedImage image = reader.read(0, param);
            return new ImageInformation(image, imageMetadata);
        } finally {
            reader.dispose();
        }
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

    public static boolean isCompleteDecodingYCbCrNecessary(
            ImageInformation imageInformation,
            TagPhotometricInterpretation declaredColorSpace,
            int[] declaredSubsampling) {
        Objects.requireNonNull(imageInformation, "Null image information");
        Objects.requireNonNull(declaredColorSpace, "Null color space");
        Objects.requireNonNull(declaredSubsampling, "Null declared subsampling");
        final String colorSpace = tryToFindColorSpace(imageInformation.metadata);
        return "RGB".equalsIgnoreCase(colorSpace)
                && declaredColorSpace == TagPhotometricInterpretation.Y_CB_CR
                && declaredSubsampling.length >= 2
                && declaredSubsampling[0] == 1 && declaredSubsampling[1] == 1
                && imageInformation.bufferedImage.getRaster().getNumBands() == 3;
        // Rare case: YCbCr is encoded with non-standard subsampling (more exactly, without subsampling),
        // and the JPEG is incorrectly detected as RGB; so, there is no sense to optimize this.
    }

    // Note: this method may be tested with the image jpeg_ycbcr_encoded_as_rgb.tiff
    public static void completeDecodingYCbCr(
            byte[][] data,
            ImageInformation imageInformation,
            TagPhotometricInterpretation declaredColorSpace,
            int[] declaredSubsampling)
            throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(imageInformation, "Null image information");
        Objects.requireNonNull(declaredColorSpace, "Null color space");
        Objects.requireNonNull(declaredSubsampling, "Null declared subsampling");
        final long bandLength = (long) imageInformation.bufferedImage.getWidth()
                * (long) imageInformation.bufferedImage.getHeight();
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
            // - This is maximally typical behavior, in particularly, used in ImageIO.read/write.
            // But it can be not the desirable behavior, when we have some additional plugins
            // like TwelveMonkeys ImageIO, which are registered as the first plugin INSTEAD of built-in
            // Java AWT plugin, because, for example, TwelveMonkeys does not guarantee the identical behavior;
            // in this case, we should try to find the original AWT plugin.
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
        return o != null && o.getClass().getName().startsWith("com.sun.");
    }
}
