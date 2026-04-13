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
import net.algart.matrices.tiff.TiffIFD;
import org.scijava.io.handle.DataHandle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

public class OldJPEGCodec extends StreamTiffCodec {

    private final JPEGCodec delegate = new JPEGCodec();

    @Override
    public byte[] compress(byte[] data, Options options) throws TiffException {
        throw new UnsupportedOperationException("OLD_JPEG encoding is not supported");
    }

    @Override
    public byte[] decompress(DataHandle<?> in, Options options) throws IOException {
        Objects.requireNonNull(in, "Null input");
        Objects.requireNonNull(options, "Null options");

        TiffIFD ifd = options.getIfd();
        if (ifd == null) {
            throw new TiffException("OLD_JPEG requires access to full IFD");
        }

        byte[] raw = readAll(in);

        byte[] jpeg = tryBuildCompleteJPEG(raw, ifd);

        if (jpeg == null) {
            // fallback: maybe it's already a valid JPEG
            return delegate.decompress(raw, options);
        }

        return delegate.decompress(jpeg, options);
    }

    private static byte[] readAll(DataHandle<?> in) throws IOException {
        long len = in.length();
        if (len > Integer.MAX_VALUE) {
            throw new IOException("Too large JPEG segment");
        }
        byte[] data = new byte[(int) len];
        in.readFully(data);
        return data;
    }

    private static byte[] tryBuildCompleteJPEG(byte[] raw, TiffIFD ifd) {
        // If already looks like JPEG → return as-is
        if (isJPEG(raw)) {
            return raw;
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            // SOI
            writeMarker(out, 0xD8);

            // --- Insert tables from IFD if present ---
/*            byte[][] qTables = ifd.getJpegQTables();
            if (qTables != null) {
                for (int i = 0; i < qTables.length; i++) {
                    writeDQT(out, i, qTables[i]);
                }
            }

            byte[][] dcTables = ifd.getJpegDCTables();
            if (dcTables != null) {
                for (int i = 0; i < dcTables.length; i++) {
                    writeDHT(out, 0, i, dcTables[i]);
                }
            }

            byte[][] acTables = ifd.getJpegACTables();
            if (acTables != null) {
                for (int i = 0; i < acTables.length; i++) {
                    writeDHT(out, 1, i, acTables[i]);
                }
            }
*/
            // --- Raw data ---
            out.write(raw);

            // EOI
            writeMarker(out, 0xD9);

            return out.toByteArray();

        } catch (Exception e) {
            return null;
        }
    }

    private static boolean isJPEG(byte[] data) {
        return data.length >= 2 &&
                (data[0] & 0xFF) == 0xFF &&
                (data[1] & 0xFF) == 0xD8;
    }

    private static void writeMarker(ByteArrayOutputStream out, int marker) {
        out.write(0xFF);
        out.write(marker);
    }

    private static void writeDQT(ByteArrayOutputStream out, int tableId, byte[] table) throws IOException {
        writeMarker(out, 0xDB);
        int length = 2 + 1 + table.length;
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(tableId);
        out.write(table);
    }

    private static void writeDHT(
            ByteArrayOutputStream out,
            int tableClass, // 0=DC, 1=AC
            int tableId,
            byte[] table) throws IOException {

        writeMarker(out, 0xC4);
        int length = 2 + 1 + table.length;
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write((tableClass << 4) | tableId);
        out.write(table);
    }
}