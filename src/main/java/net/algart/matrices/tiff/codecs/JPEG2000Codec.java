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

package net.algart.matrices.tiff.codecs;

import com.github.jaiimageio.jpeg2000.J2KImageReadParam;
import com.github.jaiimageio.jpeg2000.J2KImageWriteParam;
import com.github.jaiimageio.jpeg2000.impl.J2KImageReader;
import com.github.jaiimageio.jpeg2000.impl.J2KImageWriter;
import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.awt.AWTImages;
import net.algart.matrices.tiff.awt.UnsignedIntBuffer;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.*;
import java.io.*;
import java.util.Arrays;
import java.util.Objects;

// This class avoids the bug in SCIFIO: https://github.com/scifio/scifio/issues/495
// and allows to work without SCIFIO context
public class JPEG2000Codec implements TiffCodec {
    // (It is placed here to avoid autocorrection by IntelliJ IDEA)
    /*
     * #%L
     * SCIFIO library for reading and converting scientific file formats.
     * %%
     * Copyright (C) 2011 - 2023 SCIFIO developers.
     * %%
     * Redistribution and use in source and binary forms, with or without
     * modification, are permitted provided that the following conditions are met:
     *
     * 1. Redistributions of source code must retain the above copyright notice,
     *    this list of conditions and the following disclaimer.
     * 2. Redistributions in binary form must reproduce the above copyright notice,
     *    this list of conditions and the following disclaimer in the documentation
     *    and/or other materials provided with the distribution.
     *
     * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
     * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
     * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
     * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
     * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
     * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
     * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
     * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
     * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
     * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
     * POSSIBILITY OF SUCH DAMAGE.
     * #L%
     */

    /**
     * Options for compressing and decompressing JPEG-2000 data.
     */
    public static class JPEG2000Options extends Options {
        public static final double DEFAULT_NORMAL_QUALITY = 10.0;

        /**
         * The lossless mode affects the default quality and the argument of J2KImageWriteParam.setFilter method
         * (WRITE).
         */
        boolean lossless = true;

        /**
         * Color model to use when constructing an image (WRITE).
         */
        ColorModel colorModel = null;

        /**
         * The maximum code-block size to use per tile-component as it would be
         * provided to: {@code J2KImageWriteParam#setCodeBlockSize(int[])} (WRITE).
         */
        int[] codeBlockSize = {64, 64};

        /**
         * The number of decomposition levels as would be provided to:
         * {@code J2KImageWriteParam#setNumDecompositionLevels(int)} (WRITE). Leaving
         * this value {@code null} signifies that when a JPEG 2000 parameter set is
         * created for the purposes of compression the number of decomposition levels
         * will be left as the default.
         */
        Integer numberOfDecompositionLevels = null;

        /**
         * The resolution level as would be provided to:
         * {@code J2KImageWriteParam#setResolution(int)} (READ). Leaving this value
         * {@code null} signifies that when a JPEG 2000 parameter set is created for
         * the purposes of compression the number of decomposition levels will be left
         * as the default.
         */
        Integer resolution = null;

        public JPEG2000Options() {
            setQuality(Double.MAX_VALUE);
        }

        public boolean isLossless() {
            return lossless;
        }

        public JPEG2000Options setLossless(boolean lossless) {
            this.lossless = lossless;
            return this;
        }

        public ColorModel getColorModel() {
            return colorModel;
        }

        public JPEG2000Options setColorModel(ColorModel colorModel) {
            this.colorModel = colorModel;
            return this;
        }

        public int[] getCodeBlockSize() {
            return codeBlockSize.clone();
        }

        public JPEG2000Options setCodeBlockSize(int[] codeBlockSize) {
            Objects.requireNonNull(codeBlockSize, "Null codeBlockSize");
            // see J2KImageWriteParamJava constructor: it requires non-null j2kParam.getCodeBlockSize
            if (codeBlockSize.length < 2) {
                throw new IllegalArgumentException("Too short codeBlockSize array: int[" + codeBlockSize.length +
                        "] (must contain 2 elements)");
            }
            this.codeBlockSize = codeBlockSize.clone();
            return this;
        }

