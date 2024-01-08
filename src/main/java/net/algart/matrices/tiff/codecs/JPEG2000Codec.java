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

import io.scif.codec.CodecOptions;
import io.scif.codec.JPEG2000CodecOptions;
import io.scif.gui.AWTImageTools;
import io.scif.gui.UnsignedIntBuffer;
import io.scif.media.imageio.plugins.jpeg2000.J2KImageReadParam;
import io.scif.media.imageio.plugins.jpeg2000.J2KImageWriteParam;
import io.scif.media.imageioimpl.plugins.jpeg2000.J2KImageReader;
import io.scif.media.imageioimpl.plugins.jpeg2000.J2KImageWriter;
import net.algart.matrices.tiff.TiffException;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.Location;
import org.scijava.util.Bytes;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.*;
import java.io.*;

// This class avoids the bug in SCIFIO: https://github.com/scifio/scifio/issues/495
// and allows to work without SCIFIO context
public class JPEG2000Codec extends AbstractCodec {
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


    // Copy of equivalent SCIFIO method, not using jaiIIOService field
    public byte[] compress(final byte[] data, final CodecOptions options) throws TiffException {
        if (data == null || data.length == 0) return data;

        JPEG2000CodecOptions j2kOptions;
        if (options instanceof JPEG2000CodecOptions) {
            j2kOptions = (JPEG2000CodecOptions) options;
        }
        else {
            j2kOptions = JPEG2000CodecOptions.getDefaultOptions(options);
        }

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        BufferedImage img = null;

        int next = 0;

        // NB: Construct BufferedImages manually, rather than using
        // AWTImageTools.makeImage. The AWTImageTools.makeImage methods
        // construct
        // images that are not properly handled by the JPEG2000 writer.
        // Specifically, 8-bit multi-channel images are constructed with type
        // DataBuffer.TYPE_INT (so a single int is used to store all of the
        // channels for a specific pixel).

        final int plane = j2kOptions.width * j2kOptions.height;

        if (j2kOptions.bitsPerSample == 8) {
            final byte[][] b = new byte[j2kOptions.channels][plane];
            if (j2kOptions.interleaved) {
                for (int q = 0; q < plane; q++) {
                    for (int c = 0; c < j2kOptions.channels; c++) {
                        b[c][q] = data[next++];
                    }
                }
            }
            else {
                for (int c = 0; c < j2kOptions.channels; c++) {
                    System.arraycopy(data, c * plane, b[c], 0, plane);
                }
            }
            final DataBuffer buffer = new DataBufferByte(b, plane);
            img = AWTImageTools.constructImage(b.length, DataBuffer.TYPE_BYTE,
                    j2kOptions.width, j2kOptions.height, false, true, buffer,
                    j2kOptions.colorModel);
        }
        else if (j2kOptions.bitsPerSample == 16) {
            final short[][] s = new short[j2kOptions.channels][plane];
            if (j2kOptions.interleaved) {
                for (int q = 0; q < plane; q++) {
                    for (int c = 0; c < j2kOptions.channels; c++) {
                        s[c][q] = Bytes.toShort(data, next, 2, j2kOptions.littleEndian);
                        next += 2;
                    }
                }
            }
            else {
                for (int c = 0; c < j2kOptions.channels; c++) {
                    for (int q = 0; q < plane; q++) {
                        s[c][q] = Bytes.toShort(data, next, 2, j2kOptions.littleEndian);
                        next += 2;
                    }
                }
            }
            final DataBuffer buffer = new DataBufferUShort(s, plane);
            img = AWTImageTools.constructImage(s.length, DataBuffer.TYPE_USHORT,
                    j2kOptions.width, j2kOptions.height, false, true, buffer,
                    j2kOptions.colorModel);
        }
        else if (j2kOptions.bitsPerSample == 32) {
            final int[][] s = new int[j2kOptions.channels][plane];
            if (j2kOptions.interleaved) {
                for (int q = 0; q < plane; q++) {
                    for (int c = 0; c < j2kOptions.channels; c++) {
                        s[c][q] = Bytes.toInt(data, next, 4, j2kOptions.littleEndian);
                        next += 4;
                    }
                }
            }
            else {
                for (int c = 0; c < j2kOptions.channels; c++) {
                    for (int q = 0; q < plane; q++) {
                        s[c][q] = Bytes.toInt(data, next, 4, j2kOptions.littleEndian);
                        next += 4;
                    }
                }
            }

            final DataBuffer buffer = new UnsignedIntBuffer(s, plane);
            img = AWTImageTools.constructImage(s.length, DataBuffer.TYPE_INT,
                    j2kOptions.width, j2kOptions.height, false, true, buffer,
                    j2kOptions.colorModel);
        }

        try {
            writeImage(out, img, j2kOptions);
        }
        catch (final IOException e) {
            throw new TiffException("Could not compress JPEG-2000 data.", e);
        }

        return out.toByteArray();
    }

