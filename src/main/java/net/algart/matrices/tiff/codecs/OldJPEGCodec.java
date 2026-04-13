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
import net.algart.matrices.tiff.tags.Tags;
import org.scijava.io.handle.DataHandle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

public class OldJPEGCodec implements TiffCodec {

    @Override
    public byte[] compress(byte[] data, Options options) throws TiffException {
        throw new UnsupportedOperationException("Old-style JPEG encoding is not supported");
    }

    @Override
    public byte[] decompress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        TiffIFD ifd = options.getIfd();
        if (ifd == null) {
            throw new TiffException("Old-style JPEG decoding requires access to full IFD");
        }
        DataHandle<?> stream = options.getStream();
        if (stream == null) {
            throw new TiffException("Old-style JPEG decoding requires access to file stream");
        }

        byte[] jpeg;
        try {
            jpeg = tryBuildCompleteJPEG(data, ifd, stream);
        } catch (IOException e) {
            throw new TiffException("Cannot decode old-style JPEG: " + e.getMessage(), e);
        }
        return new JPEGCodec().decompress(jpeg, options);
    }

    private static byte[] tryBuildCompleteJPEG(byte[] raw, TiffIFD ifd, DataHandle<?> handle) throws IOException {
        if (isJPEG(raw)) {
            return raw;
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 1. SOI (Start of Image)
            writeMarker(out, 0xD8);

            // 2. Insert tables from IFD.
            // These methods return full JPEG markers (FF DB... or FF C4...)
            byte[] qTables = getJpegQTables(ifd, handle);
            if (qTables != null) {
                out.write(qTables);
            }

            byte[] dcTables = getJpegDCTables(ifd, handle);
            if (dcTables != null) {
                out.write(dcTables);
            }

            byte[] acTables = getJpegACTables(ifd, handle);
            if (acTables != null) {
                out.write(acTables);
            }

            // 3. Main data (usually starts with SOS - Start of Scan)
            // Note: If raw data lacks SOS (FF DA) or SOF markers,
            // they might need to be injected here based on other TIFF tags.
            out.write(raw);

            // 4. EOI (End of Image)
            writeMarker(out, 0xD9);

            return out.toByteArray();
        }
    }

  private static byte[] readJpegTables(TiffIFD ifd, DataHandle<?> handle, int tag) throws TiffException {
        if (handle == null) {
            return null;
        }

        final long[] offsets = ifd.getLongArray(tag);
        if (offsets == null || offsets.length == 0) {
            return null;
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (long offset : offsets) {
                byte[] table = readJpegTable(handle, offset);
                out.write(table);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new TiffException("Error reading JPEG tables for tag " +
                    Tags.prettyName(tag, true), e);
        }
    }

    private static byte[] readJpegTable(DataHandle<?> handle, long offset) throws IOException {
        handle.seek(offset);

        // JPEG marker must start with 0xFF
        int first = handle.readUnsignedByte();
        if (first != 0xFF) {
            throw new IOException("Invalid JPEG table at offset " + offset + ": expected 0xFF");
        }

        int marker = handle.readUnsignedByte();
        // DQT = 0xDB, DHT = 0xC4
        int length = handle.readUnsignedShort();

        // Construct a buffer for the full marker: [0xFF, marker, lengthHigh, lengthLow, data...]
        byte[] result = new byte[length + 2];
        result[0] = (byte) 0xFF;
        result[1] = (byte) marker;
        result[2] = (byte) ((length >> 8) & 0xFF);
        result[3] = (byte) (length & 0xFF);

        handle.readFully(result, 4, length - 2);
        return result;
    }

    public static byte[] getJpegQTables(TiffIFD ifd, DataHandle<?> handle) throws TiffException {
        return readJpegTables(ifd, handle, Tags.JPEG_Q_TABLES);
    }

    public static byte[] getJpegDCTables(TiffIFD ifd, DataHandle<?> handle) throws TiffException {
        return readJpegTables(ifd, handle, Tags.JPEG_DC_TABLES);
    }

    public static byte[] getJpegACTables(TiffIFD ifd, DataHandle<?> handle) throws TiffException {
        return readJpegTables(ifd, handle, Tags.JPEG_AC_TABLES);
    }

    private static void writeMarker(ByteArrayOutputStream out, int marker) {
        out.write(0xFF);
        out.write(marker);
    }

    private static boolean isJPEG(byte[] data) {
        return data.length >= 2 &&
                (data[0] & 0xFF) == 0xFF &&
                (data[1] & 0xFF) == 0xD8;
    }
}