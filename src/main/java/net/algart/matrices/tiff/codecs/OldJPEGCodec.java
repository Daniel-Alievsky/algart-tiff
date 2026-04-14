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
import net.algart.matrices.tiff.TiffIO;
import net.algart.matrices.tiff.UnsupportedTiffFormatException;
import net.algart.matrices.tiff.tags.Tags;
import org.scijava.io.handle.DataHandle;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;

public class OldJPEGCodec implements TiffCodec {

    @Override
    public byte[] compress(byte[] data, Options options) throws TiffException {
        throw new UnsupportedTiffFormatException("Old-style JPEG compression is not supported");
    }

    @Override
    public byte[] decompress(byte[] data, Options options) throws TiffException {
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(options, "Null codec options");
        TiffIFD ifd = options.getIfd();
        if (ifd == null) {
            throw new TiffException("Old-style JPEG decoding requires access to full IFD");
        }
        final TiffIO reader = options.getIo();
        if (reader == null) {
            throw new TiffException("Old-style JPEG decoding requires access to TIFF reader");
        }
        final DataHandle<?> stream = reader.stream();
        final Object fileLock = reader.fileLock();
        try {
            byte[] jpeg;
            synchronized (fileLock) {
                jpeg = tryBuildCompleteJPEG(data, ifd, options, stream);
            }
            return new JPEGCodec().decompress(jpeg, options);
        } catch (IOException e) {
            throw new TiffException("Cannot decode old-style JPEG: " + e.getMessage(), e);
        }
    }

