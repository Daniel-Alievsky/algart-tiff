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

import net.algart.arrays.JArrays;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tiles.*;
import org.scijava.io.handle.BytesHandle;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.BytesLocation;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

@SuppressWarnings("UnusedReturnValue")
public final class TiffCopier {
    public final class ProgressInformation {
        private int imageIndex = 0;
        private int imageCount = 0;
        private int tileIndex = 0;
        private int tileCount = 0;
        private boolean copyingTemporaryFile = false;

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

        public boolean isCopyingTemporaryFile() {
            return copyingTemporaryFile;
        }
    }

    private static final System.Logger LOG = System.getLogger(TiffCopier.class.getName());
    private static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);

    private boolean directCopy = true;
    private int maxInMemoryTemporaryFileSize = 0;
    private TemporaryFileCreator temporaryFileCreator = TemporaryFileCreator.DEFAULT;
    private TiffIFDCorrector ifdCorrector = null;
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
     * Default value is <code>true</code>.
     *
     * @param directCopy whether the direct copying of TIFF tiles should be used.
     * @return a reference to this object.
     */
    public TiffCopier setDirectCopy(boolean directCopy) {
        this.directCopy = directCopy;
        return this;
    }

    public int getMaxInMemoryTemporaryFileSize() {
        return maxInMemoryTemporaryFileSize;
    }

    /**
     * Sets the threshold used by {@link #compact(Path)} method.
     * If the source TIFF file size is not longer than this limit, the source TIFF file
     * is copied to temporary "file" in memory ({@link BytesHandle},
     * otherwise we create a real temporary file using
     * {@link Files#createTempFile(Path, String, String, java.nio.file.attribute.FileAttribute[])
     * Files.createTempFile} method.
     *
     * <p>The default value is 0, which means that this feature is disabled.
     * You may set this limit to some value like 100 MB, depending on the available memory.
     * Please note that the actual size of the temporary file may become larger than this limit;
     * for example, this is possible when the source TIFF contains multiple tiles with identical offsets.
     * Another possible situation &mdash; if you enforce recompression of all tiles via the call
     * {@link #setDirectCopy(boolean) setDirectCopy(false)}; however, this optimization is not as useful
     * in the latter case.
     *
     * @param maxInMemoryTemporaryFileSize maximal TIFF file size to be compacted in-memory.
     * @return a reference to this object.
     * @throws IllegalArgumentException if the argument is negative.
     */
    public TiffCopier setMaxInMemoryTemporaryFileSize(int maxInMemoryTemporaryFileSize) {
        if (maxInMemoryTemporaryFileSize < 0) {
            throw new IllegalArgumentException("Negative maxInMemoryTemporaryFileSize: " +
                    maxInMemoryTemporaryFileSize);
        }
        this.maxInMemoryTemporaryFileSize = maxInMemoryTemporaryFileSize;
        return this;
    }

    public TemporaryFileCreator getTemporaryFileCreator() {
        return temporaryFileCreator;
    }

    /**
     * Sets the tool used for creating temporary files by {@link #compact(Path)} method.
     * Default value is {@link TemporaryFileCreator#DEFAULT}.
     *
     * @param temporaryFileCreator the temporary file creator.
     * @return a reference to this object.
     * @throws NullPointerException if the argument is {@code null}.
     */
    public TiffCopier setTemporaryFileCreator(TemporaryFileCreator temporaryFileCreator) {
        this.temporaryFileCreator = Objects.requireNonNull(temporaryFileCreator,
                "Null temporary file creator");
        return this;
    }

    /**
     * Returns a function that corrects IFD from the source TIFF read map before writing it to the target TIFF.
     *
     * @return a function that corrects IFD writing it to the target TIFF.
     */
    public TiffIFDCorrector getIfdCorrector() {
        return ifdCorrector;
    }

    /**
     * Sets a function that corrects IFD from the source TIFF read map before writing it to the target TIFF.
     * Usually you should not use this method if the flag {@link #isDirectCopy()}
     * is set to <code>true</code>.
     * Default value is <code>null</code>, meaning that no correction is performed.
     *
     * @param tiffIfdCorrector function that corrects IFD writing it to the target TIFF;
     *                     may be <code>null</code>, than it is ignored.
     * @return a reference to this object.
     */
    public TiffCopier setIfdCorrector(TiffIFDCorrector tiffIfdCorrector) {
        this.ifdCorrector = tiffIfdCorrector;
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

    public static void copyFile(Path targetTiffFile, Path sourceTiffFile)
            throws IOException {
        new TiffCopier().copyAllTiff(targetTiffFile, sourceTiffFile);
    }

    /**
     * <i>Compacts</i> the TIFF file: copies it to itself via a temporary file (which is automatically deleted).
     * This operation helps to reorder all IFD headers, eliminate possible fragmentation (unused space), etc.
     *
     * @param tiffFile file to be compacted.
     * @throws IOException in the case of any I/O errors.
     */
    public void compact(Path tiffFile) throws IOException {
        Objects.requireNonNull(tiffFile, "Null TIFF file");
        final boolean inMemory = Files.size(tiffFile) <= maxInMemoryTemporaryFileSize;
        // - note: a correct TIFF cannot have a zero length
        if (inMemory) {
            final BytesHandle tempBytes = new BytesHandle(new BytesLocation(0));
            // final BytesHandle sourceBytes = TiffIO.getBytesHandle(Files.readAllBytes(tiffFile));
            // - not-too-serious optimization: TiffReader automatically uses buffered reading
            try (TiffReader reader = new TiffReader(tiffFile, TiffOpenMode.VALID_TIFF);
                 TiffWriter writer = new TiffWriter(tempBytes)) {
                copyAllTiff(writer, reader);
            }
            copyTempFileBackToTiff(tiffFile, tempBytes);
        } else {
            Path tempFile = null;
            try {
                try (TiffReader reader = new TiffReader(tiffFile)) {
                    tempFile = temporaryFileCreator.createTemporaryFile();
                    try (TiffWriter writer = new TiffWriter(tempFile, TiffCreateMode.NO_ACTIONS)) {
                        copyAllTiff(writer, reader);
                    }
                }
                copyTempFileBackToTiff(tiffFile, tempFile);
            } finally {
                if (tempFile != null) {
                    Files.delete(tempFile);
                }
            }
        }
    }

    public void copyAllTiff(Path targetTiffFile, Path sourceTiffFile) throws IOException {
        Objects.requireNonNull(targetTiffFile, "Null target TIFF file");
        Objects.requireNonNull(sourceTiffFile, "Null source TIFF file");
        try (TiffReader reader = new TiffReader(sourceTiffFile);
             TiffWriter writer = new TiffWriter(targetTiffFile, TiffCreateMode.NO_ACTIONS)) {
            copyAllTiff(writer, reader);
        }
    }

    public void copyAllTiff(TiffWriter writer, TiffReader reader) throws IOException {
        Objects.requireNonNull(writer, "Null TIFF writer");
        Objects.requireNonNull(reader, "Null TIFF reader");
        writer.setFormatLike(reader);
        writer.create();
        copyImages(writer, reader);
    }

    public void copyImages(TiffWriter writer, TiffReader reader) throws IOException {
        copyImages(writer, reader, 0, reader.numberOfImages());
    }

    public void copyImages(TiffWriter writer, TiffReader reader, int fromIndex, int toIndex) throws IOException {
        Objects.requireNonNull(writer, "Null TIFF writer");
        Objects.requireNonNull(reader, "Null TIFF reader");
        if (fromIndex < 0) {
            throw new IllegalArgumentException("Negative fromIndex: " + fromIndex);
        }
        if (toIndex < fromIndex) {
            throw new IllegalArgumentException("toIndex < fromIndex: " + toIndex + " < " + fromIndex);
        }
        final int n = reader.numberOfImages();
        if (toIndex > n) {
            throw new IndexOutOfBoundsException("toIndex > numberOfImages: " + toIndex + " > " + n);
        }
        resetAllCounters();
        for (int i = fromIndex; i < toIndex; i++) {
            progressInformation.imageIndex = i - fromIndex;
            progressInformation.imageCount = toIndex - fromIndex;
            copyImage(writer, reader, i);
        }
    }

    public TiffWriteMap copyImage(TiffWriter writer, TiffReader reader, int sourceIfdIndex) throws IOException {
        Objects.requireNonNull(writer, "Null TIFF writer");
        Objects.requireNonNull(reader, "Null TIFF reader");
        final TiffReadMap readMap = reader.map(sourceIfdIndex);
        return copyImage(writer, readMap);
    }

    public TiffWriteMap copyImage(TiffWriter writer, TiffReadMap readMap) throws IOException {
        Objects.requireNonNull(writer, "Null TIFF writer");
        Objects.requireNonNull(readMap, "Null TIFF read map");
        long t1 = TiffIO.debugTime();
        resetImageCounters();
        final TiffIFD writeIFD = new TiffIFD(readMap.ifd());
        // - creating a clone of IFD: we must not modify the reader IFD
        if (ifdCorrector != null) {
            ifdCorrector.correct(writeIFD);
            // - theoretically, we can essentially modify IFD here
        }
        final boolean actuallyDirectCopy = canBeImageCopiedDirectly(writeIFD, writer.getByteOrder(), readMap);
        // - Note: unlike copying a rectangle in the other method, here we check compatibility
        // BEFORE calling newMap and, so, without correction of writeIFD by correctFormatForEncoding.
        // Thus, most of the checks besides the byte order will usually be unnecessary
        // (unless they have been changed by ifdCorrector).
        final boolean correctFormatForEncoding = !actuallyDirectCopy;
        // - There is no sense to call correctFormatForEncoding() method if we use tile-per-tile direct copying.
        // We could use smartFormatCorrection mode always by explicitly calling correctFormatForEncoding(ifd, true),
        // but we prefer not to do this.
        // Let this class depend on the smartFormatCorrection flag in the writer.
        final TiffWriteMap writeMap = writer.newMap(writeIFD, false, correctFormatForEncoding);
        checkImageCompatibility(writeMap, readMap);
        this.actuallyDirectCopy = actuallyDirectCopy;
        writer.writeForward(writeMap);
        final Collection<TiffTile> targetTiles = writeMap.tiles();
        progressInformation.tileCount = targetTiles.size();
        int tileCount = 0;
        long t2 = TiffIO.debugTime();
        long timeReading = 0;
        long timeCopying = 0;
        long timeWriting = 0;
        for (TiffTile targetTile : targetTiles) {
            final TiffTileIndex readIndex = readMap.copyIndex(targetTile.index());
            // - important to copy index: targetTile.index() refer to the writeIFD instead of some source IFD
            long t1Tile = TiffIO.debugTime(), t2Tile;
            if (this.actuallyDirectCopy) {
                final TiffTile sourceTile = readMap.readEncodedTile(readIndex);
                t2Tile = TiffIO.debugTime();
                targetTile.copyData(sourceTile, false);
            } else {
                final TiffTile sourceTile = readMap.readCachedTile(readIndex);
                t2Tile = TiffIO.debugTime();
                targetTile.copyUnpackedSamples(sourceTile, readMap.isAutoScaleWhenIncreasingBitDepth());
                // - this method performs necessary unpacking/packing bytes when the byte order is incompatible
            }
            writeMap.put(targetTile);
            long t3Tile = TiffIO.debugTime();
            writeMap.writeTile(targetTile, true);
            long t4Tile = TiffIO.debugTime();
            timeReading += t2Tile - t1Tile;
            timeCopying += t3Tile - t2Tile;
            timeWriting += t4Tile - t3Tile;
            progressInformation.tileIndex = tileCount;
            if (shouldBreak()) {
                break;
            }
            tileCount++;
        }
        long t3 = TiffIO.debugTime();
        writeMap.completeWriting();
        if (TiffIO.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            final long sizeInBytes = writeMap.totalSizeInBytes();
            long t4 = TiffIO.debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s copied entire image %s %dx%dx%d (%d tiles, %.3f MB) in %.3f ms = " +
                            "%.3f prepare " +
                            "+ %.3f copy " +
                            "(%.3f read + %.3f copy data + %.3f write) " +
                            "+ %.3f complete, %.3f MB/s",
                    getClass().getSimpleName(),
                    actuallyDirectCopy ? "directly" : "with repacking" + (this.directCopy ?
                            " (direct mode rejected)" : ""),
                    writeMap.dimX(), writeMap.dimY(), writeMap.numberOfChannels(),
                    tileCount,
                    sizeInBytes / 1048576.0,
                    (t4 - t1) * 1e-6,
                    (t2 - t1) * 1e-6,
                    (t3 - t2) * 1e-6,
                    timeReading * 1e-6, timeCopying * 1e-6, timeWriting * 1e-6,
                    (t4 - t3) * 1e-6,
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
        long t1 = TiffIO.debugTime();
        resetImageCounters();
        final TiffIFD writeIFD = new TiffIFD(readMap.ifd());
        // - creating a clone of IFD: we must not modify the reader IFD
        writeIFD.putImageDimensions(sizeX, sizeY, false);
        if (ifdCorrector != null) {
            ifdCorrector.correct(writeIFD);
        }
        final TiffWriteMap writeMap = writer.newMap(writeIFD, false, true);
        // - Unlike copying the entire image, here we MUST call correctFormatForEncoding() even for direct copying:
        // the direct mode will be disabled for some fromX/fromY, and this behavior should not depend on it.
        // correctFormatForEncoding(), in particular, corrects PhotometricInterpretation, and this may be necessary
        // if we need to encode tiles.
        checkImageCompatibility(writeMap, readMap);
        writer.writeForward(writeMap);
        progressInformation.tileCount = writeMap.numberOfGridTiles();
        final int readDimX = readMap.dimX();
        final int readDimY = readMap.dimY();
        final int gridCountY = writeMap.gridCountY();
        final int gridCountX = writeMap.gridCountX();
        final int mapTileSizeX = writeMap.tileSizeX();
        final int mapTileSizeY = writeMap.tileSizeY();
        final boolean directCopy = canBeRectangleCopiedDirectly(writeMap, readMap, fromX, fromY);
        final boolean swapOrder = !readMap.isByteOrderCompatible(writeMap.byteOrder());
        if (swapOrder && directCopy) {
            throw new AssertionError("If we use swapOrder branch, " +
                    "we must be sure that no tiles will be coped directly");
        }
        if (swapOrder) {
            assert readMap.byteOrder() != writeMap.byteOrder();
            assert readMap.elementType() != boolean.class;
        }
        final int fromXIndex = directCopy ? fromX / mapTileSizeX : Integer.MIN_VALUE;
        final int fromYIndex = directCopy ? fromY / mapTileSizeY : Integer.MIN_VALUE;
        long t2 = TiffIO.debugTime();
        int repackCount = 0;
        int tileCount = 0;
        for (int yIndex = 0, y = 0; yIndex < gridCountY; yIndex++, y += mapTileSizeY) {
            final int sizeYInTile = Math.min(sizeY - y, mapTileSizeY);
            final int readY = fromY + y;
            for (int xIndex = 0, x = 0; xIndex < gridCountX; xIndex++, x += mapTileSizeX) {
                final int sizeXInTile = Math.min(sizeX - x, mapTileSizeX);
                final int readX = fromX + x;
                this.actuallyDirectCopy = directCopy
                        && sizeXInTile == mapTileSizeX
                        && sizeYInTile == mapTileSizeY
                        && readY <= readDimY - sizeYInTile
                        && readX <= readDimX - sizeXInTile;
                // - note that the tile must be completely inside BOTH maps to be copied directly
                if (this.actuallyDirectCopy) {
                    final int readXIndex = fromXIndex + xIndex;
                    final int readYIndex = fromYIndex + yIndex;
                    copyEncodedTile(writeMap, readMap, xIndex, yIndex, readXIndex, readYIndex);
                } else {
                    final int written = copyRectangle(
                            writeMap, readMap, x, y, readX, readY, sizeXInTile, sizeYInTile, swapOrder);
                    if (written != writeMap.numberOfSeparatedPlanes()) {
                        // - we copy exactly one tile, and it should be completed: we created a non-resizable map,
                        // so, the initial unset area of the tile, i.e. actualRectangle(),
                        // was cropped, i.e. equal to the rectangle calculated above
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
        long t3 = TiffIO.debugTime();
        final int written = writeMap.completeWriting();
        if (written != 0) {
            throw new AssertionError("Number of tiles when completion " + written + " != 0");
        }
        if (TiffIO.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            final long sizeInBytes = writeMap.totalSizeInBytes();
            long t4 = TiffIO.debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s copied %s%s %dx%dx%d samples in %d tiles%s (%.3f MB) in %.3f ms = " +
                            "%.3f prepare + %.3f copy + %.3f complete, %.3f MB/s",
                    getClass().getSimpleName(),
                    directCopy ? "directly" : "with repacking" + (this.directCopy ? " (direct mode rejected)" : ""),
                    swapOrder ? " and reordering bytes" : "",
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
        final TagCompression compression = readMap.compression().orElse(null);
        final boolean byteOrderIsNotUsedForImageData =
                isByteOrBinary(readMap) && (compression != null && !compression.canUseByteOrderForByteData());
        // - for unknown compressions, we cannot be sure of this fact even for 1-bit or 8-bit samples
        final boolean bitDepthEqual = Arrays.equals(readMap.bitsPerSample(), writeIFD.getBitsPerSample());
        // - Number of bits per sample MAY become different after smart correction by correctFormatForEncoding:
        // 1) in the case of unusual precisions like 16-bit float:
        // for writing, it will be transformed into 32-bit float values;
        // 2) in the case of non-whole bytes: N bits per samples, N > 1 and N%8 != 1;
        // they are always unpacked to format with an integer number of bytes per sample.
        // However, if they are actually equal, even non-standard, we should enable direct copying.
        final boolean photometricInterpretationEqual =
                readMap.photometricInterpretationCode() == writeIFD.getPhotometricInterpretationCode();
        // - Photometric interpretation MAY become different after smart correction by correctFormatForEncoding
        return this.directCopy &&
                (readMap.byteOrder() == writeByteOrder || byteOrderIsNotUsedForImageData) &&
                compressionCode == writeIFD.getCompressionCode() &&
                bitDepthEqual &&
                photometricInterpretationEqual;
        // Also note that the compatibility of the sample type and pixel structure (number of channels and
        // planar separated mode) will be checked later in checkImageCompatibility() method.

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
            boolean swapOrder) throws IOException {
        List<TiffTile> tiles;
        byte[] samples = readMap.loadSampleBytes(
                readX, readY, sizeX, sizeY, TiffReader.UnusualPrecisions.UNPACK);
        if (swapOrder) {
            samples = JArrays.copyAndSwapByteOrder(samples, readMap.elementType());
        }
        tiles = writeMap.updateSampleBytes(samples, writeX, writeY, sizeX, sizeY);
        return writeMap.writeCompletedTiles(tiles);
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

    private void copyTempFileBackToTiff(Path tiffFile, Object tempBuffer) throws IOException {
        progressInformation.copyingTemporaryFile = true;
        try {
            long t1 = TiffIO.debugTime();
            final long sizeInBytes;
            shouldBreak();
            // - ignore the result: no sense to break at this last stage
            if (tempBuffer instanceof BytesHandle tempBytes) {
                try (DataHandle<?> tiffStream = TiffIO.getFileHandle(tiffFile)) {
                    sizeInBytes = TiffIO.copyFile(tempBytes, tiffStream);
                }
            } else if (tempBuffer instanceof Path tempFile) {
                sizeInBytes = Files.size(tempFile);
                Files.copy(tempFile, tiffFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                throw new AssertionError("Unknown tempBuffer " + tempBuffer);
            }
            if (TiffIO.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
                long t2 = TiffIO.debugTime();
                LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                        "%s copied %d bytes%s (%.3f MB) in %.3f ms, %.3f MB/s",
                        getClass().getSimpleName(),
                        sizeInBytes,
                        tempBuffer instanceof BytesHandle ? " from memory" : " from temporary file",
                        sizeInBytes / 1048576.0,
                        (t2 - t1) * 1e-6,
                        sizeInBytes / 1048576.0 / ((t2 - t1) * 1e-9)));

            }
        } finally {
            progressInformation.copyingTemporaryFile = false;
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
        progressInformation.copyingTemporaryFile = false;
    }

    private boolean shouldBreak() {
        if (progressUpdater != null) {
            progressUpdater.accept(progressInformation);
        }
        return interruptionChecker != null && interruptionChecker.getAsBoolean();
    }
}

