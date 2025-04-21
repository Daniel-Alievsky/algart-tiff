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
import java.nio.file.Path;
import java.util.Collection;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@SuppressWarnings("UnusedReturnValue")
public final class TiffCopier {
    @FunctionalInterface
    public interface IFDCorrector {
        /**
         * Corrects IFD, usually before writing it to the target TIFF.
         *
         * @param ifd IFD to be corrected.
         */
        void correct(TiffIFD ifd);
    }

    private boolean directCopy = false;
    private IFDCorrector ifdCorrector = null;
    private Consumer<TiffCopier> progressUpdater = null;
    private BooleanSupplier interruptionChecker = null;

    private int copiedTileCount = 0;
    private int tileCount = 0;
    private int copiedIfdCount = 0;
    private int ifdCount = 0;

    public TiffCopier() {
    }

    /**
     * Returns the flag that instructs this object to copy TIFF image tiles directly.
     *
     * @return <code>true</code> if the direct copying of TIFF tiles should be used,
     */
    public boolean isDirectCopy() {
        return directCopy;
    }

    /**
     * Sets the flag that enforces direct copying of TIFF image tiles without decompression/compression.
     * Default value is <code>false</code>, meaning that TIFF tiles are always decompressed and compressed
     * during the copy process.
     *
     * @param directCopy whether the direct copying of TIFF tiles should be used.
     * @return a reference to this object.
     */
    public TiffCopier setDirectCopy(boolean directCopy) {
        this.directCopy = directCopy;
        return this;
    }

    /**
     * Returns a function that corrects IFD from the source TIFF read map before writing it to the target TIFF.
     *
     * @return a function that corrects IFD writing it to the target TIFF.
     */
    public IFDCorrector getIfdCorrector() {
        return ifdCorrector;
    }

    /**
     * Sets a function that corrects IFD from the source TIFF read map before writing it to the target TIFF.
     * Usually you should not use this method if the flag {@link #isDirectCopy()}
     * is set to <code>true</code>.
     * Default value is <code>null</code>, meaning that no correction is performed.
     *
     * @param ifdCorrector function that corrects IFD writing it to the target TIFF;
     *                     may be <code>null</code>, than it is ignored.
     * @return a reference to this object.
     */
    public TiffCopier setIfdCorrector(IFDCorrector ifdCorrector) {
        this.ifdCorrector = ifdCorrector;
        return this;
    }

    public Consumer<TiffCopier> progressUpdater() {
        return progressUpdater;
    }

    public TiffCopier setProgressUpdater(Consumer<TiffCopier> progressUpdater) {
        this.progressUpdater = progressUpdater;
        return this;
    }

    public BooleanSupplier cancellationChecker() {
        return interruptionChecker;
    }

    public TiffCopier setInterruptionChecker(BooleanSupplier interruptionChecker) {
        this.interruptionChecker = interruptionChecker;
        return this;
    }

    public int copiedIfdCount() {
        return copiedIfdCount;
    }

    public int ifdCount() {
        return ifdCount;
    }

    public int copiedTileCount() {
        return copiedTileCount;
    }

    public int tileCount() {
        return tileCount;
    }

    public static void copyAll(Path targetTiffFile, Path sourceTiffFile, boolean directCopy)
            throws IOException {
        new TiffCopier().setDirectCopy(directCopy).copyAll(targetTiffFile, sourceTiffFile);
    }

    public static void copyImage(TiffWriter writer, TiffReadMap readMap, boolean directCopy)
            throws IOException {
        new TiffCopier().setDirectCopy(directCopy).copyImage(writer, readMap);
    }

    public void copyAll(Path targetTiffFile, Path sourceTiffFile) throws IOException {
        try (TiffReader reader = new TiffReader(sourceTiffFile);
             TiffWriter writer = new TiffWriter(targetTiffFile)) {
            writer.setBigTiff(reader.isBigTiff());
            writer.setLittleEndian(reader.isLittleEndian());
            writer.create();
            copyAll(writer, reader);
        }
    }

