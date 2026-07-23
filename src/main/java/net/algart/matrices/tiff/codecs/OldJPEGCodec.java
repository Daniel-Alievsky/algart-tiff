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
import net.algart.matrices.tiff.awt.JPEGMarkerInspector;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.Tags;
import org.scijava.io.handle.DataHandle;

import javax.imageio.IIOException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

public class OldJPEGCodec implements TiffCodec {
    public static class OldJPEGCodecReport extends TiffIO.CodecReport {
        private boolean normalJPEGStream = false;
        private boolean basedOnInterchange = false;
        private boolean basedOnTables = false;
        private boolean syntheticSOS = false;

        public boolean isNormalJPEGStream() {
            return normalJPEGStream;
        }

        public OldJPEGCodecReport setNormalJPEGStream(boolean normalJPEGStream) {
            this.normalJPEGStream = normalJPEGStream;
            return this;
        }

        public boolean isBasedOnInterchange() {
            return basedOnInterchange;
        }

        public OldJPEGCodecReport setBasedOnInterchange(boolean basedOnInterchange) {
            this.basedOnInterchange = basedOnInterchange;
            return this;
        }

        public boolean isBasedOnTables() {
            return basedOnTables;
        }

        public OldJPEGCodecReport setBasedOnTables(boolean basedOnTables) {
            this.basedOnTables = basedOnTables;
            return this;
        }

        public boolean isSyntheticSOS() {
            return syntheticSOS;
        }

        public OldJPEGCodecReport setSyntheticSOS(boolean syntheticSOS) {
            this.syntheticSOS = syntheticSOS;
            return this;
        }

        @Override
        public String toString() {
            return "Old-style JPEG decoder report:" +
                    (normalJPEGStream ?
                            "%n    Normal JPEG stream detected".formatted() :
                            "") +
                    (basedOnInterchange ?
                            "%n    Decoded using JPEGInterchangeFormat tag".formatted() :
                            "") +
                    (basedOnTables ?
                            "%n    Decoded using tables (JPEGQTables, JPEGDCTables, JPEGACTables tags)"
                            .formatted() :
                            "") +
                    (syntheticSOS ?
                            "%n    Synthetic SOS (start-of-scan) marker added to JPEG interchange data".formatted() :
                            "");
        }
    }

    private static final int DHT_DC_CLASS = 0;
    private static final int DHT_AC_CLASS = 1;

