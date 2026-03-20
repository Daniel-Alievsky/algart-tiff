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

package net.algart.matrices.tiff.codecs;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.awt.AWTImages;
import net.algart.matrices.tiff.awt.JPEGDecoding;
import net.algart.matrices.tiff.awt.JPEGEncoding;
import net.algart.matrices.tiff.tags.TagPhotometric;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandleInputStream;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Objects;

public class JPEGCodec extends StreamTiffCodec implements TiffCodec.Timing {
    public static class JPEGOptions extends Options {
        /**
         * Value of TIFF tag YCbCrSubSampling (READ).
         */
        private int[] yCbCrSubsampling = {2, 2};

        public JPEGOptions() {
            setPhotometric(TagPhotometric.Y_CB_CR);
            // - not too important: JPEGCodec should work normally with null photometric value
            setCompressionQuality(1.0);
            // - but the quality should not be null!
        }

        public int[] getYCbCrSubsampling() {
            return yCbCrSubsampling.clone();
        }

        /**
         * Sets the YCbCr subsampling. This option is usually not needed, but it was used
         * in an old version compatible with SCIFIO (see JPEGDecoding.CORRECT_Y_CB_CR_WITH_SUB_SAMPLING_1X1_ONLY).
         *
         * @param yCbCrSubsampling new subsampling.
         * @return a reference to this object.
         * @throws NullPointerException if the given array is null.
         */
        public JPEGOptions setYCbCrSubsampling(int[] yCbCrSubsampling) {
            this.yCbCrSubsampling = Objects.requireNonNull(yCbCrSubsampling, "Null yCbCrSubsampling").clone();
            return this;
        }

        @Override
        public JPEGOptions setTo(Options options) {
            super.setTo(options);
            if (options instanceof JPEGOptions o) {
                this.yCbCrSubsampling = o.yCbCrSubsampling.clone();
            } else {
                // for example, this branch is actual in TagCompression.customizeWritingJpeg
                Double quality = getCompressionQuality();
                if (quality == null) {
                    setCompressionQuality(1.0);
                } else if (quality > 1.0) {
                    // - for JPEG, the maximal possible quality is 1.0
                    // (for comparison, the maximal quality in JPEG-2000 is Double.MAX_VALUE)
                    setCompressionQuality(1.0);
                }
            }
            return this;
        }

        @Override
        public String toString() {
            return super.toString() +
                    ", yCbCrSubsampling=" + Arrays.toString(yCbCrSubsampling);
        }
    }

    private long timeMain = 0;
    private long timeBridge = 0;
    private long timeAdditional = 0;
    private boolean timing = false;

//    @Parameter
//    private CodecService codecService;

    @Override
    public byte[] compress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        if (options.floatingPoint) {
            throw new TiffException("JPEG compression cannot be used for floating-point values");
        }
        if (options.numberOfChannels != 1 && options.numberOfChannels != 3) {
            throw new TiffException("JPEG compression for " + options.numberOfChannels + " channels is not supported");
        }
        if (options.bitsPerSample != 8) {
            throw new TiffException("JPEG compression for " + options.bitsPerSample +
                    "-bit samples is not supported (only unsigned 8-bit samples allowed)");
        }
        if (options.signed) {
            throw new TiffException("JPEG compression for signed " + options.bitsPerSample +
                    "-bit samples is not supported (only unsigned 8-bit samples allowed)");
        }
        if (data.length == 0) {
            return data;
        }
        long t1 = timing ? System.nanoTime() : 0;

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final BufferedImage image = AWTImages.makeImage(data, options.width,
                options.height, options.numberOfChannels, options.interleaved,
                options.bitsPerSample / 8, false, options.littleEndian,
                false);
        // - original SCIFIO codec io.scif.codec.JPEGCodec supports any "signed" parameter,
        // loaded from CodecOptions class, but in does not make sense in TIFF

        long t2 = timing ? System.nanoTime() : 0;
        final double jpegQuality = Math.min(options.compressionQuality(), 1.0);
        // - for JPEG, the maximal possible quality is 1.0, but it is better to allow greater qualities
        // (for comparison, the maximal quality in JPEG-2000 is Double.MAX_VALUE)
        try {
            JPEGEncoding.writeJPEG(image, output, options.photometric, jpegQuality);
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
    public byte[] decompress(DataHandle<?> in, Options options) throws IOException {
        Objects.requireNonNull(in, "Null input handle");
        if (options == null) {
            options = new JPEGOptions();
        }
        final long offset = in.offset();
        long t1 = timing ? System.nanoTime() : 0;
        JPEGDecoding.ImageInformation info;
        try (InputStream input = new BufferedInputStream(new DataHandleInputStream<>(in), 8192)) {
            info = JPEGDecoding.readJPEG(input, options.photometric, options.numberOfChannels, options.littleEndian);
        } catch (IOException jpegException) {
            // probably a lossless JPEG; delegate to LosslessJPEGCodec
            in.seek(offset);
            byte[] tryLossless = (new LosslessJPEGCodec()).decompress(in, options);
            assert tryLossless != null;
            if (tryLossless.length > 0) {
                return tryLossless;
            }
            // zero length usually means that SOF3 (lossless JPEG) was not found
            throw jpegException;
        }
        if (info == null) {
            throw new TiffException("Cannot read JPEG image: unknown format");
            // - for example, OLD_JPEG
        }

        final boolean completeDecoding = JPEGDecoding.isCompleteDecodingYCbCrNecessary(
                info,
                options.photometric,
                options instanceof JPEGOptions extended ? extended.getYCbCrSubsampling() : null);
        long t2 = timing ? System.nanoTime() : 0;
        timeMain += t2 - t1;

        final byte[][] data = info.pixelBytes();

        if (completeDecoding) {
            JPEGDecoding.completeDecodingYCbCr(data, info);
        }
        long t3 = timing ? System.nanoTime() : 0;
        timeBridge += t3 - t2;

        final byte[] result;
        int bandSize = data[0].length;
        if (data.length == 1) {
            result = data[0];
        } else {
            result = new byte[Math.multiplyExact(data.length, bandSize)];
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
