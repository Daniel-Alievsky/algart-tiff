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
            jpeg = tryBuildCompleteJPEG(data, ifd, options, stream);
        } catch (IOException e) {
            throw new TiffException("Cannot decode old-style JPEG: " + e.getMessage(), e);
        }
        return new JPEGCodec().decompress(jpeg, options);
    }

    private static byte[] tryBuildCompleteJPEG(byte[] raw, TiffIFD ifd, Options options, DataHandle<?> handle)
            throws IOException {
        // Если это уже полноценный JPEG (Case 1), возвращаем как есть
        if (isJPEG(raw)) {
            return raw;
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // 1. SOI (Start of Image)
            writeMarker(out, 0xD8);

            // 2. TABLES (Case 4/5)
            // Проверяем JPEGInterchangeFormat для Case 4 (таблицы могут быть общими в заголовке файла)
            long interchangeOffset = ifd.getLong(Tags.JPEG_INTERCHANGE_FORMAT, -1);
            long interchangeLength = ifd.getLong(Tags.JPEG_INTERCHANGE_FORMAT_LENGTH, -1);

            if (interchangeOffset >= 0 && interchangeLength >= 2) {
                handle.seek(interchangeOffset);
                byte[] tables = new byte[(int) interchangeLength];
                handle.readFully(tables);

                // TwelveMonkeys отрезает SOI и EOI из этого блока, если они есть
                int start = (tables[0] & 0xFF) == 0xFF && (tables[1] & 0xFF) == 0xD8 ? 2 : 0;
                int end = (tables[tables.length - 2] & 0xFF) == 0xFF && (tables[tables.length - 1] & 0xFF) == 0xD9
                        ? tables.length - 2 : tables.length;
                out.write(tables, start, end - start);
            } else {
                // Case 5: Собираем таблицы из тегов Q/DC/AC вручную (наша предыдущая логика)
                byte[] q = getJpegQTables(ifd, handle);
                if (q != null) out.write(q);
                byte[] dc = getJpegDCTables(ifd, handle);
                if (dc != null) out.write(dc);
                byte[] ac = getJpegACTables(ifd, handle);
                if (ac != null) out.write(ac);
            }

            // 3. SOF0 (Start of Frame)
            // TwelveMonkeys берет субдискретизацию из тега 530
            int[] subsampling = ifd.getIntArray(Tags.Y_CB_CR_SUB_SAMPLING);
            int subX = (subsampling != null && subsampling.length >= 2) ? subsampling[0] : 2;
            int subY = (subsampling != null && subsampling.length >= 2) ? subsampling[1] : 2;

            writeSOF0_Advanced(out, options.getWidth(), options.getHeight(), options.getNumberOfChannels(), subX, subY);

            // 4. SOS (Start of Scan)
            // Если raw не начинается с SOS, добавляем его
            if (!((raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xDA)) {
                writeSOS(out, options.getNumberOfChannels());
            }

            // 5. DATA
            out.write(raw);

            // 6. EOI
            writeMarker(out, 0xD9);

            return out.toByteArray();
        }
    }

    private static void writeSOF0_Advanced(ByteArrayOutputStream out, int w, int h, int samples, int subX, int subY)
            throws IOException {
        writeMarker(out, 0xC0);
        int length = 8 + (samples * 3);
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(8); // Precision
        out.write((h >> 8) & 0xFF);
        out.write(h & 0xFF);
        out.write((w >> 8) & 0xFF);
        out.write(w & 0xFF);
        out.write(samples);

        for (int i = 0; i < samples; i++) {
            out.write(i + 1); // Component ID
            if (i == 0) {
                // Для Y используем реальную субдискретизацию (обычно 0x22 или 0x21)
                out.write(((subX & 0x0F) << 4) | (subY & 0x0F));
            } else {
                // Для Cb/Cr всегда 1x1
                out.write(0x11);
            }
            out.write(i); // Q-Table ID
        }
    }

    private static void writeSOS(ByteArrayOutputStream out, int samples) throws IOException {
        writeMarker(out, 0xDA);
        int length = 6 + (samples * 2);
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(samples);
        for (int i = 0; i < samples; i++) {
            out.write(i + 1);
            out.write((i << 4) | i); // DC/AC table IDs
        }
        out.write(0x00); // Start of spectral
        out.write(0x3F); // End of spectral
        out.write(0x00); // Successive approximation
    }

    private static byte[] readJpegTables(TiffIFD ifd, DataHandle<?> handle, int tag) throws TiffException {
        final long[] offsets = ifd.getLongArray(tag);
        if (offsets == null) return null;

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < offsets.length; i++) {
                byte[] table = readAndWrapTable(handle, offsets[i], tag, i);
                out.write(table);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new TiffException("Error reading tag " + tag, e);
        }
    }

    private static byte[] readAndWrapTable(DataHandle<?> handle, long offset, int tag, int tableId) throws IOException {
        handle.seek(offset);
        // Проверяем, нет ли там уже готового маркера (Case 1 у TwelveMonkeys)
        int first = handle.readUnsignedByte();
        if (first == 0xFF) {
            int marker = handle.readUnsignedByte();
            int length = handle.readUnsignedShort();
            byte[] result = new byte[length + 2];
            result[0] = (byte) 0xFF;
            result[1] = (byte) marker;
            result[2] = (byte) ((length >> 8) & 0xFF);
            result[3] = (byte) (length & 0xFF);
            handle.readFully(result, 4, length - 2);
            return result;
        }

        // Если данных нет, возвращаемся к началу смещения
        handle.seek(offset);

        if (tag == Tags.JPEG_Q_TABLES) {
            // Логика JAI/TwelveMonkeys для DQT
            byte[] qtable = new byte[64];
            handle.readFully(qtable);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(0xff);
            baos.write(0xDB); // DQT
            baos.write(0);    // Length MSB
            baos.write(67);   // Length LSB (2 + 1 + 64)
            baos.write(tableId); // У них это: "Table ID and precision"
            baos.write(qtable);
            return baos.toByteArray();
        } else {
            // Логика JAI/TwelveMonkeys для DHT (DC/AC)
            byte[] blengths = new byte[16];
            handle.readFully(blengths);
            int numCodes = 0;
            for (int j = 0; j < 16; j++) {
                numCodes += (blengths[j] & 0xff);
            }

            int markerLength = 19 + numCodes;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(0xff);
            baos.write(0xC4); // DHT
            baos.write((markerLength >>> 8) & 0xff);
            baos.write(markerLength & 0xff);

            // В TwelveMonkeys: i | (k << 4), где k=0 для DC, k=1 для AC
            int tableClass = (tag == Tags.JPEG_AC_TABLES) ? 1 : 0;
            baos.write(tableId | (tableClass << 4));

            baos.write(blengths);
            byte[] bcodes = new byte[numCodes];
            handle.readFully(bcodes);
            baos.write(bcodes);
            return baos.toByteArray();
        }
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