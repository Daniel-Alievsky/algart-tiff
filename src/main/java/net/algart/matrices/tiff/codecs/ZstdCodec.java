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

import io.airlift.compress.zstd.ZstdCompressor;
import io.airlift.compress.zstd.ZstdDecompressor;
import net.algart.matrices.tiff.TiffException;

import java.util.Arrays;
import java.util.Objects;

/**
 * This class implements ZStandard compression/decompression.
 */
public class ZstdCodec implements TiffCodec {
    @Override
    public byte[] compress(byte[] data, Options options) {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");

        // options.getLosslessCompressionLevel() is not used yet;
        // it will be used in aircompressor-v3

        final ZstdCompressor compressor = new ZstdCompressor();
        final byte[] compressed = new byte[compressor.maxCompressedLength(data.length)];
        final int compressedSize = compressor.compress(
                data, 0, data.length,
                compressed, 0, compressed.length);
        return Arrays.copyOf(compressed, compressedSize);
    }

    @Override
    public byte[] decompress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        final ZstdDecompressor decompressor = new ZstdDecompressor();
        final byte[] decompressed = new byte[options.getMaxUnpackedSizeInBytes()];
        final int decompressedBytes;
        try {
            decompressedBytes = decompressor.decompress(
                    data, 0, data.length,
                    decompressed, 0, decompressed.length);
        } catch (io.airlift.compress.MalformedInputException e) {
            throw new TiffException("Invalid TIFF format: broken compressed data in ZSTD block", e);
        }
        return Arrays.copyOf(decompressed, decompressedBytes);
    }
}

