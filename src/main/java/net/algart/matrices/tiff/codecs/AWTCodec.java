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
import net.algart.matrices.tiff.UnsupportedTiffFormatException;
import net.algart.matrices.tiff.awt.AWTImages;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.*;
import java.awt.image.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Objects;

public class AWTCodec implements TiffCodec {
    static final boolean RESTRICT_READING_TOO_LARGE_STRIPS = true;
    // - should be true for normal processing some old-style JPEG files

    @Override
    public byte[] compress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        throw new UnsupportedTiffFormatException("Compression is not supported (" +
                options.getCompression() + ")");
    }

    @Override
    public byte[] decompress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        final byte[][] pixelBytes;
        final InputStream input = new ByteArrayInputStream(data);
        boolean littleEndian = options.isLittleEndian();
        final ImageInputStream stream = new MemoryCacheImageInputStream(input);
        // - instead of createImageInputStream, which creates temporary files on disk
        final ImageReader reader = tryToFindImageReader(stream);
        if (reader == null) {
            throw new TiffException("Cannot read image: unknown format (" + options.getCompression() + ")");
        }
        reader.setInput(stream, true, true);
        final ImageReadParam param = buildReadParameters(options, reader);
        final int imageDimX, imageDimY;
        try {
            final BufferedImage image = reader.read(0, param);
            imageDimX = image.getWidth();
            imageDimY = image.getHeight();
            pixelBytes = AWTImages.getImagePixelBytes(image, littleEndian);
        } catch (IOException e) {
            throw new TiffException("Cannot decompress image", e);
        } finally {
            reader.dispose();
        }
        return toDecodedData(pixelBytes, imageDimX, imageDimY, options.isInterleaved());
    }

    protected ImageReader tryToFindImageReader(ImageInputStream stream) {
        Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
        return readers.hasNext() ? readers.next() : null;
    }

    public static ImageReadParam buildReadParameters(Options options, ImageReader reader) {
        final ImageReadParam param = reader.getDefaultReadParam();
        Dimension sizes = RESTRICT_READING_TOO_LARGE_STRIPS && !options.isTiled() ?
                new Dimension(options.getWidth(), options.getHeight()) :
                null;
        // - for stripped image we also specify "sizes" argument that enforces the reader
        // to restrict reading via param.setSourceRegion call;
        // this was useful for some OLD_JPEG (old-style JPEG) files like
        // "libtiff/test/images/ojpeg_chewey_subsamp21_multi_strip.tiff"
        if (sizes != null) {
            param.setSourceRegion(new Rectangle(0, 0, sizes.width, sizes.height));
        }
        return param;
    }

    public static DataBuffer toDataBuffer(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        final int bitsPerSample = options.getNormalizedBitsPerSample();
        if (bitsPerSample != 8 && bitsPerSample != 16) {
            throw new TiffException("Compression for " + bitsPerSample +
                    "-bit samples is not supported (only unsigned 8/16-bit samples allowed)");
            // Note: jai-imageio.jpeg2000 1.4.0 does not work correctly with 32-bit samples
            // due to integer bit-shift overflow (1 << ntdepth[0]) in calcMixedBitDepths and other jai-imageio methods
        }
        final int samplesPerPixel = options.getSamplesPerPixel();
        final boolean interleaved = options.isInterleaved();
        final boolean littleEndian = options.isLittleEndian();
        final int numberOfPixels = Math.multiplyExact(options.getWidth(), options.getHeight());
        if (bitsPerSample == 8) {
            final byte[][] result = new byte[samplesPerPixel][numberOfPixels];
            if (interleaved) {
                for (int next = 0, q = 0; q < numberOfPixels; q++) {
                    for (int c = 0; c < samplesPerPixel; c++) {
                        result[c][q] = data[next++];
                    }
                }
            } else {
                for (int c = 0; c < samplesPerPixel; c++) {
                    System.arraycopy(data, c * numberOfPixels, result[c], 0, numberOfPixels);
                }
            }
            return new DataBufferByte(result, numberOfPixels);
        } else {
            final short[][] result = new short[samplesPerPixel][numberOfPixels];
            if (interleaved) {
                for (int next = 0, q = 0; q < numberOfPixels; q++) {
                    for (int c = 0; c < samplesPerPixel; c++) {
                        // assert toShort(data, next, false) == Bytes.toShort(data, next, 2, false);
                        // assert toShort(data, next, true) == Bytes.toShort(data, next, 2, true);
                        result[c][q] = toShort(data, next, littleEndian);
                        next += 2;
                    }
                }
            } else {
                for (int next = 0, c = 0; c < samplesPerPixel; c++) {
                    for (int q = 0; q < numberOfPixels; q++) {
                        result[c][q] = toShort(data, next, littleEndian);
                        next += 2;
                    }
                }
            }
            return new DataBufferUShort(result, numberOfPixels);
        }
    }

    public static byte[] toDecodedData(byte[][] pixelBytes, Raster raster, boolean interleaved) {
        return toDecodedData(pixelBytes, raster.getWidth(), raster.getHeight(), interleaved);
    }

    public static byte[] toDecodedData(byte[][] pixelBytes, int dimX, int dimY, boolean interleaved) {
        // System.out.println("!!! Interleaved: " + interleaved);
        Objects.requireNonNull(pixelBytes, "Null pixelBytes");
        if (dimX < 0 || dimY < 0) {
            throw new IllegalArgumentException("Negative dimensions " + dimX + " " + dimY);
        }
        if (pixelBytes.length == 0) {
            throw new IllegalArgumentException("pixelBytes is empty");
        }
        pixelBytes = pixelBytes.clone();
        // - guarantees that pixelBytes will not be changed from a parallel thread
        for (int i = 0; i < pixelBytes.length; i++) {
            Objects.requireNonNull(pixelBytes[i], "Null pixelBytes[" + i + "]");
        }
        final int bandSize = pixelBytes[0].length;
        for (int i = 0; i < pixelBytes.length; i++) {
            if (pixelBytes[i].length != bandSize) {
                throw new IllegalArgumentException(
                        "Different pixelBytes lengths: pixelBytes[0].length = "
                                + bandSize + ", pixelBytes[" + i + "].length = "
                                + pixelBytes[i].length);
            }
        }
        if (pixelBytes.length == 1) {
            return pixelBytes[0];
        } else {
            if (interleaved) {
                if (dimX == 0 || dimY == 0) {
                    return new byte[0];
                }
                final int numberOfPixels = Math.multiplyExact(dimX, dimY);
                final int bytesPerSample = bandSize / numberOfPixels;
                if (bytesPerSample * numberOfPixels != bandSize) {
                    throw new IllegalArgumentException("Strange length of pixelBytes[0]: " + bandSize
                        + " is not divisible by " + dimX + "*" +  dimY);
                }
                final byte[] result = new byte[Math.multiplyExact(pixelBytes.length, bandSize)];
                if (bytesPerSample == 1) {
                    for (int next = 0, i = 0; i < bandSize; i++) {
                        for (byte[] bytes : pixelBytes) {
                            result[next++] = bytes[i];
                        }
                    }
                } else {
                    for (int next = 0, i = 0; i < numberOfPixels; i++) {
                        final int disp = i * bytesPerSample;
                        for (byte[] bytes : pixelBytes) {
                            for (int j = 0; j < bytesPerSample; j++) {
                                result[next++] = bytes[disp + j];
                            }
                        }
                    }
                }
                return result;
            } else {
                byte[] result = new byte[Math.multiplyExact(pixelBytes.length, bandSize)];
                for (int i = 0; i < pixelBytes.length; i++) {
                    System.arraycopy(pixelBytes[i], 0, result, i * bandSize, bandSize);
                }
                return result;
            }
        }

    }

    private static short toShort(byte[] src, int srcPos, boolean littleEndian) {
        return (short) (littleEndian ?
                (src[srcPos] & 0xFF) | ((src[srcPos + 1] & 0xFF) << 8) :
                ((src[srcPos] & 0xFF) << 8) | (src[srcPos + 1] & 0xFF));
    }

    // Deprecated, probably will be replaced with JArrays.arrayToBytes
    private static int toInt(byte[] src, int srcPos, boolean littleEndian) {
        return littleEndian ?
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
