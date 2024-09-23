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

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.tags.TagCompression;

import java.io.*;
import java.util.Objects;

public class CCITTFaxCodec implements TiffCodec {
    @Override
    public byte[] compress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        final TiffIFD ifd = options.getIfd();
        Objects.requireNonNull(ifd, "IFD is not set in the options");
        if (options.numberOfChannels != 1 || options.bitsPerSample != 1) {
            throw new TiffException("CCITT compression (" +
                    TagCompression.toPrettyString(options.compressionCode) +
                    ") for " + options.numberOfChannels + " channels and " + options.bitsPerSample +
                    "-bit samples is not allowed (CCITT compressions support 1 sample/pixel, 1 bit/sample only)");
        }

        final ByteArrayOutputStream compressedDataStream = new ByteArrayOutputStream();
        final long writingOptions = TinyTwelveMonkey.getCCITTWritingOptions(ifd, options.compressionCode);
        final OutputStream compressorStream = new CCITTFaxEncoderStreamAdapted(
                compressedDataStream, options.width, options.height, options.compressionCode,
                TinyTwelveMonkey.FILL_LEFT_TO_RIGHT,
                writingOptions);
        // - we always specify FILL_LEFT_TO_RIGHT: TiffWriter performs the bit inversion, if necessary,
        // at the final stage after calling the codec
        try {
            compressorStream.write(data);
            compressorStream.close();
        } catch (IOException e) {
            throw new TiffException(e);
        }
        return compressedDataStream.toByteArray();
    }

    public byte[] decompress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        final TiffIFD ifd = options.getIfd();
        Objects.requireNonNull(ifd, "IFD is not set in the options");

        final ByteArrayInputStream compressedDataStream = new ByteArrayInputStream(data);
        final long readingOptions = TinyTwelveMonkey.getCCITTReadingOptions(ifd, options.compressionCode);
        try {
            final int overrideCCITTCompressionCode = TinyTwelveMonkey.findCCITTType(
                    options.compressionCode, compressedDataStream);
            final InputStream decompressorStream = new CCITTFaxDecoderStreamAdapted(
                    compressedDataStream, options.width, overrideCCITTCompressionCode, readingOptions,
                    options.compressionCode == TinyTwelveMonkey.COMPRESSION_CCITT_MODIFIED_HUFFMAN_RLE);
            // - note: the last byteAligned argument should be determined one the base of compression code,
            // written in TIFF IFD, not on the base of overrideCCITTCompressionCode
            final int bitsPerPixel = options.numberOfChannels * options.bitsPerSample;
            final int bytesPerRow = (bitsPerPixel * options.width + 7) / 8;
            final int resultSize = bytesPerRow * options.height;
            final byte[] result = new byte[resultSize];
            new DataInputStream(decompressorStream).readFully(result);
            return result;
        } catch (IOException e) {
            throw new TiffException(e);
        }
    }

    public byte[] decompressViaJAIImageIO(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");

        final int bitsPerPixel = options.numberOfChannels * options.bitsPerSample;
        final int bytesPerRow = (bitsPerPixel * options.width + 7) / 8;
        final int resultSize = bytesPerRow * options.height;
        byte[] result = new byte[resultSize];
        final TIFFFaxDecompressorAdapted decompressor = new TIFFFaxDecompressorAdapted(options);
        try {
            decompressor.unpackBytes(result, data, bitsPerPixel, bytesPerRow);
        } catch (IOException e) {
            throw new TiffException(e);
        }
        return result;
    }
}
