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
import org.scijava.io.handle.BytesHandle;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.Location;

import java.io.IOException;

abstract class StreamTiffCodec implements TiffCodec {
    @Override
    public byte[] decompress(byte[] data, Options options) throws TiffException {
        try {
            try (DataHandle<?> handle = new BytesHandle(new BytesLocation(data))) {
                return decompress(handle, options);
            }
        } catch (IOException e) {
            throw e instanceof TiffException tiffException ? tiffException : new TiffException(e);
            // - last variant is very improbable
        }
    }


    /**
     * Decompresses data from the given DataHandle.
     *
     * @param in      The stream from which to read compressed data.
     * @param options Options to be used during decompression.
     * @return The decompressed data.
     * @throws TiffException If data is not valid, compressed data for this decompressor.
     */
    abstract byte[] decompress(DataHandle<?> in, Options options) throws IOException;
}
