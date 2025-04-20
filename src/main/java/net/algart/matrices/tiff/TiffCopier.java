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
import java.util.Objects;
import java.util.Set;

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

    @FunctionalInterface
    public interface ProgressUpdater {
        void onProgress();
    }

    @FunctionalInterface
    public interface CancellationChecker {
        boolean shouldContinue();
    }

    private boolean directCopy = false;
    private IFDCorrector ifdCorrector = null;
    private ProgressUpdater progressUpdater = null;
    private CancellationChecker cancellationChecker = null;

    private int copiedTileCount = 0;
    private int totalTileCount = 0;
    private int copiedImageCount = 0;
    private int totalImageCount = 0;

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

    public ProgressUpdater getProgressUpdater() {
        return progressUpdater;
    }

    public TiffCopier setProgressUpdater(ProgressUpdater progressUpdater) {
        this.progressUpdater = progressUpdater;
        return this;
    }

    public CancellationChecker getCancellationChecker() {
        return cancellationChecker;
    }

    public TiffCopier setCancellationChecker(CancellationChecker cancellationChecker) {
        this.cancellationChecker = cancellationChecker;
        return this;
    }

    public int copiedImageCount() {
        return copiedImageCount;
    }

    public int totalImageCount() {
        return totalImageCount;
    }

    public int copiedTileCount() {
        return copiedTileCount;
    }

    public int totalTileCount() {
        return totalTileCount;
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
            copiedImageCount = i;
            totalImageCount = n;
            copyImage(writer, reader, i);
            if (progressUpdater != null) {
                copiedImageCount = i + 1;
                progressUpdater.onProgress();
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
        Objects.requireNonNull(writer, "Null writer");
        Objects.requireNonNull(readMap, "Null TIFF read map");
        copiedTileCount = 0;
        totalTileCount = 0;
        @SuppressWarnings("resource") final TiffReader reader = readMap.reader();
        final TiffIFD targetIFD = new TiffIFD(readMap.ifd());
        // - creating a clone of IFD: we must not modify the reader IFD
        if (ifdCorrector != null) {
            ifdCorrector.correct(targetIFD);
        }
        final TiffWriteMap targetMap = writer.newMap(targetIFD, false, !directCopy);
        // - there is no sense to call correctIFDForWriting() method if we use direct copying
        writer.writeForward(targetMap);
        final Set<TiffTileIndex> indexes = readMap.indexes();
        totalTileCount = indexes.size();
        for (TiffTileIndex index : indexes) {
            final TiffTile targetTile = targetMap.getOrNew(targetMap.copyIndex(index));
            if (!directCopy) {
                final TiffTile sourceTile = reader.readCachedTile(index);
                final byte[] decodedData = sourceTile.unpackUnusualDecodedData();
                targetTile.setDecodedData(decodedData);
            } else {
                final TiffTile sourceTile = reader.readEncodedTile(index);
                targetTile.copy(sourceTile, false);
            }
            targetMap.put(targetTile);
            writer.writeTile(targetTile, true);
            copiedTileCount++;
            if (progressUpdater != null) {
                progressUpdater.onProgress();
            }
            if (cancellationChecker != null && !cancellationChecker.shouldContinue()) {
                break;
            }
        }
        writer.completeWriting(targetMap);
        return targetMap;
    }
}
