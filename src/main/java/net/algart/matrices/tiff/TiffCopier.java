/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2023-2026 Daniel Alievsky, AlgART Laboratory (http://algart.net)
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

        public boolean isCompactingInMemory() {
            return TiffCopier.this.isCompactingInMemory();
        }
    }

    @FunctionalInterface
    public interface ProgressUpdater {
        void update(ProgressInformation progressInformation);
    }

    public static final int DEFAULT_MAX_IN_MEMORY_TEMP_FILE_SIZE = Math.max(0,
            net.algart.arrays.Arrays.SystemSettings.getIntProperty(
                    "net.algart.matrices.tiff.defaultMaxInMemoryTempFileSize", 32 * 1048576));

    private static final System.Logger LOG = System.getLogger(TiffCopier.class.getName());
    private static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);

    private boolean directCopy = true;
    private int maxInMemoryTempFileSize = DEFAULT_MAX_IN_MEMORY_TEMP_FILE_SIZE;
    private TemporaryFileCreator temporaryFileCreator = TemporaryFileCreator.DEFAULT;
    private TiffIFDCorrector ifdCorrector = null;
    private TagCompression compression = null;
    private ProgressUpdater progressUpdater = null;
    private BooleanSupplier interruptionChecker = null;
    private int progressUpdateDelay = 0;

    private volatile boolean cancelled = false;
    private final ProgressInformation progressInformation = new ProgressInformation();
    private long lastProgressUpdateTime = Long.MIN_VALUE;

    private boolean compactingInMemory = false;
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
     * Enables or disables direct copying of TIFF image tiles without decompression and recompression.
     * The default value is <code>true</code>.
     *
     * <p>This flag is ignored (treated as <code>false</code>) when the
     * encoded tiles in the source and target TIFF may differ, for example,
     * due to different byte order, a change of compression as a result of
     * calling {@link #setCompression(TagCompression)}, or other modifications
     * performed by the {@link #setIfdCorrector(TiffIFDCorrector) IFD corrector}.</p>
     *
     * @param directCopy whether direct copying of TIFF tiles should be used when possible.
     * @return a reference to this object.
     */
    public TiffCopier setDirectCopy(boolean directCopy) {
        this.directCopy = directCopy;
        return this;
    }

    public int getMaxInMemoryTempFileSize() {
        return maxInMemoryTempFileSize;
    }

    /**
     * Sets the threshold used by {@link #compact(Path)} method.
     * If the source TIFF file size is not larger than this threshold, the source TIFF file
     * is copied to temporary "file" in memory ({@link BytesHandle}),
     * otherwise we create a real temporary file using
     * {@link Files#createTempFile(Path, String, String, java.nio.file.attribute.FileAttribute[])
     * Files.createTempFile} method.
     * Zero value disables this feature.
     *
     * <p>The initial value is {@link #DEFAULT_MAX_IN_MEMORY_TEMP_FILE_SIZE} (32 MB by default).
     * You may set this threshold to some value like 100 MB, depending on the available memory.
     * Please note that the actual size of the temporary file may become larger than this threshold;
     * for example, this is possible when the source TIFF contains multiple tiles with identical offsets.
     * Another possible situation &mdash; if you enforce recompression of all tiles via the call
     * {@link #setDirectCopy(boolean) setDirectCopy(false)}; however, this optimization is not as useful
     * in the latter case.
     *
     * @param maxInMemoryTempFileSize maximal TIFF file size to be compacted in-memory.
     * @return a reference to this object.
     * @throws IllegalArgumentException if the argument is negative.
     */
    public TiffCopier setMaxInMemoryTempFileSize(int maxInMemoryTempFileSize) {
        if (maxInMemoryTempFileSize < 0) {
            throw new IllegalArgumentException("Negative maxInMemoryTemporaryFileSize: " +
                    maxInMemoryTempFileSize);
        }
        this.maxInMemoryTempFileSize = maxInMemoryTempFileSize;
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

    public boolean hasCompression() {
        return compression != null;
    }

    public TagCompression getCompression() {
        return compression;
    }

    /**
     * Sets the compression to be used when writing images to the target TIFF.
     * The default value is <code>null</code>, which means that the copier writes
     * the target TIFF tiles using the same compression as the source TIFF.
     *
     * <p>If this method is called with a non-<code>null</code> argument,
     * the copier overrides the compression specified in the source IFD
     * with the compression provided in the argument.</p>
     *
     * <p>Note: if the specified compression belongs to the
     * {@link TagCompression#isJpegFamily() JPEG family},
     * the copier will also {@link TiffIFD#removePhotometric()
     * remove the photometric interpretation} (if present in the source IFD),
     * so that the TIFF writer will set the appropriate
     * <code>PhotometricInterpretation</code> tag automatically.</p>
     *
     * <p>In most cases, this method should not be used
     * when {@link #isDirectCopy() direct copy} mode is enabled:
     * changing the compression disables direct copying,
     * even if that flag is <code>true</code>.</p>
     *
     * <p>Please remember that the compression set by this method will apply to <i>all</i>
     * TIFF images copied by this object. This can be problematic if the TIFF contains multiple images
     * compressed using different methods, such as an SVS image pyramid or similar files.</p>
     *
     * @param compression the new compression; can be {@code null}, in which case the source compression is used.
     * @return a reference to this object.
     */
    public TiffCopier setCompression(TagCompression compression) {
        this.compression = compression;
        return this;
    }

    public TiffCopier removeCompression() {
        this.compression = null;
        return this;
    }

    public boolean hasIfdCorrector() {
        return ifdCorrector != null;
    }

    /**
     * Returns the IFD corrector: a function that corrects the IFD obtained
     * from the source TIFF read map before writing it to the target TIFF.
     *
     * @return the current IFD curretor or <code>null</code> if no IFD corrector is set.
     */
    public TiffIFDCorrector getIfdCorrector() {
        return ifdCorrector;
    }

    public TiffCopier removeIfdCorrector() {
        this.ifdCorrector = null;
        return this;
    }

    /**
     * Sets the IFD corrector: a function that corrects the IFD obtained
     * from the source TIFF read map before writing it to the target TIFF.
     * Usually this method should not be used when {@link #isDirectCopy() direct copy} mode is enabled.
     * The default value is <code>null</code>, meaning that no correction
     * is performed.
     *
     * <p>Note: this function is called <i>after</i> applying the compression
     * change requested by {@link #setCompression(TagCompression)} method
     * (if that method was called with a non-<code>null</code> argument).</p>
     *
     * @param tiffIfdCorrector new IFD corrector; can be {@code null}, in which case no correction is performed.
     * @return a reference to this object.
     */
    public TiffCopier setIfdCorrector(TiffIFDCorrector tiffIfdCorrector) {
        this.ifdCorrector = tiffIfdCorrector;
        return this;
    }

    public ProgressUpdater getProgressUpdater() {
        return progressUpdater;
    }

    public TiffCopier setProgressUpdater(ProgressUpdater progressUpdater, int progressUpdateDelay) {
        return setProgressUpdateDelay(progressUpdateDelay).setProgressUpdater(progressUpdater);
    }

    public TiffCopier setProgressUpdater(ProgressUpdater progressUpdater) {
        this.progressUpdater = progressUpdater;
        return this;
    }

    public BooleanSupplier getInterruptionChecker() {
        return interruptionChecker;
    }

    public TiffCopier setInterruptionChecker(BooleanSupplier interruptionChecker) {
        this.interruptionChecker = interruptionChecker;
        return this;
    }

    public long getProgressUpdateDelay() {
        return progressUpdateDelay;
    }

    public TiffCopier removeProgressUpdateDelay() {
        this.progressUpdateDelay = 0;
        return this;
    }

    /**
     * Sets the minimal delay between calls of the {@link ProgressUpdater}.
     *
     * <p>If the delay is positive, the progress updater is not called more often than once
     * within the specified number of milliseconds. This helps to avoid excessively frequent
     * updates when copying many small tiles.</p>
     *
     * <p>Note: the progress updater is always called after processing the last tile in an image
     * regardless of this parameter.</p>
     *
     * <p>A zero value disables this feature and allows the updater to be called for every
     * progress event.</p>
     *
     * @param progressUpdateDelay minimal delay between progress updates in milliseconds, 0 to disable this feature.
     * @return this copier instance.
     * @throws IllegalArgumentException if {@code progressUpdateDelay} is negative.
     */
    public TiffCopier setProgressUpdateDelay(int progressUpdateDelay) {
        if (progressUpdateDelay < 0) {
            throw new IllegalArgumentException("Negative progressUpdateDelay: " + progressUpdateDelay);
        }
        this.progressUpdateDelay = progressUpdateDelay;
        return this;
    }

    public ProgressInformation progressInformation() {
        return progressInformation;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Returns <code>true</code> if the last (or current) {@link #compact(Path)} operation
     * was performed in-memory.
     *
     * <p>This flag is set to <code>true</code> at the beginning of the {@link #compact(Path)}
     * method if the source TIFF file size does not exceed the threshold set by
     * {@link #setMaxInMemoryTempFileSize(int)}. If the file is larger, or if
     * the <code>compact</code> method has not been called yet, this method
     * returns <code>false</code>.</p>
     *
     * @return <code>true</code> if the compaction is performed in RAM;
     * <code>false</code> if a real temporary file on disk is used.
     */
    public boolean isCompactingInMemory() {
        return compactingInMemory;
    }

    public boolean isActuallyDirectCopy() {
        return actuallyDirectCopy;
    }

    public static void copyFile(Path targetTiffFile, Path sourceTiffFile)
            throws IOException {
        new TiffCopier().copyAllTiff(targetTiffFile, sourceTiffFile);
    }


    /**
     * Checks whether the specified position ({@code x}, {@code y}) is aligned
     * with the tile grid of the given TIFF image.
     *
     * <p>This method is equivalent to the following check:
     * <pre>
     *     x % map.tileSizeX() == 0 &amp;&amp; y % map.tileSizeY() == 0
     * </pre>
     *
     * <p>This allows ensuring that the starting position is aligned with tile boundaries
     * is useful when calling
     * {@link #copyImage(TiffWriter, TiffReadMap, int, int, int, int)}
     * with {@code fromX} and {@code fromY} equal to the checked coordinates.
     * In most cases, such alignment allows significantly faster data transfer
     * because whole tiles can be copied directly in their compressed form,
     * bypassing decoding and recompression by the codec.
     *
     * @param map the TIFF map describing the source image.
     * @param x   the starting X-coordinate in the source image.
     * @param y   the starting Y-coordinate in the source image.
     * @return {@code true} if the specified position is aligned with the tile grid; {@code false} otherwise.
     */
    public static boolean isTileAligned(TiffMap map, int x, int y) {
        return x % map.tileSizeX() == 0 && y % map.tileSizeY() == 0;
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
        this.compactingInMemory = Files.size(tiffFile) <= maxInMemoryTempFileSize;
        // - note: a correct TIFF cannot have a zero length
        if (compactingInMemory) {
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
        correctIFD(writeIFD);
        final boolean actuallyDirectCopy = canCopyImageDirectly(writeIFD, writer.getByteOrder(), readMap);
        // - Note: unlike copying a rectangle in the other method, here we check compatibility
        // BEFORE calling newMap and, so, without correction of writeIFD by correctForEncoding.
        // Thus, most of the checks besides the byte order will usually be unnecessary
        // (unless they have been changed by ifdCorrector).
        final boolean correctForEncoding = !actuallyDirectCopy;
        // - There is no sense to call correctForEncoding() method if we use tile-per-tile direct copying.
        // We could use smartCorrection mode always by explicitly calling correctForEncoding(ifd, true),
        // but we prefer not to do this.
        // Let this class depend on the smartCorrection flag in the writer.
        final TiffWriteMap writeMap = writer.newMap(writeIFD, false, correctForEncoding);
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
        if (fromX < 0) {
            throw new IllegalArgumentException("Negative fromX: " + fromX);
        }
        if (fromY < 0) {
            throw new IllegalArgumentException("Negative fromY: " + fromY);
        }
        long t1 = TiffIO.debugTime();
        resetImageCounters();
        final TiffIFD writeIFD = new TiffIFD(readMap.ifd());
        // - creating a clone of IFD: we must not modify the reader IFD
        writeIFD.putImageDimensions(sizeX, sizeY, false);
        correctIFD(writeIFD);
        final TiffWriteMap writeMap = writer.newMap(writeIFD, false, true);
        // - Unlike copying the entire image, here we MUST call correctForEncoding() even for direct copying:
        // the direct mode will be disabled for some fromX/fromY, and this behavior should not depend on it.
        // correctForEncoding(), in particular, corrects PhotometricInterpretation, and this may be necessary
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
        assert readMap.isTiled() == writeMap.isTiled() : "Different tiling";
        if (readMap.isTiled()) {
            // - for stripped images, the strip width is equal to the image width and can be different
            if (readMap.tileSizeX() != mapTileSizeX) {
                throw new AssertionError("Different tiles in%n%s and %n%s".formatted(readMap, writeMap));
            }
            if (readMap.tileSizeY() != mapTileSizeY) {
                throw new AssertionError("Different tiles in%n%s and %n%s".formatted(readMap, writeMap));
            }
        }
        final boolean directCopy = canCopyRectangleDirectly(writeMap, readMap, fromX, fromY);
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
        for (int toYIndex = 0, y = 0; toYIndex < gridCountY; toYIndex++, y += mapTileSizeY) {
            final int readY = fromY + y;
            final int writeSizeYInTile = Math.min(sizeY - y, mapTileSizeY);
            final int readSizeYInTile = Math.min(readDimY - readY, mapTileSizeY);
            for (int toXIndex = 0, x = 0; toXIndex < gridCountX; toXIndex++, x += mapTileSizeX) {
                final int readX = fromX + x;
                final int writeSizeXInTile = Math.min(sizeX - x, mapTileSizeX);
                final int readSizeXInTile = Math.min(readDimX - readX, mapTileSizeX);
                // - readSizeX/YInTile can be < writeSizeX/YInTile and even < 0:
                // then we will use repacking to fill the extra pixels by zeros
                this.actuallyDirectCopy = directCopy
                        && writeSizeXInTile == readSizeXInTile
                        && writeSizeYInTile == readSizeYInTile;
                // - note that the tile must be completely inside BOTH maps to be copied directly
                if (this.actuallyDirectCopy) {
                    final int readXIndex = fromXIndex + toXIndex;
                    final int readYIndex = fromYIndex + toYIndex;
                    copyEncodedTile(writeMap, readMap, toXIndex, toYIndex, readXIndex, readYIndex);
                } else {
                    final int written = copyRectangle(
                            writeMap, readMap, x, y, readX, readY, writeSizeXInTile, writeSizeYInTile, swapOrder);
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

    private boolean canCopyImageDirectly(TiffIFD writeIFD, ByteOrder writeByteOrder, TiffReadMap readMap)
            throws TiffException {
        if (!this.directCopy) {
            return false;
        }
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
        // - Number of bits per sample MAY become different after smart correction by correctForEncoding:
        // 1) in the case of unusual precisions like 16-bit float:
        // for writing, it will be transformed into 32-bit float values;
        // 2) in the case of non-whole bytes: N bits per samples, N > 1 and N%8 != 1;
        // they are always unpacked to format with an integer number of bytes per sample.
        // However, if they are actually equal, even non-standard, we should enable direct copying.
        final boolean photometricEqual = readMap.photometricCode() == writeIFD.getPhotometricCode();
        // - necessary because corrrectIFD() can remove the photometric interpretation!
        // It also MAY become different after smart correction by correctForEncoding
        return (readMap.byteOrder() == writeByteOrder || byteOrderIsNotUsedForImageData) &&
                compressionCode == writeIFD.getCompressionCode() &&
                bitDepthEqual &&
                photometricEqual;
        // Also note that the compatibility of the sample type and pixel structure (number of channels and
        // planar separated mode) will be checked later in checkImageCompatibility() method.

    }

    private static boolean isByteOrBinary(TiffReadMap readMap) {
        return readMap.isBinary() || readMap.sampleType().bitsPerSample() == 8;
    }

    private boolean canCopyRectangleDirectly(TiffWriteMap writeMap, TiffReadMap readMap, int fromX, int fromY)
            throws TiffException {
        final boolean equalTiles =
                readMap.tileSizeX() == writeMap.tileSizeX() && readMap.tileSizeY() == writeMap.tileSizeY();
        // - note: for stripped images, this flag will be false almost always:
        // the strip "size-X" is the image width
        return equalTiles &&
                isTileAligned(writeMap, fromX, fromY) &&
                canCopyImageDirectly(writeMap.ifd(), writeMap.byteOrder(), readMap);
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

    private void correctIFD(TiffIFD writeIFD) {
        Objects.requireNonNull(writeIFD, "Null TIFF IFD");
        if (compression != null) {
            final TagCompression existing = writeIFD.optCompression().orElse(null);
            if (compression != existing) {
                // - in particular, with the same compression code: JPEG_RGB != JPEG!
                // But if we have correctly recognized JPEG_RGB, and we request JPEG_RGB,
                // there is no sense to change anything.
                if (compression.isJpegFamily()) {
                    writeIFD.removePhotometric();
                    // - usually leads to disabling direct copy, even though the compression code is the same (JPEG)
                }
                writeIFD.putCompression(compression);
            }
            // - else we still can perform repacking by setDirectCopy(false)
        }
        if (ifdCorrector != null) {
            ifdCorrector.correct(writeIFD);
            // - theoretically, we can essentially modify IFD here
        }
    }

    private void copyTempFileBackToTiff(Path tiffFile, Object tempBuffer) throws IOException {
        progressInformation.copyingTemporaryFile = true;
        try {
            long t1 = TiffIO.debugTime();
            final long sizeInBytes;
            updateProgress();
            // - don't try to break: no sense at this last stage
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
        cancelled = false;
    }

    private boolean shouldBreak() {
        updateProgress();
        final boolean result = interruptionChecker != null && interruptionChecker.getAsBoolean();
        if (result) {
            cancelled = true;
        }
        return result;
    }

    private void updateProgress() {
        if (progressUpdater != null) {
            boolean enableUpdate = progressUpdateDelay <= 0 ||
                    progressInformation.isLastTileCopied() ||
                    progressInformation.isCopyingTemporaryFile();
            if (!enableUpdate) {
                long t = System.currentTimeMillis();
                if (t > lastProgressUpdateTime + progressUpdateDelay) {
                    // - not use "t - lastProgressUpdateTime": overflow at the very beginning
                    lastProgressUpdateTime = t;
                    enableUpdate = true;
                }
            }
            if (enableUpdate) {
                progressUpdater.update(progressInformation);
            }
        }
    }
}

