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
import net.algart.matrices.tiff.io.ReadBufferDataHandle;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagType;
import net.algart.matrices.tiff.tags.TagValue;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.*;
import org.scijava.io.handle.BytesHandle;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.FileHandle;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;

import java.awt.*;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.Array;
import java.net.URI;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

public sealed abstract class TiffIO implements Closeable permits TiffReader, TiffWriter {
    /**
     * What parts of the IFD information should be read.
     */
    public enum ReadIFDMode {
        /**
         * Reading all IFD information: entries and the offset of the next IFD in the IFDs chain.
         */
        NORMAL(true, true),

        /**
         * Reading only the next IFD offset and the common information.
         * The properties {@link TiffIFD#getNextIFDOffset() next IFD offset}
         * and {@link TiffIFD#getFileOffsetOfNextIFDOffset() offset of the next IFD offset} will be filled,
         * along with other common information like
         * {@link TiffIFD#getFileOffsetOfIFD() IFD start offset}, {@link TiffIFD#isBigTiff() BigTIFF} flag, and
         * {@link TiffIFD#getByteOrder() byte order}.
         * The {@link TiffIFD#map() entries map} will remain empty.
         */
        SKIP_IFD_ENTRIES(false, true),

        /**
         * Reads the entries and common information, skipping the next IFD offset.
         * The {@link TiffIFD#map() entries map} and common information (such as
         * {@link TiffIFD#getFileOffsetOfIFD() IFD start offset}, {@link TiffIFD#isBigTiff() BigTIFF} flag,
         * and {@link TiffIFD#getByteOrder() byte order}) will be parsed,
         * but the properties {@link TiffIFD#getNextIFDOffset() next IFD offset}
         * and {@link TiffIFD#getFileOffsetOfNextIFDOffset() offset of the next IFD offset} will remain unset.
         *
         * <p>This mode is intended for sub-IFDs, EXIF, GPS, and other
         * {@link TiffReader#linkedIFD(TiffIFD, int) linked IFDs}.
         * Note: the TIFF standard requires the next offset field to be present,
         * though it is irrelevant for these IFD types.
         * Moreover, some "wild" TIFF files
         * may lack this field entirely, and attempting to read it could lead to
         * {@link java.io.EOFException}.
         * (Note: the standard <code>libtiff</code> library silently fills this field with zero in such cases.)</p>
         */
        SKIP_NEXT_IFD_OFFSET(true, false);

        private final boolean readingIFDEntries;
        private final boolean readingNextIFDOffset;

        ReadIFDMode(boolean readingIFDEntries, boolean readingNextIFDOffset) {
            this.readingIFDEntries = readingIFDEntries;
            this.readingNextIFDOffset = readingNextIFDOffset;
        }

        public boolean isReadingIFDEntries() {
            return readingIFDEntries;
        }

        public boolean isReadingNextIFDOffset() {
            return readingNextIFDOffset;
        }
    }

    /**
     * Options for customizing {@link TiffMap} creation, such as in
     * {@link TiffReader#map(TiffIFD, Set)} and {@link TiffWriter#newMap(TiffIFD, boolean, Set)}.
     */
    public enum MapOption {
        /**
         * If this option is set,
         * the method {@link TiffWriter#newMap(TiffIFD, boolean, Set)} automatically calls the
         * {@link TiffWriter#correctForEncoding(TiffIFD)} method for
         * the specified <code>ifd</code> argument.
         * In typical usage, this option should be set.
         * But you may remove this option if you want to control all IFD settings yourself,
         * in particular if you prefer to call the method {@link TiffWriter#correctForEncoding(TiffIFD, boolean)}
         * with non-standard {@link TiffWriter#setSmartCorrection smartCorrection} flag.
         *
         * <p>Note: this option is ignored by {@link TiffReader}.</p>
         */
        CORRECT_FOR_ENCODING,
        /**
         * If this option is set,
         * the methods creating a new map automatically call the
         * {@link TiffMap#buildTileGrid()} method for the created map.
         * It is useful to perform loops on all tiles.
         * However, you may freely remove this option if you are not going to work with the map yourself:
         * usually this method is called again inside all methods that need the grid.
         */
        BUILD_GRID;

        /**
         * Set of options with IFD correction and tile grid building.
         */
        public static final Set<MapOption> CORRECTION_SET = Set.of(BUILD_GRID, CORRECT_FOR_ENCODING);

        /**
         * Options set without IFD correction.
         */
        public static final Set<MapOption> NO_CORRECTION_SET = Set.of(BUILD_GRID);

        /**
         * Default set of options for general map creation. Identical to {@link #CORRECTION_SET}.
         */
        public static final Set<MapOption> DEFAULT = CORRECTION_SET;

        /**
         * Returns a copy of {@link #DEFAULT} with the specified options removed.
         *
         * @param options options to be excluded from the default set.
         * @return the reduced set of options.
         */
        public static Set<MapOption> without(MapOption... options) {
            Objects.requireNonNull(options, "Null options array");
            final EnumSet<MapOption> result = EnumSet.copyOf(DEFAULT);
            for (MapOption option : options) {
                result.remove(option);
            }
            return Collections.unmodifiableSet(result);
        }

        public static Set<MapOption> ofCorrection(boolean correctForEncoding) {
            return correctForEncoding ? CORRECTION_SET : NO_CORRECTION_SET;
        }
    }

    /**
     * Subclasses of this class can be used for storing additional information about encoding or decoding tiles,
     * for example, in some specific TIFF codecs.
     * This additional information is provided via override {@link #toString()} method in the subclasses.
     */
    public static class CodecReport {
    }

    /**
     * If the number of IFDs in a TIFF file is greater than this limit, an exception will be thrown:
     * it is mostly probable that it is a corrupted file.
     */
    public static final int MAX_NUMBER_OF_IFDS = 100_000_000;

    public static final int FILE_USUAL_MAGIC_NUMBER = 0x2a;
    public static final int FILE_BIG_TIFF_MAGIC_NUMBER = 0x2b;
    public static final int FILE_PREFIX_LITTLE_ENDIAN = 0x49;
    public static final int FILE_PREFIX_BIG_ENDIAN = 0x4d;

    public static final boolean BUILT_IN_TIMING = getBooleanProperty("net.algart.matrices.tiff.timing");

