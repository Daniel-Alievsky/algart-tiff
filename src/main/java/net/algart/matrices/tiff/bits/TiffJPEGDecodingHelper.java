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

package net.algart.matrices.tiff.bits;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.awt.JPEGDecoding;
import net.algart.matrices.tiff.awt.JPEGMarkerInspector;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagType;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.TiffTile;

public class TiffJPEGDecodingHelper {
    private TiffJPEGDecodingHelper() {
    }

    // For example, this is necessary in src/test/resources/demo/images/tiff/libtiffpic/quad-jpeg.tif
    public static void embedJPEGTableInDataIfRequested(TiffTile tile) throws TiffException {
        final TiffIFD ifd = tile.ifd();
        final TagCompression compression = ifd.optCompression().orElse(null);
        if (compression == null || !compression.isStandardJpeg()) {
            // This method is designed for standard JPEG only (code 7)!
            // For Old-style JPEG (code 6), using JPEG_TABLES has no sense: we should use
            // JPEG_Q_TABLES, JPEG_DC_TABLES, JPEG_AC_TABLES or JPEG_INTERCHANGE_FORMAT
            return;
        }
        final byte[] data = tile.getEncodedData();
        final byte[] jpegTable = ifd.getValue(Tags.JPEG_TABLES, byte[].class, TagType.UNDEFINED).orElse(null);
        // Structure of data:
        //      FF D8 (SOI, start of image)
        //      FF C0 (SOF0, start of frame, or some other marker)
        //      ...
        //      FF D9 (EOI, end of image)
        // Structure of jpegTable:
        //      FF D8 (SOI, start of image)
        //      FF DB (DQT, define quantization table(s)
        //      ...
        //      FF D9 (EOI, end of image)
        // From libtiff specification:
        //      When the JPEGTables field is present, it shall contain a valid JPEG
        //      "abbreviated table specification" data stream. This data stream shall begin
        //      with SOI and end with EOI.
        final JPEGMarkerInspector inspector = JPEGMarkerInspector.of(data);
        if (!inspector.hasSOI()) {
            // - the same check is performed inside Java API ImageIO (JPEGImageReaderSpi),
            // and we prefer to repeat it here for better diagnostics
            throw new TiffException(
                    "Invalid TIFF image: it is declared as JPEG, but the data are not actually JPEG: " +
                            "no starting Start-Of-Image (SOI) marker");
        }
        assert data[0] == (byte) 0xFF && data[1] == (byte) JPEGDecoding.SOI_BYTE : "but JPEGMarkerInspector.hasSOI!";
        if (jpegTable != null) {
            // If the tile already contains a complete JPEG stream (has both DQT and DHT),
            // or if it is not a valid JPEG (without SOF), we don't embed JPEGTables.
            if (!inspector.isProbablyAbbreviatedStream()) {
//                System.out.printf("Skipping embedding tables into %s...%n", tile);
                return;
            }
            assert !inspector.hasDQT() || !inspector.hasDHT() : "invalid isAbbreviatedStream";
            // We need to include JPEG table into JPEG data stream
            final int m = jpegTable.length;
//            System.out.printf("Embedding tables into %s...%n", tile);
            if (m <= 4) {
                throw new TiffException("Too short JPEGTables tag: only " + m + " bytes");
            }
            if (jpegTable[0] != (byte) 0xFF || jpegTable[1] != (byte) JPEGDecoding.SOI_BYTE) {
                throw new TiffException("Invalid JPEGTables: expected SOI marker in first two bytes");

            }
            if (jpegTable[m - 2] != (byte) 0xFF || jpegTable[m - 1] != (byte) JPEGDecoding.EOI_BYTE) {
                throw new TiffException("Invalid JPEGTables: expected EOI marker in last two bytes");
            }
            if ((long) m + (long) data.length - 4 >= Integer.MAX_VALUE) {
                // - very improbable
                throw new TiffException(
                        "Too large tile/strip at " + tile.index() + ": JPEGTables length " +
                                (m - 2) + " + number of bytes " + (data.length - 2) + " > 2^31-1");
            }
            final byte[] newData = new byte[m + data.length - 4];
            newData[0] = (byte) 0xFF;
            newData[1] = (byte) JPEGDecoding.SOI_BYTE;
            // - writing SOI, as in data[0..1]
            System.arraycopy(jpegTable, 2, newData, 2, m - 4);
            // - excluding both SOI and EOI (2 first and 2 last bytes) from jpegTable
            System.arraycopy(data, 2, newData, m - 2, data.length - 2);
            // - excluding SOI data[0..1], but including EOI in the end of data
            tile.setEncodedData(newData);
        }
        // However, if inspector.isProbablyAbbreviatedStream() and JPEGTables is ABSENT,
        // this is not necessarily an error: for example, lossless JPEG also has no DQT/DHT tables
    }
}