        public Integer getNumberOfDecompositionLevels() {
            return numberOfDecompositionLevels;
        }

        public JPEG2000Options setNumberOfDecompositionLevels(Integer numberOfDecompositionLevels) {
            this.numberOfDecompositionLevels = numberOfDecompositionLevels;
            return this;
        }

        public Integer getResolution() {
            return resolution;
        }

        public JPEG2000Options setResolution(Integer resolution) {
            this.resolution = resolution;
            return this;
        }

        // Note: this method SHOULD be overridden to provide correct clone() behavior.
        @Override
        public JPEG2000Options setTo(Options options) {
            return setTo(options, true);
        }

        public JPEG2000Options setTo(Options options, boolean lossless) {
            super.setTo(options);
            if (options instanceof JPEG2000Options o) {
                setLossless(o.lossless);
                setColorModel(o.colorModel);
                setCodeBlockSize(o.codeBlockSize);
                setNumberOfDecompositionLevels(o.numberOfDecompositionLevels);
                setResolution(o.resolution);
            } else {
                setLossless(lossless);
                if (!hasQuality()) {
                    setQuality(lossless ? Double.MAX_VALUE : DEFAULT_NORMAL_QUALITY);
                }
            }
            return this;
        }

        @Override
        public <T> T toSCIFIOStyleOptions(Class<T> scifioStyleClass) {
            T result = super.toSCIFIOStyleOptions(scifioStyleClass);
            setField(scifioStyleClass, result, "lossless", lossless);
            setField(scifioStyleClass, result, "colorModel", colorModel);
            return result;
        }

        @Override
        public void setToSCIFIOStyleOptions(Object scifioStyleOptions) {
            super.setToSCIFIOStyleOptions(scifioStyleOptions);
            lossless = getField(scifioStyleOptions, Boolean.class, "lossless");
            colorModel = getField(scifioStyleOptions, ColorModel.class, "colorModel");
        }

        @Override
        public String toString() {
            return super.toString() +
                    ", lossless=" + lossless +
                    ", colorModel=" + colorModel +
                    ", codeBlockSize=" + Arrays.toString(codeBlockSize) +
                    ", numDecompositionLevels=" + numberOfDecompositionLevels +
                    ", resolution=" + resolution;
        }
    }

    // Copy of equivalent SCIFIO method, not using jaiIIOService field
    public byte[] compress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        if (options.floatingPoint) {
            throw new TiffException("JPEG-2000 compression cannot be used for floating-point values");
        }
        if (options.signed) {
            throw new TiffException("JPEG compression for signed samples is not supported " +
                    "(only unsigned samples allowed)");
        }
        if (data.length == 0) {
            return data;
        }

        JPEG2000Options jpeg2000Options = new JPEG2000Options().setTo(options);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        BufferedImage img;

        int next = 0;

        // NB: Construct BufferedImages manually, rather than using
        // AWTImages.makeImage. The AWTImages.makeImage methods
        // construct
        // images that are not properly handled by the JPEG2000 writer.
        // Specifically, 8-bit multichannel images are constructed with type
        // DataBuffer.TYPE_INT (so a single int is used to store all the
        // channels for a specific pixel).

        final int plane = jpeg2000Options.width * jpeg2000Options.height;

