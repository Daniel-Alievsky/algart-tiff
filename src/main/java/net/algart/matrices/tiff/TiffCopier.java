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
import java.util.List;
import java.util.Locale;
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

    private static final System.Logger LOG = System.getLogger(TiffCopier.class.getName());
    private static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);

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
            writer.setFormatLike(reader);
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
        long t1 = debugTime();
        resetCounters();
        final TiffIFD writeIFD = new TiffIFD(readMap.ifd());
        // - creating a clone of IFD: we must not modify the reader IFD
        final TiffWriteMap writeMap = getWriteMap(writer, writeIFD);
        // - there is no sense to call correctIFDForWriting() method if we use direct copying
        writer.writeForward(writeMap);
        final Collection<TiffTile> targetTiles = writeMap.tiles();
        tileCount = targetTiles.size();
        long t2 = debugTime();
        for (TiffTile targetTile : targetTiles) {
            final TiffTileIndex readIndex = readMap.copyIndex(targetTile.index());
            // - important to copy index: targetTile.index() refer to the writeIFD instead of some source IFD
            if (!directCopy) {
                final TiffTile sourceTile = readMap.readCachedTile(readIndex);
                final byte[] decodedData = sourceTile.unpackUnusualDecodedData();
                targetTile.setDecodedData(decodedData);
            } else {
                final TiffTile sourceTile = readMap.readEncodedTile(readIndex);
                targetTile.copy(sourceTile, false);
            }
            writeMap.put(targetTile);
            writeMap.writeTile(targetTile, true);
            copiedTileCount++;
            if (shouldBreak()) {
                break;
            }
        }
        long t3 = debugTime();
        writeMap.completeWriting();
        if (TiffReader.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            final long sizeInBytes = writeMap.totalSizeInBytes();
            long t4 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s copied entire image %s %dx%dx%d (%.3f MB) in %.3f ms = " +
                            "%.3f prepare + %.3f copy + %.3f complete, %.3f MB/s",
                    getClass().getSimpleName(),
                    directCopy ? "directly" : "with repacking",
                    writeMap.dimX(), writeMap.dimY(), writeMap.numberOfChannels(),
                    sizeInBytes / 1048576.0,
                    (t4 - t1) * 1e-6,
                    (t2 - t1) * 1e-6, (t3 - t2) * 1e-6, (t4 - t3) * 1e-6,
                    sizeInBytes / 1048576.0 / ((t4 - t1) * 1e-9)));

        }
        return writeMap;
    }

    public TiffWriteMap copyImage(TiffWriter writer, TiffReadMap readMap, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        // - note that this area may be outside readMap!
        Objects.requireNonNull(writer, "Null TIFF writer");
        Objects.requireNonNull(readMap, "Null TIFF read map");
        TiffReader.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        long t1 = debugTime();
        resetCounters();
        final TiffIFD writeIFD = new TiffIFD(readMap.ifd());
        // - creating a clone of IFD: we must not modify the reader IFD
        writeIFD.putImageDimensions(sizeX, sizeY, false);
        final TiffWriteMap writeMap = getWriteMap(writer, writeIFD);
        writer.writeForward(writeMap);
        tileCount = writeMap.numberOfGridTiles();
        final int gridCountY = writeMap.gridCountY();
        final int gridCountX = writeMap.gridCountX();
        final int mapTileSizeX = writeMap.tileSizeX();
        final int mapTileSizeY = writeMap.tileSizeY();
        final boolean directCopy = canBeCopiedDirectly(writeMap, readMap, fromX, fromY);
        final int fromXIndex = directCopy ? fromX / mapTileSizeX : Integer.MIN_VALUE;
        final int fromYIndex = directCopy ? fromY / mapTileSizeY : Integer.MIN_VALUE;
        long t2 = debugTime();
        int repackCount = 0;
        for (int yIndex = 0, y = 0; yIndex < gridCountY; yIndex++, y += mapTileSizeY) {
            final int sizeYInTile = Math.min(sizeY - y, mapTileSizeY);
            for (int xIndex = 0, x = 0; xIndex < gridCountX; xIndex++, x += mapTileSizeX) {
                final int sizeXInTile = Math.min(sizeX - x, mapTileSizeX);
                if (directCopy && sizeXInTile == mapTileSizeX && sizeYInTile == mapTileSizeY) {
                    final int readXIndex = fromXIndex + xIndex;
                    final int readYIndex = fromYIndex + yIndex;
                    copyEncodedTile(writeMap, readMap, xIndex, yIndex, readXIndex, readYIndex);
                } else {
                    final int readX = fromX + x;
                    final int readY = fromY + y;
                    copyRectangle(writeMap, readMap, x, y, readX, readY, sizeXInTile, sizeYInTile);
                    repackCount++;
                }
                copiedTileCount++;
                if (shouldBreak()) {
                    break;
                }
            }
        }
        long t3 = debugTime();
        writeMap.completeWriting();
        if (TiffReader.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            final long sizeInBytes = writeMap.totalSizeInBytes();
            long t4 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s copied %s %dx%dx%d samples in %d tiles%s (%.3f MB) in %.3f ms = " +
                            "%.3f prepare + %.3f copy + %.3f complete, %.3f MB/s",
                    getClass().getSimpleName(),
                    directCopy ? "directly" : "with repacking" + (this.directCopy ? " (direct mode rejected)" : ""),
                    sizeX, sizeY, writeMap.numberOfChannels(),
                    copiedTileCount, repackCount < copiedTileCount ? " (" + repackCount + " with repacking)" : "",
                    sizeInBytes / 1048576.0,
                    (t4 - t1) * 1e-6,
                    (t2 - t1) * 1e-6, (t3 - t2) * 1e-6, (t4 - t3) * 1e-6,
                    sizeInBytes / 1048576.0 / ((t4 - t1) * 1e-9)));

        }
        return writeMap;
    }

    private boolean canBeCopiedDirectly(TiffWriteMap writeMap, TiffReadMap readMap, int fromX, int fromY) {
        final int mapTileSizeX = writeMap.tileSizeX();
        final int mapTileSizeY = writeMap.tileSizeY();
        return this.directCopy &&
                readMap.compressionCode() == writeMap.compressionCode() &&
                readMap.numberOfSeparatedPlanes() == writeMap.numberOfSeparatedPlanes() &&
                readMap.tileSizeX() == mapTileSizeX && readMap.tileSizeY() == mapTileSizeY &&
                fromX % mapTileSizeX == 0 && fromY % mapTileSizeY == 0;
    }

    private static void copyRectangle(
            TiffWriteMap writeMap,
            TiffReadMap readMap,
            int writeX,
            int writeY,
            int readX,
            int readY,
            int sizeX,
            int sizeY) throws IOException {
        final byte[] samples = readMap.loadSamples(readX, readY, sizeX, sizeY);
        List<TiffTile> tiles = writeMap.updateSamples(samples, writeX, writeY, sizeX, sizeY);
        writeMap.writeCompletedTiles(tiles);
    }

    private static void copyEncodedTile(
            TiffWriteMap writeMap,
            TiffReadMap readMap,
            int writeXIndex,
            int writeYIndex,
            int readXIndex,
            int readYIndex) throws IOException {
        final int numberOfSeparatedPlanes = writeMap.numberOfSeparatedPlanes();
        for (int p = 0; p < numberOfSeparatedPlanes; p++) {
            TiffTile targetTile = writeMap.getOrNew(writeXIndex, writeYIndex, p);
            final TiffTile sourceTile = readMap.readEncodedTile(readMap.index(readXIndex, readYIndex, p));
            targetTile.copy(sourceTile, false);
            writeMap.put(targetTile);
            writeMap.writeTile(targetTile, true);
        }
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

    private boolean shouldBreak() {
        if (progressUpdater != null) {
            progressUpdater.accept(this);
        }
        return interruptionChecker != null && interruptionChecker.getAsBoolean();
    }

    private static long debugTime() {
        return TiffReader.BUILT_IN_TIMING && LOGGABLE_DEBUG ? System.nanoTime() : 0;
    }
}