    public void copyAll(TiffWriter writer, TiffReader reader) throws IOException {
        Objects.requireNonNull(writer, "Null TIFF writer");
        Objects.requireNonNull(reader, "Null TIFF reader");
        for (int i = 0, n = reader.numberOfImages(); i < n; i++) {
            copiedIfdCount = i;
            ifdCount = n;
            copyImage(writer, reader, i);
            if (progressUpdater != null) {
                copiedIfdCount = i + 1;
                progressUpdater.accept(this);
            }
        }
    }

    public TiffWriteMap copyImage(TiffWriter writer, TiffReader reader, int sourceIfdIndex) throws IOException {
        Objects.requireNonNull(writer, "Null TIFF writer");
        Objects.requireNonNull(reader, "Null TIFF reader");
        final TiffReadMap readMap = reader.newMap(sourceIfdIndex);
        return copyImage(writer, readMap);
    }

    public TiffWriteMap copyImage(TiffWriter writer, TiffReadMap readMap) throws IOException {
        Objects.requireNonNull(writer, "Null TIFF writer");
        Objects.requireNonNull(readMap, "Null TIFF read map");
        resetCounters();
        @SuppressWarnings("resource") final TiffReader reader = readMap.reader();
        final TiffIFD writeIFD = new TiffIFD(readMap.ifd());
        // - creating a clone of IFD: we must not modify the reader IFD
        final TiffWriteMap writeMap = getWriteMap(writer, writeIFD);
        // - there is no sense to call correctIFDForWriting() method if we use direct copying
        writer.writeForward(writeMap);
        final Collection<TiffTile> targetTiles = writeMap.tiles();
        tileCount = targetTiles.size();
        for (TiffTile targetTile : targetTiles) {
            final TiffTileIndex readIndex = readMap.copyIndex(targetTile.index());
            // - important to copy index: targetTile.index() refer to the writeIFD instead of some source IFD
            if (!directCopy) {
                final TiffTile sourceTile = reader.readCachedTile(readIndex);
                final byte[] decodedData = sourceTile.unpackUnusualDecodedData();
                targetTile.setDecodedData(decodedData);
            } else {
                final TiffTile sourceTile = reader.readEncodedTile(readIndex);
                targetTile.copy(sourceTile, false);
            }
            writeMap.put(targetTile);
            writer.writeTile(targetTile, true);
            copiedTileCount++;
            if (progressUpdater != null) {
                progressUpdater.accept(this);
            }
            if (interruptionChecker != null && interruptionChecker.getAsBoolean()) {
                break;
            }
        }
        writer.completeWriting(writeMap);
        return writeMap;
    }

    public TiffWriteMap copyImage(TiffWriter writer, TiffReadMap readMap, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        Objects.requireNonNull(writer, "Null TIFF writer");
        Objects.requireNonNull(readMap, "Null TIFF read map");
        TiffReader.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        resetCounters();
        @SuppressWarnings("resource") final TiffReader reader = readMap.reader();
        final TiffIFD writeIFD = new TiffIFD(readMap.ifd());
        // - creating a clone of IFD: we must not modify the reader IFD
        writeIFD.putImageDimensions(sizeX, sizeY);
        final TiffWriteMap writeMap = getWriteMap(writer, writeIFD);
        writer.writeForward(writeMap);
        //TODO!!

        return writeMap;
    }

    private TiffWriteMap getWriteMap(TiffWriter writer, TiffIFD targetIFD) throws TiffException {
        if (ifdCorrector != null) {
            ifdCorrector.correct(targetIFD);
        }
        // - there is no sense to call correctIFDForWriting() method if we use direct copying
        return writer.newMap(targetIFD, false, !directCopy);
    }

    private void resetCounters() {
        copiedTileCount = 0;
        tileCount = 0;
        copiedIfdCount = 0;
        ifdCount = 0;
    }
}