        if (jpeg2000Options.bitsPerSample == 8) {
            final byte[][] b = new byte[jpeg2000Options.numberOfChannels][plane];
            if (jpeg2000Options.interleaved) {
                for (int q = 0; q < plane; q++) {
                    for (int c = 0; c < jpeg2000Options.numberOfChannels; c++) {
                        b[c][q] = data[next++];
                    }
                }
            } else {
                for (int c = 0; c < jpeg2000Options.numberOfChannels; c++) {
                    System.arraycopy(data, c * plane, b[c], 0, plane);
                }
            }
            final DataBuffer buffer = new DataBufferByte(b, plane);
            img = AWTImages.constructImage(b.length, DataBuffer.TYPE_BYTE,
                    jpeg2000Options.width, jpeg2000Options.height, false, true, buffer,
                    jpeg2000Options.colorModel);
        } else if (jpeg2000Options.bitsPerSample == 16) {
            final short[][] s = new short[jpeg2000Options.numberOfChannels][plane];
            if (jpeg2000Options.interleaved) {
                for (int q = 0; q < plane; q++) {
                    for (int c = 0; c < jpeg2000Options.numberOfChannels; c++) {
                        // assert toShort(data, next, false) == Bytes.toShort(data, next, 2, false);
                        // assert toShort(data, next, true) == Bytes.toShort(data, next, 2, true);
                        s[c][q] = toShort(data, next, jpeg2000Options.littleEndian);
                        next += 2;
                    }
                }
            } else {
                for (int c = 0; c < jpeg2000Options.numberOfChannels; c++) {
                    for (int q = 0; q < plane; q++) {
                        s[c][q] = toShort(data, next, jpeg2000Options.littleEndian);
                        next += 2;
                    }
                }
            }
            final DataBuffer buffer = new DataBufferUShort(s, plane);
            img = AWTImages.constructImage(s.length, DataBuffer.TYPE_USHORT,
                    jpeg2000Options.width, jpeg2000Options.height, false, true, buffer,
                    jpeg2000Options.colorModel);
        } else if (jpeg2000Options.bitsPerSample == 32) {
            final int[][] s = new int[jpeg2000Options.numberOfChannels][plane];
            if (jpeg2000Options.interleaved) {
                for (int q = 0; q < plane; q++) {
                    for (int c = 0; c < jpeg2000Options.numberOfChannels; c++) {
//                        assert toInt(data, next, true) == Bytes.toInt(data, next, 4, true);
//                        assert toInt(data, next, false) == Bytes.toInt(data, next, 4, false);
                        s[c][q] = toInt(data, next, jpeg2000Options.littleEndian);
                        next += 4;
                    }
                }
            } else {
                for (int c = 0; c < jpeg2000Options.numberOfChannels; c++) {
                    for (int q = 0; q < plane; q++) {
                        s[c][q] = toInt(data, next, jpeg2000Options.littleEndian);
                        next += 4;
                    }
                }
            }

            final DataBuffer buffer = new UnsignedIntBuffer(s, plane);
            img = AWTImages.constructImage(s.length, DataBuffer.TYPE_INT,
                    jpeg2000Options.width, jpeg2000Options.height, false, true, buffer,
                    jpeg2000Options.colorModel);
        } else {
            throw new TiffException("JPEG-2000 compression for " + jpeg2000Options.bitsPerSample +
                    "-bit samples is not supported (only 8-bit, 16-bit and 32-bit samples allowed)");
        }

        try {
            writeImage(out, img, jpeg2000Options);
        } catch (final IOException e) {
            throw new TiffException("Could not compress JPEG-2000 data.", e);
        }

        return out.toByteArray();
    }

    // Note: SCIFIO codec also overrides the method decompress(DataHandle) of AbstractCodec,
    // but this is probably a bug:
    // 1) actually we use decompress(byte[] data) method, which is also overridden;
    // 2) the implementation used options.maxSizeInBytes for reading COMPRESSED data.

    // Below is a copy of equivalent SCIFIO method, not using jaiIIOService field
    @Override
    public byte[] decompress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        JPEG2000Options jpeg2000Options = new JPEG2000Options().setTo(options);

        byte[][] single;
        WritableRaster b;
        int bpp;

        try {
            final ByteArrayInputStream bis = new ByteArrayInputStream(data);
            b = (WritableRaster) readRaster(bis, jpeg2000Options);
            // - instead of:
            // b = (WritableRaster) this.jaiIIOService.readRaster(bis,
            //        (JPEG2000CodecOptions) options);
            single = AWTImages.getPixelBytes(b, jpeg2000Options.littleEndian);
            bpp = single[0].length / (b.getWidth() * b.getHeight());

            bis.close();
        } catch (final IOException e) {
            throw new TiffException("Could not decompress JPEG2000 image. Please " +
                    "make sure that jai_imageio.jar is installed.", e);
        }
