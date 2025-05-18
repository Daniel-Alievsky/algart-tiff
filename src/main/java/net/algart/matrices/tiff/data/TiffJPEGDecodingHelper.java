/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2025 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

package net.algart.matrices.tiff.data;

import net.algart.matrices.tiff.TiffException;
import net.algart.matrices.tiff.TiffIFD;
import net.algart.matrices.tiff.UnsupportedTiffFormatException;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.TiffTile;

public class TiffJPEGDecodingHelper {
    private TiffJPEGDecodingHelper() {
    }

    // For example, this is necessary in src/test/resources/demo/images/tiff/libtiffpic/quad-jpeg.tif
    public static void embedJPEGTableInDataIfRequested(TiffTile tile, boolean throwExceptionForStrangeDataStream)
            throws TiffException {
        final TiffIFD ifd = tile.ifd();
        final TagCompression compression = ifd.optCompression();
        if (compression == null || !compression.isJpegOrOldJpeg()) {
            return;
        }
        final byte[] data = tile.getEncodedData();
        final byte[] jpegTable = ifd.getValue(Tags.JPEG_TABLES, byte[].class).orElse(null);
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
        if (data.length < 2 || data[0] != (byte) 0xFF || data[1] != (byte) 0xD8) {
            // - the same check is performed inside Java API ImageIO (JPEGImageReaderSpi),
            // and we prefer to repeat it here for better diagnostics
            if (compression.isStandardJpeg()) {
                throw new TiffException(
                        "Invalid TIFF image: it is declared as JPEG, but the data are not actually JPEG");
            } else {
                if (!throwExceptionForStrangeDataStream) {
                    return;
                }
                throw new UnsupportedTiffFormatException(
                        "Unsupported format of TIFF image: it is declared as \"" + compression.prettyName() +
                                "\", but the data are not actually JPEG");
                // - it is better than throwing strange exception in SCIFIO external codec
                // (like NullPointerException)
            }
        }
        if (jpegTable != null) {
            // We need to include JPEG table into JPEG data stream
            if (jpegTable.length <= 4) {
                throw new TiffException("Too short JPEGTables tag: only " + jpegTable.length + " bytes");
            }
            if ((long) jpegTable.length + (long) data.length - 4 >= Integer.MAX_VALUE) {
                // - very improbable
                throw new TiffException(
                        "Too large tile/strip at " + tile.index() + ": JPEG table length " +
                                (jpegTable.length - 2) + " + number of bytes " +
                                (data.length - 2) + " > 2^31-1");

            }
            final byte[] appended = new byte[jpegTable.length + data.length - 4];
            appended[0] = (byte) 0xFF;
            appended[1] = (byte) 0xD8;
            // - writing SOI
            System.arraycopy(jpegTable, 2, appended, 2, jpegTable.length - 4);
            // - skipping both SOI and EOI (2 first and 2 last bytes) from jpegTable
            System.arraycopy(data, 2, appended, jpegTable.length - 2, data.length - 2);
            // - skipping SOI (2 first bytes) from main data
            tile.setEncodedData(appended);
        }
    }
}
