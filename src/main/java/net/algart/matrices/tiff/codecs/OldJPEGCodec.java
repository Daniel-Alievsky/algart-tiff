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
            return new JPEGCodec().decompress(jpeg, options);
        } catch (IOException e) {
            throw new TiffException("Cannot decode old-style JPEG: " + e.getMessage(), e);
        }
    }

    private static byte[] tryBuildCompleteJPEG(byte[] raw, TiffIFD ifd, Options options, DataHandle<?> handle)
            throws IOException {
        // 1. If it's already a valid JPEG (starts with SOI), return as is
        if (isJPEG(raw)) {
            return raw;
        }

        final int samplesPerPixel = options.getNumberOfChannels();
        // Use TileWidth/Height if available, falling back to ImageWidth/Height
        final int width = options.getWidth();
        final int height = options.getHeight();

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // --- STEP 1: SOI (Start of Image) ---
            out.write(0xFF); out.write(0xD8);

            boolean hasSOF = false;
            boolean hasSOS = false;

            // --- STEP 2: Tables / Header from JPEGInterchangeFormat ---
            long jpegOffset = ifd.getLong(Tags.JPEG_INTERCHANGE_FORMAT, -1);
            long jpegLength = ifd.getLong(Tags.JPEG_INTERCHANGE_FORMAT_LENGTH, -1);

            if (jpegOffset > 0) {
                handle.seek(jpegOffset);
                byte[] interchange = new byte[(int) (jpegLength > 0 ? jpegLength : 1024)];
                int actualRead = handle.read(interchange);

                // Scan interchange for existing markers to avoid duplication
                for (int i = 0; i < actualRead - 1; i++) {
                    if ((interchange[i] & 0xFF) == 0xFF) {
                        int marker = interchange[i + 1] & 0xFF;
                        if (marker >= 0xC0 && marker <= 0xC3) hasSOF = true;
                        if (marker == 0xDA) hasSOS = true;
                    }
                }

                // Write interchange data, skipping its SOI if present
                int startIdx = (actualRead >= 2 && (interchange[0] & 0xFF) == 0xFF && (interchange[1] & 0xFF) == 0xD8) ? 2 : 0;
                out.write(interchange, startIdx, actualRead - startIdx);
            } else {
                // No interchange: build tables from individual TIFF tags
                byte[] q = getJpegQTables(ifd, handle);
                if (q != null) out.write(q);
                byte[] dc = getJpegDCTables(ifd, handle);
                if (dc != null) out.write(dc);
                byte[] ac = getJpegACTables(ifd, handle);
                if (ac != null) out.write(ac);
            }

            // --- STEP 3: Mandatory SOF0 (if not provided by interchange) ---
            if (!hasSOF) {
                int[] subsampling = ifd.getIntArray(Tags.Y_CB_CR_SUB_SAMPLING);
                int subX = (subsampling != null && subsampling.length >= 2) ? subsampling[0] : 2;
                int subY = (subsampling != null && subsampling.length >= 2) ? subsampling[1] : 2;

                out.write(0xFF); out.write(0xC0); // SOF0 marker
                int sofLen = 8 + 3 * samplesPerPixel;
                out.write((sofLen >>> 8) & 0xFF); out.write(sofLen & 0xFF);
                out.write(8); // Precision (8 bits)
                out.write((height >>> 8) & 0xFF); out.write(height & 0xFF);
                out.write((width >>> 8) & 0xFF); out.write(width & 0xFF);
                out.write(samplesPerPixel);
                for (int i = 0; i < samplesPerPixel; i++) {
                    out.write(i + 1); // Component ID (1, 2, 3)
                    out.write(i == 0 ? (((subX & 0x0F) << 4) | (subY & 0x0F)) : 0x11);
                    out.write(i); // Quantization table ID
                }
            }

            // --- STEP 4: Handle SOS and "Wang" raw data offset ---
            int rawOffset = 0;
            if (raw.length >= 4 && (raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xDA) {
                // Raw data starts with an SOS marker. We skip it because we provide our own
                // normalized SOS header to ensure component IDs match our SOF.
                int internalSosLen = ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
                rawOffset = 2 + internalSosLen;
            }

            if (!hasSOS) {
                // Write our normalized SOS header
                out.write(0xFF); out.write(0xDA); // SOS marker
                int sosLen = 6 + 2 * samplesPerPixel;
                out.write((sosLen >>> 8) & 0xFF); out.write(sosLen & 0xFF);
                out.write(samplesPerPixel);
                for (int i = 0; i < samplesPerPixel; i++) {
                    out.write(i + 1); // Component ID must match SOF
                    out.write((i << 4) | i); // Huffman table selectors (DC | AC)
                }
                out.write(0x00); // Start of spectral selection
                out.write(0x3F); // End of spectral selection
                out.write(0x00); // Successive approximation
            }

            // --- STEP 5: Data and EOI ---
            if (rawOffset < raw.length) {
                out.write(raw, rawOffset, raw.length - rawOffset);
            }
            out.write(0xFF); out.write(0xD9); // EOI (End of Image)

            return out.toByteArray();
        }
    }

    private static byte[] readJpegTables(TiffIFD ifd, DataHandle<?> handle, int tag) throws TiffException {
        final long[] offsets = ifd.getLongArray(tag);
        if (offsets == null) return null;

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < offsets.length; i++) {
                // Read and wrap each table found at the given offsets
                byte[] table = readAndWrapTable(handle, offsets[i], tag, i);
                out.write(table);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new TiffException("Error reading tag " + tag, e);
        }
    }

    private static byte[] readAndWrapTable(DataHandle<?> handle, long offset, int tag, int tableIndex) throws IOException {
        handle.seek(offset);

        // Check if the table already has a valid JPEG marker (0xFF)
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

        // If no marker found, return to the start of the offset to read raw data
        handle.seek(offset);

        if (tag == Tags.JPEG_Q_TABLES) {
            // Handle Quantization Tables (DQT)
            byte[] qtable = new byte[64];
            handle.readFully(qtable);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(0xff);
            baos.write(0xDB); // DQT marker
            baos.write(0);    // Length MSB
            baos.write(67);   // Length LSB (2 bytes for length + 1 byte for ID + 64 bytes for table)
            baos.write(tableIndex); // Table ID and precision (0..3)
            baos.write(qtable);
            return baos.toByteArray();
        } else {
            // Handle Huffman Tables (DHT: DC or AC)
            byte[] blengths = new byte[16];
            handle.readFully(blengths);
            int numCodes = 0;
            for (int j = 0; j < 16; j++) {
                numCodes += (blengths[j] & 0xff);
            }

            int markerLength = 19 + numCodes;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            baos.write(0xff);
            baos.write(0xC4); // DHT marker
            baos.write((markerLength >>> 8) & 0xff);
            baos.write(markerLength & 0xff);

            // Define table class: 0 for DC, 1 for AC
            int tableClass = (tag == Tags.JPEG_AC_TABLES) ? 1 : 0;
            // The byte is formatted as (Class << 4) | ID
            baos.write(tableIndex | (tableClass << 4));

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

    private static boolean isJPEG(byte[] data) {
        return data.length >= 2 &&
                (data[0] & 0xFF) == 0xFF &&
                (data[1] & 0xFF) == 0xD8;
    }
}