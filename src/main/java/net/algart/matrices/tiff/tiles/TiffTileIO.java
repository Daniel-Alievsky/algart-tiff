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

package net.algart.matrices.tiff.tiles;

import org.scijava.io.handle.DataHandle;

import java.io.IOException;
import java.util.Objects;

public class TiffTileIO {
    private TiffTileIO() {
    }

    public static void readAt(TiffTile tile, DataHandle<?> inputStream, long fileOffset, int dataLength)
            throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(inputStream, "Null input stream");
        if (fileOffset < 0) {
            throw new IllegalArgumentException("Negative file position to read data: " + fileOffset);
        }
        if (fileOffset == 0) {
            throw new IllegalArgumentException("Zero file position to read data is not allowed for TIFF format");
        }
        if (dataLength < 0) {
            throw new IllegalArgumentException("Negative length of data to read: " + dataLength);
        }
        final byte[] data = new byte[dataLength];
        inputStream.seek(fileOffset);
        final int result = inputStream.read(data);
        if (result < data.length) {
            throw new IOException("File exhausted at " + fileOffset +
                    ": loaded " + result + " bytes instead of " + data.length +
                    " (" + inputStream.get() + ")");
        }
        tile.setStoredInFileDataRange(fileOffset, data.length, true);
        tile.setEncodedData(data);
    }

    public static void write(
            TiffTile tile,
            DataHandle<?> outputStream,
            boolean alwaysWriteToFileEnd,
            boolean strictlyRequire32Bit) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        if (alwaysWriteToFileEnd || !tryToWriteInPlace(tile, outputStream)) {
            writeAtEnd(tile, outputStream, strictlyRequire32Bit);
        }
    }

    public static void writeAtEnd(
            TiffTile tile,
            DataHandle<?> outputStream,
            boolean strictlyRequire32Bit) throws IOException {
//        System.outputStream.printf("!!! Writing to end: %s: %s%n", outputStream, tile.toString());
        final long fileOffset = offsetAtEnd(tile, outputStream, strictlyRequire32Bit);
        writeAt(tile, outputStream, fileOffset, true);
    }

    public static void writeAt(TiffTile tile, DataHandle<?> outputStream, long fileOffset, boolean resetCapacity)
            throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(outputStream, "Null output stream");
        if (fileOffset < 0) {
            throw new IllegalArgumentException("Negative file position to write data: " + fileOffset);
        }
        if (fileOffset == 0) {
            throw new IllegalArgumentException("Zero file position to write data is not allowed in TIFF format");
        }
        final byte[] encodedData = tile.getEncodedData();
        outputStream.seek(fileOffset);
        outputStream.write(encodedData);
        tile.setStoredInFileDataRange(fileOffset, encodedData.length, resetCapacity);
    }

    private static boolean tryToWriteInPlace(TiffTile tile, DataHandle<?> outputStream) throws IOException {
        if (!tile.isStoredInFile()) {
            return false;
        }
        final long fileOffset = tile.getStoredInFileDataOffset();
        if (fileOffset + tile.getStoredInFileDataLength() == outputStream.length()) {
            writeAt(tile, outputStream, fileOffset, true);
            outputStream.setLength(outputStream.offset());
            return true;
        }
        if (tile.getEncodedDataLength() <= tile.getStoredInFileDataCapacity()) {
            writeAt(tile, outputStream, fileOffset, false);
            return true;
        }
        return false;
    }

    private static long offsetAtEnd(TiffTile tile, DataHandle<?> outputStream, boolean strictlyRequire32Bit)
            throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(outputStream, "Null output stream");
        final long length = outputStream.length();
        if (strictlyRequire32Bit && length > 0xFFFFFFF0L - tile.getEncodedData().length) {
            throw new IOException("Attempt to write TIFF tile outside maximal allowed 32-bit file length 2^32-16 = " +
                    0xFFFFFFF0L + "; such large files should be written in BigTIFF mode");
        }
        return length;
    }
}