    // Almost exact copy of equivalent SCIFIO method
    @Override
    public byte[] decompress(final DataHandle<Location> in, CodecOptions options)
            throws IOException
    {
        if (in == null) {
            throw new IllegalArgumentException("No data to decompress.");
        }
        if (!(options instanceof JPEG2000CodecOptions)) {
            options = JPEG2000CodecOptions.getDefaultOptions(options);
        }

        byte[] buf = null;
        final long fp = in.offset();
        if (options.maxBytes == 0) {
            buf = new byte[(int) (in.length() - fp)];
        }
        else {
            buf = new byte[(int) (options.maxBytes - fp)];
        }
        in.read(buf);
        return decompress(buf, options);
    }

    // Copy of equivalent SCIFIO method, not using jaiIIOService field
    @Override
    public byte[] decompress(byte[] buf, CodecOptions options) throws TiffException {
        if (!(options instanceof JPEG2000CodecOptions)) {
            options = JPEG2000CodecOptions.getDefaultOptions(options);
        }
        else {
            options = new JPEG2000CodecOptions(options);
        }

        byte[][] single = null;
        WritableRaster b = null;
        int bpp = options.bitsPerSample / 8;

        try {
            final ByteArrayInputStream bis = new ByteArrayInputStream(buf);
            b = (WritableRaster) readRaster(bis,
                (JPEG2000CodecOptions) options);
            // - instead of:
            // b = (WritableRaster) this.jaiIIOService.readRaster(bis,
            //        (JPEG2000CodecOptions) options);
            single = AWTImageTools.getPixelBytes(b, options.littleEndian);
            bpp = single[0].length / (b.getWidth() * b.getHeight());

            bis.close();
            b = null;
        }
        catch (final IOException e) {
            throw new TiffException("Could not decompress JPEG2000 image. Please " +
                "make sure that jai_imageio.jar is installed.", e);
        }
//        catch (final ServiceException e) {
//            throw new TiffException("Could not decompress JPEG2000 image. Please " +
//                "make sure that jai_imageio.jar is installed.", e);
//        }

        if (single.length == 1) return single[0];
        final byte[] rtn = new byte[single.length * single[0].length];
        if (options.interleaved) {
            int next = 0;
            for (int i = 0; i < single[0].length / bpp; i++) {
                for (int j = 0; j < single.length; j++) {
                    for (int bb = 0; bb < bpp; bb++) {
                        rtn[next++] = single[j][i * bpp + bb];
                    }
                }
            }
        }
        else {
            for (int i = 0; i < single.length; i++) {
                System.arraycopy(single[i], 0, rtn, i * single[0].length,
                    single[i].length);
            }
        }
        single = null;

        return rtn;
    }

    private static void writeImage(final OutputStream out, final BufferedImage img,
                           final JPEG2000CodecOptions options) throws IOException {
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
        param.setCodeBlockSize(options.codeBlockSize);
        param.setEncodingRate(options.quality);
        if (options.tileWidth > 0 && options.tileHeight > 0) {
            param.setTiling(options.tileWidth, options.tileHeight,
                    options.tileGridXOffset, options.tileGridYOffset);
        }
        if (options.numDecompositionLevels != null) {
            param.setNumDecompositionLevels(options.numDecompositionLevels);
        }
        writer.write(null, iioImage, param);
        ios.close();
    }

    private static Raster readRaster(final InputStream in, final JPEG2000CodecOptions options) throws IOException {
        final J2KImageReader reader = new J2KImageReader(null);
        final MemoryCacheImageInputStream mciis = new MemoryCacheImageInputStream(in);
        reader.setInput(mciis, false, true);
        final J2KImageReadParam param = (J2KImageReadParam) reader.getDefaultReadParam();
        if (options.resolution != null) {
            param.setResolution(options.resolution.intValue());
        }
        return reader.readRaster(0, param);
    }
}
