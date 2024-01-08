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
import net.algart.matrices.tiff.TiffException;
import io.scif.gui.AWTImageTools;
import net.algart.matrices.tiff.TiffPhotometricInterpretation;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandleInputStream;
import org.scijava.io.location.Location;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public class JPEGCodec extends AbstractCodec implements TiffCodecTiming {
    private static final boolean OPTIMIZE_SEPARATING_BGR = true;

    private long timeMain = 0;
    private long timeBridge = 0;
    private long timeAdditional = 0;
    private boolean timing = false;

//    @Parameter
//    private CodecService codecService;

    @Override
    public byte[] compress(byte[] data, CodecOptions options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        if (data.length == 0) {
            return data;
        }
        long t1 = timing ? System.nanoTime() : 0;

        if (options.channels != 1 && options.channels != 3) {
            throw new TiffException("JPEG compression for " + options.channels + " channels is not supported");
        }
        if (options.bitsPerSample != 8) {
            throw new TiffException("JPEG compression for " + options.bitsPerSample +
                    "-bit data is not supported (only 8-bit samples allowed)");
        }
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final BufferedImage image = AWTImageTools.makeImage(data, options.width,
                options.height, options.channels, options.interleaved,
                options.bitsPerSample / 8, false, options.littleEndian, options.signed);

        long t2 = timing ? System.nanoTime() : 0;
        final TiffPhotometricInterpretation colorSpace = options instanceof JPEGCodecOptions extended ?
                extended.getPhotometricInterpretation() :
                TiffPhotometricInterpretation.Y_CB_CR;
        final double jpegQuality = Math.min(options.quality, 1.0);
            // - for JPEG, maximal possible quality is 1.0, but it is better to allow greater qualities
            // (for comparison, maximal quality in JPEG-2000 is Double.MAX_VALUE)
        try {
            JPEGTools.writeJPEG(image, output, colorSpace, jpegQuality);
        } catch (final IOException e) {
            throw new TiffException("Cannot compress JPEG data", e);
        }
        byte[] result = output.toByteArray();
        long t3 = timing ? System.nanoTime() : 0;
        timeBridge += t2 - t1;
        timeMain += t3 - t2;
        return result;
    }

    @Override
    public byte[] decompress(final DataHandle<Location> in, CodecOptions options) throws IOException {
        final long offset = in.offset();
        long t1 = timing ? System.nanoTime() : 0;
        JPEGTools.ImageInformation info;
        try (InputStream input = new BufferedInputStream(new DataHandleInputStream<>(in), 8192)) {
            info = JPEGTools.readJPEG(input);
        } catch (final IOException exc) {
            // probably a lossless JPEG; delegate to LosslessJPEGCodec
            in.seek(offset);
            return (new LosslessJPEGCodec()).decompress(in, options);
            // Legacy solution, requiring Context
//            if (codecService == null) {
//                throw new IllegalStateException(
//                        "Decompressing unusual JPEG (probably lossless) requires specifying non-null SCIFIO context");
//            }
//            final Codec codec = codecService.getCodec(io.scif.codec.LosslessJPEGCodec.class);
//            return codec.decompress(in, options);
        }
        if (info == null) {
            throw new TiffException("Cannot read JPEG image: unknown format");
            // - for example, OLD_JPEG
        }

        if (options == null) {
            options = CodecOptions.getDefaultOptions();
        }
        boolean completeDecoding = false;
        TiffPhotometricInterpretation declaredColorSpace = null;
        int[] declaredSubsampling = null;
        if (options instanceof JPEGCodecOptions extended) {
            declaredColorSpace = extended.getPhotometricInterpretation();
            declaredSubsampling = extended.getYCbCrSubsampling();
            completeDecoding = JPEGTools.completeDecodingYCbCrNecessary(info, declaredColorSpace, declaredSubsampling);
        }
        BufferedImage bi = info.bufferedImage();
        long t2 = timing ? System.nanoTime() : 0;
        timeMain += t2 - t1;

        final byte[] quickResult = options.interleaved || completeDecoding || !OPTIMIZE_SEPARATING_BGR?
                null :
                JPEGTools.quickBGRPixelBytes(bi);
        final byte[][] data = quickResult != null ? null : AWTImageTools.getPixelBytes(bi, options.littleEndian);
        long t3 = timing ? System.nanoTime() : 0;

        if (completeDecoding) {
            JPEGTools.completeDecodingYCbCr(data, info, declaredColorSpace, declaredSubsampling);
        }
        timeBridge += t3 - t2;

        final byte[] result;
        if (quickResult != null) {
            result = JPEGTools.separateBGR(quickResult, bi.getWidth() * bi.getHeight());
        } else {
            int bandSize = data[0].length;
            if (data.length == 1) {
                result = data[0];
            } else {
                result = new byte[data.length * bandSize];
                if (options.interleaved) {
                    int next = 0;
                    for (int i = 0; i < bandSize; i++) {
                        for (byte[] bytes : data) {
                            result[next++] = bytes[i];
                        }
                    }
                } else {
                    for (int i = 0; i < data.length; i++) {
                        System.arraycopy(data[i], 0, result, i * bandSize, bandSize);
                    }
                }
            }
        }
        long t4 = timing ? System.nanoTime() : 0;
        timeAdditional += t4 - t3;
        return result;
    }

    public void setTiming(boolean timing) {
        this.timing = timing;
    }

    public void clearTiming() {
        timeMain = 0;
        timeBridge = 0;
        timeAdditional = 0;
    }

    public long timeMain() {
        return timeMain;
    }

    public long timeBridge() {
        return timeBridge;
    }

    public long timeAdditional() {
        return timeAdditional;
    }
}
