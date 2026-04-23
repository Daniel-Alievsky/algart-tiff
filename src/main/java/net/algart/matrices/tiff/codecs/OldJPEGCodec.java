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
import net.algart.matrices.tiff.awt.JPEGDecoding;
import net.algart.matrices.tiff.tags.Tags;
import org.scijava.io.handle.DataHandle;

import javax.imageio.IIOException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public class OldJPEGCodec implements TiffCodec {
    private static final int DHT_DC_CLASS = 0;
    private static final int DHT_AC_CLASS = 1;

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
        Objects.requireNonNull(raw, "Null raw");
        Objects.requireNonNull(ifd, "Null IFD");
        Objects.requireNonNull(handle, "Null handle");
        Objects.requireNonNull(options, "Null codec options");
        final int jpegProc = ifd.getInt(Tags.OLD_JPEG_PROC, 1);
        if (jpegProc != 1 && jpegProc != 14) {
            // 1 is "Baseline", 14 is "Lossless"
            throw new IIOException("Unknown TIFF JPEGProc value: " + jpegProc);
            // Actually we do not support "Lossless" and always try to interpret data as "Baseline":
            // "Lossless" is a very rare case and probably written by a mistake?
        }
        if (isJPEG(raw)) {
            return raw;
        }
        final int samplesPerPixel = options.getSamplesPerPixel();
        if (samplesPerPixel > 16) {
            throw new TiffException("Cannot decode old-style JPEG: " +
                    "too many number of channels = " + samplesPerPixel);
        }
        final int width = options.getWidth();
        final int height = options.getHeight();
//        System.out.printf("%x %x %x %x ...%n", raw[0], raw[1], raw[2], raw[3]);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            out.write(0xFF);
            out.write(JPEGDecoding.SOI_BYTE);

            // According to TIFF Supplement 2 (Old-style JPEG), tables can be stored in two ways:
            // 1. As a single block pointed to by JPEGInterchangeFormat (Tag 513). This block
            // usually contains all necessary DQT, DHT, and SOF markers.
            // 2. As individual components in JPEGQTables (519), JPEGDCTables (520), and JPEGACTables (521) tags.
            // If JPEGInterchangeFormat is present, it takes precedence. We must ignore
            // individual table tags to avoid duplicating markers in the resulting JPEG stream,
            // which could lead to decoding errors.

            final boolean hasInterchange = ifd.containsKey(Tags.OLD_JPEG_INTERCHANGE_FORMAT);
            final boolean hasTables = ifd.containsKey(Tags.OLD_JPEG_Q_TABLES) &&
                    ifd.containsKey(Tags.OLD_JPEG_DC_TABLES) &&
                    ifd.containsKey(Tags.OLD_JPEG_AC_TABLES);
            byte[] interchange = hasInterchange ? readJpegInterchange(ifd, handle) : null;
            if (hasInterchange) {
                if (interchange == null || !isStartingWithMarker(interchange)) {
                    if (hasTables) {
                        interchange = null;
                    } else {
                        // see readJpegInterchange: it returns null if OLD_JPEG_INTERCHANGE_FORMAT_LENGTH is not found
                        throw new TiffException(
                                "Cannot decode old-style JPEG: " + (interchange == null ?
                                        "TIFF tag " + Tags.prettyName(Tags.OLD_JPEG_INTERCHANGE_FORMAT_LENGTH) +
                                        " is missing, JPEGInterchangeFormat cannot be decoded" :
                                        "JPEGInterchangeFormat does not start with a marker"));
                    }
                }
            }
            if (interchange != null) {
                // Scan interchange for existing markers to avoid duplication (heuristic)
                final TinyJPEGMarkers markers = new TinyJPEGMarkers(interchange, "interchange");
                final int startOffset = markers.hasSOI ? 2 : 0;
                // - if interchange data does not start with SOI, but start with another marker (like DHT),
                // we will still use these data without skipping first 2 bytes

                /*
                // Below is more simple, but inaccurate and obsolete version: false detection is theoretically possible
                hasSOF = false;
                hasSOS = false;
                for (int i = 0; i < interchange.length - 1; i++) {
                    if ((interchange[i] & 0xFF) == 0xFF) {
                        int marker = interchange[i + 1] & 0xFF;
                        if (marker >= 0xC0 && marker <= 0xCF &&
                                marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                            hasSOF = true;
                        }
                        if (marker == SOS_BYTE) {
                            hasSOS = true;
                        }
                        if (hasSOF && hasSOS) {
                            break;
                        }
                    }
                }
                */
                if (!markers.hasSOF) {
                    throw new TiffException(
                            "Cannot decode old-style JPEG: JPEGInterchangeFormat does not contain " +
                                    "necessary Start-Of-Frame (SOF) marker");
                }
                out.write(interchange, startOffset, interchange.length - startOffset);
                if (!markers.hasSOS) {
                    // - this is possible in Wang TIFF files, containing SOS in another place
                    // (the beginning of the 1st strip);
                    // in this case, we will try to synthesize SOS on the base on SOF
                    if (markers.sofMarker != JPEGDecoding.SOF0_BASELINE) {
                        throw new TiffException("Cannot decode old-style JPEG: " +
                                "JPEGInterchangeFormat does not contain Start-Of-Scan (SOS) marker and " +
                                "uses non-baseline format");
                    }
                    writeSOS(out, samplesPerPixel, markers);
                }
                return finishJPEG(out, raw, true);
            } else {
                if (jpegProc == 14) {
                    throw new UnsupportedTiffFormatException(
                            "Cannot decode old-style JPEG: lossless JPEG (JPEGProc=14) is not supported");
                }
                final byte[][] qTables = readJpegQTables(ifd, handle);
                for (int i = 0; i < qTables.length; i++) {
                    writeDQT(out, i, qTables[i]);
                }
                final byte[][] dcTables = readJpegDCTables(ifd, handle);
                for (int i = 0; i < dcTables.length; i++) {
                    writeDHT(out, DHT_DC_CLASS, i, dcTables[i]);
                }
                final byte[][] acTables = readJpegACTables(ifd, handle);
                for (int i = 0; i < acTables.length; i++) {
                    writeDHT(out, DHT_AC_CLASS, i, acTables[i]);
                }
                writeSOF0(out, ifd, height, width, samplesPerPixel);
                writeSOS(out, samplesPerPixel, null);
                return finishJPEG(out, raw, false);
            }
        }
    }

    private static byte[] finishJPEG(ByteArrayOutputStream out, byte[] raw, boolean interchange) {
        int rawOffset = interchange ? sosOffset(raw) : 0;
        if (rawOffset < raw.length) {
            out.write(raw, rawOffset, raw.length - rawOffset);
        }
        out.write(0xFF);
        out.write(JPEGDecoding.EOI_BYTE);
        return out.toByteArray();
    }

    private static int sosOffset(byte[] raw) {
        if (raw.length < 4) {
            return 0;
        }
        if ((raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == JPEGDecoding.SOS_BYTE) {
            // Raw data starts with an SOS marker. We skip it because we provide our own
            // normalized SOS header to ensure component IDs match our SOF.
            // Necessary for old-style-jpeg-bogus-jpeginterchangeformatlength.tif (for example).
            // Here false detection is impossible due to byte stuffing
            // (every 0xFF in raw data is replaced with 0xFF-0x00)
            int internalSosLen = ((raw[2] & 0xFF) << 8) | (raw[3] & 0xFF);
            return 2 + internalSosLen;
        }
        return 0;
    }

    public static byte[] readJpegInterchange(TiffIFD ifd, DataHandle<?> handle) throws IOException {
        final long interchangeOffset = ifd.getLong(Tags.OLD_JPEG_INTERCHANGE_FORMAT, -1);
        if (interchangeOffset <= 0) {
            return null;
        }
        final int interchangeLength = ifd.getInt(Tags.OLD_JPEG_INTERCHANGE_FORMAT_LENGTH, -1);
        if (interchangeLength <= 0) {
            return null;
        }
        handle.seek(interchangeOffset);
        byte[] data = new byte[interchangeLength];
        final int actualLength = handle.read(data);
        return actualLength == data.length ? data : Arrays.copyOf(data, actualLength);
    }


    public static byte[][] readJpegQTables(TiffIFD ifd, DataHandle<?> handle) throws IOException {
        return readRawJpegTables(ifd, handle, Tags.OLD_JPEG_Q_TABLES, true);
    }

    public static byte[][] readJpegDCTables(TiffIFD ifd, DataHandle<?> handle) throws IOException {
        // For Huffman tables, we don't have a fixed size, so we pass -1
        return readRawJpegTables(ifd, handle, Tags.OLD_JPEG_DC_TABLES, false);
    }

    public static byte[][] readJpegACTables(TiffIFD ifd, DataHandle<?> handle) throws IOException {
        return readRawJpegTables(ifd, handle, Tags.OLD_JPEG_AC_TABLES, false);
    }

    private static byte[][] readRawJpegTables(TiffIFD ifd, DataHandle<?> handle, int tag, boolean quantizationTables)
            throws IOException {
        final long[] offsets = ifd.reqLongArray(tag);
        assert offsets != null;
        byte[][] tables = new byte[offsets.length][];
        for (int i = 0; i < offsets.length; i++) {
            handle.seek(offsets[i]);
            if (quantizationTables) {
                byte[] raw = new byte[64];
                // Structure of Quantization Tables, TIFF tag 519: 8x8 = 64 bytes
                handle.readFully(raw);
                tables[i] = raw;
            } else {
                // Structure of a raw Huffman table, TIFF tags 520/521:
                // 1. BITS: 16 bytes.
                //    Each byte i contains the number of codes with length i bits.
                // 2. HUFFVAL: n bytes (where n is the sum of all bytes in BITS).
                //    Contains the symbols associated with each code.
                // Total length = 16 + sum(BITS).
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
        return tables;
    }

    private static void writeSOS(ByteArrayOutputStream out, int samplesPerPixel, TinyJPEGMarkers markers) {
        final boolean useMarkers = markers != null && markers.hasSOF;
        if (useMarkers) {
            samplesPerPixel = markers.sofNumberOfChannels;
        }
        out.write(0xFF);
        out.write(JPEGDecoding.SOS_BYTE); // SOS marker
        int sosLen = 6 + 2 * samplesPerPixel;
        out.write((sosLen >>> 8) & 0xFF);
        out.write(sosLen & 0xFF);
        out.write(samplesPerPixel);
        for (int i = 0; i < samplesPerPixel; i++) {
            out.write(useMarkers ? markers.sofComponentId[i] : i + 1);
            // - component ID must match SOF
            int tableId = useMarkers ? markers.sofDQTIndexes[i] : i;
            // - we synthesize absent SOS on the base of existing SOF,
            // assuming that the table IDs are the same for DQT and DHT
            out.write((tableId << 4) | tableId);
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
            int samplesPerPixel)
            throws TiffException {
        int[] subsampling = ifd.getIntArray(Tags.Y_CB_CR_SUB_SAMPLING);
        int subX = samplesPerPixel == 1 ? 1 : subsampling != null ? subsampling[0] : 2;
        int subY = samplesPerPixel == 1 ? 1 : subsampling != null ? subsampling[1] : 2;

        out.write(0xFF);
        out.write(JPEGDecoding.SOF0_BASELINE); // SOF0 marker
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
            out.write(i + 1);
            // - component ID (1, 2, 3)
            out.write(i == 0 ? (((subX & 0x0F) << 4) | (subY & 0x0F)) : 0x11);
            out.write(i);
            // - quantization table ID = i
        }
    }

    private static void writeDQT(ByteArrayOutputStream out, int tableId, byte[] table) throws IOException {
        out.write(0xFF);
        out.write(JPEGDecoding.DQT_BYTE);
        int length = 2 + 1 + table.length;
        // - length(2) + identifier(1) + data
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(tableId & 0x0F);
        // - precision 0 (8-bit) and Table ID
        out.write(table);
    }

    private static void writeDHT(ByteArrayOutputStream out, int tableClass, int tableId, byte[] table)
            throws IOException {
        out.write(0xFF);
        out.write(JPEGDecoding.DHT_BYTE);
        int length = 2 + 1 + table.length;
        // - length(2) + class/ID(1) + data
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write((tableClass << 4) | (tableId & 0x0F));
        out.write(table);
    }

    private static boolean isStartingWithMarker(byte[] data) {
        return data.length >= 2 &&
                (data[0] & 0xFF) == 0xFF &&
                (data[1] & 0xFF) != 0 &&
                (data[1] & 0xFF) != 0xFF;
    }

    private static boolean isJPEG(byte[] data) {
        return isStartingWithMarker(data) &&
                (data[1] & 0xFF) == JPEGDecoding.SOI_BYTE;
        // Note: due to byte stuffing (every 0xFF in raw data is replaced with 0xFF-0x00),
        // raw JPEG data in a strip/tile cannot start from these 2 bytes
    }

    private static class TinyJPEGMarkers {
        boolean hasSOI = false;
        boolean hasSOF = false;
        int sofMarker = -1;
        boolean hasSOS = false;
        int sofNumberOfChannels = 0;
        int[] sofComponentId = null;
        int[] sofDQTIndexes = null;

        TinyJPEGMarkers(byte[] data, String dataName) throws TiffException {
            Objects.requireNonNull(data, "Null data");
            if (data.length < 2) {
                return;
            }
            int p = 0;
            if (data[0] == (byte) 0xFF && data[1] == (byte) JPEGDecoding.SOI_BYTE) {
                hasSOI = true;
                p = 2;
            }
            while (p < data.length - 1) {
                if (data[p] != (byte) 0xFF) {
                    p++;
                    continue;
                    // strange stream: let's search for the next 0xFF
                }
                while (p + 1 < data.length && data[p + 1] == (byte) 0xFF) {
                    // skipping several 0xFF
                    p++;
                }
                if (p + 1 >= data.length) {
                    return;
                }
                final int marker = data[p + 1] & 0xFF;
                switch (marker) {
                    case 0 -> {
                        // stuffed byte
                        // (not too important for OldJPEGCodec: it should not occur in JPEGInterchangeFormat)
                        p += 2;
                        continue;
                    }
                    case JPEGDecoding.SOI_BYTE -> {
                        // strange stream: several SOI
                        p += 2;
                        continue;
                    }
                    case JPEGDecoding.SOS_BYTE -> {
                        hasSOS = true;
                        // entropy data start here: we don't need the following markers
                        // (entropy data are not included into JPEGInterchangeFormat)
                        return;
                    }
                    case JPEGDecoding.EOI_BYTE -> {
                        return;
                    }
                    case JPEGDecoding.SOF0_BASELINE,
                         0xC1, 0xC2, 0xC3,
                         // not 0xC4 (DHT)
                         0xC5, 0xC6, 0xC7,
                         // not 0xC8 (JPG)
                         0xC9, 0xCA, 0xCB,
                         // not 0xCC (DAC)
                         0xCD, 0xCE, 0xCF -> {
                        if (p < data.length - 10) {
                            // - in other case, this SOF is invalid
                            final int n = data[p + 9] & 0xFF;
                            if (n > 16) {
                                throw new TiffException("Cannot decode old-style JPEG: " +
                                        dataName + " contains an invalid Start-Of-Frame (SOF) marker with " +
                                        "too many number of channels = " + n);
                            }
                            if (p + 10 < data.length - 3 * n) {
                                // - in other case, this SOF is invalid
                                hasSOF = true;
                                sofMarker = marker;
                                sofNumberOfChannels = n;
                                sofDQTIndexes = new int[sofNumberOfChannels];
                                sofComponentId = new int[sofNumberOfChannels];
                                for (int i = 0; i < sofNumberOfChannels; i++) {
                                    sofComponentId[i] = data[p + 10 + 3 * i] & 0xFF;
                                    sofDQTIndexes[i] = data[p + 12 + 3 * i] & 0xF;
                                    // - cannot be >15
                                }
                            }
                        }
                    }
                }
                boolean hasLength = marker != JPEGDecoding.TEM_BYTE &&
                        !(marker >= JPEGDecoding.RST_FIRST && marker <= JPEGDecoding.RST_LAST);
                if (!hasLength) {
                    p += 2;
                } else if (p < data.length - 3) {
                    final int segmentLength = ((data[p + 2] & 0xFF) << 8) | (data[p + 3] & 0xFF);
                    p += 2 + segmentLength;
                } else {
                    return;
                }
            }
        }
    }
}