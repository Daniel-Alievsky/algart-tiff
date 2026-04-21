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
import net.algart.matrices.tiff.TiffIO;
import net.algart.matrices.tiff.awt.AWTImages;
import net.algart.matrices.tiff.awt.JPEGDecoding;
import net.algart.matrices.tiff.awt.JPEGEncoding;
import net.algart.matrices.tiff.tags.TagPhotometric;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandleInputStream;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public class JPEGCodec extends StreamTiffCodec implements TiffCodec.Timing {
    private static final boolean RESTRICT_READING_TOO_LARGE_STRIPS = true;
    // - should be true for normal processing some old-style JPEG files
    private static final System.Logger LOG = System.getLogger(JPEGCodec.class.getName());

    public static class JPEGCodecReport extends TiffIO.CodecReport {
        private TagPhotometric tiffPhotometric;
        private String encodedColorSpace;
        private boolean losslessJPEG;

        public TagPhotometric getTiffPhotometric() {
            return tiffPhotometric;
        }

        public JPEGCodecReport setTiffPhotometric(TagPhotometric tiffPhotometric) {
            this.tiffPhotometric = tiffPhotometric;
            return this;
        }

        public String getEncodedColorSpace() {
            return encodedColorSpace;
        }

        public JPEGCodecReport setEncodedColorSpace(String encodedColorSpace) {
            this.encodedColorSpace = encodedColorSpace;
            return this;
        }

        public boolean isLosslessJPEG() {
            return losslessJPEG;
        }

        public JPEGCodecReport setLosslessJPEG(boolean losslessJPEG) {
            this.losslessJPEG = losslessJPEG;
            return this;
        }

        @Override
        public String toString() {
            return "JPEG codec report:" +
                    (tiffPhotometric != null ?
                            "%n    Color space declared in TIFF PhotometricInterpretation tag: %s"
                            .formatted(tiffPhotometric) :
                            "") +
                    (encodedColorSpace != null ?
                            "%n    Color space encoded in JPEG stream: %s".formatted(encodedColorSpace) :
                            "") +
                    (losslessJPEG ?
                            "%n    Lossless JPEG".formatted() :
                            "");
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
        if (options.isFloatingPoint()) {
            throw new TiffException("JPEG compression cannot be used for floating-point values");
        }
        final TagPhotometric photometric = options.getPhotometric();
        final int numberOfChannels = options.getNumberOfChannels();
        final int expectedChannels = switch (photometric) {
            case null -> throw new TiffException("Photometric interpretation is not set in the options");
            case BLACK_IS_ZERO -> 1;
            // - unlike decompress() method, compression of WHITE_IS_ZERO is not supported
            case RGB, Y_CB_CR -> 3;
            default -> throw new TiffException("JPEG compression for photometric interpretation " + photometric +
                    " is not supported");
        };
        if (numberOfChannels != expectedChannels) {
            throw new TiffException("JPEG compression for " + numberOfChannels + " channels for " +
                    "photometric interpretation " + photometric + " is not supported");
        }
        final int bitsPerSample = options.getBitsPerSample();
        if (bitsPerSample != 8) {
            throw new TiffException("JPEG compression for " + bitsPerSample +
                    "-bit samples is not supported (only unsigned 8-bit samples allowed)");
        }
        if (options.isSigned()) {
            throw new TiffException("JPEG compression for signed " + bitsPerSample +
                    "-bit samples is not supported (only unsigned 8-bit samples allowed)");
        }
        if (data.length == 0) {
            return data;
        }
        long t1 = timing ? System.nanoTime() : 0;

        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final BufferedImage image = AWTImages.makeImage(
                data, options.getWidth(), options.getHeight(), numberOfChannels,
                options.isInterleaved(),
                bitsPerSample / 8, false, options.isLittleEndian(),
                false);
        // - original SCIFIO codec io.scif.codec.JPEGCodec supports any "signed" parameter,
        // loaded from CodecOptions class, but in does not make sense in TIFF

        long t2 = timing ? System.nanoTime() : 0;
        final double jpegQuality = Math.min(options.compressionQuality(1.0), 1.0);
        // - for JPEG, the maximal possible quality is 1.0, but it is better to allow greater qualities
        // (for comparison, the maximal quality in JPEG-2000 is Double.MAX_VALUE)
        try {
            JPEGEncoding.writeJPEG(image, output, photometric, jpegQuality);
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
        Objects.requireNonNull(in, "Null input stream");
        Objects.requireNonNull(options, "Null codec options");
        final JPEGCodecReport report = new JPEGCodecReport();
        options.setReport(report);
        report.setTiffPhotometric(options.getPhotometric());
        final long offset = in.offset();
        long t1 = timing ? System.nanoTime() : 0;
        JPEGDecoding.ImageData imageData;
        try (InputStream input = new BufferedInputStream(new DataHandleInputStream<>(in), 8192)) {
            imageData = JPEGDecoding.readJPEG(
                    input,
                    RESTRICT_READING_TOO_LARGE_STRIPS && !options.isTiled() ?
                            new Dimension(options.getWidth(), options.getHeight()) :
                            null,
                    options.getPhotometric(),
                    options.getNumberOfChannels(),
                    options.isLittleEndian());
            // - for stripped image we also specify "sizes" argument that enforces readJPEG
            // to restrict reading via param.setSourceRegion call;
            // this is necessary for some OLD_JPEG (old-style JPEG) files like
            // "libtiff/test/images/ojpeg_chewey_subsamp21_multi_strip.tiff"
            LOG.log(System.Logger.Level.TRACE, "TIFF JPEG image decoded using standard AWT codec");
        } catch (IOException jpegException) {
            // probably a lossless JPEG; delegate to LosslessJPEGCodec
            in.seek(offset);
            byte[] tryLossless = (new LosslessJPEGCodec()).decompress(in, options);
            assert tryLossless != null;
            if (tryLossless.length > 0) {
                report.setLosslessJPEG(true);
                LOG.log(TiffIO.BUILT_IN_TIMING ? System.Logger.Level.DEBUG : System.Logger.Level.TRACE,
                        "TIFF JPEG image decoded using lossless-JPEG codec");
                return tryLossless;
            }
            // zero length usually means that SOF3 (lossless JPEG) was not found
            throw jpegException;
        }
        if (imageData == null) {
            throw new TiffException("Cannot read JPEG image: unknown format");
            // - for example, OLD_JPEG
        }
        report.setEncodedColorSpace(imageData.colorSpaceName());
        long t2 = timing ? System.nanoTime() : 0;
        timeMain += t2 - t1;

        final byte[][] data = imageData.pixelBytes();

        JPEGDecoding.completeDecodingYCbCr(data, imageData, options.getPhotometric(), options.getYCbCrSubsampling());
        JPEGDecoding.completeDecodingWhiteIsZero(data, imageData, options.getPhotometric());
        long t3 = timing ? System.nanoTime() : 0;
        timeBridge += t3 - t2;

        final byte[] result;
        int bandSize = data[0].length;
        if (data.length == 1) {
            result = data[0];
        } else {
            result = new byte[Math.multiplyExact(data.length, bandSize)];
            if (options.isInterleaved()) {
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
