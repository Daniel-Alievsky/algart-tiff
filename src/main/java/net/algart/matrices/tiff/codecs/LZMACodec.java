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
import org.tukaani.xz.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

/**
 * LZMA codec (Lempel–Ziv–Markov chain Algorithm) for TIFF Compression=34925.
 */
public class LZMACodec implements TiffCodec {

    @Override
    public byte[] compress(byte[] data, Options options) throws IOException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");

        final LZMA2Options lzmaOptions = new LZMA2Options();
        final Double level = options.getLosslessCompressionLevel();
        if (level != null) {
            int preset = level <= 0.0 ? 0 : Math.max(1, (int) Math.round(9.0 * Math.min(level, 1.0)));
            try {
                lzmaOptions.setPreset(preset);
            } catch (UnsupportedOptionsException e) {
                throw new IllegalArgumentException("Invalid LZMA2 preset", e);
            }
        }

        final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try (XZOutputStream stream = new XZOutputStream(outputStream, lzmaOptions, XZ.CHECK_NONE)) {
            stream.write(data);
            stream.finish();
        }
        return outputStream.toByteArray();
    }

    @Override
    public byte[] decompress(byte[] data, Options options) throws IOException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");

        try (XZInputStream stream = new XZInputStream(new ByteArrayInputStream(data))) {
            final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            final byte[] buffer = new byte[65536];
            int len;
            while ((len = stream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, len);
            }
            return outputStream.toByteArray();
        } catch (CorruptedInputException e) {
            throw new TiffException("Invalid TIFF format: broken LZMA/XZ data", e);
        }
    }
}