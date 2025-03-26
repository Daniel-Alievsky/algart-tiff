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

    public static void read(TiffTile tile, DataHandle<?> in, long filePosition, int dataLength) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(in, "Null input stream");
        tile.setStoredDataFileRange(filePosition, dataLength);
        read(tile, in, dataLength);
    }

    public static void read(TiffTile tile, DataHandle<?> in, int dataLength) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(in, "Null input stream");
        final long filePosition = tile.getStoredDataFileOffset();
        final byte[] data = new byte[dataLength];
        in.seek(filePosition);
        final int result = in.read(data);
        if (result < data.length) {
            throw new IOException("File exhausted at " + filePosition +
                    ": loaded " + result + " bytes instead of " + data.length +
                    " (" + in.get() + ")");
        }
        tile.setEncodedData(data);
    }

    public static void writeToEnd(
            TiffTile tile,
            DataHandle<?> out,
            boolean disposeAfterWriting,
            boolean strictlyRequire32Bit) throws IOException {
//        System.out.printf("%s: %s%n", out, tile.toString());
        setWritePositionToEnd(tile, out, strictlyRequire32Bit);
        write(tile, out, disposeAfterWriting);
    }

    public static void setWritePositionToEnd(TiffTile tile, DataHandle<?> out, boolean strictlyRequire32Bit)
            throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(out, "Null output stream");
        final long length = out.length();
        if (strictlyRequire32Bit && length > 0xFFFFFFF0L - tile.getEncodedData().length) {
            throw new IOException("Attempt to write TIFF tile outside maximal allowed 32-bit file length 2^32-16 = " +
                    0xFFFFFFF0L + "; such large files should be written in Big-TIFF mode");
        }
        tile.setStoredDataFileOffset(length);
    }

    public static void write(TiffTile tile, DataHandle<?> out, boolean disposeAfterWriting) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(out, "Null output stream");
        final long filePosition = tile.getStoredDataFileOffset();
        final byte[] encodedData = tile.getEncodedData();
        out.seek(filePosition);
        out.write(encodedData);
        if (disposeAfterWriting) {
            tile.dispose();
        }
    }
}
