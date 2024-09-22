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
import net.algart.matrices.tiff.UnsupportedTiffFormatException;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.Tags;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Objects;

public class CCITTFaxCodec implements TiffCodec {
    @Override
    public byte[] compress(final byte[] data, final Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        final TiffIFD ifd = options.getIfd();
        Objects.requireNonNull(ifd, "IFD is not set in the options");
        throw new UnsupportedTiffFormatException("Writing with TIFF compression " +
                TagCompression.toPrettyString(ifd.optInt(Tags.COMPRESSION, TagCompression.UNCOMPRESSED.code())) +
                " is not supported");
    }


    public byte[] decompress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        final TiffIFD ifd = options.getIfd();
        Objects.requireNonNull(ifd, "IFD is not set in the options");

        final int compression = ifd.reqInt(Tags.COMPRESSION);
        final long ccittOptions = CCITTFaxDecoderStreamAdapted.getCCITTOptions(ifd, compression);
        final CCITTFaxDecoderStreamAdapted decompressorStream = new CCITTFaxDecoderStreamAdapted(
                new ByteArrayInputStream(data), options.width, compression, ccittOptions);
        byte[] result = new byte[options.maxSizeInBytes];
        try {
            new DataInputStream(decompressorStream).readFully(result);
        } catch (IOException e) {
            throw new TiffException(e);
        }
        return result;
    }

    public byte[] decompressViaJAIImageIO(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");

        final int bitsPerPixel = options.numberOfChannels * options.bitsPerSample;
        final int bytesPerRow = (bitsPerPixel * options.width + 7) / 8;

        byte[] result = new byte[options.maxSizeInBytes];
        final TIFFFaxDecompressorAdapted decompressor = new TIFFFaxDecompressorAdapted(options);
        try {
            decompressor.unpackBytes(result, data, bitsPerPixel, bytesPerRow);
        } catch (IOException e) {
            throw new TiffException(e);
        }
        return result;
    }
}
