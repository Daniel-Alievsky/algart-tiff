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

package net.algart.matrices.tiff;

import net.algart.matrices.tiff.tiles.TiffReadMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.matrices.tiff.tiles.TiffTileIndex;
import net.algart.matrices.tiff.tiles.TiffWriteMap;

import java.io.IOException;
import java.util.Objects;
import java.util.function.Consumer;

public class TiffCopier {
    public TiffCopier() {
    }

    public static TiffWriteMap copyImage(TiffWriter writer, TiffReader source, int sourceIfdIndex) throws IOException {
        Objects.requireNonNull(source, "Null source TIFF reader");
        return copyImage(writer, source.newMap(sourceIfdIndex));
    }

    public static TiffWriteMap copyImage(
            TiffWriter writer,
            TiffReader source,
            int sourceIfdIndex,
            boolean decodeAndEncode) throws IOException {
        Objects.requireNonNull(source, "Null source TIFF reader");
        return copyImage(writer, source.newMap(sourceIfdIndex), null, decodeAndEncode);
    }

    public static TiffWriteMap copyImage(TiffWriter writer, TiffReadMap sourceMap) throws IOException {
        return copyImage(writer, sourceMap, null, false);
    }

    public static TiffWriteMap copyImage(
            TiffWriter writer,
            TiffReadMap sourceMap,
            Consumer<TiffIFD> correctingResultIFDAfterCopyingFromSource,
            boolean decodeAndEncode) throws IOException {
        Objects.requireNonNull(sourceMap, "Null source TIFF map");
        @SuppressWarnings("resource") final TiffReader source = sourceMap.reader();
        final TiffIFD targetIFD = new TiffIFD(sourceMap.ifd());
        // - creating a clone of IFD: we must not modify the source IFD
        if (correctingResultIFDAfterCopyingFromSource != null) {
            correctingResultIFDAfterCopyingFromSource.accept(targetIFD);
        }
        final TiffWriteMap targetMap = writer.newMap(targetIFD, false, decodeAndEncode);
        // - there is no sense to call correctIFDForWriting() method if we will not decode/encode tile
        writer.writeForward(targetMap);
        for (TiffTileIndex index : sourceMap.indexes()) {
            final TiffTile targetTile = targetMap.getOrNew(targetMap.copyIndex(index));
            if (decodeAndEncode) {
                final TiffTile sourceTile = source.readCachedTile(index);
                final byte[] decodedData = sourceTile.unpackUnusualDecodedData();
                targetTile.setDecodedData(decodedData);
            } else {
                final TiffTile sourceTile = source.readEncodedTile(index);
                targetTile.copy(sourceTile, false);
            }
            targetMap.put(targetTile);
            writer.writeTile(targetTile, true);
        }
        writer.completeWriting(targetMap);
        return targetMap;
    }
}
