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

package net.algart.matrices.tiff.tiles;

import org.scijava.io.handle.DataHandle;

import java.io.IOException;
import java.util.Objects;

public class TiffTileIO {
    private TiffTileIO() {
    }

    public static void read(TiffTile tile, DataHandle<?> in, long fileOffset, int dataLength) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(in, "Null input stream");
        if (fileOffset < 0) {
            throw new IllegalArgumentException("Negative file position to read data: " + fileOffset);
        }
        if (fileOffset == 0) {
            throw new IllegalArgumentException("Zero file position to read data is not allowed in TIFF format");
        }
        if (dataLength < 0) {
            throw new IllegalArgumentException("Negative length of data to read: " + dataLength);
        }
        final byte[] data = new byte[dataLength];
        in.seek(fileOffset);
        final int result = in.read(data);
        if (result < data.length) {
            throw new IOException("File exhausted at " + fileOffset +
                    ": loaded " + result + " bytes instead of " + data.length +
                    " (" + in.get() + ")");
        }
        tile.setStoredInFileDataRange(fileOffset, data.length);
        tile.setEncodedData(data);
    }

    public static void writeAtExistingPositionIfPossible(
            TiffTile tile,
            DataHandle<?> out,
            boolean strictlyRequire32Bit) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        if (tile.isEncodedDataFitsInCapacity()) {
            write(tile, out, tile.getStoredInFileDataOffset());
        } else {
            writeAtEnd(tile, out, strictlyRequire32Bit);
        }
    }

    public static void writeAtEnd(
            TiffTile tile,
            DataHandle<?> out,
            boolean strictlyRequire32Bit) throws IOException {
//        System.out.printf("%s: %s%n", out, tile.toString());
        final long fileOffset = offsetAtEnd(tile, out, strictlyRequire32Bit);
        write(tile, out, fileOffset);
        tile.resetStoredInFileDataCapacity();
    }

    public static void write(TiffTile tile, DataHandle<?> out, long fileOffset) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(out, "Null output stream");
        if (fileOffset < 0) {
            throw new IllegalArgumentException("Negative file position to write data: " + fileOffset);
        }
        if (fileOffset == 0) {
            throw new IllegalArgumentException("Zero file position to write data is not allowed in TIFF format");
        }
        final byte[] encodedData = tile.getEncodedData();
        out.seek(fileOffset);
        out.write(encodedData);
        tile.setStoredInFileDataRange(fileOffset, encodedData.length);
    }

    private static long offsetAtEnd(TiffTile tile, DataHandle<?> out, boolean strictlyRequire32Bit)
            throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(out, "Null output stream");
        final long length = out.length();
        if (strictlyRequire32Bit && length > 0xFFFFFFF0L - tile.getEncodedData().length) {
            throw new IOException("Attempt to write TIFF tile outside maximal allowed 32-bit file length 2^32-16 = " +
                    0xFFFFFFF0L + "; such large files should be written in Big-TIFF mode");
        }
        return length;
    }
}