    /**
     * Note: this implementation only delegates to {@link JPEGCodec#compress(byte[], Options)}.
     * This is <b>not</b> a valid implementation for {@link TagCompression#OLD_JPEG},
     * so {@link TagCompression#isWritingSupported()} method returns {@code false} for this constant.
     * But this is better than nothing.
     */
    @Override
    public byte[] compress(byte[] data, Options options) throws TiffException {
        return new JPEGCodec().compress(data, options);
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
            final OldJPEGCodecReport report = new OldJPEGCodecReport();
            byte[] jpeg;
            synchronized (fileLock) {
                jpeg = tryBuildCompleteJPEG(data, ifd, stream, options, report);
            }
            final byte[] result = new JPEGCodec().decompress(jpeg, options);
            options.setReport(report);
            return result;
        } catch (IOException e) {
            throw new TiffException("Cannot decode old-style JPEG: " + e.getMessage(), e);
        }
    }

    private static byte[] tryBuildCompleteJPEG(
            byte[] raw,
            TiffIFD ifd,
            DataHandle<?> stream,
            Options options,
            OldJPEGCodecReport report)
            throws IOException {
        Objects.requireNonNull(raw, "Null raw");
        Objects.requireNonNull(ifd, "Null IFD");
        Objects.requireNonNull(stream, "Null stream");
        Objects.requireNonNull(options, "Null codec options");
        final int jpegProc = ifd.getInt(Tags.OLD_JPEG_PROC, 1);
        if (jpegProc != 1 && jpegProc != 14) {
            // 1 is "Baseline", 14 is "Lossless"
            throw new IIOException("Unknown TIFF JPEGProc value: " + jpegProc);
            // Actually we do not support "Lossless" and always try to interpret data as "Baseline":
            // "Lossless" is a very rare case and probably written by a mistake?
        }
        if (isJPEG(raw)) {
            report.setNormalJPEGStream(true);
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

            final boolean hasInterchange = ifd.hasTag(Tags.OLD_JPEG_INTERCHANGE_FORMAT);
            final boolean hasTables = ifd.hasTag(Tags.OLD_JPEG_Q_TABLES) &&
                    ifd.hasTag(Tags.OLD_JPEG_DC_TABLES) &&
                    ifd.hasTag(Tags.OLD_JPEG_AC_TABLES);
            byte[] interchange = hasInterchange ? readJpegInterchange(ifd, stream) : null;
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
                report.setBasedOnInterchange(true);
                // Scan interchange for existing markers to avoid duplication (heuristic)
                final JPEGMarkerInspector markers = JPEGMarkerInspector.of(interchange);
                final int startOffset = markers.hasSOI() ? 2 : 0;
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
                if (!markers.hasSOF()) {
                    throw new TiffException(
                            "Cannot decode old-style JPEG: JPEGInterchangeFormat does not contain " +
                                    "necessary Start-Of-Frame (SOF) marker");
                }
                out.write(interchange, startOffset, interchange.length - startOffset);
                if (!markers.hasSOS()) {
                    // - this is possible in Wang TIFF files, containing SOS in another place
                    // (the beginning of the 1st strip);
                    // in this case, we will try to synthesize SOS on the base on SOF
                    if (markers.sofMarker() != JPEGDecoding.SOF0_BASELINE) {
                        throw new TiffException("Cannot decode old-style JPEG: " +
                                "JPEGInterchangeFormat does not contain Start-Of-Scan (SOS) marker and " +
                                "uses non-baseline format");
                    }
                    report.setSyntheticSOS(true);
                    writeSOS(out, samplesPerPixel, markers);
                }
                return finishJPEG(out, raw, true);
            } else {
                if (jpegProc == 14) {
                    throw new UnsupportedTiffFormatException(
                            "Cannot decode old-style JPEG: lossless JPEG (JPEGProc=14) is not supported");
                }
                report.setBasedOnTables(true);
                final byte[][] qTables = readJpegQTables(ifd, stream);
                for (int i = 0; i < qTables.length; i++) {
                    writeDQT(out, i, qTables[i]);
                }
                final byte[][] dcTables = readJpegDCTables(ifd, stream);
                for (int i = 0; i < dcTables.length; i++) {
                    writeDHT(out, DHT_DC_CLASS, i, dcTables[i]);
                }
                final byte[][] acTables = readJpegACTables(ifd, stream);
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

    public static byte[] readJpegInterchange(TiffIFD ifd, DataHandle<?> stream) throws IOException {
        final long interchangeOffset = ifd.getLong(Tags.OLD_JPEG_INTERCHANGE_FORMAT, -1);
        if (interchangeOffset <= 0) {
            return null;
        }
        final int interchangeLength = ifd.getInt(Tags.OLD_JPEG_INTERCHANGE_FORMAT_LENGTH, -1);
        if (interchangeLength <= 0) {
            return null;
        }
        stream.seek(interchangeOffset);
        byte[] data = new byte[interchangeLength];
        final int actualLength = stream.read(data);
        return actualLength == data.length ? data : Arrays.copyOf(data, actualLength);
    }


    public static byte[][] readJpegQTables(TiffIFD ifd, DataHandle<?> stream) throws IOException {
        return readRawJpegTables(ifd, stream, Tags.OLD_JPEG_Q_TABLES, true);
    }

    public static byte[][] readJpegDCTables(TiffIFD ifd, DataHandle<?> stream) throws IOException {
        // For Huffman tables, we don't have a fixed size, so we pass -1
        return readRawJpegTables(ifd, stream, Tags.OLD_JPEG_DC_TABLES, false);
    }

    public static byte[][] readJpegACTables(TiffIFD ifd, DataHandle<?> stream) throws IOException {
        return readRawJpegTables(ifd, stream, Tags.OLD_JPEG_AC_TABLES, false);
    }

    private static byte[][] readRawJpegTables(TiffIFD ifd, DataHandle<?> stream, int tag, boolean quantizationTables)
            throws IOException {
        final long[] offsets = ifd.reqLongArray(tag);
        assert offsets != null;
        byte[][] tables = new byte[offsets.length][];
        for (int i = 0; i < offsets.length; i++) {
            stream.seek(offsets[i]);
            if (quantizationTables) {
                byte[] raw = new byte[64];
                // Structure of Quantization Tables, TIFF tag 519: 8x8 = 64 bytes
                stream.readFully(raw);
                tables[i] = raw;
            } else {
                // Structure of a raw Huffman table, TIFF tags 520/521:
                // 1. BITS: 16 bytes.
                //    Each byte i contains the number of codes with length i bits.
                // 2. HUFFVAL: n bytes (where n is the sum of all bytes in BITS).
                //    Contains the symbols associated with each code.
                // Total length = 16 + sum(BITS).
                byte[] lengths = new byte[16];
                stream.readFully(lengths);
                int count = 0;
                for (byte b : lengths) {
                    count += b & 0xFF;
                }
                if (count > 256) {
                    throw new TiffException("Invalid JPEG Huffman table: too many symbols (" + count + ")");
                }
                byte[] raw = new byte[16 + count];
                System.arraycopy(lengths, 0, raw, 0, 16);
                stream.readFully(raw, 16, count);
                tables[i] = raw;
            }
        }
        return tables;
    }

    private static void writeSOS(ByteArrayOutputStream stream, int samplesPerPixel, JPEGMarkerInspector markers) {
        final boolean useMarkers = markers != null && markers.hasSOF();
        if (useMarkers) {
            samplesPerPixel = markers.sofNumberOfChannels();
        }
        stream.write(0xFF);
        stream.write(JPEGDecoding.SOS_BYTE); // SOS marker
        int sosLen = 6 + 2 * samplesPerPixel;
        stream.write((sosLen >>> 8) & 0xFF);
        stream.write(sosLen & 0xFF);
        stream.write(samplesPerPixel);
        for (int i = 0; i < samplesPerPixel; i++) {
            stream.write(useMarkers ? markers.sofComponentId(i) : i + 1);
            // - component ID must match SOF
            int tableId = useMarkers ? markers.sofDQTIndexes(i) : i;
            // - we synthesize absent SOS on the base of existing SOF,
            // assuming that the table IDs are the same for DQT and DHT
            stream.write((tableId << 4) | tableId);
        }
        stream.write(0x00); // Start of spectral selection
        stream.write(0x3F); // End of spectral selection
        stream.write(0x00); // Successive approximation
    }

    private static void writeSOF0(
            ByteArrayOutputStream stream,
            TiffIFD ifd,
            int height,
            int width,
            int samplesPerPixel)
            throws TiffException {
        int[] subsampling = ifd.getIntArray(Tags.Y_CB_CR_SUB_SAMPLING);
        int subX = samplesPerPixel == 1 ? 1 : subsampling != null ? subsampling[0] : 2;
        int subY = samplesPerPixel == 1 ? 1 : subsampling != null ? subsampling[1] : 2;

        stream.write(0xFF);
        stream.write(JPEGDecoding.SOF0_BASELINE); // SOF0 marker
        int sofLen = 8 + 3 * samplesPerPixel;
        stream.write((sofLen >>> 8) & 0xFF);
        stream.write(sofLen & 0xFF);
        stream.write(8); // Precision (8 bits)
        stream.write((height >>> 8) & 0xFF);
        stream.write(height & 0xFF);
        stream.write((width >>> 8) & 0xFF);
        stream.write(width & 0xFF);
        stream.write(samplesPerPixel);
        for (int i = 0; i < samplesPerPixel; i++) {
            stream.write(i + 1);
            // - component ID (1, 2, 3)
            stream.write(i == 0 ? (((subX & 0x0F) << 4) | (subY & 0x0F)) : 0x11);
            stream.write(i);
            // - quantization table ID = i
        }
    }

    private static void writeDQT(ByteArrayOutputStream stream, int tableId, byte[] table) throws IOException {
        stream.write(0xFF);
        stream.write(JPEGDecoding.DQT_BYTE);
        int length = 2 + 1 + table.length;
        // - length(2) + identifier(1) + data
        stream.write((length >> 8) & 0xFF);
        stream.write(length & 0xFF);
        stream.write(tableId & 0x0F);
        // - precision 0 (8-bit) and Table ID
        stream.write(table);
    }

    private static void writeDHT(ByteArrayOutputStream stream, int tableClass, int tableId, byte[] table)
            throws IOException {
        stream.write(0xFF);
        stream.write(JPEGDecoding.DHT_BYTE);
        int length = 2 + 1 + table.length;
        // - length(2) + class/ID(1) + data
        stream.write((length >> 8) & 0xFF);
        stream.write(length & 0xFF);
        stream.write((tableClass << 4) | (tableId & 0x0F));
        stream.write(table);
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
}