    static final System.Logger LOG = System.getLogger(TiffIO.class.getName());
    static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);

    private static final int MINIMAL_ALLOWED_TIFF_FILE_LENGTH =
            TiffIFD.TIFF_FILE_HEADER_LENGTH + 2 + TiffIFD.BYTES_PER_ENTRY + 4;
    // - 8 bytes header and at least 1 IFD entry (usually at least 2 entries required: ImageWidth + ImageLength):
    // see TiffIFD.lengthInFileExcludingEntries;
    // note that this constant should be > 16 to detect a "fake" BigTIFF file, containing header only

    private static final boolean OPTIMIZE_READING_IFD_ARRAYS = true;
    // - Note: this optimization allows speeding up reading a large array of offsets.
    // If we use simple FileHandle for reading files (based on RandomAccessFile),
    // acceleration is up to 100 and more times:
    // on my computer, 23220 int32 values were loaded in 0.2 ms instead of 570 ms.
    // Since scijava-common 2.95.1, we use optimized ReadBufferDataHandle for reading a file;
    // now acceleration for 23220 int32 values is 0.2 ms instead of 0.4 ms.

    private static final boolean AVOID_LONG8_FOR_ACTUAL_32_BITS = true;
    // - If was necessary for some old programs (like Aperio Image Viewer), which
    // did not understand LONG8 values for some popular tags like image sizes.
    // In any case, real BigTIFF files usually store most tags in standard LONG type (32 bits), not in LONG8.

    final DataHandle<?> stream;
    final Path filePath;

    volatile boolean tiff;
    volatile boolean validTiff;
    volatile boolean bigTiff = false;
    private volatile Object context = null;
    private volatile byte byteFiller = 0;
    private volatile Consumer<TiffTile> tileInitializer = null;

    volatile boolean fileOpen = false;
    volatile TiffIFD.Linkage linkage = null;

    volatile Object scifio = null;
    private volatile CodecReport lastCodecReport = null;

    final Object fileLock = new Object();

    public TiffIO(DataHandle<?> stream) {
        this(stream, null);
    }

    TiffIO(DataHandle<?> stream, Path filePath) {
        this.stream = Objects.requireNonNull(stream, "Null data handle (input/output stream)");
        this.filePath = filePath;
        this.tiff = this.validTiff = this instanceof TiffWriter;
        // - TiffWriter is usually created without any file, but analyzeFileHeader() is not called always;
        // we MUST suppose that it is a valid TIFF for all other cases
    }

    /**
     * Returns whether this file is a TIFF file, either classic TIFF or BigTIFF
     * (i.e., whether it contains the correct TIFF or BigTIFF file header).
     *
     * <p>For a {@link TiffReader}, this flag is set according to the actual
     * file header analyzed by the constructor. If the constructor with
     * {@link TiffReader.OpenMode#VALID_TIFF} mode completed successfully,
     * this method is guaranteed to return {@code true}.
     *
     * <p>Note that a {@code true} result does not guarantee that the TIFF file is
     * completely valid. In particular, when a reader is opened in
     * {@link TiffReader.OpenMode#NO_CHECKS} mode, a {@code true} result may
     * coexist with {@code false} returned by {@link #isValidTiff()}.
     *
     * <p>For a {@link TiffWriter}, the flag is initially {@code true}, since
     * a writer is assumed to operate on a TIFF file even before the file is
     * opened or created. However, if you try to open an existing non-TIFF file,
     * for example, via the {@link TiffWriter#openExisting()} method,
     * an exception will be thrown, and this flag will be cleared to {@code false}.
     *
     * @return whether the file is a TIFF.
     */
    public final boolean isTiff() {
        return tiff;
    }

    /**
     * Returns {@code true} if the file is a TIFF file and no problems
     * were found while analyzing the file header.
     * However, this is not a guarantee that problems
     * (like format errors) will not be found later while reading IFDs or image data.
     *
     * <p>In most cases, this method returns the same value as {@link #isTiff()}.
     * It may return {@code false} while {@link #isTiff()} is {@code true} only
     * if the TIFF header is present but the file is corrupted (e.g., too short,
     * or writing an image to a file was interrupted before the first IFD offset was written).
     * This can happen in a {@link TiffReader} opened in
     * {@link TiffReader.OpenMode#NO_CHECKS} mode.
     *
     * <p>Note: if a {@link TiffReader} was successfully created in
     * {@link TiffReader.OpenMode#VALID_TIFF} mode, this method is guaranteed
     * to return {@code true}.
     *
     * <p>Note: for a {@link TiffReader}, this method is equivalent to the check
     * <code>{@link TiffReader#openingException() reader.openingException()} == null</code>.
     *
     * <p>For a {@link TiffWriter}, the flag is initially {@code true}, since
     * a writer is assumed to operate on a valid TIFF file even before the file is
     * opened or created. However, if you try to open an existing corrupted file,
     * for example, via the {@link TiffWriter#openExisting()} method,
     * an exception will be thrown, and this flag will be cleared to {@code false}.
     *
     * @return whether the file is a valid TIFF/BigTIFF file.
     */
    public final boolean isValidTiff() {
        return validTiff;
    }

    /**
     * Returns whether this file is a BigTIFF file.
     *
     * @return whether the file is a BigTIFF.
     */
    public final boolean isBigTiff() {
        return bigTiff;
    }

    /**
     * Returns whether we are reading or writing little-endian data.
     *
     * @return whether this is a little-endian TIFF.
     */
    public final boolean isLittleEndian() {
        synchronized (fileLock) {
            return stream.isLittleEndian();
        }
    }

    /**
     * Returns <code>{@link #isLittleEndian()} ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN</code>.
     *
     * @return byte order in the TIFF file.
     */
    public final ByteOrder getByteOrder() {
        return isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    }

    public Object getContext() {
        return context;
    }

    public void setContext(Object context) {
        this.scifio = null;
        this.context = context;
    }

    public final byte getByteFiller() {
        return byteFiller;
    }

    /**
     * Sets the filler byte for tiles that lie completely outside the image boundaries.
     *
     * <p>A value of {@code 0} typically represents black; {@code 0xFF} represents
     * white in most 8-bit sample formats.</p>
     *
     * <p><b>Warning:</b> when working with non-8-bit TIFFs (especially floating-point data),
     * it is recommended to preserve the default {@code 0} value. Otherwise, interpreting
     * the raw filled bytes under non-8-bit data types can lead to unexpected results.
     * To configure more complex backgrounds or multibyte default values, use
     * {@link #setTileInitializer(Consumer)}.</p>
     *
     * @param byteFiller the new filler byte.
     * @return a reference to this object.
     */
    public TiffIO setByteFiller(byte byteFiller) {
        this.byteFiller = byteFiller;
        return this;
    }

    public final Consumer<TiffTile> getTileInitializer() {
        return tileInitializer;
    }

    /**
     * Sets the <i>tile initializer</i>: a callback function used to initialize empty tiles.
     * By default, this is {@code null} (not specified).
     *
     * <p>During reading:
     * the {@link TiffReader} class (and {@link TiffWriteMap} when loading an existing image prior to
     * rewriting the TIFF) reads tiles or strips via the current
     * {@link TiffIOMap#setTileSupplier(TileSupplier) tile supplier} (typically
     * via {@link TiffReader#readCachedTile(TiffTileIndex)}), and, usually, loaded tiles are
     * not {@link TiffTile#isEmpty() empty}. However, empty tiles can occur in
     * some "sparse" formats such as <b>Philips TIFF</b> and <b>ARGOS TIFF</b> when
     * {@link TiffReader#setMissingTilesAllowed(boolean)}
     * is enabled. In such situations, if a non-null <i>tile initializer</i> is specified, it is
     * automatically invoked for each empty tile. You can use this, for example, to fill missing tiles with a
     * custom background color.</p>
     *
     * <p>If the <i>tile initializer</i> is {@code null}, such empty tiles,
     * if any, are ignored during reading, and the corresponding
     * regions remain filled with the {@link #setByteFiller(byte) byte filler}.</p>
     *
     * <p>During writing:
     * the {@link TiffWriteMap} class invokes this initializer for any new tile before
     * filling it with data via {@link TiffWriteMap#updateMatrix} or similar methods.
     * It is also invoked right before writing a tile to the file if the tile remains unfilled.</p>
     *
     * <p>If the <i>tile initializer</i> is {@code null}, newly allocated tile data arrays are automatically filled
     * with the {@link #setByteFiller(byte) byte filler}.</p>
     *
     * @param tileInitializer the <i>tile initializer</i>, or {@code null} to disable custom initialization.
     * @return a reference to this object.
     */
    public TiffIO setTileInitializer(Consumer<TiffTile> tileInitializer) {
        this.tileInitializer = tileInitializer;
        return this;
    }

    /**
     * Sets the background color for initializing empty tiles.
     * Equivalent to
     * <pre>{@link #setTileInitializer(Consumer)
     * setTileInitializer}(tile -> tile.{@link TiffTile#fillColor(Color) fillColor}(color))</pre>
     *
     * <p>Note: if the image uses {@link TiffMap#isPlanarSeparated() planar-separated} mode,
     * tiles are stored separately per color channel (for example, an RGB image is split into
     * three separate groups of tiles), and the initializer processes each channel plane independently.
     * Because each tile represents a single-channel (grayscale) plane, the specified {@code color}
     * (such as green) is converted to a gray color of similar brightness within each plane.</p>
     *
     * @param color color to fill empty tiles.
     * @return a reference to this object.
     * @throws NullPointerException if the argument is {@code null}.
     */
    public TiffIO setTileInitializer(Color color) {
        Objects.requireNonNull(color, "Null color");
        return setTileInitializer(tile -> tile.fillColor(color));
    }

    /**
     * Returns the object used to synchronize access to the underlying file {@link #stream() stream}.
     * Clients must synchronize on this object when performing manual I/O operations to ensure thread safety.
     *
     * <p>Note that a companion reader created via {@link TiffWriter#companionReader()}
     * <b>does not</b> share this lock object with its parent writer. You should not try using
     * the writer and its companion reader together from parallel threads.</p>
     *
     * @return the lock object for synchronizing access to the stream.
     */
    public final Object fileLock() {
        // We do not try using here this.stream object: TiffReader uses a cached version of the stream
        // (ReadBufferDataHandle) instead of the constuctor's argument
        return fileLock;
    }

    /**
     * Returns an {@link Optional} containing the path to the TIFF file if this object was created by a constructor
     * with a {@link Path} argument.
     * If the path is unknown (for example, if the object was created from a {@link DataHandle}),
     * it returns {@link Optional#empty()}.
     *
     * @return the path to the TIFF file, or an empty {@code Optional} if there is no associated file path.
     */
    public final Optional<Path> filePath() {
        return Optional.ofNullable(filePath);
    }

    /**
     * Returns the input/output stream used for operations with this TIFF file.
     *
     * @return the {@link DataHandle} for this TIFF file; never {@code null}.
     * @see #fileLock()
     */
    public final DataHandle<?> stream() {
        synchronized (fileLock) {
            // - we prefer not to return this stream in the middle of I/O operations
            return stream;
        }
    }

    /**
     * Returns the original stream that was passed when creating this object.
     *
     * <p>Note that {@link TiffReader} automatically wraps the stream
     * with {@link ReadBufferDataHandle} if the stream specified in the constructor
     * is not an instance of that class. This method returns the original (unwrapped) stream.
     * Unlike {@link ReadBufferDataHandle} (which is a read-only stream), the result of this method
     * can be passed to a {@link TiffWriter} constructor.</p>
     *
     * <p>In the {@link TiffWriter} class, this method is equivalent to {@link #stream()}.</p>
     *
     * @return the original {@link DataHandle} for this TIFF file without buffering wrappers; never {@code null}.
     * @see #fileLock()
     */
    public DataHandle<?> originalStream() {
        return stream();
    }

    public final String streamName() {
        return streamName("");
    }

    public final String streamName(String prefix) {
        Objects.requireNonNull(prefix, "Null prefix");
        if (filePath != null) {
            return prefix + filePath;
        }
        return streamName(stream, prefix);
    }

    public static String streamName(DataHandle<?> stream) {
        return streamName(stream, "");
    }

    public static String streamName(DataHandle<?> stream, String prefix) {
        Objects.requireNonNull(stream, "Null stream");
        Location location = stream.get();
        if (location == null) {
            return "";
        }
        URI uri = location.getURI();
        if (uri == null) {
            return "";
        }
        return prefix + uri;
    }

    /**
     * Returns the length of the file, in bytes.
     * This method never throws an exception; in case of any error (for example, "access is denied"),
     * it returns 0.
     *
     * @return the length of this file.
     */
    public final long fileLength() {
        try {
            return stream.length();
        } catch (IOException e) {
            // - very improbable
            // (example: this is a subdirectory, and length() throws "FileNotFoundExceptoin ... (Access is denied)";
            // it is much better just to return something
            return 0;
        }
    }

    /**
     * Creates a new "companion" TIFF writer for rewriting or appending to this TIFF,
     * which shares the same file {@link #stream() stream} with this reader or writer.
     *
     * <p>This method is equivalent to:</p>
     * <pre>
     * {@link TiffWriter} result = new {@link TiffWriter#TiffWriter(DataHandle)
     * TiffWriter}({@link #originalStream()});
     * result.{@link TiffWriter#openExisting() openExisting()};
     * </pre>
     *
     * <p><b>Do not close</b> the returned writer independently: the shared stream will be closed
     * automatically when closing this reader or writer.</p>
     *
     * @return a new TIFF writer.
     * @throws IOException if an I/O error occurs.
     * @see #newSharedReader()
     */
    public final TiffWriter newSharedWriter() throws IOException {
        final TiffWriter result = new TiffWriter(originalStream(), filePath);
        result.openExisting();
        return result;
    }

    /**
     * Creates a new "companion" TIFF reader for reading this TIFF,
     * which shares the same file {@link #stream() stream} with this reader or writer.
     *
     * <p>This method is almost equivalent to:
     *
     * <pre>new {@link TiffReader#TiffReader(DataHandle, TiffReader.OpenMode, boolean)
     *       TiffReader}({@link #originalStream()}, {@link TiffReader.OpenMode#NO_CHECKS}, false)</pre>
     *
     * <p>The only difference is that this method catches and suppresses {@link IOException}:
     * such exceptions are impossible when using {@link TiffReader.OpenMode#NO_CHECKS} mode.</p>
     *
     * <p><b>Do not close</b> the returned reader independently: the shared stream will be closed
     * automatically when closing this reader or writer.</p>
     *
     * <p>Note that the cache in the returned reader is enabled by default.
     * Therefore, you may use the {@link TiffWriteMap} served by this companion reader
     * for usual access to the TIFF image, for example, in an image viewer or editor.
     * If the only goal of the {@link TiffWriteMap} is to modify the TIFF and then commit changes
     * via {@link TiffWriteMap#flushCompletedTiles(Collection)} or {@link TiffWriteMap#completeWriting()},
     * you may disable caching by explicitly calling {@link TiffReader#disableCaching()} on the reader returned by
     * this method. Note that single-use operations (read, write, and flush) generally
     * do not spend memory even with caching enabled.</p>
     *
     * @return a new TIFF reader.
     * @see #newSharedWriter()
     * @see TiffWriter#companionReader()
     */
    public final TiffReader newSharedReader() {
        try {
            return new TiffReader(
                    originalStream(), filePath, TiffReader.OpenMode.NO_CHECKS, false);
        } catch (IOException e) {
            throw new AssertionError("IOException is impossible in NO_CHECKS mode", e);
        }
    }

    public final int sizeOfTiffHeader() {
        return TiffIFD.sizeOfFileHeader(bigTiff);
    }

    /**
     * Returns position in the file of the first IFD offset:
     * 8 for {@link TiffReader#isBigTiff() BigTIFF}, 4 for a usual TIFF.
     *
     * @return position in the file of the first IFD offset.
     */
    public final long offsetOfFirstIFDOffset() {
        return offsetOfFirstIFDOffset(this.bigTiff);
    }

    public static long offsetOfFirstIFDOffset(boolean bigTiff) {
        return bigTiff ? 8L : 4L;
    }

    /**
     * Returns the byte length of fields containing file offsets, in particular, IFD offsets.
     *
     * @return 8 for BigTIFF (long), 4 for classic TIFF (unsigned int).
     */
    public final int sizeOfOffset() {
        return bigTiff ? 8 : 4;
    }

    /**
     * Returns the byte length of the field containing the number of IFD entries.
     * This corresponds to the size of the value read by {@link #readNumberOfIFDEntriesAt(long)}.
     *
     * @return 8 for BigTIFF (long), 2 for classic TIFF (unsigned short).
     */
    public final int sizeOfNumberOfIFDEntries() {
        return bigTiff ? 8 : 2;
    }

    /**
     * Returns the byte length of a single IFD entry based on the TIFF format version,
     * depending on {@link #isBigTiff()} flag.
     *
     * @return 12 for classic TIFF, 20 for BigTIFF.
     */
    public final int sizeOfIFDEntry() {
        return TiffIFD.Entry.sizeOfEntry(bigTiff);
    }

    /**
     * Returns the total size in bytes of the IFD entries table (excluding the entry count
     * and the next IFD offset fields).
     *
     * @param numberOfEntries the number of entries in the IFD.
     * @return the total size of all IFD entries in bytes.
     * @throws IllegalArgumentException if {@code numberOfEntries} is invalid or too large.
     */
    public final int sizeOfAllIFDEntries(int numberOfEntries) {
        if (numberOfEntries < 0) {
            throw new IllegalArgumentException("Negative number of IFD entries: " + numberOfEntries);
        }
        if (numberOfEntries > TiffIFD.MAX_NUMBER_OF_IFD_ENTRIES) {
            throw new IllegalArgumentException("Too many IFD entries: " + numberOfEntries);
        }
        return numberOfEntries * sizeOfIFDEntry();
    }

    public final void checkFileOpen() {
        if (!fileOpen) {
            throw new IllegalStateException(getClass().getSimpleName() + " is " +
                    (this instanceof TiffWriter ? "not yet created / opened for writing, or it is " : "") +
                    "already closed: " + streamName());
        }
    }

    public final TiffReader newReader(TiffReader.OpenMode openMode) throws IOException {
        return new TiffReader(stream, openMode, false);
    }

    /**
     * Returns the number of regular IFDs currently present in this TIFF file.
     *
     * <p>Note: this method counts only the {@link TiffIFD#isMainIFD() main} (regular) TIFF images.
     * The child sub-IFDs (if any), as well as associated EXIF, GPS, and other possible linked IFDs
     * are not included in this number.</p>
     *
     * <p>This method is equivalent to:</p>
     * <pre>{@link #isValidTiff()} ? {@link TiffWriter#linkage()
     * linkage()}.{@link TiffIFD.Linkage#numberOfMainIFDs()
     * numberOfMainIFDs()} : 0</pre>
     *
     * <p>This information is cached and quickly available on subsequent calls.
     * If this information is not yet available, any necessary reading of the file is synchronized
     * on {@link #fileLock()}.</p>
     *
     * <p>Additional check of {@link #isValidTiff()} allows to silently return 0 when {@link TiffReader}
     * was opened in the {@link TiffReader.OpenMode#NO_CHECKS} mode and the file is is not a valid TIFF.
     * It matches to the behavior of {@link TiffReader#allIFDs()} and {@link TiffReader#mainIFDs()}
     * in such a situation (when these methods silently return empty lists).</p>
     *
     * @return the number of existing main images (IFDs).
     * @throws IOException if an I/O error occurs while reading necessary information.
     */
    public final int numberOfMainImages() throws IOException {
        synchronized (fileLock) {
            return validTiff ? linkage("Reading number of main images").numberOfMainIFDs() : 0;
        }
    }

    /**
     * Reads the IFD with the given index from the file or throws an exception if the index is out of bounds.
     *
     * <p>This method is almost equivalent to:</p>
     * <pre>
     *     {@link #readIFDAt(long) readIFDAt}({@link #readMainIFDOffset(int)
     *     readMainIFDOffset}(mainIFDIndex))</pre>
     *
     * <p>The only difference is that the returned IFD is automatically corrected by the
     * {@link TiffIFD.Linkage#correctInvalidLinkage(TiffIFD)} method in the very rare case
     * when this IFD is the last in the TIFF and its {@link TiffIFD#getNextIFDOffset() next IFD offset}
     * field refers to one of the previous IFDs (an infinite loop).</p>
     *
     * <p>Note: this method works only with {@link TiffIFD#isMainIFD() regular IFDs} (not sub-IFDs).
     * Therefore, this index must be in the range {@code 0..}{@link TiffReader#numberOfMainImages()}{@code -1}.</p>
     *
     * @param mainIFDIndex index of the regular IFD (0, 1, ...).
     * @return the selected IFD.
     * @throws IllegalArgumentException if the index is negative.
     * @throws TiffException            if the index is too high.
     * @throws IOException              if an I/O error occurs.
     */
    public final TiffIFD readMainIFD(int mainIFDIndex) throws IOException {
        final LinkageHolder holder = new LinkageHolder();
        long ifdOffset = readMainIFDOffset(mainIFDIndex, holder);
        assert ifdOffset >= 0;  
        assert holder.linkage != null : "linkage is not filled";
        // - note: we do not call setIndexInList(mainIFDIndex),
        // because this index will DIFFER from the index inside the allIFDs() list
        final TiffIFD result = readIFDAt(ifdOffset);
        holder.linkage.correctInvalidLinkage(result);
        return result;
    }

    /**
     * Reads the IFD stored at the given offset.
     * Equivalent to
     * <pre>{@link #readIFDAt(long, ReadIFDMode) readIFDAt}(ifdOffset, {@link ReadIFDMode#NORMAL})</pre>
     *
     * @param ifdOffset the start offset of the IFD inside the TIFF file.
     * @return the IFD; never {@code null}.
     * @throws IllegalArgumentException if the offset is negative or too low (less than {@link #sizeOfTiffHeader()}).
     * @throws IOException              if an I/O error occurs.
     */
    public final TiffIFD readIFDAt(long ifdOffset) throws IOException {
        return readIFDAt(ifdOffset, ReadIFDMode.NORMAL);
    }

    /**
     * Reads the IFD stored at the given offset.
     *
     * <p>The {@code readIFDMode} specifies which parts of the IFD should be parsed.
     * Typically, {@link ReadIFDMode#NORMAL} is used, which reads all available information.
     * This can be useful for better performance or robustness for "wild" TIFF while reading sub-IFDs.</p>
     *
     * @param ifdOffset   the start offset of the IFD within the TIFF file.
     * @param readIFDMode defines what parts of the IFD to read.
     * @return the IFD; never {@code null}.
     * @throws IllegalArgumentException if the offset is negative or less than {@link #sizeOfTiffHeader()}.
     * @throws IOException              if an I/O error occurs.
     */
    public final TiffIFD readIFDAt(long ifdOffset, ReadIFDMode readIFDMode) throws IOException {
        Objects.requireNonNull(readIFDMode, "Null readIFDMode");
        if (ifdOffset < 0) {
            throw new IllegalArgumentException("Negative IFD file offset = " + ifdOffset);
        }
        if (ifdOffset < sizeOfTiffHeader()) {
            throw new IllegalArgumentException("Attempt to read IFD from too small start offset " + ifdOffset);
        }
        long t1 = debugTime();
        long timeEntries = 0;
        long timeArrays = 0;
        final TiffIFD ifd;
        final Map<Integer, Object> map = new LinkedHashMap<>();
        final LinkedHashMap<Integer, TiffIFD.Entry> detailedEntries = new LinkedHashMap<>();
        synchronized (fileLock) {
            final IFDCommonInformation info = prepareReadingIFD(ifdOffset, readIFDMode.isReadingNextIFDOffset());
            final DataHandle<?> ifdStream = getBytesHandle(info.ifdBytes(), info.littleEndian());
            if (readIFDMode.isReadingIFDEntries()) {
                for (int i = 0, disp = 0; i < info.n(); i++, disp += info.sizeOfEntry()) {
                    long tEntry1 = debugTime();
                    final TiffIFD.Entry entry = readIFDEntry(
                            ifdStream,
                            disp,
                            info.offsetOfFirstEntry(),
                            info.fileLength());
                    final int tag = entry.tag();
                    long tEntry2 = debugTime();
                    timeEntries += tEntry2 - tEntry1;

                    final Object value = readIFDValueAtEntryOffset(
                            ifdStream,
                            stream,
                            entry.isDataEmbeddedInEntry(),
                            info.offsetOfFirstEntry(),
                            entry);
                    long tEntry3 = debugTime();
                    timeArrays += tEntry3 - tEntry2;
//            System.err.printf("%d values from %d: %.6f ms%n", valueCount, valueOffset, (tEntry3 - tEntry2) * 1e-6);

                    if (value != null && !map.containsKey(tag)) {
                        // - null value should not occur in the current version;
                        // if this tag is present twice (strange mistake in a TIFF file),
                        // we do not throw exception and just use the 1st entry
                        map.put(tag, value);
                        detailedEntries.put(tag, entry);
                    }
                }
            }
            stream.seek(info.offsetOfNextIFDOffset());
            // - this "in.seek" provides maximal compatibility with old code (which did not read next IFD offset)
            // and also with the behavior of this method when skipReadingNextOffset==true

            ifd = new TiffIFD(map, detailedEntries);
            ifd.setLoadedFromFile(true);
            ifd.setLittleEndian(info.littleEndian());
            ifd.setBigTiff(bigTiff);
            ifd.setFileOffsetOfIFD(ifdOffset);
            if (readIFDMode.isReadingNextIFDOffset()) {
                assert info.nextIFDOffset() != null;
                final long nextOffset = info.nextIFDOffset();
                ifd.setFileOffsetOfNextIFDOffset(info.offsetOfNextIFDOffset());
                ifd.setNextIFDOffset(nextOffset);
            }
        }

        if (BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t2 = debugTime();
            LOG.log(System.Logger.Level.TRACE, String.format(Locale.ROOT,
                    "%s read IFD at offset %d: %.3f ms, including %.6f entries + %.6f arrays",
                    getClass().getSimpleName(), ifdOffset,
                    (t2 - t1) * 1e-6, timeEntries * 1e-6, timeArrays * 1e-6));
        }
        return ifd;
    }

    /**
     * Seeks the {@link #stream()} to the specified offset and reads the number of IFD entries.
     * The value is validated using {@link TiffIFD#checkNumberOfEntries(long, boolean)}.
     *
     * <p>After a successful call, the stream position is set immediately after
     * the count field (2 bytes for classic TIFF, 8 bytes for BigTIFF).</p>
     *
     * @param ifdOffset the absolute offset of the IFD structure inside the TIFF file.
     * @return the validated number of IFD entries.
     * @throws TiffException if the number of entries is invalid or exceeds limits.
     * @throws IOException   if an I/O error occurs.
     */
    public final int readNumberOfIFDEntriesAt(long ifdOffset) throws IOException {
        synchronized (fileLock) {
            if (ifdOffset < 0) {
                throw new IllegalArgumentException("Negative IFD file offset = " + ifdOffset);
            }
            if (ifdOffset < sizeOfTiffHeader()) {
                throw new IllegalArgumentException("Attempt to read IFD from too small start offset " + ifdOffset);
            }
            if (ifdOffset >= stream.length()) {
                throw new TiffException("TIFF IFD offset " + ifdOffset + " is outside the file");
            }
            stream.seek(ifdOffset);
            final long numberOfEntries = bigTiff ? stream.readLong() : stream.readUnsignedShort();
            return TiffIFD.checkNumberOfEntries(numberOfEntries, bigTiff);
        }
    }

    /**
     * Reads the file offset of the regular IFD with the given index,
     * or throws an exception if the index is out of bounds.
     *
     * <p><b>Important:</b> unlike {@link #readMainIFDOffsets()}, this method reads <b>only</b>
     * the first {@code mainIFDIndex+1} IFD offsets from the TIFF file. For example, if {@code mainIFDIndex=1},
     * it reads the offset <b>o1</b> of IFD #0 at file position <b>p1</b>&nbsp;=&nbsp;4,
     * and the "next IFD offset" field <b>o2</b> inside this IFD at file position:<br>
     * &nbsp;&nbsp;&nbsp;&nbsp;<b>p2</b> = <b>o1</b> + 2 + <i>n1</i> * 12<br>
     * (where <i>n1</i> is the number of entries in the first IFD #0; assuming this is
     * a classic TIFF, not a BigTIFF).
     * The result of this method will be <b>o2</b>.
     * The total number of offsets read is 2: <b>o1</b> and <b>o2</b>.</p>
     *
     * <p>In comparison, if the TIFF contains only these 2 IFDs,
     * and we call {@link #readMainIFDOffsets()},
     * we <b>will also</b> read the IFD offset <b>o3</b> at file position:<br>
     * &nbsp;&nbsp;&nbsp;&nbsp;<b>p3</b> = <b>o2</b> + 2 + <i>n2</i> * 12<br>
     * (where <i>n2</i> is the number of entries in the second IFD #1).
     * Although this offset <b>o3</b> is zero (since IFD #1 is the last one, and a zero offset indicates
     * the end of the chain), it will still be read.
     * The total number of offsets read is 3: <b>o1</b>, <b>o2</b>, and <b>o3</b>.</p>
     *
     * <p>Also note that calling this method with a {@code mainIFDIndex} equal to the total number of IFDs will throw
     * an exception instead of returning this trailing zero offset (as you might think).</p>
     *
     * <p>This method works only with {@link TiffIFD#isMainIFD() regular IFDs} (main, not sub-IFDs).
     * Therefore, the index must be in the range <code>0..{@link TiffReader#numberOfMainImages()}-1</code>.</p>
     *
     * @param mainIFDIndex index of the regular IFD (0, 1, ...).
     * @return the offset of this IFD in the file.
     * @throws IllegalArgumentException if the index is negative.
     * @throws TiffException            if the index is too high, the TIFF file is empty,
     *                                  or if a corrupted structure or infinite loop is detected.
     * @throws IOException              if an I/O error occurs.
     */
    public final long readMainIFDOffset(int mainIFDIndex) throws IOException {
        return readMainIFDOffset(mainIFDIndex, null);
    }

    /**
     * Analog of {@link #readMainIFDOffset(int)} returning {@code OptionalLong.empty()}
     * instead of throwing an exception when the IFD index is too high or the file contains no images.
     *
     * @param mainIFDIndex index of regular IFD (0, 1, ...).
     * @return offset of this IFD in the file, wrapped in {@link OptionalLong},
     * or an empty value if the index is too high or the file contains no images.
     * @throws IllegalArgumentException if the index is negative.
     * @throws TiffException            if a corrupted structure or infinite loop is detected.
     * @throws IOException              if an I/O error occurs.
     */
    public final OptionalLong readMainIFDOffsetIfPresent(int mainIFDIndex) throws IOException {
        return readMainIFDOffsetIfPresent(true, mainIFDIndex, null);
    }

    /**
     * Equivalent to {@link #readMainIFDOffsets(boolean) readMainIFDOffsets(false)}.
     *
     * <p>Note: an empty TIFF file without IFDs is not allowed.
     * You may use the {@link #readMainIFDOffsets(boolean)} method with the {@code true} argument
     * to process such TIFF files.</p>
     *
     * @return an array of all main IFD offsets.
     * @throws TiffException if the TIFF file is empty, or if a corrupted structure or infinite loop is detected.
     * @throws IOException   if an I/O error occurs.
     */
    public final long[] readMainIFDOffsets() throws IOException {
        return readMainIFDOffsets(false);
    }

    /**
     * Reads the offsets of all main IFDs in the file (excluding child sub-IFDs).
     *
     * <p>This method is equivalent to the following call:</p>
     * <pre>{@link #readLinkage(boolean) readLinkage(allowNoIFDs)}.{@link TiffIFD.Linkage#mainIFDOffsetsArray()
     * mainIFDOffsetsArray()}</pre>
     *
     * @param allowNoIFDs {@code true} to allow an empty TIFF file without throwing an exception.
     * @return an array of all main IFD offsets.
     * @throws TiffException if the TIFF file is empty and {@code allowNoIFDs} is {@code false},
     *                       or if a corrupted structure or infinite loop is detected.
     * @throws IOException   if an I/O error occurs.
     */
    public final long[] readMainIFDOffsets(boolean allowNoIFDs) throws IOException {
        return readLinkage(allowNoIFDs).mainIFDOffsetsArray();
    }

    /**
     * Creates a new {@link TiffIFD.Linkage} instance initialized for an empty TIFF file.
     *
     * <p>This is equivalent to:</p>
     * <pre>new {@link TiffIFD.Linkage#Linkage(boolean) TiffIFD.Linkage}({@link #isBigTiff()})</pre>
     *
     * @return a new linkage instance for an empty TIFF file.
     */
    public final TiffIFD.Linkage newEmptyLinkage() {
        return new TiffIFD.Linkage(isBigTiff());
    }

    /**
     * Equivalent to <code>{@link #readLinkage(boolean) readLinkage(allowNoIFDs)}</code>,
     * where {@code allowNoIFDs} is chosen automatically: it is {@code true} in {@link TiffWriter}
     * and {@code false} in {@link TiffReader}.
     *
     * <p>Note that {@link TiffReader} cannot do anything useful when the TIFF does not contain
     * any IFDs (invalid TIFF). So, {@code allowNoIFDs=false} is usually the right choice: it will lead
     * to an {@link UncompletedTiffException} for such an invalid TIFF.</p>
     *
     * <p>In contrast, {@link TiffWriter} usually builds a new TIFF, and this method can be useful
     * at the stage when the first image is not completely written yet. So, {@code allowNoIFDs=true} is a better
     * choice.</p>
     *
     * @return the linkage information.
     * @throws TiffException if a corrupted structure is detected.
     * @throws IOException   if an I/O error occurs.
     */
    public final TiffIFD.Linkage readLinkage() throws IOException {
        return readLinkage(this instanceof TiffWriter);
    }

    /**
     * Reads the linkage information: the offsets of all main IFDs in the file (excluding child sub-IFDs)
     * and the offset of the chain terminator (the zero value stored within the last IFD structure in the file).
     * For a non-empty valid TIFF file, the size of the {@link TiffIFD.Linkage#mainIFDOffsetPairs()} set
     * in the result is equal to {@link TiffReader#numberOfMainImages()}.
     *
     * <p>If the {@code allowNoIFDs} argument is {@code false} and the file contains no IFDs
     * (i.e., {@link TiffIFD#IFD_CHAIN_TERMINATOR} is written at {@link #offsetOfFirstIFDOffset()}),
     * this method throws an {@link UncompletedTiffException}.</p>
     *
     * <p>If the argument is {@code true}, this method handles this state silently.
     * This can be useful to process an intermediate state during TIFF creation.</p>
     *
     * <p>Note that this method <b>does not</b> update any internal fields stored by this class.
     * The only state that is changed by this method
     * is the current file position in the {@link #stream()}.</p>
     *
     * <p>This method is used inside the {@link #linkage()} method. In most situations, you should
     * prefer the {@link #linkage()} method &mdash; it caches the loaded linkage in an internal field
     * of this object and does not change the current file position.</p>
     *
     * @param allowNoIFDs {@code true} to allow an empty TIFF file without throwing an exception.
     * @return the linkage information read from the file.
     * @throws TiffException if a corrupted structure is detected.
     * @throws IOException   if an I/O error occurs.
     * @see #readMainIFDOffsets(boolean)
     * @see #readLinkage()
     */
    public final TiffIFD.Linkage readLinkage(boolean allowNoIFDs) throws IOException {
        return readLinkage(allowNoIFDs, Long.MAX_VALUE);
    }

    /**
     * Clears the reference to the IFD linkage information stored inside this object
     * and returned by the {@link #linkage()} method.
     * Ensures that the next call to {@link #linkage()} will reload this information from the file.
     *
     * <p>Usually, you do not need to use this method directly: it is called automatically by {@link TiffWriter}
     * whenever there is a risk that this linkage information may become incorrect,
     * as well as by {@link TiffReader#clearCache()}.
     * <b>The exception</b>: you should use this method if you call the low-level
     * {@link TiffWriter#writeOffsetAt(long, long)} method or perform direct modifications in the file
     * via the {@link #stream()} object or other external means.</p>
     *
     * <p>Performance note: sometimes this library <b>skips</b> calling this method
     * if we are certain the IFD linkage structure remains correct,
     * even if documentation of other methods states that &ldquo;they invalidate linkage&rdquo;.
     * We are free to add such cases in future versions as part of our ongoing library optimization.
     * In any case, the only way to detect such changes in behavior is to use the
     * {@link #linkageIfPresent()} method: it is possible that it will return a non-empty result
     * more often than the documentation implies.</p>
     *
     * @see TiffWriter#writeIFD(TiffIFD, TiffIFD.Linkage.UpdateMode)
     * @see TiffWriter#rewriteIFDStrictlyInPlace(TiffIFD, IntPredicate, TiffIFD.Linkage.UpdateMode)
     */
    public final void invalidateLinkage() {
        invalidateLinkage(true, null);
    }

    /**
     * Returns the linkage information for the TIFF file.
     *
     * <p>This method reads the information via {@link #readLinkage()} on the first call,
     * stores the reference inside this object, and returns it for subsequent calls.
     * However, the stored reference is cleared to {@code null} by the {@link #invalidateLinkage()} method
     * (triggering a reload on the next call).</p>
     *
     * <p>Note: this method does not modify the environment, including the current file position in the
     * {@link #stream()}. (It saves the current position before calling {@link #readLinkage()}
     * and restores it after the call, ensuring all exceptions are handled correctly.)</p>
     *
     * <p>Usually, you do not need to use this method manually: it is called automatically by
     * {@link TiffReader} or {@link TiffWriter}
     * whenever it requires up-to-date linkage information. The only situation when you might use it
     * is if you want to inspect this information for your own purposes, for example, for logging.</p>
     *
     * <p>Also note: the returned linkage is a live object owned by this {@link TiffReader} or {@link TiffWriter}.
     * It is not immutable: {@link TiffWriter} modifies it while adding new images to the TIFF file.
     * Of course, all modifications are synchronized with the help of the {@link #fileLock()} object.
     * So, if you decide to use it for reading some information, you should synchronize
     * access to it using the same {@link #fileLock()} object.</p>
     *
     * @return the linkage information.
     * @throws IOException if an I/O error occurs while reading the linkage.
     * @see #readLinkage()
     * @see #readLinkage(boolean)
     * @see #invalidateLinkage()
     */
    public final TiffIFD.Linkage linkage() throws IOException {
        return linkage("Reloading linkage");
    }

    /**
     * Returns the linkage information (see {@link #linkage()}) if it has already been loaded,
     * or {@link Optional#empty()} otherwise.
     *
     * <p>Unlike {@link #linkage()}, this method does not attempt to reload the linkage
     * information from the file if the stored reference is {@code null}.</p>
     *
     * @return the linkage information, wrapped in {@link Optional},
     * or {@link Optional#empty()} if it has not been read or if it was invalidated.
     */
    public final Optional<TiffIFD.Linkage> linkageIfPresent() {
        return Optional.ofNullable(linkage);
    }

    public final CodecReport lastCodecReport() {
        return lastCodecReport;
    }

    @Override
    public void close() throws IOException {
        synchronized (fileLock) {
            stream.close();
            fileOpen = false;
            linkage = null;
        }
    }

    public static Object newSCIFIOContext() {
        return SCIFIOBridge.getDefaultScifioContext();
    }

    /**
     * Returns a new stream (file handle) associated with the specified file.
     * Equivalent to:
     * <pre>
     * new {@link FileHandle#FileHandle(FileLocation)
     * FileHandle}(new {@link org.scijava.io.location.FileLocation#FileLocation(java.io.File)
     * FileLocation}(file.toFile()))
     * </pre>
     *
     * @param file a file.
     * @return a {@link FileHandle} instance associated with this file.
     * @throws NullPointerException if the argument is {@code null}.
     */
    public static FileHandle getFileHandle(Path file) {
        Objects.requireNonNull(file, "Null file");
        FileHandle fileHandle = new FileHandle(new FileLocation(file.toFile()));
        fileHandle.setLittleEndian(false);
        // - in the current implementation it is an extra operator: BigEndian is defaulted in scijava;
        // but we want to be sure that this behavior will be the same in all future versions
        return fileHandle;
    }

    @SuppressWarnings("UnusedReturnValue")
    public static long copyFile(DataHandle<?> inputStream, DataHandle<?> outputStream) throws IOException {
        Objects.requireNonNull(inputStream, "Null input stream");
        Objects.requireNonNull(outputStream, "Null output stream");
        inputStream.seek(0);
        outputStream.seek(0);
        final long inputLength = inputStream.length();
        final long result = copyData(inputStream, outputStream, false, inputLength);
        outputStream.setLength(outputStream.offset());
        if (result != inputLength) {
            throw new EOFException("Copied only " + result + " bytes from all " + inputLength + " bytes");
            // - should not occur in the normal situation
        }
        return result;
    }

    static long copyData(DataHandle<?> in, DataHandle<?> out) throws IOException {
        return copyData(in, out, true, in.length());
    }

    // A simplified clone of the function DataHandles.copy without the problem with invalid generic types
    static long copyData(DataHandle<?> in, DataHandle<?> out, boolean fromZeroOffset, long length)
            throws IOException {
        if (length < 0) {
            throw new IllegalArgumentException("Negative length: " + length);
        }
        if (fromZeroOffset) {
            in.seek(0);
        }
        final byte[] buffer = new byte[256 * 1024];
        long result = 0;
        while (result < length) {
            final int len = (int) Math.min(length - result, buffer.length);
            int actuallyRead = in.read(buffer, 0, len);
            if (actuallyRead <= 0) {
                break; // EOF
            }
            out.write(buffer, 0, actuallyRead);
            result += actuallyRead;
        }
        return result;
    }

    void setLastCodecReport(CodecReport lastCodecReport) {
        this.lastCodecReport = lastCodecReport;
    }

    String spacedStreamName() {
        return streamName(" ");
    }

    Object scifio() {
        Object scifio = this.scifio;
        if (scifio == null) {
            this.scifio = scifio = SCIFIOBridge.createScifioFromContext(context);
        }
        return scifio;
    }

    Object requireScifio(TiffIFD ifd) throws UnsupportedTiffFormatException {
        Object scifio = scifio();
        if (scifio == null) {
            // - in other words, this.context is not set
            throw new UnsupportedTiffFormatException("Reading with TIFF compression " +
                    TagCompression.toPrettyString(ifd.optCompressionCode(TiffIFD.COMPRESSION_NONE)) +
                    " is not supported without external codecs");
        }
        return scifio;
    }

    void analyzeFileHeader() throws IOException {
        this.tiff = false;
        this.validTiff = false;
        this.bigTiff = false;
        // TIFF file header:
        //  16 bits - TIFF byte-order identifier:
        //                  4949h ("II", little-endian, Intel format)
        //                  4D4Dh ("MM", big-endian, Motorola format)
        //                  any other means non-TIFF
        //  16 bits - TIFF version (this and following information is written in little-endian or big-endian format):
        //                  002Ah usual TIFF
        //                  002Bh BigTIFF
        //                  any other means non-TIFF
        //  TIFF:
        //      32 bits - the offset of the first IFD
        //  BigTIFF:
        //      16 bits - always 8 (means the number of bytes in BigTIFF offsets)
        //      16 bits - always 0
        //      64 bits - the offset of the first IFD
        final long savedOffset = stream.offset();
        try {
            stream.seek(0);
            final long length = stream.length();
            if (length < 8) {
                throw new TiffException("The file" + spacedStreamName() + " is not TIFF (only " + length +
                        " bytes, but a valid TIFF cannot be shorter than 8 bytes)");
            }
            final int endianOne = stream.readByte() & 0xff;
            final int endianTwo = stream.readByte() & 0xff;
            final boolean littleEndian = endianOne == FILE_PREFIX_LITTLE_ENDIAN &&
                    endianTwo == FILE_PREFIX_LITTLE_ENDIAN;
            final boolean bigEndian = endianOne == FILE_PREFIX_BIG_ENDIAN &&
                    endianTwo == FILE_PREFIX_BIG_ENDIAN;
            if (!littleEndian && !bigEndian) {
                throw new TiffException("The file" + spacedStreamName() + " is not TIFF");
            }
            stream.setLittleEndian(littleEndian);
            final short magic = stream.readShort();
            // - not readByte()! the result depends on the previous in.setLittleEndian()
            final boolean bigTiff = magic == FILE_BIG_TIFF_MAGIC_NUMBER;
            if (magic != FILE_USUAL_MAGIC_NUMBER && magic != FILE_BIG_TIFF_MAGIC_NUMBER) {
                throw new TiffException("The file" + spacedStreamName() + " is not TIFF");
            }
            this.tiff = true;
            this.bigTiff = bigTiff;
            // - this is definitely TIFF, but, probably, non-valid; in the latter case we will throw exceptions
            if (length < MINIMAL_ALLOWED_TIFF_FILE_LENGTH) {
                // - sometimes we can meet 8-byte "TIFF files" (or 16-byte "BigTIFF") that contain only header
                // and no actual data: possible results of debugging writing algorithm or bugs while writing
                // (forgotten completeWriting() call).
                throw new TiffException("Too short TIFF file" + spacedStreamName() + ": only " + length +
                        " bytes (a valid TIFF must contain at least " + MINIMAL_ALLOWED_TIFF_FILE_LENGTH +
                        " bytes); probably the TIFF writing process was not completed normally");
            }
            this.fileOpen = true;
            checkFirstOffset();
            // - Additional check of zero or extremely large offset and throws an exception.
            // In TiffReader, this exception is caught in NO_CHECKS mode,
            // isValidTiff() will be false, and allIFDs() will return an empty result.
            this.validTiff = true;

            // Note: in old versions, before 13.Nov.2025, the analogous code was executed here:
            //
            // readFirstOffsetFromCurrentPosition(false, bigTiff);
            // - an additional check for a zero offset, updating fileOffsetOfLastIFDOffset
            //
            // As a result, an exception was thrown for an empty TIFF file (no IFDs), and the
            // validTiff flag was set to false.
            // After that, the readMainIFDOffsets() and allIFDs() methods did not throw exceptions
            // and returned empty results.
            // Now readMainIFDOffsets() does not check validTiff: its behavior depends on the boolean argument.
        } finally {
            stream.seek(savedOffset);
            // - for maximal compatibility: in old versions, the constructor of this class
            // guaranteed that file position in the input stream will not change
            // (that is illogical, because "little-endian" mode was still changed)
        }
    }

    IFDCommonInformation prepareReadingIFD(long ifdOffset, boolean includeNextOffset) throws IOException {
        final boolean littleEndian = stream.isLittleEndian();
        final long tiffFileLength = stream.length();
        final int n = readNumberOfIFDEntriesAt(ifdOffset);
        final long offsetOfFirstEntry = ifdOffset + sizeOfNumberOfIFDEntries();

        final int sizeOfEntry = sizeOfIFDEntry();
        final int sizeOfAllEntries = sizeOfEntry * n;
        final int sizeOfNextOffset = includeNextOffset ? sizeOfOffset() : 0;
        if (offsetOfFirstEntry > tiffFileLength - (sizeOfAllEntries + sizeOfNextOffset)) {
            throw new TiffException("%d IFD entries at the offset %d exceeds the file length %d%s".formatted(
                    n, offsetOfFirstEntry, tiffFileLength,
                    includeNextOffset ? " (including " + sizeOfNextOffset + "-byte next-IFD-offset field)" : ""));
        }
        if (stream.offset() != offsetOfFirstEntry) {
            throw new ConcurrentModificationException("Strange stream offset " +
                    stream.offset() + " != " + offsetOfFirstEntry +
                    ", probably due to operations in a parallel thread");
        }
        final byte[] ifdBytes = new byte[sizeOfAllEntries + sizeOfNextOffset];
        stream.readFully(ifdBytes);
        final Long nextIFDOffset = includeNextOffset ?
                JArrays.getBytes8(
                        ifdBytes, sizeOfAllEntries, sizeOfNextOffset,
                        littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN) :
                null;
        final long fileOffsetOfNextIFDOffset = offsetOfFirstEntry + sizeOfAllEntries;
        return new IFDCommonInformation(
                littleEndian,
                n,
                sizeOfEntry,
                sizeOfAllEntries,
                ifdBytes,
                offsetOfFirstEntry,
                fileOffsetOfNextIFDOffset,
                nextIFDOffset,
                tiffFileLength);
    }

    TiffIFD.Entry readIFDEntry(
            DataHandle<?> ifdStream,
            long entryOffset,
            long ifdStreamOffsetInTiffFile,
            long tiffFileLength) throws IOException {
        return readIFDEntry(
                ifdStream, bigTiff, entryOffset, ifdStreamOffsetInTiffFile, tiffFileLength, this::streamName);
    }

    static TiffIFD.Entry readIFDEntry(
            DataHandle<?> ifdStream,
            boolean bigTiff,
            long entryOffset,
            long ifdStreamOffsetInTiffFile,
            long tiffFileLength,
            Supplier<String> fileNameSupplier) throws IOException {
        Objects.requireNonNull(ifdStream, "Null ifdStream");
        ifdStream.seek(entryOffset);
        final int entryTag = ifdStream.readUnsignedShort();
        final int entryType = ifdStream.readUnsignedShort();

        final long valueCount = bigTiff ? ifdStream.readLong() : ((long) ifdStream.readInt()) & 0xFFFFFFFFL;
        if (valueCount < 0 || valueCount > Integer.MAX_VALUE) {
            throw new TiffException("Invalid TIFF: very large number of IFD values in array " +
                    (valueCount < 0 ? " >= 2^63" : valueCount + " >= 2^31") + " is not supported");
        }
        final TagType type = TagType.fromTypeCode(entryType).orElse(null);
        final int bytesPerElement = type == null ? 0 : type.bytesPerElement();
        // - will be zero for an unknown type; in this case we will set valueOffset=in.offset() below
        final long valueLength = valueCount * (long) bytesPerElement;
        final boolean embeddedInEntry = TiffIFD.Entry.isDataEmbeddedInEntry(valueLength, bigTiff);
        final long valueOffset;
        final long embedded;
        if (embeddedInEntry) {
            valueOffset = ifdStreamOffsetInTiffFile + ifdStream.offset();
            embedded = bigTiff ? ifdStream.readLong() : ((long) ifdStream.readInt()) & 0xFFFFFFFFL;
        } else {
            valueOffset = readOffset(ifdStream, bigTiff, ifdStreamOffsetInTiffFile, tiffFileLength, fileNameSupplier);
            embedded = valueOffset;
        }
        // - position in the file will be different depending on embeddedInEntry,
        // but it is not a problem: we will not use this position
        if (valueOffset < 0) {
            throw new TiffException("Invalid TIFF: negative offset of IFD values " + valueOffset);
        }
        if (valueOffset > tiffFileLength - valueLength) {
            throw new TiffException("Invalid TIFF: offset of IFD values " + valueOffset +
                    " + total lengths of values " + valueLength + " = " + valueCount + "*" + bytesPerElement +
                    " is outside the file length " + tiffFileLength);
        }
        final TiffIFD.Entry result = new TiffIFD.Entry(
                entryTag, type, entryType, (int) valueCount, valueOffset, embedded, bigTiff);
        assert result.valueLength() == valueLength;
        assert result.isDataEmbeddedInEntry() == embeddedInEntry;
        LOG.log(System.Logger.Level.TRACE, () -> String.format(
                "Reading IFD entry: %s - %s", result, Tags.prettyName(result.tag())));
        return result;
    }

    static Object readIFDValueAtEntryOffset(
            DataHandle<?> ifdStream,
            DataHandle<?> fileStream,
            boolean readEmbeddedData,
            long ifdStreamOffsetInTiffFile,
            TiffIFD.Entry entry) throws IOException {
        Objects.requireNonNull(ifdStream, "Null ifdStream");
        Objects.requireNonNull(fileStream, "Null fileStream");
        Objects.requireNonNull(entry, "Null entry");
        final TagType type = entry.type();
        final int count = entry.valueCount();
        final long offset = entry.valueOffset();
        assert ifdStream.isLittleEndian() == fileStream.isLittleEndian();
        final ByteOrder byteOrder = fileStream.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        final DataHandle<?> stream = readEmbeddedData ? ifdStream : fileStream;

        LOG.log(System.Logger.Level.TRACE, () ->
                "Reading entry " + entry.tag() + " from " + offset + "; type=" + type +
                        ", count=" + count + ", embedded=" + readEmbeddedData);

        stream.seek(readEmbeddedData ? offset - ifdStreamOffsetInTiffFile : offset);
        switch (type) {
            case BYTE -> {
                // 8-bit unsigned integer
                if (count == 1) {
                    return (short) stream.readUnsignedByte();
                }
                final byte[] bytes = new byte[count];
                stream.readFully(bytes);
                // bytes are unsigned, so use shorts
                final short[] shorts = new short[count];
                for (int j = 0; j < count; j++) {
                    shorts[j] = (short) (bytes[j] & 0xFF);
                }
                return shorts;
            }
            case ASCII -> {
                // 8-bit byte that contains a 7-bit ASCII code;
                // the last byte must be NUL (binary zero)
                final byte[] ascii = new byte[count];
                stream.readFully(ascii);

                String[] lines = TiffIFD.asciiToText(ascii);
                return lines.length != 1 ? lines : lines[0] == null ? "" : lines[0];

                /* // Deprecated solution:
                // count the number of null terminators
                int zeroCount = 0;
                for (int j = 0; j < count; j++) {
                    if (ascii[j] == 0 || j == count - 1) {
                        zeroCount++;
                    }
                }
                // convert character array to array of strings
                final String[] strings = zeroCount == 1 ? null : new String[zeroCount];
                String s = null;
                int c = 0, index = -1;
                for (int j = 0; j < count; j++) {
                    if (ascii[j] == 0) {
                        s = new String(ascii, index + 1, j - index - 1, StandardCharsets.UTF_8);
                        index = j;
                    } else if (j == count - 1) {
                        // handle non-null-terminated strings
                        s = new String(ascii, index + 1, j - index, StandardCharsets.UTF_8);
                    } else {
                        s = null;
                    }
                    if (strings != null && s != null) {
                        strings[c++] = s;
                    }
                }
                return strings != null ? strings : s != null ? s : "";
                */
            }
            case SHORT -> {
                // 16-bit (2-byte) unsigned integer
                if (count == 1) {
                    return stream.readUnsignedShort();
                }
                if (OPTIMIZE_READING_IFD_ARRAYS) {
                    final byte[] bytes = readBytes(stream, 2 * (long) count);
                    final short[] shorts = JArrays.bytesToShortArray(bytes, byteOrder);
                    final int[] result = new int[count];
                    for (int j = 0; j < count; j++) {
                        result[j] = shorts[j] & 0xFFFF;
                    }
                    return result;
                } else {
                    final int[] ints = new int[count];
                    for (int j = 0; j < count; j++) {
                        ints[j] = stream.readUnsignedShort();
                    }
                    return ints;
                }
            }
            case LONG -> {
                // 32-bit (4-byte) unsigned integer
                if (count == 1) {
                    return (long) stream.readInt() & 0xFFFFFFFFL;
                }
                if (OPTIMIZE_READING_IFD_ARRAYS) {
                    final byte[] bytes = readBytes(stream, 4 * (long) count);
                    final int[] ints = JArrays.bytesToIntArray(bytes, byteOrder);
                    return Arrays.stream(ints).mapToLong(anInt -> anInt & 0xFFFFFFFFL).toArray();
                    // note: TIFF_LONG is UNSIGNED long
                } else {
                    final long[] longs = new long[count];
                    for (int j = 0; j < count; j++) {
                        longs[j] = stream.readInt() & 0xFFFFFFFFL;
                    }
                    return longs;
                }
            }
            case RATIONAL, SRATIONAL -> {
                // Two LONGs or SLONGs: the first represents the numerator of a fraction; the second, the denominator
                if (count == 1) {
                    return TagValue.ofRational(type, stream.readInt(), stream.readInt());
                }
                final var rationals = (TagValue[]) Array.newInstance(type.javaType(), count);
                for (int j = 0; j < count; j++) {
                    rationals[j] = TagValue.ofRational(type, stream.readInt(), stream.readInt());
                }
                return rationals;
            }
            case SBYTE -> {
                if (count == 1) {
                    return TagValue.SByte.of(stream.readByte());
                }
                final TagValue.SByte[] values = new TagValue.SByte[count];
                for (int j = 0; j < count; j++) {
                    values[j] = TagValue.SByte.of(stream.readByte());
                }
                return values;
            }
            case UNDEFINED -> {
                // UNDEFINED: An 8-bit byte that may contain anything,
                // depending on the definition of the field
                if (count == 1) {
                    return stream.readByte();
                }
                final byte[] bytes = new byte[count];
                stream.readFully(bytes);
                return bytes;
            }
            case SSHORT -> {
                if (count == 1) {
                    return TagValue.SShort.of(stream.readShort());
                }
                final TagValue.SShort[] values = new TagValue.SShort[count];
                for (int j = 0; j < count; j++) {
                    values[j] = TagValue.SShort.of(stream.readShort());
                }
                return values;
            }
            case SLONG -> {
                if (count == 1) {
                    return TagValue.SLong.of(stream.readInt());
                }
                final TagValue.SLong[] values = new TagValue.SLong[count];
                for (int j = 0; j < count; j++) {
                    values[j] = TagValue.SLong.of(stream.readInt());
                }
                return values;
            }
            case FLOAT -> {
                // Single precision (4-byte) IEEE format
                if (count == 1) {
                    return stream.readFloat();
                }
                final float[] floats = new float[count];
                for (int j = 0; j < count; j++) {
                    floats[j] = stream.readFloat();
                }
                return floats;
            }
            case DOUBLE -> {
                // Double precision (8-byte) IEEE format
                if (count == 1) {
                    return stream.readDouble();
                }
                final double[] doubles = new double[count];
                for (int j = 0; j < count; j++) {
                    doubles[j] = stream.readDouble();
                }
                return doubles;
            }
            case IFD -> {
                if (count == 1) {
                    return TagValue.IFD.ofUnsigned32(stream.readInt() & 0xFFFFFFFFL);
                }
                final TagValue.IFD[] ifdOffsets = new TagValue.IFD[count];
                for (int j = 0; j < count; j++) {
                    ifdOffsets[j] = TagValue.IFD.ofUnsigned32(stream.readInt() & 0xFFFFFFFFL);
                }
                return ifdOffsets;
            }
            case LONG8 -> {
                if (count == 1) {
                    return stream.readLong();
                }
                if (OPTIMIZE_READING_IFD_ARRAYS) {
                    final byte[] bytes = readBytes(stream, 8 * (long) count);
                    return JArrays.bytesToLongArray(bytes, byteOrder);
                } else {
                    long[] longs = new long[count];
                    for (int j = 0; j < count; j++) {
                        longs[j] = stream.readLong();
                    }
                    return longs;
                }
            }
            case SLONG8 -> {
                if (count == 1) {
                    return TagValue.SLong8.of(stream.readLong());
                }
                final TagValue.SLong8[] values = new TagValue.SLong8[count];
                for (int j = 0; j < count; j++) {
                    values[j] = TagValue.SLong8.of(stream.readLong());
                }
                return values;
            }
            case IFD8 -> {
                if (count == 1) {
                    return TagValue.IFD.of(stream.readLong());
                }
                final TagValue.IFD[] ifdOffsets = new TagValue.IFD[count];
                for (int j = 0; j < count; j++) {
                    ifdOffsets[j] = TagValue.IFD.of(stream.readLong());
                }
                return ifdOffsets;
            }
            case null, default -> {
                return new TiffIFD.UnsupportedTypeValue(entry.rawType(), count, entry.embeddedValueOrOffset());
            }
        }
    }

    /**
     * Writes the given IFD value, splitting it between the {@code ifdStream}
     * (the directory entry) and the {@code extraBuffer} (the actual data if it
     * doesn't fit in the entry).
     *
     * <p>Writing in both streams is performed starting from their current positions.
     * After calling this method, you
     * should copy full content of {@code extraBuffer} into the main stream at the position
     * specified by the second argument;
     * {@link TiffWriter#writeIFD(TiffIFD, TiffIFD.Linkage.UpdateMode)} method does it automatically.
     *
     * <p>Here "extra" data means all data, for which IFD contains their offsets instead of data itself,
     * like arrays or text strings. The "main" data is a 12-byte IFD record (20-byte for BigTIFF),
     * which is written by this method into the main output stream from its current position.
     *
     * <p>The argument {@code additionToExtraBufferOffset} is used to calculate the position of "extra" data
     * in the result TIFF file: it is<br>
     * &nbsp;&nbsp;&nbsp;&nbsp;{@code additionToExtraBufferOffset} +
     * offset of the written "extra" data inside {@code extraBuffer};<br>
     * for example, this argument may be a position directly after
     * the "main" content (sequence of 12/20-byte records).
     * This position will be written into {@code ifdStream}.
     * However, if {@code additionToExtraBufferOffset=null}, this operation will be skipped.</p>
     *
     * <p>Note: the current version <b>does not</b> use {@link TagType} information;
     * instead, the type is recognized based on of Java type of {@code entry.getValue()}.
     * As a result, we cannot write {@link TagType#SBYTE}, {@link TagType#SSHORT},
     * {@link TagType#SLONG}, {@link TagType#SLONG8},
     * {@link TagType#IFD}, {@link TagType#IFD8} types.</p>
     *
     * @param ifdStream                   the main stream where IFD entries should be written.
     * @param extraBuffer                 the buffer where "extra" IFD information should be written.
     * @param bigTiff                     BigTIFF flag.
     * @param additionToExtraBufferOffset the position of "extra" data in the result TIFF file =
     *                                    {@code additionToExtraBufferOffset} +
     *                                    offset of the written "extra" data inside {@code extraBuffer};
     *                                    for example, this argument may be a position directly after
     *                                    the "main" content (sequence of 12/20-byte records).
     * @param tagKey                      the IFD tag to write.
     * @param value                       the IFD value to write.
     */
    static void writeIFDValueAtCurrentOffsets(
            final DataHandle<?> ifdStream,
            final DataHandle<?> extraBuffer,
            final boolean bigTiff,
            final long additionToExtraBufferOffset,
            final Integer tagKey,
            Object value) throws IOException {
        if (tagKey == null) {
            throw new UnsupportedOperationException("Cannot write null IFD tag");
            // - should not occur in a correct TiffIFD
        }
        final int tag = tagKey;
        if (tag < 0 || tag > 0xFFFF) {
            throw new TiffException("Invalid TIFF IFD tag code %d (0x%04X)".formatted(tag, tag) +
                    ": only unsigned 16-bit values 0..65535 are allowed");
        }
        switch (value) {
            case null -> throw new UnsupportedOperationException("Cannot write IFD tag " + tag +
                    ": it contains null value");
            // - should not occur in a correct TiffIFD
            case Byte v -> value = new byte[]{v};
            case Short v -> value = new short[]{v};
            case Integer v -> value = new int[]{v};
            case Long v -> value = new long[]{v};
            case Float v -> value = new float[]{v};
            case Double v -> value = new double[]{v};
            case TagValue v -> {
                value = Array.newInstance(v.getClass(), 1);
                Array.set(value, 0, v);
            }
            default -> {
            }
        }
        final int dataLength = value.getClass().isArray() ? Array.getLength(value) : 0;

        boolean emptyStringList = false;
        if (value instanceof String[] list) {
            emptyStringList = list.length == 0;
            value = java.util.Arrays.stream(list)
                    .map(s -> s != null ? s : "")
                    // - null string is equivalent to ""
                    .collect(java.util.stream.Collectors.joining("\0"));
        }
        final TagType tagType = TagType.fromJavaType(value.getClass(), bigTiff).orElse(null);
        final int embeddedDataSize = bigTiff ? 8 : 4;
        final int embeddedDataSizeDiv2 = embeddedDataSize >> 1;
        final int embeddedDataSizeDiv4 = embeddedDataSize >> 2;
        final int embeddedDataSizeDiv8 = embeddedDataSize >> 3;
        // assert embeddedDataSizeDiv8 == (bigTiff ? 1 : 0);
        long actualDataSize = tagType == null || tagType == TagType.ASCII ?
                0 :
                (long) dataLength * (long) tagType.bytesPerElement();
        final int paddingSize = actualDataSize < embeddedDataSize ? embeddedDataSize - (int) actualDataSize : 0;
        final ByteOrder byteOrder = extraBuffer.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;

        writeUnsignedShort(ifdStream, tag);
        // Note: we cannot get extraBuffer.offset() here, we MUST call appendUntilEvenOffset() before this!
        switch (value) {
            case byte[] v -> {
                writeTagType(ifdStream, tagType, TagType.UNDEFINED);
                // - Most probable type. Maybe in future we will support here some algorithm,
                // determining the necessary type on the base of the tag value.
                TagValue.writeUnsigned(ifdStream, bigTiff, v.length);
                if (v.length <= embeddedDataSize) {
                    for (byte byteValue : v) {
                        ifdStream.writeByte(byteValue);
                    }
                    writeZeroPadding(ifdStream, paddingSize);
                } else {
                    appendUntilEvenOffset(extraBuffer);
                    writeOffsetWithAddition(ifdStream, bigTiff, additionToExtraBufferOffset, extraBuffer.offset());
                    extraBuffer.write(v);
                }
            }
            case short[] v -> {
                writeTagType(ifdStream, tagType, TagType.BYTE);
                TagValue.writeUnsigned(ifdStream, bigTiff, v.length);
                if (v.length <= embeddedDataSize) {
                    for (short s : v) {
                        writeUnsignedByte(ifdStream, s);
                    }
                    writeZeroPadding(ifdStream, paddingSize);
                } else {
                    appendUntilEvenOffset(extraBuffer);
                    writeOffsetWithAddition(ifdStream, bigTiff, additionToExtraBufferOffset, extraBuffer.offset());
                    byte[] bytes = new byte[v.length];
                    for (int i = 0; i < v.length; i++) {
                        bytes[i] = checkUnsignedByte(v[i]);
                    }
                    extraBuffer.write(bytes);
                }
            }
            case String stringValue -> {
                writeTagType(ifdStream, tagType, TagType.ASCII);
                final byte[] v = emptyStringList ? new byte[0] : stringValue.getBytes(StandardCharsets.UTF_8);
                // note that an empty list will be an empty value (0 bytes),
                // while an empty string will be a single empty string (1 zero byte)
                if (v.length > Integer.MAX_VALUE - 1) {
                    throw new TiffException("Cannot write TIFF IFD: string value is too large (2^31-1 bytes)");
                }
                actualDataSize = emptyStringList ? 0 : v.length + 1;
                TagValue.writeUnsigned(ifdStream, bigTiff, actualDataSize);
                // - with concluding zero bytes, excepting an empty String[] array (produced by ASCII byte[0])
                if (actualDataSize <= embeddedDataSize) {
                    // - this branch is the same for an empty String[] array (byte[0])
                    // and for an empty string (byte[1] which contains 0)
                    for (byte c : v) {
                        writeUnsignedByte(ifdStream, c & 0xFF);
                    }
                    // assert v.length <= embeddedDataSize; // - because v.length <= actualDataSize
                    writeZeroPadding(ifdStream, embeddedDataSize - v.length);
                } else {
                    appendUntilEvenOffset(extraBuffer);
                    writeOffsetWithAddition(ifdStream, bigTiff, additionToExtraBufferOffset, extraBuffer.offset());
                    extraBuffer.write(v);
//                for (byte c : v) {
//                    if (charValue > 0xFF) {
//                        throw new TiffException("Attempt to write a character with code " + (int) charValue +
//                                " > 255; only ASCII characters with 0..255 codes are supported in string TIFF tags");
//                    }
//                    writeUnsignedByte(extraBuffer, c & 0xFF);
//                }
                    extraBuffer.writeByte(0); // concluding zero bytes
                }
            }
            case int[] v -> {
                // suppose SHORT (unsigned 16-bit)
                if (v.length == 1) {
                    // - we should allow using usual int values for 32-bit tags to avoid a lot of obvious bugs
                    final int v0 = v[0];
                    if (v0 > 0xFFFF) {
                        // - for example, TileWidth/TileLength are stored as int in TiffIFD.putTileSizes;
                        // see also TiffIFD.USE_LONG_IMAGE_DIMENSIONS
                        if (writeSpecialTag(ifdStream, bigTiff, tag, v0)) {
                            return;
                        }
                        // - for other (non "special") tags we use standard selection:
                        // LONG8 for BigTIFF, LONG for non-BigTIFF
                        writeTagType(ifdStream, bigTiff ? TagType.LONG8 : TagType.LONG, null);
                        TagValue.writeUnsigned(ifdStream, bigTiff, v.length);
                        TagValue.writeUnsigned(ifdStream, bigTiff, v0);
                        return;
                    }
                }
                writeTagType(ifdStream, tagType, TagType.SHORT);
                TagValue.writeUnsigned(ifdStream, bigTiff, v.length);
                if (v.length <= embeddedDataSizeDiv2) {
                    for (int intValue : v) {
                        writeUnsignedShort(ifdStream, intValue);
                    }
                    writeZeroPadding(ifdStream, paddingSize);
                } else {
                    appendUntilEvenOffset(extraBuffer);
                    writeOffsetWithAddition(ifdStream, bigTiff, additionToExtraBufferOffset, extraBuffer.offset());
                    short[] shorts = new short[v.length];
                    for (int i = 0; i < v.length; i++) {
                        shorts[i] = checkUnsignedShort(v[i]);
                    }
                    extraBuffer.write(JArrays.shortArrayToBytes(shorts, byteOrder));
                }
            }
            case long[] v -> {
                // suppose LONG (unsigned 32-bit) or LONG8 for BigTIFF
                if (v.length == 1 && writeSpecialTag(ifdStream, bigTiff, tag, v[0])) {
                    return;
                }
                writeTagType(ifdStream, tagType, bigTiff ? TagType.LONG8 : TagType.LONG);
                TagValue.writeUnsigned(ifdStream, bigTiff, v.length);
                if (v.length <= 1) {
                    // - for both types, bigTiff and !bigTiff, we have 1 LONG8/LONG element in the embedded area
                    for (long longValue : v) {
                        TagValue.writeUnsigned(ifdStream, bigTiff, longValue);
                    }
                    if (paddingSize != (v.length != 0 ? 0 : bigTiff ? 8 : 4)) {
                        throw new AssertionError("Padding " + paddingSize + ", " + v.length + " values");
                    }
                    writeZeroPadding(ifdStream, paddingSize);
                } else {
                    appendUntilEvenOffset(extraBuffer);
                    writeOffsetWithAddition(ifdStream, bigTiff, additionToExtraBufferOffset, extraBuffer.offset());
                    extraBuffer.write(longArrayToBytes(v, bigTiff, byteOrder));
//              Old solution:
//                for (long longValue : v) {
//                    writeIntOrLong(extraBuffer, bigTiff, longValue);
//                }
                }
            }
            case float[] v -> {
                writeTagType(ifdStream, tagType, TagType.FLOAT);
                TagValue.writeUnsigned(ifdStream, bigTiff, v.length);
                if (v.length <= embeddedDataSizeDiv4) {
                    for (float floatValue : v) {
                        ifdStream.writeFloat(floatValue);
                        // - in old SCIFIO code, here was a bug (for a case bigTiff): v[0] was always written
                    }
                    writeZeroPadding(ifdStream, paddingSize);
                } else {
                    appendUntilEvenOffset(extraBuffer);
                    writeOffsetWithAddition(ifdStream, bigTiff, additionToExtraBufferOffset, extraBuffer.offset());
                    for (float floatValue : v) {
                        extraBuffer.writeFloat(floatValue);
                    }
                }
            }
            case double[] v -> {
                writeTagType(ifdStream, tagType, TagType.DOUBLE);
                TagValue.writeUnsigned(ifdStream, bigTiff, v.length);
                if (v.length <= embeddedDataSizeDiv8) {
                    for (double doubleValue : v) {
                        ifdStream.writeDouble(doubleValue);
                    }
                    writeZeroPadding(ifdStream, paddingSize);
                    // - possible special case: v.length=0, not BigTIFF, but we still need to fill 4 (not 8) bytes!
                } else {
                    appendUntilEvenOffset(extraBuffer);
                    writeOffsetWithAddition(ifdStream, bigTiff, additionToExtraBufferOffset, extraBuffer.offset());
                    for (final double doubleValue : v) {
                        extraBuffer.writeDouble(doubleValue);
                    }
                }
            }
            case TagValue[] v -> {
                if (tagType == null) {
                    throw writingUnsupportedTagException(tag, value);
                }
                writeTagType(ifdStream, tagType, null);
                TagValue.writeUnsigned(ifdStream, bigTiff, v.length);
                if (actualDataSize <= embeddedDataSize) {
                    for (TagValue tagValue : v) {
                        tagValue.write(ifdStream, bigTiff);
                    }
                    writeZeroPadding(ifdStream, paddingSize);
                } else {
                    appendUntilEvenOffset(extraBuffer);
                    writeOffsetWithAddition(ifdStream, bigTiff, additionToExtraBufferOffset, extraBuffer.offset());
                    for (TagValue tagValue : v) {
                        tagValue.write(extraBuffer, bigTiff);
                    }
                }
            }
            case TiffIFD.UnsupportedTypeValue unsupported -> {
                if (tagType != null) {
                    throw new AssertionError("Strange type " + tagType + " instead of null");
                }
                ifdStream.writeShort(unsupported.type());
                // - but we don't know the sense of its valueOrOffset field; it is better to write "0 elements"
                TagValue.writeUnsigned(ifdStream, bigTiff, 0);
                writeZeroPadding(ifdStream, embeddedDataSize);
            }
            default -> throw writingUnsupportedTagException(tag, value);
        }
    }

    TiffIFD.Linkage readLinkage(boolean allowNoIFDs, long maxNumberOfIFDs) throws IOException {
        if (maxNumberOfIFDs <= 0) {
            throw new IllegalArgumentException("maxNumberOfIFDs must be > 0");
        }
        synchronized (fileLock) {
            final TiffIFD.Linkage result = new TiffIFD.Linkage(bigTiff);
            final long fileLength = stream.length();
            long offset = allowNoIFDs ?
                    readFirstIFDOffsetIfPresent().orElse(0L) :
                    readFirstIFDOffset();
            assert allowNoIFDs || offset != 0 : "readFirstIFDOffset returned 0";
            long count = 0;
            while (offset != TiffIFD.IFD_CHAIN_TERMINATOR) {
                // - actually "!= 0"; negative and too high offsets are checked inside low-level readOffset() method
                ++count;
                if (count > MAX_NUMBER_OF_IFDS) {
                    throw new TiffException("Too many IFDs: more than " + MAX_NUMBER_OF_IFDS +
                            "; probably it is a corrupted file");
                }
                skipIFDEntries(offset, fileLength);
                final long offsetOfNextOffset = stream.offset();
                final long nextOffset = readIFDOffset();
                result.addOffsetPair(new TiffIFD.Linkage.OffsetPair(offset, offsetOfNextOffset));
                result.setOffsetOfChainTerminator(offsetOfNextOffset);
                // - Note: we DO NOT call setChainTerminator here without checks!
                // setChainTerminator method is intended only for detection of invalid (infinite) chain loop,
                // not for detection of breaking due to the maxNumberOfIFDs limit.
                final boolean wasPresent = result.containsIFDOffset(nextOffset);
                if (wasPresent) {
                    result.setChainTerminator(nextOffset);
                    // - we must set it BEFORE exiting the loop due to count==maxNumberOfIFDs;
                    // for example, maxNumberOfIFDs=1 (we read mainIFDIndex=0),
                    // and the only IFD refers to itself
                    break;
                    // - throwing exception (the code below) is not the best idea: for example,
                    // GIMP allows reading such a TIFF
                    // throw new TiffException("TIFF file is broken - infinite loop of IFD offsets is detected " +
                    //         "for offset %d; the stored offset pairs are:%n%s"
                    //                .formatted(offset, result.toString(1)));

                }
                if (count >= maxNumberOfIFDs) {
                    break;
                }
                offset = nextOffset;
            }
            return result;
        }
    }

    void invalidateLinkage(boolean logging, Supplier<String> comment) {
        final TiffIFD.Linkage linkage = this.linkage;
        this.linkage = null;
        if (logging) {
            LOG.log(System.Logger.Level.DEBUG, () -> "Invalidating linkage" +
                    (comment == null ? "" : comment.get()) +
                    (linkage == null ? " (no linkage)" : ": " + linkage));
        }
    }

    TiffIFD.Linkage linkage(String comment) throws IOException {
        TiffIFD.Linkage currentLinkage = this.linkage;
        boolean needToReload = currentLinkage == null;
        if (needToReload) {
            synchronized (fileLock) {
                // double-checked locking idiom
                currentLinkage = this.linkage;
                needToReload = currentLinkage == null;
                if (needToReload) {
                    boolean success = false;
                    final long savedOffset = stream.offset();
                    try {
                        this.linkage = currentLinkage = readLinkage();
                        stream.seek(savedOffset);
                        success = true;
                    } finally {
                        if (!success) {
                            this.linkage = null;
                            // - the next call to this method will try to refresh the linkage again
                            // (this is necessary only if "stream.seek(savedOffset)" thrown an exception)
                            try {
                                stream.seek(savedOffset);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        }
        if (needToReload) {
            final var newLinkage = currentLinkage;
            LOG.log(System.Logger.Level.DEBUG, () -> comment + ": " + newLinkage);
        }
        return currentLinkage;
    }

    private long readFirstIFDOffset() throws IOException {
        synchronized (fileLock) {
            return readFirstIFDOffsetIfPresent()
                    .orElseThrow(() -> new UncompletedTiffException("Uncompleted TIFF" + spacedStreamName() +
                            ": the file does not contain any images; " +
                            "probably the TIFF writing process was not completed normally"));
        }
    }

    private OptionalLong readFirstIFDOffsetIfPresent() throws IOException {
        synchronized (fileLock) {
            final long firstIFDOffset = offsetOfFirstIFDOffset();
            stream.seek(firstIFDOffset);
            final long result = readIFDOffset();
            return result == 0 ? OptionalLong.empty() : OptionalLong.of(result);
        }
    }

    private long readMainIFDOffset(int mainIFDIndex, LinkageHolder holder) throws IOException {
        if (mainIFDIndex == 0 && holder == null) {
            return readFirstIFDOffset();
            // - UncompletedTiffException when the TIFF file is empty
        }
        return readMainIFDOffsetIfPresent(false, mainIFDIndex, holder)
                .orElseThrow(() -> new TiffException("No main IFD #" +
                        mainIFDIndex + " in TIFF" + spacedStreamName() + ": too large index"));
        // alloNoIFDs=false leads to correct UncompletedTiffException while reading mainIFDIndex=0
    }

    private OptionalLong readMainIFDOffsetIfPresent(boolean allowNoIFDs, int mainIFDIndex, LinkageHolder holder)
            throws IOException {
        if (mainIFDIndex < 0) {
            throw new IllegalArgumentException("Negative IFD index = " + mainIFDIndex);
        }
        if (mainIFDIndex == 0 && holder == null) {
            // - micro-optimization
            return readFirstIFDOffsetIfPresent();
        }
        final TiffIFD.Linkage linkage = readLinkage(allowNoIFDs, (long) mainIFDIndex + 1L);
        // allowNoIFDs affects only the situation when mainIFDIndex==0
        if (holder != null) {
            holder.linkage = linkage;
        }
        final int subchainLength = linkage.numberOfMainIFDs();
        final OptionalLong lastOffset = linkage.lastIFDOffset();
        assert subchainLength == 0 || lastOffset.isPresent() : "Unset ifdLastOffset";
        return mainIFDIndex < subchainLength ? lastOffset : OptionalLong.empty();
    }

    private long readIFDOffset() throws IOException {
        return readOffset(stream, bigTiff, 0, stream.length(), this::streamName);
    }

    void checkFirstOffset() throws IOException {
        synchronized (fileLock) {
            final long savedOffset = stream.offset();
            try {
                readFirstIFDOffset();
            } finally {
                try {
                    stream.seek(savedOffset);
                    // - usually cannot throw an exception;
                    // in the worst case, we will stay at the position after the first IFD offset
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void skipIFDEntries(long ifdOffset, long fileLength) throws IOException {
        final int numberOfEntries = readNumberOfIFDEntriesAt(ifdOffset);
        final int skippedIFDBytes = sizeOfAllIFDEntries(numberOfEntries);
        if (ifdOffset >= fileLength - skippedIFDBytes) {
            throw new TiffException(
                    "Invalid TIFF" + spacedStreamName() + ": position of next IFD offset " +
                            (ifdOffset + skippedIFDBytes) + " after " + numberOfEntries +
                            " entries is outside the file (probably file is broken)");
        }
        stream.skipBytes(skippedIFDBytes);
    }

    private OptionalLong readMainIFDOffsetIfPresentDeprecated(int mainIFDIndex) throws IOException {
        if (mainIFDIndex < 0) {
            throw new IllegalArgumentException("Negative IFD index = " + mainIFDIndex);
        }
        synchronized (fileLock) {
            final long fileLength = stream.length();
            final OptionalLong first = readFirstIFDOffsetIfPresent();
            if (first.isEmpty()) {
                return OptionalLong.empty();
            }
            long offset = first.getAsLong();
            int index = mainIFDIndex;
            while (offset != 0) {
                // - negative and too high offsets are checked inside low-level readOffset() method
                if (index-- <= 0) {
                    return OptionalLong.of(offset);
                }
                skipIFDEntries(offset, fileLength);
                final long newOffset = readIFDOffsetDeprecated();
                if (newOffset == offset) {
                    throw new TiffException("TIFF file is broken - infinite loop of IFD offsets is detected " +
                            "for offset " + offset);
                }
                offset = newOffset;
            }
            return OptionalLong.empty();
        }
    }

    private long readIFDOffsetDeprecated() throws IOException {
        final long fileOffsetOfNextOffset = stream.offset();
        final long result = readOffset(stream, bigTiff, 0, stream.length(), this::streamName);
        // this.offsetOfLastScannedIFDOffset = fileOffsetOfNextOffset; // - deprecated solution
        return result;
    }

    static long readOffset(
            DataHandle<?> stream,
            boolean bigTiff,
            long positionStartIncrement,
            long tiffFileLength,
            Supplier<String> fileNameSupplier) throws IOException {
//        final long fileOffsetOfNextOffset = stream.offset();
        long offset;
        if (bigTiff) {
            offset = stream.readLong();
        } else {
            // Below is a deprecated solution
            // (this "trick" cannot help if a SINGLE image is very large (>2^32): for example,
            // previous = 8 (1st IFD) and the next is 0x120000000; but it is the mostly typical
            // problematic situation: for example, very large 1st IFD in SVS file).
            //
            // offset = (previous & ~0xffffffffL) | (in.readInt() & 0xffffffffL);
            // Only adjust the offset if we know that the file is too large for
            // 32-bit
            // offsets to be accurate; otherwise, we're making the incorrect
            // assumption
            // that IFDs are stored sequentially.
            // if (offset < previous && offset != 0 && in.length() > Integer.MAX_VALUE) {
            //      offset += 0x100000000L;
            // }
            // return offset;

            offset = (long) stream.readInt() & 0xFFFFFFFFL;
            // - in usual TIFF format, offset if 32-bit UNSIGNED value
        }
//        if (stream.offset() - (bigTiff ? 8 : 4) == fileOffsetOfNextOffset) {
//            System.out.println(stream.offset() - (bigTiff ? 8 : 4) + " " + bigTiff);
//        } else throw new AssertionError();
        if (offset < 0 || offset >= tiffFileLength) {
            // offset < 0 is possible in BigTIFF only
            final String fileName = fileNameSupplier.get();
            final long offsetOfOffset = stream.offset() - (bigTiff ? 8 : 4) + positionStartIncrement;
            throw new TiffException((
                    (offset < 0 ? "Invalid TIFF%s: negative 64-bit IFD offset %d (0x%X) at file offset %d (0x%X), " :
                            "Invalid TIFF%s: IFD offset %d (0x%X) at file offset %d (0x%X) " +
                            "is outside the file length " + tiffFileLength + ", ") +
                            "probably the file is corrupted").formatted(
                    fileName.isEmpty() ? "" : " " + fileName,
                    offset, offset,
                    offsetOfOffset, offsetOfOffset));
        }
        return offset;
    }

    static void writeOffset(DataHandle<?> stream, boolean bigTiff, long offsetValueToWrite) throws IOException {
        if (offsetValueToWrite < 0) {
            throw new IllegalArgumentException("Illegal usage of writeOffset: negative offset " + offsetValueToWrite);
        }
        if (bigTiff) {
            stream.writeLong(offsetValueToWrite);
        } else {
            if (offsetValueToWrite > 0xFFFFFFF0L) {
                throw new TiffException("Attempt to write too large 64-bit offset as unsigned 32-bit: " +
                        offsetValueToWrite + " > 2^32-16; such large files should be written in BigTIFF mode");
            }
            stream.writeInt((int) offsetValueToWrite);
            // - masking by 0xFFFFFFFF is unnecessary: cast to (int) works properly also for 32-bit unsigned values
        }
    }

    // Note used in the current version.
    // It was used in the TiffReader constructor with Path argument: see comments in its implementation.
    static DataHandle<?> getExistingFileHandle(Path file) throws FileNotFoundException {
        if (!Files.isRegularFile(file)) {
            throw new FileNotFoundException("File " + file
                    + (Files.exists(file) ? " is not a regular file" : " does not exist"));
        }
        return getFileHandle(file);
    }

    static void appendUntilEvenOffset(DataHandle<?> stream) throws IOException {
        if ((stream.offset() & 0x1) != 0) {
//            System.out.println("Correction " + stream.offset());
            stream.writeByte(0);
            // - Well-formed IFD requires even offsets
        }
    }

    static BytesHandle getBytesHandle(byte[] data, boolean littleEndian) {
        Objects.requireNonNull(data, "Null data");
        final BytesHandle result = new BytesHandle(new BytesLocation(data));
        result.setLittleEndian(littleEndian);
        return result;
    }

    static BytesHandle newBytesHandle(boolean littleEndian) {
        final BytesLocation bytesLocation = new BytesLocation(0, "memory-buffer");
        final BytesHandle result = new BytesHandle(bytesLocation);
        result.setLittleEndian(littleEndian);
        return result;
    }

    static long debugTime() {
        return BUILT_IN_TIMING && LOGGABLE_DEBUG ? System.nanoTime() : 0;
    }

    @SuppressWarnings("SameParameterValue")
    static boolean getBooleanProperty(String propertyName) {
        try {
            return Boolean.getBoolean(propertyName);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readBytes(DataHandle<?> stream, long length) throws IOException {
        if (length > Integer.MAX_VALUE) {
            throw new TiffException("Too large IFD value: " + length + " >= 2^31 bytes");
        }
        byte[] bytes = new byte[(int) length];
        stream.readFully(bytes);
        return bytes;
    }

    private static byte[] longArrayToBytes(long[] values, boolean bigTiff, ByteOrder byteOrder) throws TiffException {
        if (bigTiff) {
            return JArrays.longArrayToBytes(values, byteOrder);
        } else {
            int[] ints = new int[values.length];
            for (int i = 0; i < values.length; i++) {
                ints[i] = checkUnsignedInt(values[i]);
            }
            return JArrays.intArrayToBytes(ints, byteOrder);
        }
    }

    private static boolean writeSpecialTag(DataHandle<?> ifdStream, boolean bigTiff, int tag, long value)
            throws IOException {
        if (AVOID_LONG8_FOR_ACTUAL_32_BITS && bigTiff) {
            // - note: inside TIFF, long[1] is saved in the same way as Long; we have a difference in Java only
            if (value == (int) value) {
                switch (tag) {
                    case Tags.IMAGE_WIDTH,
                         Tags.IMAGE_LENGTH,
                         Tags.TILE_WIDTH,
                         Tags.TILE_LENGTH,
                         Tags.IMAGE_DEPTH,
                         Tags.ROWS_PER_STRIP,
                         Tags.NEW_SUBFILE_TYPE -> {
                        ifdStream.writeShort(TagType.LONG.typeCode());
                        TagValue.writeUnsigned(ifdStream, true, 1);
                        // - the length
                        ifdStream.writeInt((int) value);
                        writeZeroPadding(ifdStream, 4);
                        // - 4 bytes of padding until full length 20 bytes
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void writeOffsetWithAddition(DataHandle<?> stream, boolean bigTiff, long addition, long offset)
            throws IOException {
        writeOffset(stream, bigTiff, addition + offset);
    }

    private static void writeTagType(DataHandle<?> ifdStream, TagType tagType, TagType expected)
            throws IOException {
        if (expected != null && tagType != expected) {
            throw new AssertionError("Invalid tagType: " + tagType + " instead of " + expected);
        }
        ifdStream.writeShort(tagType.typeCode());
    }

    private static void writeUnsignedShort(DataHandle<?> stream, int value) throws IOException {
        checkUnsignedShort(value);
        stream.writeShort(value);
    }

    private static void writeUnsignedByte(DataHandle<?> stream, int value) throws IOException {
        checkUnsignedByte(value);
        stream.writeByte(value);
    }

    private static void writeZeroPadding(DataHandle<?> ifdStream, int length) throws IOException {
        if (length > 0) {
            final byte[] zeroPadding = new byte[length];
            // - zero-filled by Java
            ifdStream.write(zeroPadding);
        }
    }

    private static byte checkUnsignedByte(int value) throws TiffException {
        if (value < 0 || value > 0xFF) {
            throw new TiffException("Attempt to write " + (value < 0 ? "negative" : "too large") +
                    " 16/32-bit value as unsigned 8-bit: " + value);
        }
        return (byte) value;
    }

    private static short checkUnsignedShort(int value) throws TiffException {
        if (value < 0 || value > 0xFFFF) {
            throw new TiffException("Attempt to write " + (value < 0 ? "negative" : "too large") +
                    " 32-bit value as unsigned 16-bit: " + value);
        }
        return (short) value;
    }

    private static int checkUnsignedInt(long value) throws TiffException {
        if (value < 0 || value > 0xFFFFFFFFL) {
            throw new TiffException("Attempt to write " + (value < 0 ? "negative" : "too large") +
                    " 64-bit value as unsigned 32-bit: " + value);
        }
        return (int) value;
    }

    private static TiffException writingUnsupportedTagException(int tag, Object value) {
        return new TiffException("Cannot write IFD tag " +
                Tags.prettyName(tag) + ": its value type \"" +
                value.getClass().getTypeName() + "\" is not supported");
    }

    static class LinkageHolder {
        TiffIFD.Linkage linkage;
    }

    record IFDCommonInformation(
            boolean littleEndian,
            int n,
            int sizeOfEntry,
            int sizeOfAllEntries,
            byte[] ifdBytes,
            long offsetOfFirstEntry,
            long offsetOfNextIFDOffset,
            Long nextIFDOffset,
            long fileLength) {
    }
}