//        catch (final ServiceException e) {
//            throw new TiffException("Could not decompress JPEG2000 image. Please " +
//                "make sure that jai_imageio.jar is installed.", e);
//        }

        if (single.length == 1) return single[0];
        final byte[] rtn = new byte[single.length * single[0].length];
        if (jpeg2000Options.interleaved) {
            int next = 0;
            for (int i = 0; i < single[0].length / bpp; i++) {
                for (byte[] bytes : single) {
                    for (int bb = 0; bb < bpp; bb++) {
                        rtn[next++] = bytes[i * bpp + bb];
                    }
                }
            }
        } else {
            for (int i = 0; i < single.length; i++) {
                System.arraycopy(single[i], 0, rtn, i * single[0].length,
                        single[i].length);
            }
        }
        return rtn;
    }

    private static void writeImage(final OutputStream out, final BufferedImage img,
                                   final JPEG2000Options options) throws IOException {
        final ImageOutputStream ios = ImageIO.createImageOutputStream(out);

        final J2KImageWriter writer = new J2KImageWriter(null);
        writer.setOutput(ios);

        final String filter = options.lossless ? J2KImageWriteParam.FILTER_53
                : J2KImageWriteParam.FILTER_97;

        final IIOImage iioImage = new IIOImage(img, null, null);
        final J2KImageWriteParam param = (J2KImageWriteParam) writer
                .getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionType("JPEG2000");
        param.setLossless(options.lossless);
        param.setFilter(filter);
        param.setCodeBlockSize(options.getCodeBlockSize());
        param.setEncodingRate(options.quality());
//      TIFF provides its own tile subsystem
//        if (options.tileWidth > 0 && options.tileHeight > 0) {
//            param.setTiling(options.tileWidth, options.tileHeight,
//                    options.tileGridXOffset, options.tileGridYOffset);
//        }
        if (options.numberOfDecompositionLevels != null) {
            param.setNumDecompositionLevels(options.numberOfDecompositionLevels);
        }
        writer.write(null, iioImage, param);
        ios.close();
    }

    private static Raster readRaster(final InputStream in, final JPEG2000Options options) throws IOException {
        final J2KImageReader reader = new J2KImageReader(null);
        final MemoryCacheImageInputStream mciis = new MemoryCacheImageInputStream(in);
        reader.setInput(mciis, false, true);
        final J2KImageReadParam param = (J2KImageReadParam) reader.getDefaultReadParam();
        if (options.resolution != null) {
            param.setResolution(options.resolution);
        }
        return reader.readRaster(0, param);
    }

    private static short toShort(final byte[] src, int srcPos, final boolean little) {
        return (short) (little ?
                (src[srcPos] & 0xFF) | ((src[srcPos + 1] & 0xFF) << 8) :
                ((src[srcPos] & 0xFF) << 8) | (src[srcPos + 1] & 0xFF));
    }

    private static int toInt(final byte[] src, int srcPos, final boolean little) {
        return little ?
                (src[srcPos] & 0xFF)
                        | ((src[srcPos + 1] & 0xFF) << 8)
                        | ((src[srcPos + 2] & 0xFF) << 16)
                        | ((src[srcPos + 3] & 0xFF) << 24) :
                ((src[srcPos] & 0xFF) << 24)
                        | ((src[srcPos + 1] & 0xFF) << 16)
                        | ((src[srcPos + 2] & 0xFF) << 8)
                        | (src[srcPos + 3] & 0xFF);
    }
}