    private static byte[] tryBuildCompleteJPEG(byte[] raw, TiffIFD ifd, Options options, DataHandle<?> handle)
            throws IOException {
        // 1. If it's already a valid JPEG (starts with SOI), return as-is
        if (isJPEG(raw)) {
            return raw;
        }

        final int samplesPerPixel = options.getNumberOfChannels();
        final int width = options.getWidth();
        final int height = options.getHeight();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // STEP 1: SOI (Start of Image)
            out.write(0xFF);
            out.write(0xD8);

            // STEP 2: Handle JPEG tables and headers.
            // According to TIFF Supplement 2 (Old-style JPEG), tables can be stored in two ways:
            // 1. As a single block pointed to by JPEGInterchangeFormat (Tag 513). This block
            // usually contains all necessary DQT, DHT, and SOF markers.
            // 2. As individual components in JPEGQTables (519), JPEGDCTables (520), and
            // JPEGACTables (521) tags.
            // If JPEGInterchangeFormat is present, it takes precedence. We must ignore
            // individual table tags to avoid duplicating markers in the resulting JPEG stream,
            // which could lead to decoding errors.

            final long interchangeOffset = ifd.getLong(Tags.JPEG_INTERCHANGE_FORMAT, -1);

            if (interchangeOffset > 0) {
                final int interchangeLength = ifd.reqInt(Tags.JPEG_INTERCHANGE_FORMAT_LENGTH);
                handle.seek(interchangeOffset);
                byte[] interchange = new byte[interchangeLength];
                int actualRead = handle.read(interchange);

                // Scan interchange for existing markers to avoid duplication (heuristic)
                boolean hasSOF = false;
                boolean hasSOS = false;
                for (int i = 0; i < actualRead - 1; i++) {
                    if ((interchange[i] & 0xFF) == 0xFF) {
                        int marker = interchange[i + 1] & 0xFF;
                        if (marker >= 0xC0 && marker <= 0xCF &&
                                marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                            hasSOF = true;
                        }
                        if (marker == 0xDA) {
                            hasSOS = true;
                        }
                        if (hasSOF && hasSOS) {
                            break;
                        }
                    }
                }

                // Write interchange data, skipping its SOI if present
                int startIdx = actualRead >= 2 &&
                        (interchange[0] & 0xFF) == 0xFF &&
                        (interchange[1] & 0xFF) == 0xD8 ?
                        2 : 0;
                out.write(interchange, startIdx, actualRead - startIdx);
                if (!hasSOF) {
                    writeSOF0(out, ifd, height, width, samplesPerPixel, true);
                }
                if (!hasSOS) {
                    writeSOS(out, samplesPerPixel, true);
                }
            } else {
                // No interchange: build tables from individual TIFF tags
                byte[][] qTables = getJpegQTables(ifd, handle);
                if (qTables != null) {
                    for (int i = 0; i < qTables.length; i++) {
                        writeDQT(out, i, qTables[i]);
                    }
                }
                byte[][] dcTables = getJpegDCTables(ifd, handle);
                if (dcTables != null) {
                    for (int i = 0; i < dcTables.length; i++) {
                        writeDHT(out, 0, i, dcTables[i]); // 0 = DC
                    }
                }
                byte[][] acTables = getJpegACTables(ifd, handle);
                if (acTables != null) {
                    for (int i = 0; i < acTables.length; i++) {
                        writeDHT(out, 1, i, acTables[i]); // 1 = AC
                    }
                }
                writeSOF0(out, ifd, height, width, samplesPerPixel, false);
                writeSOS(out, samplesPerPixel, false);
            }

            int rawOffset = 0;
            if (raw.length >= 4 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xDA) {
                // Raw data starts with an SOS marker. We skip it because we provide our own
                // normalized SOS header to ensure component IDs match our SOF.
                int internalSosLen = ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
                rawOffset = 2 + internalSosLen;
            }

            // --- STEP 5: Data and EOI ---
            if (rawOffset < raw.length) {
                out.write(raw, rawOffset, raw.length - rawOffset);
            }
            out.write(0xFF);
            out.write(0xD9); // EOI (End of Image)

            return out.toByteArray();
        }
    }

    public static byte[][] getJpegQTables(TiffIFD ifd, DataHandle<?> handle) throws TiffException {
        return readRawJpegTables(ifd, handle, Tags.JPEG_Q_TABLES, 64);
    }

    public static byte[][] getJpegDCTables(TiffIFD ifd, DataHandle<?> handle) throws TiffException {
        // For Huffman tables, we don't have a fixed size, so we pass -1
        return readRawJpegTables(ifd, handle, Tags.JPEG_DC_TABLES, -1);
    }

    public static byte[][] getJpegACTables(TiffIFD ifd, DataHandle<?> handle) throws TiffException {
        return readRawJpegTables(ifd, handle, Tags.JPEG_AC_TABLES, -1);
    }

    private static byte[][] readRawJpegTables(TiffIFD ifd, DataHandle<?> handle, int tag, int fixedSize)
            throws TiffException {
        final long[] offsets = ifd.getLongArray(tag);
        if (offsets == null) {
            return null;
        }
        byte[][] tables = new byte[offsets.length][];
        try {
            for (int i = 0; i < offsets.length; i++) {
                handle.seek(offsets[i]);
                if (fixedSize > 0) {
                    byte[] raw = new byte[fixedSize];
                    handle.readFully(raw);
                    tables[i] = raw;
                } else {
                    byte[] lengths = new byte[16];
                    handle.readFully(lengths);
                    int count = 0;
                    for (byte b : lengths) {
                        count += b & 0xFF;
                    }
                    if (count > 256) {
                        throw new TiffException("Invalid JPEG Huffman table: too many symbols (" + count + ")");
                    }
                    byte[] raw = new byte[16 + count];
                    System.arraycopy(lengths, 0, raw, 0, 16);
                    handle.readFully(raw, 16, count);
                    tables[i] = raw;
                }
            }
        } catch (IOException e) {
            throw new TiffException("Error reading raw JPEG tables for tag " + tag, e);
        }
        return tables;
    }

    private static void writeSOS(ByteArrayOutputStream out, int samplesPerPixel, boolean twoTables) {
        out.write(0xFF);
        out.write(0xDA); // SOS marker
        int sosLen = 6 + 2 * samplesPerPixel;
        out.write((sosLen >>> 8) & 0xFF);
        out.write(sosLen & 0xFF);
        out.write(samplesPerPixel);
        for (int i = 0; i < samplesPerPixel; i++) {
            out.write(i + 1); // Component ID must match SOF
            int tableId = twoTables && i > 0 ? 1 : i;
            out.write((tableId << 4) | tableId); // DC | AC
        }
        out.write(0x00); // Start of spectral selection
        out.write(0x3F); // End of spectral selection
        out.write(0x00); // Successive approximation
    }

    private static void writeSOF0(
            ByteArrayOutputStream out,
            TiffIFD ifd,
            int height,
            int width,
            int samplesPerPixel,
            boolean twoTables)
            throws TiffException {
        int[] subsampling = ifd.getIntArray(Tags.Y_CB_CR_SUB_SAMPLING);
        int subX = samplesPerPixel == 1 ? 1 : subsampling != null ? subsampling[0] : 2;
        int subY = samplesPerPixel == 1 ? 1 : subsampling != null ? subsampling[1] : 2;

        out.write(0xFF);
        out.write(0xC0); // SOF0 marker
        int sofLen = 8 + 3 * samplesPerPixel;
        out.write((sofLen >>> 8) & 0xFF);
        out.write(sofLen & 0xFF);
        out.write(8); // Precision (8 bits)
        out.write((height >>> 8) & 0xFF);
        out.write(height & 0xFF);
        out.write((width >>> 8) & 0xFF);
        out.write(width & 0xFF);
        out.write(samplesPerPixel);
        for (int i = 0; i < samplesPerPixel; i++) {
            out.write(i + 1); // Component ID (1, 2, 3)
            out.write(i == 0 ? (((subX & 0x0F) << 4) | (subY & 0x0F)) : 0x11);
            out.write(twoTables && i > 0 ? 1 : i); // Quantization table ID
        }
    }

    private static void writeDQT(ByteArrayOutputStream out, int tableId, byte[] table) throws IOException {
        out.write(0xFF);
        out.write(0xDB);
        int length = 2 + 1 + table.length; // length(2) + identifier(1) + data
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(tableId & 0x0F); // Precision 0 (8-bit) and Table ID
        out.write(table);
    }

    private static void writeDHT(ByteArrayOutputStream out, int tableClass, int tableId, byte[] table)
            throws IOException {
        out.write(0xFF);
        out.write(0xC4);
        int length = 2 + 1 + table.length; // length(2) + class/ID(1) + data
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write((tableClass << 4) | (tableId & 0x0F));
        out.write(table);
    }

    private static boolean isJPEG(byte[] data) {
        return data.length >= 2 &&
                (data[0] & 0xFF) == 0xFF &&
                (data[1] & 0xFF) == 0xD8;
    }
}