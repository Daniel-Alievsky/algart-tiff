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

import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.DataHandleInputStream;
import org.scijava.io.location.Location;

import java.io.EOFException;
import java.io.IOException;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;

/**
 * This class implements ZLIB decompression.
 *
 * @author Melissa Linkert
 */
public class ZlibCodec extends AbstractCodec {
    // (It is placed here to avoid autocorrection by IntelliJ IDEA)
    /*
     * #%L
     * SCIFIO library for reading and converting scientific file formats.
     * %%
     * Copyright (C) 2011 - 2023 SCIFIO developers.
     * %%
     * Redistribution and use in source and binary forms, with or without
     * modification, are permitted provided that the following conditions are met:
     *
     * 1. Redistributions of source code must retain the above copyright notice,
     *    this list of conditions and the following disclaimer.
     * 2. Redistributions in binary form must reproduce the above copyright notice,
     *    this list of conditions and the following disclaimer in the documentation
     *    and/or other materials provided with the distribution.
     *
     * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
     * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
     * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
     * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
     * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
     * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
     * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
     * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
     * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
     * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
     * POSSIBILITY OF SUCH DAMAGE.
     * #L%
     */

    static class ByteVector {
        private byte[] data;

        private int size;

        public ByteVector() {
            data = new byte[10];
            size = 0;
        }

        public ByteVector(final int initialSize) {
            data = new byte[initialSize];
            size = 0;
        }

        public ByteVector(final byte[] byteBuffer) {
            data = byteBuffer;
            size = 0;
        }

        public void add(final byte x) {
            while (size >= data.length)
                doubleCapacity();
            data[size++] = x;
        }

        public int size() {
            return size;
        }

        public byte get(final int index) {
            return data[index];
        }

        public void add(final byte[] array) {
            add(array, 0, array.length);
        }

        public void add(final byte[] array, final int off, final int len) {
            while (data.length < size + len)
                doubleCapacity();
            if (len == 1) data[size] = array[off];
            else if (len < 35) {
                // for loop is faster for small number of elements
                for (int i = 0; i < len; i++)
                    data[size + i] = array[off + i];
            } else System.arraycopy(array, off, data, size, len);
            size += len;
        }

        void doubleCapacity() {
            final byte[] tmp = new byte[data.length * 2 + 1];
            System.arraycopy(data, 0, tmp, 0, data.length);
            data = tmp;
        }

        public void clear() {
            size = 0;
        }

        public byte[] toByteArray() {
            final byte[] bytes = new byte[size];
            System.arraycopy(data, 0, bytes, 0, size);
            return bytes;
        }

    }

    @Override
    public byte[] compress(final byte[] data, final Options options) {
        if (data == null || data.length == 0)
            throw new IllegalArgumentException("No data to compress");
        final Deflater deflater = new Deflater();
        deflater.setInput(data);
        deflater.finish();
        final byte[] buf = new byte[8192];
        final ByteVector bytes = new ByteVector();
        int r = 0;
        // compress until eof reached
        while ((r = deflater.deflate(buf, 0, buf.length)) > 0) {
            bytes.add(buf, 0, r);
        }
        return bytes.toByteArray();
    }

    @Override
    public byte[] decompress(final DataHandle<? extends Location> in, Options options) throws IOException {
        final InflaterInputStream i = new InflaterInputStream(
                new DataHandleInputStream<>(in));
        final ByteVector bytes = new ByteVector();
        final byte[] buf = new byte[8192];
        int r = 0;
        // read until eof reached
        try {
            while ((r = i.read(buf, 0, buf.length)) > 0)
                bytes.add(buf, 0, r);
        } catch (final EOFException ignored) {
        }
        return bytes.toByteArray();
    }

}
