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

import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.*;

import java.io.IOException;
import java.nio.ByteOrder;
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

    public final class ProgressInformation {
        private int imageIndex = 0;
        private int imageCount = 0;
        private int tileIndex = 0;
        private int tileCount = 0;

        public TiffCopier copier() {
            return TiffCopier.this;
        }

        public int imageIndex() {
            return imageIndex;
        }

        public int imageCount() {
            return imageCount;
        }

        public boolean isLastImageCopied() {
            return imageIndex == imageCount - 1;
        }

        public int tileIndex() {
            return tileIndex;
        }

        public int tileCount() {
            return tileCount;
        }

        public boolean isLastTileCopied() {
            return tileIndex == tileCount - 1;
        }
    }

    private static final System.Logger LOG = System.getLogger(TiffCopier.class.getName());
    private static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);

    private boolean directCopy = false;
    private IFDCorrector ifdCorrector = null;
    private Consumer<ProgressInformation> progressUpdater = null;
    private BooleanSupplier interruptionChecker = null;
    private final ProgressInformation progressInformation = new ProgressInformation();

    private boolean actuallyDirectCopy = false;

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

    public Consumer<ProgressInformation> progressUpdater() {
        return progressUpdater;
    }

    public TiffCopier setProgressUpdater(Consumer<ProgressInformation> progressUpdater) {
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

    public ProgressInformation progressInformation() {
        return progressInformation;
    }

    public boolean actuallyDirectCopy() {
        return actuallyDirectCopy;
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
        resetAllCounters();
        for (int i = 0, n = reader.numberOfImages(); i < n; i++) {
            progressInformation.imageIndex = i;
            progressInformation.imageCount = n;
            copyImage(writer, reader, i);
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
        resetImageCounters();
        final TiffIFD writeIFD = new TiffIFD(readMap.ifd());
        // - creating a clone of IFD: we must not modify the reader IFD
        if (ifdCorrector != null) {
            ifdCorrector.correct(writeIFD);
            // - theoretically, we can essentially modify IFD here
        }
        final boolean actuallyDirectCopy = canBeImageCopiedDirectly(writeIFD, writer.getByteOrder(), readMap);
        final boolean correctFormatForEncoding = !actuallyDirectCopy;
        // - there is no sense to call correctFormatForEncoding() method if we use tile-per-tile direct copying
        final TiffWriteMap writeMap = writer.newMap(writeIFD, false, correctFormatForEncoding);
        checkImageCompatibility(writeMap, readMap);
        this.actuallyDirectCopy = actuallyDirectCopy;
        writer.writeForward(writeMap);
        final Collection<TiffTile> targetTiles = writeMap.tiles();
        progressInformation.tileCount = targetTiles.size();
        int tileCount = 0;
        long t2 = debugTime();
        for (TiffTile targetTile : targetTiles) {
            final TiffTileIndex readIndex = readMap.copyIndex(targetTile.index());
            // - important to copy index: targetTile.index() refer to the writeIFD instead of some source IFD
            if (this.actuallyDirectCopy) {
                final TiffTile sourceTile = readMap.readEncodedTile(readIndex);
                targetTile.copyData(sourceTile, false);
            } else {
                final TiffTile sourceTile = readMap.readCachedTile(readIndex);
                targetTile.copyUnpackedSamples(sourceTile, readMap.isAutoScaleWhenIncreasingBitDepth());
                // - this method performs necessary unpacking/packing bytes when the byte order is incompatible
            }
            writeMap.put(targetTile);
            writeMap.writeTile(targetTile, true);
            progressInformation.tileIndex = tileCount;
            if (shouldBreak()) {
                break;
            }
            tileCount++;
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
                    actuallyDirectCopy ? "directly" : "with repacking" + (this.directCopy ?
                            " (direct mode rejected)" : ""),
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
        TiffMap.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        long t1 = debugTime();
        resetImageCounters();
        final TiffIFD writeIFD = new TiffIFD(readMap.ifd());
        // - creating a clone of IFD: we must not modify the reader IFD
        writeIFD.putImageDimensions(sizeX, sizeY, false);
        if (ifdCorrector != null) {
            ifdCorrector.correct(writeIFD);
        }
        final TiffWriteMap writeMap = writer.newMap(writeIFD, false, true);
        // - here we MUST call correctFormatForEncoding() method: this behavior should not depend on fromX/fromY
        checkImageCompatibility(writeMap, readMap);
        writer.writeForward(writeMap);
        progressInformation.tileCount = writeMap.numberOfGridTiles();
        final int gridCountY = writeMap.gridCountY();
        final int gridCountX = writeMap.gridCountX();
        final int mapTileSizeX = writeMap.tileSizeX();
        final int mapTileSizeY = writeMap.tileSizeY();
        final boolean directCopy = canBeRectangleCopiedDirectly(writeMap, readMap, fromX, fromY);
        final boolean unpackBytes = !readMap.isByteOrderCompatible(writeMap.byteOrder());
        final int fromXIndex = directCopy ? fromX / mapTileSizeX : Integer.MIN_VALUE;
        final int fromYIndex = directCopy ? fromY / mapTileSizeY : Integer.MIN_VALUE;
        long t2 = debugTime();
        int repackCount = 0;
        int tileCount = 0;
        for (int yIndex = 0, y = 0; yIndex < gridCountY; yIndex++, y += mapTileSizeY) {
            final int sizeYInTile = Math.min(sizeY - y, mapTileSizeY);
            for (int xIndex = 0, x = 0; xIndex < gridCountX; xIndex++, x += mapTileSizeX) {
                final int sizeXInTile = Math.min(sizeX - x, mapTileSizeX);
                this.actuallyDirectCopy = directCopy && sizeXInTile == mapTileSizeX && sizeYInTile == mapTileSizeY;
                if (this.actuallyDirectCopy) {
                    final int readXIndex = fromXIndex + xIndex;
                    final int readYIndex = fromYIndex + yIndex;
                    copyEncodedTile(writeMap, readMap, xIndex, yIndex, readXIndex, readYIndex);
                } else {
                    final int readX = fromX + x;
                    final int readY = fromY + y;
                    final int written = copyRectangle(
                            writeMap, readMap, x, y, readX, readY, sizeXInTile, sizeYInTile, unpackBytes);
                    if (written != writeMap.numberOfSeparatedPlanes()) {
                        // - we copy exactly one tile, and it should be completed: we created a non-resizable map,
                        // so, the initial unset area of the tile, i.e. actualRectangle(),
                        // was equal to the rectangle calculated above
                        throw new AssertionError("Number of written tiles " + written + " != " +
                                writeMap.numberOfSeparatedPlanes());
                    }
                    repackCount++;
                }
                progressInformation.tileIndex = tileCount;
                if (shouldBreak()) {
                    break;
                }
                tileCount++;
            }
        }
        long t3 = debugTime();
        final int written = writeMap.completeWriting();
        if (written != 0) {
            throw new AssertionError("Number of tiles when completion " + written + " != 0");
        }
        if (TiffReader.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            final long sizeInBytes = writeMap.totalSizeInBytes();
            long t4 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s copied %s%s %dx%dx%d samples in %d tiles%s (%.3f MB) in %.3f ms = " +
                            "%.3f prepare + %.3f copy + %.3f complete, %.3f MB/s",
                    getClass().getSimpleName(),
                    directCopy ? "directly" : "with repacking" + (this.directCopy ? " (direct mode rejected)" : ""),
                    unpackBytes ? " and reordering bytes" : "",
                    sizeX, sizeY, writeMap.numberOfChannels(),
                    tileCount,
                    repackCount < tileCount ? " (" + repackCount + " with repacking)" : "",
                    sizeInBytes / 1048576.0,
                    (t4 - t1) * 1e-6,
                    (t2 - t1) * 1e-6, (t3 - t2) * 1e-6, (t4 - t3) * 1e-6,
                    sizeInBytes / 1048576.0 / ((t4 - t1) * 1e-9)));

        }
        return writeMap;
    }

    private static void checkImageCompatibility(TiffWriteMap writeMap, TiffReadMap readMap) {
        // Note: this method does not check ANY possible incompatibilities.
        // On the other hand, incompatibilities are improbable here;
        // they can appear only as a result of using ifdCorrector.
        if (writeMap.sampleType() != readMap.sampleType()) {
            throw new IllegalArgumentException("Incompatible sample types: " +
                    writeMap.sampleType() + " in target TIFF, " + readMap.sampleType() + " in source TIFF");
        }
        if (writeMap.numberOfChannels() != readMap.numberOfChannels()) {
            throw new IllegalArgumentException("Incompatible number of channels: " +
                    writeMap.numberOfChannels() + " in target TIFF, " +
                    readMap.numberOfChannels() + " in source TIFF");
        }
        if (writeMap.isPlanarSeparated() != readMap.isPlanarSeparated()) {
            throw new IllegalArgumentException("Incompatible \"planar separated\" mode: " +
                    writeMap.isPlanarSeparated() + " in target TIFF, " +
                    readMap.isPlanarSeparated() + " in source TIFF");

        }
    }

    private boolean canBeImageCopiedDirectly(TiffIFD writeIFD, ByteOrder writeByteOrder, TiffReadMap readMap)
            throws TiffException {
        if (!readMap.isByteOrderCompatible(writeByteOrder)) {
            // - theoretically, this check is extra: byteOrderIsNotUsedForImageData will be false
            return false;
        }
        final int compressionCode = readMap.compressionCode();
        final TagCompression compression = TagCompression.ofOrNull(compressionCode);
        final boolean byteOrderIsNotUsedForImageData =
                isByteOrBinary(readMap) && (compression != null && !compression.canUseByteOrderForByteData());
        // - for unknown compressions, we cannot be sure of this fact even for 1-bit or 8-bit samples
        return this.directCopy &&
                (readMap.byteOrder() == writeByteOrder || byteOrderIsNotUsedForImageData) &&
                compressionCode == writeIFD.getCompressionCode();
        // - note that the compatibility of the sample type and pixel structure (number of channels and
        // planar separated mode) will be checked later in checkImageCompatibility() method
    }

    private static boolean isByteOrBinary(TiffReadMap readMap) {
        return readMap.isBinary() || readMap.sampleType().bitsPerSample() == 8;
    }

    private boolean canBeRectangleCopiedDirectly(TiffWriteMap writeMap, TiffReadMap readMap, int fromX, int fromY)
            throws TiffException {
        final int mapTileSizeX = writeMap.tileSizeX();
        final int mapTileSizeY = writeMap.tileSizeY();
        final boolean equalTiles = readMap.tileSizeX() == mapTileSizeX && readMap.tileSizeY() == mapTileSizeY;
        // - note: for stripped images, this flag will be false almost always:
        // the strip "size-X" is the image width
        return equalTiles &&
                fromX % mapTileSizeX == 0 && fromY % mapTileSizeY == 0 &&
                canBeImageCopiedDirectly(writeMap.ifd(), writeMap.byteOrder(), readMap);
    }

    private static int copyRectangle(
            TiffWriteMap writeMap,
            TiffReadMap readMap,
            int writeX,
            int writeY,
            int readX,
            int readY,
            int sizeX,
            int sizeY,
            boolean unpackBytes) throws IOException {
        List<TiffTile> tiles;
        if (unpackBytes) {
            final Object unpacked = readMap.readJavaArray(readX, readY, sizeX, sizeY);
            tiles = writeMap.updateJavaArray(unpacked, writeX, writeY, sizeX, sizeY);
        } else {
            final byte[] samples = readMap.readSamples(
                    readX, readY, sizeX, sizeY,
                    false,
                    true,
                    false);
            tiles = writeMap.updateSamples(samples, writeX, writeY, sizeX, sizeY);
        }
        return writeMap.flushCompletedTiles(tiles);
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
            targetTile.copyData(sourceTile, false);
            writeMap.put(targetTile);
            writeMap.writeTile(targetTile, true);
        }
    }

    private void resetAllCounters() {
        progressInformation.imageIndex = 0;
        progressInformation.imageCount = 0;
        resetImageCounters();
    }

    private void resetImageCounters() {
        progressInformation.tileIndex = 0;
        progressInformation.tileCount = 0;
    }

    private boolean shouldBreak() {
        if (progressUpdater != null) {
            progressUpdater.accept(progressInformation);
        }
        return interruptionChecker != null && interruptionChecker.getAsBoolean();
    }

    private static long debugTime() {
        return TiffReader.BUILT_IN_TIMING && LOGGABLE_DEBUG ? System.nanoTime() : 0;
    }
}

