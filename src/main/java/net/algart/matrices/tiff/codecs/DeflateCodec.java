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

import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * This class implements ZLIB compression/decompression.
 */
public class DeflateCodec implements TiffCodec {
    @Override
    public byte[] compress(byte[] data, Options options) {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        final Double compressionLevel = options.getLosslessCompressionLevel();
        final Deflater deflater;
        if (compressionLevel != null) {
            final int level = compressionLevel <= 0.0 ? 0 :
                    Math.max(1, (int) Math.round(9.0 * Math.min(compressionLevel, 1.0)));
            assert level <= 9;
            deflater = new Deflater(level);
        } else {
            deflater = new Deflater();
        }
        deflater.setInput(data);
        deflater.finish();

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final byte[] buffer = new byte[65536];
        while (!deflater.finished()) {
            final int compressedSize = deflater.deflate(buffer);
            outputStream.write(buffer, 0, compressedSize);
        }
        return outputStream.toByteArray();
    }


    @Override
    public byte[] decompress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Inflater inflater = new Inflater();
        inflater.setInput(data);

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        final byte[] buffer = new byte[65536];
        try {
            while (!inflater.finished()) {
                final int decompressedSize = inflater.inflate(buffer);
                outputStream.write(buffer, 0, decompressedSize);
            }
        } catch (DataFormatException e) {
            throw new TiffException("Invalid TIFF format: broken compressed data in ZIP (Deflate) block", e);
        }
        return outputStream.toByteArray();
    }
}

