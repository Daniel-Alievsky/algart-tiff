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

import net.algart.arrays.*;
import net.algart.io.MatrixIO;
import net.algart.matrices.tiff.codecs.TiffCodec;
import net.algart.matrices.tiff.data.TiffJPEGDecodingHelper;
import net.algart.matrices.tiff.data.TiffPrediction;
import net.algart.matrices.tiff.data.TiffUnpacking;
import net.algart.matrices.tiff.data.TiffUnusualPrecisions;
import net.algart.matrices.tiff.io.ReadBufferDataHandle;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagPhotometric;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.*;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.FileHandle;
import org.scijava.io.location.FileLocation;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.*;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Reads TIFF format.
 *
 * <p>This object is internally synchronized and thread-safe for concurrent use.
 * However, you should not modify objects, passed to the methods of this class from a parallel thread;
 * first, it concerns the {@link TiffIFD} arguments of many methods.
 * The same is true for the result of {@link #stream()} method.</p>
 */
public non-sealed class TiffReader extends TiffIO {
    public enum UnpackBits {
        NONE((byte) 0),
        UNPACK_TO_0_1((byte) 1),
        UNPACK_TO_0_255((byte) 255);

        private final byte bit1Value;

        UnpackBits(byte bit1Value) {
            this.bit1Value = bit1Value;
        }

        public static UnpackBits of(boolean unpack) {
            return unpack ? UNPACK_TO_0_255 : NONE;
        }


        public boolean isEnabled() {
            return this != NONE;
        }

        public byte bit1Value() {
            return bit1Value;
        }
    }

    public enum UnusualPrecisions {
        NONE,
        DISABLE,
        UNPACK;

        public UnusualPrecisions unpackIfEnabled() {
            return this == NONE ? UNPACK : this;
        }

        public static UnusualPrecisions of(boolean unpack) {
            return unpack ? UNPACK : NONE;
        }

        public void throwIfDisabled(TiffMap map) throws TiffException {
            Objects.requireNonNull(map, "Null TIFF map");
            if (this == DISABLE && TiffUnusualPrecisions.isUnusualPrecisions(map.ifd())) {
                throw new UnsupportedTiffFormatException("Support of unusual TIFF bit depth is disabled: " +
                        Arrays.toString(map.ifd().getBitsPerSample()) + " bits/sample for " +
                        map.sampleType().prettyName() + " values");
            }
        }

        public byte[] unpackIfNecessary(TiffMap map, byte[] samples, long numberOfPixels, boolean scaleUnsignedInt24)
                throws TiffException {
            Objects.requireNonNull(map, "Null TIFF map");
            Objects.requireNonNull(samples, "Null samples");
            throwIfDisabled(map);
            return this != TiffReader.UnusualPrecisions.UNPACK ?
                    samples :
                    TiffUnusualPrecisions.unpackUnusualPrecisions(
                            samples, map.ifd(), map.numberOfChannels(), numberOfPixels,
                            scaleUnsignedInt24);
        }
    }

    public static final long DEFAULT_MAX_CACHING_MEMORY = Math.max(0,
            net.algart.arrays.Arrays.SystemSettings.getLongProperty(
                    "net.algart.matrices.tiff.defaultMaxCachingMemory", 256 * 1048576L));
    // - 256 MB maximal cache by default: enough to store 256 RGBA tiles 512x512
    // (for example, one tiles row in the image 131072x131072)

    static final boolean USE_LEGACY_UNPACK_BYTES = false;
    // - should be false for better performance; necessary for debugging needs only
    // (together with uncommenting unpackBytesLegacy call)
    private static final boolean AUTO_BUFFERING_INPUT_STREAM = true;
    // - should be true for good performance
    private static final boolean READ_IFD_WITH_BUFFERING = true;
    // - should be true for good performance when the stream is not ReadBufferDataHandle,
    // for example, if the previous flag is false


    private static final int MINIMAL_ALLOWED_TIFF_FILE_LENGTH =
            TiffIFD.TIFF_FILE_HEADER_LENGTH + 2 + TiffIFD.BYTES_PER_ENTRY + 4;
    // - 8 bytes header and at least 1 IFD entry (usually at least 2 entries required: ImageWidth + ImageLength):
    // see TiffIFD.lengthInFileExcludingEntries;
    // note that this constant should be > 16 to detect a "fake" BigTIFF file, containing header only

    private volatile boolean caching = true;
    private volatile long maxCacheMemory = DEFAULT_MAX_CACHING_MEMORY;
    private UnpackBits autoUnpackBits = UnpackBits.NONE;
    private UnusualPrecisions unusualPrecisions = UnusualPrecisions.UNPACK;
    private boolean scaleWhenIncreasingBitDepth = true;
    private boolean colorCorrection = false;
    private boolean removeExtraChannelsIf5OrMoreForBufferedImage = false;
    private boolean enforceUseExternalCodec = false;
    private boolean cropTilesToImageBoundaries = true;
    private boolean cachingIFDs = true;
    private boolean missingTilesAllowed = false;
    private byte byteFiller = 0;

    private final IOException openingException;
    private volatile boolean existingFile;
    private volatile boolean validTiff;
    private volatile boolean tiff;

    /**
     * Cached list of IFDs in the current file.
     */
    private volatile List<TiffIFD> allIFDs;
    private volatile List<TiffIFD> mainIFDs;

    /**
     * Cached first IFD in the current file.
     */
    private volatile TiffIFD firstIFD;

    private TiffCodec.Options codecOptions = new TiffCodec.Options();

    private final Map<TiffTileIndex, CachedTile> tileCacheMap = new HashMap<>();
    private final Queue<CachedTile> tileCache = new LinkedList<>();
    private long currentCacheMemory = 0;
    private final Object tileCacheLock = new Object();

    private volatile TiffReadMap lastMap = null;

    private long timeReading = 0;
    private long timeCustomizingDecoding = 0;
    private long timeDecoding = 0;
    private long timeDecodingMain = 0;
    private long timeDecodingBridge = 0;
    private long timeDecodingAdditional = 0;
    private long timeCompleteDecoding = 0;

    /**
     * Reads the image from the specified file as a list of 2-dimensional matrices containing color channels.
     *
     * <p>If the file is a TIFF file, this method calls
     * {@link #readChannels(int) readChannels(0)} to read data.
     * If the file is not a TIFF, the call is redirected to {@link MatrixIO#readImage(Path)}.</p>
     *
     * @param file the path to the image file.
     * @return a list of matrices representing the image channels.
     * @throws IOException in the case of any problems with the input file or if the format is unsupported.
     * @see MatrixIO#readImage(Path)
     */
    public static List<Matrix<UpdatablePArray>> readImage(Path file) throws IOException {
        try (TiffReader reader = new TiffReader(file, TiffOpenMode.ALLOW_EXISTING_NON_TIFF)) {
            if (reader.isTiff()) {
                return reader.readChannels(0);
            }
        }
        return MatrixIO.readImage(file);
    }

    /**
     * Reads the image from the specified file as a <code>BufferedImage</code>.
     *
     * <p>If the file is a TIFF file, this method calls
     * {@link #readBufferedImage(int) readBufferedImage(0)} to read data.
     * Otherwise, the call is redirected to {@link MatrixIO#readBufferedImage(Path)}.</p>
     *
     * @param file the path to the image file.
     * @return the content of the image as a {@link BufferedImage}.
     * @throws IOException in the case of any problems with the input file or if the format is unsupported.
     * @see MatrixIO#readBufferedImage(Path)
     */
    public static BufferedImage readBufferedImage(Path file) throws IOException {
        try (TiffReader reader = new TiffReader(file, TiffOpenMode.ALLOW_EXISTING_NON_TIFF)) {
            if (reader.isTiff()) {
                return reader.readBufferedImage(0);
            }
        }
        return MatrixIO.readBufferedImage(file);
    }

    /**
     * Equivalent to <code>{@link #TiffReader(Path, TiffOpenMode)
     * TiffReader}(file, {@link TiffOpenMode#VALID_TIFF})</code>.
     *
     * @param file input TIFF file.
     * @throws IOException in the case of any I/O errors, including a non-TIFF file or non-existing file.
     */
    public TiffReader(Path file) throws IOException {
        this(file, TiffOpenMode.VALID_TIFF);
    }

    /**
     * Equivalent to {@link #TiffReader(DataHandle, TiffOpenMode, boolean)
     * TiffReader(inputStream, openMode, true)}, where the <code>inputStream</code> argument is<br>
     * <code>new {@link FileHandle#FileHandle(FileLocation)
     * FileHandle}(new {@link FileLocation#FileLocation(File)
     * FileLocation}(file.toFile()))</code>
     *
     * @param file     input TIFF file.
     * @param openMode what should be checked while opening?
     * @throws IOException in the case of any I/O errors, including a non-TIFF file or non-existing file.
     */
    public TiffReader(Path file, TiffOpenMode openMode) throws IOException {
        // We should not use getExistingFileHandle() here: if the file does not exist,
        // the exception should be suppressed when openMode=TiffOpenMode.NO_CHECKS
        this(getFileHandle(file), file, openMode, true);
    }

    /**
     * Equivalent to {@link #TiffReader(DataHandle, TiffOpenMode, boolean)} with the <code>false</code> last argument.
     * Note that you <b>should not</b> call this constructor from another constructor, creating this
     * <code>DataHandle</code>: in this case, the handle will never be closed!
     *
     * @param inputStream input stream; automatically replaced (wrapped) with {@link ReadBufferDataHandle},
     *                    if this stream is still not an instance of this class.
     * @param openMode    whether the input file must exist and be a readable TIFF-file
     *                    with a correct header.
     * @throws TiffException if the file is not a correct TIFF file
     * @throws IOException   in the case of any problems with the input file
     */
    public TiffReader(DataHandle<?> inputStream, TiffOpenMode openMode) throws IOException {
        this(inputStream, openMode, false);
    }

    /**
     * Constructs a new TIFF reader.
     *
     * <p>If <code>openMode</code> is {@link TiffOpenMode#VALID_TIFF} (standard variant), the constructor throws
     * an exception in case of an incorrect TIFF header (non-TIFF file) or any other problems including I/O errors.
     *
     * <p>If <code>openMode</code> is {@link TiffOpenMode#ALLOW_NON_TIFF}, the constructor
     * allows opening non-TIFF files, but the list of IFDs returned by {@link #allIFDs()} will be empty.
     * You can detect whether the opened file is TIFF using {@link #isTiff()} method.
     * Non-existing file is also successfully "opened", but {@link #isTiff()} will return <code>false</code>.
     * However, the constructor throws an exception if it has successfully read the 8-byte file header
     * and found that it is indeed a TIFF file header, but then something went wrong.
     * (Example of a possible problem: the file is too short for a valid TIFF).
     * All other errors including a non-existing file do not result in exceptions (they are caught),
     * and you can know the occurred exception by {@link #openingException()} method.
     *
     * <p>If <code>openMode</code> is {@link TiffOpenMode#ALLOW_EXISTING_NON_TIFF}, the behavior is similar,
     * but non-existing file leads to throwing an exception.</p>
     *
     * <p>If <code>openMode</code> is {@link TiffOpenMode#NO_CHECKS}, the constructor catches
     * all possible exceptions. In the case of any exception, {@link #isValidTiff()} method will return
     * <code>false</code> and you can know the occurred exception by {@link #openingException()} method.
     *
     * <p>In the case where an exception is thrown (not caught),
     * <code>closeStreamOnException</code> argument specifies whether this function
     * must close the input stream or not. It <b>should</b> be true when you call this constructor
     * from another constructor, which creates <code>DataHandle</code>: it is the only way to close
     * an invalid file. In other situations this flag may be <code>false</code>, then you must close
     * the input stream yourself.
     *
     * <p>The specified input stream is automatically replaced (wrapped) with {@link ReadBufferDataHandle}
     * if this stream is still not an instance of this class.
     * Note: as a result, you cannot use the stream returned by {@link #stream()} method to modify the file.
     *
     * @param inputStream            input stream.
     * @param openMode               what should be checked while opening?
     * @param closeStreamOnException if <code>true</code>, the input stream is closed in the case of any exception;
     *                               ignored if <code>openMode</code> is {@link TiffOpenMode#NO_CHECKS}.
     * @throws TiffException if the file is not a correct TIFF file.
     * @throws IOException   in the case of any problems with the input file.
     */
    public TiffReader(
            DataHandle<?> inputStream,
            TiffOpenMode openMode,
            boolean closeStreamOnException) throws IOException {
        this(inputStream, (Path) null, openMode, closeStreamOnException);
    }

    /**
     * Universal constructor, called from other constructors.
     * Its behavior is equivalent to the constructor
     * {@link #TiffReader(DataHandle, TiffOpenMode)} with the argument {@link TiffOpenMode#NO_CHECKS}.
     *
     * <p>Unlike other constructors, this one never throws an exception.
     * This is helpful because it allows
     * making constructors in subclasses, which do not declare any exceptions to be thrown.
     *
     * <p>If the file is not a correct TIFF or in the case of any other I/O problem,
     * the information about the problem is stored in an exception, which can be retrieved later
     * by {@link #openingException()} method.
     *
     * <p>The argument <code>exceptionHandler</code> may be used to handle the caught exceptions.
     * But the main goal of adding this argument is to avoid calling this constructor by a mistake.
     * If we instead provided a constructor with a single inputStream argument,
     * you might think it would be a good idea to use it by default, but it is not so:
     * typically you need {@link TiffOpenMode#VALID_TIFF} variant of behavior.
     *
     * <p>The specified input stream is automatically replaced (wrapped) with {@link ReadBufferDataHandle}
     * if this stream is still not an instance of this class.
     * Note: as a result, you cannot use the stream returned by {@link #stream()} method to modify the file.
     *
     * @param inputStream      input stream.
     * @param exceptionHandler if not {@code null}, it will be called in the case of some checked exception;
     *                         for example, it may log it. But usually it is better idea to use the main
     *                         constructor {@link #TiffReader(DataHandle, TiffOpenMode, boolean)}
     *                         with catching exception.
     */
    public TiffReader(DataHandle<?> inputStream, Consumer<Exception> exceptionHandler) {
        //noinspection RedundantCast
        this(inputStream, (Path) null, exceptionHandler);
    }

    private TiffReader(
            DataHandle<?> inputStream,
            Path file,
            TiffOpenMode openMode,
            boolean closeStreamOnException) throws IOException {
        this(checkNonNull(inputStream, openMode), file, (Consumer<Exception>) null);
        assert this.tiff || !this.validTiff;
        // - in other words, if validTiff, then tiff
        if (!openMode.isAnythingChecked()) {
            assert openMode == TiffOpenMode.NO_CHECKS;
            return;
        }
        boolean tiffButInvalid = this.tiff && !this.validTiff;
        if (openMode.isExistingRequired() && !this.existingFile) {
            // - see startReading()
            tiffButInvalid = true;
        }
        if (openMode.isTiffRequired() ? !this.validTiff : tiffButInvalid) {
            if (closeStreamOnException) {
                try {
                    stream.close();
                } catch (Exception ignored) {
                }
            }
            if (openingException != null) {
                throw openingException;
            } else {
                throw new AssertionError("Should not occur!");
            }
        }
        assert !tiffButInvalid : "exception was not thrown";
        if (this.tiff != this.validTiff) {
            throw new AssertionError("tiff != validTiff is possible for NO_CHECKS only");
        }
        if (openMode.isTiffRequired()) {
            checkFirstOffset();
            // - additional check of zero or extremely large offset
        }
    }

    private TiffReader(DataHandle<?> inputStream, Path file, Consumer<Exception> exceptionHandler) {
        super(inputStream instanceof ReadBufferDataHandle || !AUTO_BUFFERING_INPUT_STREAM ?
                        inputStream :
                        new ReadBufferDataHandle<>(inputStream),
                file);
        // - Note: the argument inputStream cannot be ReadBufferDataHandle if we use TiffWriter.newReader method.
        // ReadBufferDataHandle is read-only (cannot write anything), so it cannot be used in TiffWriter.
        this.openingException = startReading();
        // - in the current version, a TIFF but invalid can be detected when
        // its length < MINIMAL_ALLOWED_TIFF_FILE_LENGTH (see testHeader())
        assert !(this.validTiff && !this.tiff);
        if (exceptionHandler != null && openingException != null) {
            exceptionHandler.accept(openingException);
        }
    }

    public boolean isCaching() {
        return caching;
    }

    /**
     * Enables or disables tile caching. If caching is enabled, {@link #readCachedTile(TiffTileIndex)} method
     * works very quickly when the tile is found in the cache.
     *
     * <p>By default, caching is enabled (<code>true</code>). We recommend disabling it to save memory
     * if you are not going to read fragments of this file many times.</p>
     *
     * @param caching whether the tiles should be cached.
     * @return a reference to this object.
     */
    public TiffReader setCaching(boolean caching) {
        this.caching = caching;
        return this;
    }

    public long getMaxCacheMemory() {
        return maxCacheMemory;
    }

    public TiffReader setMaxCacheMemory(long maxCacheMemory) {
        if (maxCacheMemory < 0) {
            throw new IllegalArgumentException("Negative maxCacheMemory = " + maxCacheMemory);
        }
        this.maxCacheMemory = maxCacheMemory;
        return this;
    }

    /**
     * Invalidates all internal caches and initializes the reader by re-reading the TIFF header.
     *
     * <p>This method clears all cached tiles and cached IFD structures, resets an internal cache in
     * the {@link ReadBufferDataHandle} input stream and seeks it to zero position,
     * and then reads the TIFF header again by calling
     * the same initialization logic that is used in the constructor.</p>
     *
     * <p>Unlike the constructor, this method is stricter: if the underlying stream does
     * not represent a valid TIFF file or if any I/O error occurs while re-reading the
     * header, an {@link IOException} is thrown.</p>
     *
     * <p>After a successful call, the reader state is equivalent to a freshly opened
     * {@code TiffReader} for the same stream.</p>
     *
     * <p>This method is thread-safe and synchronizes on both the tile cache lock and
     * the file lock.</p>
     *
     * @return this reader.
     * @throws IOException if the file is not a valid TIFF file,
     *                     or if an I/O error occurs while re-reading the header
     */
    public TiffReader clearCache() throws IOException {
        synchronized (tileCacheLock) {
            synchronized (fileLock()) {
                // Lock order: tileCacheLock -> fileLock; we should use the same order in all places!
                clearTileCache();
                this.allIFDs = null;
                this.mainIFDs = null;
                this.fileOffsetOfLastIFDOffset = -1;
                if (!(stream instanceof ReadBufferDataHandle<?>)) {
                    throw new AssertionError(
                            "Input stream was not correctly replaced in the constructor");
                }
                ((ReadBufferDataHandle<?>) stream).clearCache();
                final IOException exception = startReading();
                if (exception != null) {
                    throw exception;
                }
            }
        }
        return this;
    }

    private void clearTileCache() {
        synchronized (tileCacheLock) {
            synchronized (fileLock()) {
                this.tileCacheMap.clear();
                this.tileCache.clear();
                this.currentCacheMemory = 0;
            }
        }
    }

    public UnpackBits getAutoUnpackBits() {
        return autoUnpackBits;
    }

    /**
     * Sets the mode, describing whether do we need to unpack binary images (one bit/pixel, black-and-white images)
     * into <code>byte</code> matrices: black pixels to value 0, white pixels to value depending on the mode.
     *
     * <p>By default, this mode is {@link UnpackBits#NONE}.
     * In this case, {@link TiffReadMap#readMatrix()} and similar methods return binary AlgART matrices.</p>
     *
     * <p>Note that some TIFF images use <i>m</i>&gt;1 bit per pixel, where <i>m</i> is not divisible by 8,
     * such as 4-bit indexed images with a palette or 15-bit RGB image, 5+5+5 bits/channel.
     * Such images are always unpacked to format with an integer number of bytes per channel (<i>m</i>=8*<i>k</i>).
     * The only exception is 1-bit monochrome images: in this case, unpacking into bytes
     * is controlled by this method.</p>
     *
     * @param autoUnpackBits whether do we need to unpack bit matrices to byte ones?
     * @return a reference to this object.
     */
    public TiffReader setAutoUnpackBits(UnpackBits autoUnpackBits) {
        this.autoUnpackBits = Objects.requireNonNull(autoUnpackBits, "Null autoUnpackBits");
        return this;
    }

    public UnusualPrecisions getUnusualPrecisions() {
        return unusualPrecisions;
    }

    /**
     * Sets the mode, what do we need with unusual precisions (bits/sample), differ than all precisions
     * supported by {@link TiffSampleType} class.
     * These are 3-byte samples (17..24 bits/sample)
     * and 16- or 24-bit floating-point formats.
     * They will be unpacked (to 32-bit integer or floating-point values)
     * when this mode is {@link UnusualPrecisions#UNPACK},
     * or they will lead to an exception when it is {@link UnusualPrecisions#DISABLE},
     * or they will be loaded as-is if it is {@link UnusualPrecisions#NONE}.
     *
     * <p>This mode is used inside {@link TiffReadMap#readSampleBytes(int, int, int, int)}
     * method after all tiles have been read.
     * This is just the default value of <code>unusualPrecisions</code>
     * argument of the more verbose method
     * {@link #readSampleBytes(TiffIOMap, int, int, int, int, UnusualPrecisions, boolean, TiffIOMap.TileSupplier)}.
     * </p>
     *
     * <p>Note that the decoded data in {@link TiffTile} in case of unusual precisions is not unpacked
     * (but you may request unpacking with {@link TiffTile#getUnpackedSampleBytes(boolean)} method).
     * On the other hand, all other precisions such as 4-bit or 12-bit (but not 1-channel 1-bit case)
     * are always unpacked to the nearest bit depth divided by 8 when decoding tiles.</p>
     *
     * <p>The {@link UnusualPrecisions#NONE} mode is ignored (as if it was {@link UnusualPrecisions#UNPACK})
     * when using high-level reading methods like {@link TiffReadMap#readMatrix} and
     * {@link TiffReadMap#readJavaArray}.</p>
     *
     * <p>This flag is {@code true} by default. Usually there are no reasons to set it to {@code false},
     * besides compatibility reasons or requirement to maximally save memory while processing 16/24-bit
     * float values.</p>
     *
     * @param unusualPrecisions whether do we need to unpack unusual precisions?
     * @return a reference to this object.
     * @see #completeDecoding(TiffTile)
     * @see TiffMap#bitsPerUnpackedSample()
     */
    public TiffReader setUnusualPrecisions(UnusualPrecisions unusualPrecisions) {
        this.unusualPrecisions = Objects.requireNonNull(unusualPrecisions, "Null unusualPrecisions");
        return this;
    }

    public boolean isScaleWhenIncreasingBitDepth() {
        return scaleWhenIncreasingBitDepth;
    }

    /**
     * Sets the flag specifying whether this reader scales pixel sample values
     * when automatically increasing bit depths,
     * for example, when we decode a 12-bit grayscale image into a 16-bit result.
     *
     * <p>This class can successfully read TIFF with bit depths multiples of 8, such as 4-bit, 12-bit images or
     * 5+5+5 "HiRes" RGB images. But the data returned by this class is always represented by 8-bit, 16-bit,
     * 32-bit integer values (signed or unsigned) or by 32- or 64-bit floating-point values
     * (these bit depths correspond to Java primitive types). If the source pixel values have another bit depth,
     * they are automatically converted to the nearest "larger" type: for example, 4-bit integer is converted
     * to 8-bit, 12-bit integer is converted to 16-bit, 24-bit to 32-bit.</p>
     *
     * <p>If this flag is <code>false</code>, this conversion is performed as-is, so, values 0..15 in 4-bit source
     * data will be converted to the same values 0..15 with 8-bit precision.
     * This is good if you need to process these values using some kind of algorithm.
     * However, if you need to show the real picture to the end user, then values 0..15 with 8-bit
     * precision (or 0..4095 with 16-bit precision) will look almost black. To avoid this,
     * you can use <code>true</code>
     * value of this flag to automatically scale the values: multiplying by
     * (2<sup><i>n</i></sup>&minus;1)/(2<sup><i>k</i></sup>&minus;1), where <i>n</i> is the result bit depth
     * and <i>k</i> is the source bit depth (for example, for 12-bit image <i>k</i>=12 and <i>n</i>=16).
     * As a result, the returned picture will look alike the source one.</p>
     *
     * <p>Default value is <code>true</code>. However, the scaling is still not performed if
     * PhotometricInterpretation TIFF tag is "Palette" (3) or "Transparency Mask" (4): in these cases,
     * scaling has no sense.
     *
     * @param scaleWhenIncreasingBitDepth whether do we need to scale pixel samples, represented with <i>k</i>
     *                                    bits/sample, <i>k</i>%8&nbsp;&ne;&nbsp;0, when increasing bit depth
     *                                    to the nearest <i>n</i> bits/sample, where
     *                                    <i>n</i>&nbsp;&gt;&nbsp;<i>k</i> and <i>n</i> is divided by 8.
     * @return a reference to this object.
     */
    public TiffReader setScaleWhenIncreasingBitDepth(boolean scaleWhenIncreasingBitDepth) {
        this.scaleWhenIncreasingBitDepth = scaleWhenIncreasingBitDepth;
        return this;
    }

    public boolean isColorCorrection() {
        return colorCorrection;
    }

    /**
     * Sets the flag specifying whether this reader performs some color correction based on the
     * {@link TagPhotometric PhotometricInterpretation} TIFF tag.
     *
     * <p>When set to <code>true</code>, the reader attempts to normalize pixel values for specific
     * color spaces, especially where the sense of brightness or color is inverted. For example:
     * <ul>
     * <li><b>WhiteIsZero (0):</b> the reader inverts samples so that 0 becomes black and
     * the maximum value becomes white.</li>
     * <li><b>CMYK (5):</b> the reader inverts samples to provide a visual representation closer to RGB
     * (though this is a simple inversion and not a professional CMYK-to-RGB conversion).</li>
     * </ul>
     *
     * <p>Note: photometric interpretation <b>YCbCr</b> (6) is always decoded regardless of this flag.
     * However, we do not support writing this photometric interpretation
     * for compressions besides JPEG codec;
     * an attempt to write such a TIFF image will replace <b>YCbCr</b> with the usual <b>RGB</b>
     * (see {@link TiffWriter#setSmartCorrection(boolean)}).</p>
     *
     * <p>Note: this flag does not provide full support for all PhotometricInterpretation values
     * (such as CIELAB) and does not replace a proper color management system.
     *
     * <p>Default value is <code>false</code>. It is the correct choice if you want to process the data,
     * not just to display the image. For instance, this flag should be <code>false</code> if you want to copy
     * the data into another TIFF using the {@link TiffCopier} class.
     *
     * @param colorCorrection whether to apply basic inversion/correction for "WhiteIsZero" and "CMYK"
     *                        photometric interpretations.
     * @return a reference to this object.
     * @see TiffMap#isColorCorrectionApplicable()
     */
    public TiffReader setColorCorrection(boolean colorCorrection) {
        // In principle, there is no problem processing YCbCr similarly to CMYK,
        // but this only applies to low-level formats (non-JPEG).
        //
        // 1) First, unpacking YCbCr in the separateYCbCrToRGB method affects
        // not only the color but the entire data stream due to subsampling reversal.
        // This unpacking is mandatory; preserving "original" values in this context
        // looks very strange.
        //
        // 2) Moreover, the current YCbCr implementation is a targeted workaround
        // for specific legacy cases (3 channels, exactly 8-bit) rather than
        // full-scale support. This workaround strictly validates these conditions
        // and throws an error if they are not met.
        // In contrast, White-Is-Zero and CMYK are fully supported from a data
        // perspective, though the library does not provide a high-level API
        // for professional-grade color visualization in these spaces.
        this.colorCorrection = colorCorrection;
        return this;
    }

    public boolean isRemoveExtraChannelsIf5OrMoreForBufferedImage() {
        return removeExtraChannelsIf5OrMoreForBufferedImage;
    }

    /**
     * Sets the flag specifying whether {@link #readBufferedImage} should automatically
     * remove (discard) extra channels if the source TIFF contains 5 or more channels.
     *
     * <p>This is a "safety" flag for {@link #readBufferedImage}. Standard Java images
     * cannot handle more than 4 channels. If this flag is <code>true</code>, the reader
     * will simply strip away the extra channels to provide
     * a viewable image. If <code>false</code>, an exception will be thrown for
     * such multichannel TIFF images.</p>
     *
     * @param removeExtraChannelsIf5OrMoreForBufferedImage whether to remove extrac channels
     *                                                     to stay compatible with BufferedImage.
     * @return a reference to this object.
     */
    public TiffReader setRemoveExtraChannelsIf5OrMoreForBufferedImage(
            boolean removeExtraChannelsIf5OrMoreForBufferedImage) {
        this.removeExtraChannelsIf5OrMoreForBufferedImage = removeExtraChannelsIf5OrMoreForBufferedImage;
        return this;
    }

    public boolean isEnforceUseExternalCodec() {
        return enforceUseExternalCodec;
    }

    public TiffReader setEnforceUseExternalCodec(boolean enforceUseExternalCodec) {
        this.enforceUseExternalCodec = enforceUseExternalCodec;
        return this;
    }

    public boolean isCropTilesToImageBoundaries() {
        return cropTilesToImageBoundaries;
    }

    public TiffReader setCropTilesToImageBoundaries(boolean cropTilesToImageBoundaries) {
        this.cropTilesToImageBoundaries = cropTilesToImageBoundaries;
        return this;
    }

    /**
     * Retrieves the current set of codec options being used to decompress pixel
     * data.
     *
     * @return See above.
     */
    public TiffCodec.Options getCodecOptions() {
        return codecOptions.clone();
    }

    /**
     * Sets the codec options to be used when decompressing pixel data.
     *
     * @param codecOptions Codec options to use.
     * @return a reference to this object.
     */
    public TiffReader setCodecOptions(TiffCodec.Options codecOptions) {
        this.codecOptions = Objects.requireNonNull(codecOptions, "Null codecOptions").clone();
        return this;
    }

    public boolean isCachingIFDs() {
        return cachingIFDs;
    }

    /**
     * Sets whether IFD entries, returned by {@link #allIFDs()} method, should be cached.
     *
     * <p>Default value is <code>true</code>. Possible reason to set is to <code>false</code>
     * is reading a file which is dynamically modified.
     * In another case, usually it should be <code>true</code>, though <code>false</code> value
     * also works well if you are not going to call {@link #allIFDs()} more than once.
     *
     * @param cachingIFDs whether caching IFD is enabled.
     * @return a reference to this object.
     */
    public TiffReader setCachingIFDs(boolean cachingIFDs) {
        this.cachingIFDs = cachingIFDs;
        return this;
    }

    public boolean isMissingTilesAllowed() {
        return missingTilesAllowed;
    }

    /**
     * Sets the special mode, when a TIFF file is allowed to contain "missing" tiles or strips,
     * for which the offset (<code>TileOffsets</code> or <code>StripOffsets</code> tag) and/or
     * byte count (<code>TileByteCounts</code> or <code>StripByteCounts</code> tag) contains zero value.
     * In this mode, such tiles/strips will be successfully read as empty rectangles, filled by
     * the {@link #setByteFiller(byte) default filler}.
     *
     * <p>Default value is <code>false</code>. In this case, such tiles/strips are not allowed,
     * as the standard TIFF format requires.
     *
     * @param missingTilesAllowed whether "missing" tiles/strips are allowed.
     * @return a reference to this object.
     */
    public TiffReader setMissingTilesAllowed(boolean missingTilesAllowed) {
        this.missingTilesAllowed = missingTilesAllowed;
        return this;
    }

    public byte getByteFiller() {
        return byteFiller;
    }

    /**
     * Sets the filler byte for tiles, lying completely outside the image.
     * Value 0 means black color, 0xFF usually means white color.
     *
     * <p><b>Warning!</b> If you want to work with non-8-bit TIFF, especially float precision, you should
     * preserve the default 0 value; in another case, results could be very strange.
     *
     * @param byteFiller new filler.
     * @return a reference to this object.
     */
    public TiffReader setByteFiller(byte byteFiller) {
        this.byteFiller = byteFiller;
        return this;
    }

    public Exception openingException() {
        return openingException;
    }

    /**
     * Returns whether this file is a TIFF file, both ordinary or BigTIFF
     * (i.e., whether it contains the correct TIFF or BigTIFF file header).
     *
     * <p>Note: if the constructor with {@link TiffOpenMode#VALID_TIFF} mode
     * completed successfully, this method is guaranteed to return {@code true}.
     *
     * <p>Note: if this method returns <code>true</code>, the file can be still invalid.
     * If the problem was detected while opening the file in the mode {@link TiffOpenMode#NO_CHECKS},
     * you can know about this by <code>false</code> result of {@link #isValidTiff()} method.
     *
     * @return whether this is a TIFF.
     */
    public boolean isTiff() {
        return tiff;
    }

    /**
     * Returns <code>true</code> if the file is a TIFF file and if the constructor did not detect
     * any problems while opening the file.
     * However, this is not a guarantee that problems
     * (like format errors) will not be found later while reading IFDs or image data.
     *
     * <p>In most cases, this method returns the same value as {@link #isTiff()}.
     * It may return {@code false} while {@link #isTiff()} is {@code true} only
     * if the TIFF header is present but corrupted (e.g., the file is truncated),
     * and the reader was opened in the {@link TiffOpenMode#NO_CHECKS} mode.
     *
     * <p>Note: if the constructor with {@link TiffOpenMode#VALID_TIFF} mode
     * completed successfully, this method is guaranteed to return {@code true}.
     *
     * <p>Note: this method is equivalent to the check <code>{@link #openingException()} == null</code>.
     *
     * @return whether this is a probably correct TIFF/BigTIFF file.
     */
    public boolean isValidTiff() {
        return validTiff;
    }

    /**
     * Returns {@code true} if the file (or directory) exists.
     *
     * <p>Note that file existence is checked without distinguishing between
     * regular files and directories: if there is an existing subdirectory
     * with the name specified by the constructor, this method returns {@code true}.
     *
     * <p>Note: if the constructor was called with {@link TiffOpenMode#VALID_TIFF}
     * or {@link TiffOpenMode#ALLOW_EXISTING_NON_TIFF} mode and
     * completed successfully, this method is guaranteed to return {@code true}.
     *
     * @return whether this file or directory exists.
     */
    public boolean isExistingFile() {
        return existingFile;
    }

    /**
     * Returns whether we are reading little-endian data.
     *
     * @return whether this is a little-endian TIFF.
     */
    public boolean isLittleEndian() {
        return stream.isLittleEndian();
    }

    /**
     * Returns <code>{@link #isLittleEndian()} ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN</code>.
     *
     * @return byte order in the TIFF file.
     */
    public ByteOrder getByteOrder() {
        return isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    }

    /**
     * Returns position in the file of the last IFD offset, loaded by {@link #readIFDOffsets()},
     * {@link #readMainIFDOffset(int)} or {@link #readFirstIFDOffset()} methods.
     * Usually it is just a position of the last IFD offset, because
     * popular {@link #allIFDs()} method calls {@link #readIFDOffsets()} inside.
     *
     * <p>Immediately after creating a new object this position is <code>-1</code>.
     *
     * @return file position of the last IFD offset.
     */
    public long fileOffsetOfLastIFDOffset() {
        return fileOffsetOfLastIFDOffset;
    }

    /**
     * Returns <code>{@link #allIFDs()}.size()</code>.
     *
     * @return number of existing IFDs.
     */
    public int numberOfImages() throws IOException {
        return allIFDs().size();
    }

    /**
     * Returns <code>{@link #allIFDs()}.size()</code>.
     *
     * <p>Note: for maximum usability, this method returns 0 instead of throwing an exception
     * if there are any problems with the input file.
     * But you will get the same exception when any
     * attempt to read IFDs, for example, when calling {@link #allIFDs()}.
     *
     * @return number of existing IFDs.
     */
    public int numberOfImagesUnchecked() {
        try {
            return allIFDs().size();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Returns <code>{@link #mainIFDs()}.size()</code>.
     * Note that this is the length of the array returned by {@link #readIFDOffsets()} method.
     *
     * <p>Note: for maximum usability, this method returns 0 instead of throwing an exception
     * if there are any problems with the input file.
     * But you will get the same exception when any
     * attempt to read IFDs, for example, when calling {@link #mainIFDs()}.
     *
     * @return number of existing IFDs, excluding child IFDs.
     */
    public int numberOfMainIFDs() {
        try {
            return mainIFDs().size();
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Calls {@link #allIFDs()} and returns IFD with the specified index.
     * If <code>ifdIndex</code> is too big (&ge;{@link #numberOfImages()}), this method throws
     * {@link TiffException}.</p>
     *
     * @param ifdIndex index of the TIFF image.
     * @return the IFD with the specified index.
     * @throws TiffException            if <code>ifdIndex</code> is too large,
     *                                  or if the file is not a correct TIFF file,
     *                                  and this was not detected while opening it.
     * @throws IOException              in the case of any problems with the input file.
     * @throws IllegalArgumentException if <code>ifdIndex&lt;0</code>.
     */
    public TiffIFD ifd(int ifdIndex) throws IOException {
        List<TiffIFD> allIFDs = allIFDs();
        if (ifdIndex < 0) {
            throw new IllegalArgumentException("Negative IFD index " + ifdIndex);
        }
        if (ifdIndex >= allIFDs.size()) {
            throw new TiffException("Too large IFD index " + ifdIndex + " >= " + allIFDs.size());
        }
        return allIFDs.get(ifdIndex);
    }

    /**
     * Returns the width of the TIFF image with the specified index.
     * Equivalent to <code>{@link #ifd(int) ifd}(ifdIndex).{@link TiffIFD#getImageDimX() getImageDimX()}</code>.
     *
     * <p>Note that you can get this information and more by creating a new TIFF map with help of the call
     * {@link #map(int) map(ifdIndex)}: the returned {@link TiffMap} object has many methods
     * that allow you to quickly find various information about the TIFF image.</p>
     *
     * @param ifdIndex index of the TIFF image.
     * @return width of this image.
     * @throws IOException in the case of any problems with the input file.
     */
    public int dimX(int ifdIndex) throws IOException {
        return ifd(ifdIndex).getImageDimX();
    }

    /**
     * Returns the height of the TIFF image with the specified index.
     * Equivalent to <code>{@link #ifd(int) ifd}(ifdIndex).{@link TiffIFD#getImageDimY() getImageDimY()}</code>.
     *
     * <p>Note that you can get this information and more by creating a new TIFF map with help of the call
     * {@link #map(int) map(ifdIndex)}: the returned {@link TiffMap} object has many methods
     * that allow you to quickly find various information about the TIFF image.</p>
     *
     * @param ifdIndex index of the TIFF image.
     * @return height of this image.
     * @throws IOException in the case of any problems with the input file.
     */
    public int dimY(int ifdIndex) throws IOException {
        return ifd(ifdIndex).getImageDimY();
    }

    /**
     * Returns the number of channels (samples per pixel) in the TIFF image with the specified index.
     * Equivalent to
     * <code>{@link #ifd(int) ifd}(ifdIndex).{@link TiffIFD#getSamplesPerPixel() getSamplesPerPixel()}</code>.
     *
     * <p>Note that you can get this information and more by creating a new TIFF map with help of the call
     * {@link #map(int) map(ifdIndex)}: the returned {@link TiffMap} object has many methods
     * that allow you to quickly find various information about the TIFF image.</p>
     *
     * @param ifdIndex index of the TIFF image.
     * @return number of channels in this image.
     * @throws IOException in the case of any problems with the input file.
     */
    public int numberOfChannels(int ifdIndex) throws IOException {
        return ifd(ifdIndex).getSamplesPerPixel();
    }

    /**
     * Equivalent to <code>{@link #map(TiffIFD) map}({@link #ifd(int) ifd}(ifdIndex))</code>.
     *
     * @param ifdIndex index of IFD.
     * @return TIFF map, allowing to read this IFD
     * @throws TiffException            if <code>ifdIndex</code> is too large,
     *                                  or if the file is not a correct TIFF file,
     *                                  and this was not detected while opening it.
     * @throws IOException              in the case of any problems with the input file.
     * @throws IllegalArgumentException if <code>ifdIndex&lt;0</code>.
     */
    public TiffReadMap map(int ifdIndex) throws IOException {
        return map(ifd(ifdIndex));
    }

    public List<TiffReadMap> allMaps() throws IOException {
        final List<TiffReadMap> result = new ArrayList<>();
        for (TiffIFD tiffIFD : allIFDs()) {
            result.add(map(tiffIFD));
        }
        return result;
    }

    /**
     * Returns all IFDs in the file in an unmodifiable list.
     * On the first call, this method reads all IFDs from the file,
     * then the result is cached and quickly returned by all further calls.
     * (But caching can be disabled using the {@link #setCachingIFDs(boolean)} method).
     *
     * <p>Note: this method also returns the child sub-IFDs of a regular IFD
     * (they are inserted into the resulting list immediately after the parent IFD).
     * To retrieve only main IFDs without the child ones, please use {@link #mainIFDs()}.</p>
     *
     * <p>Note: this method does not process sub-IFDs recursively
     * (nested sub-IFDs of sub-IFDs are extremely rare in typical TIFF files).</p>
     *
     * <p>Note: this method <i>does not</i> read associated EXIF, GPS, and other possible linked IFDs (if they exist).
     * Thus, even if a TIFF file contains malformed or corrupted EXIF data,
     * it will not throw any exceptions in this method.
     * You can retrieve linked data explicitly using the {@link #exifIFD(TiffIFD)}, {@link #gpsIFD(TiffIFD)},
     * {@link #interoperabilityIFD(TiffIFD)} or common {@link #linkedIFD(TiffIFD, int)} method.</p>
     *
     * <p>Note: if this TIFF file is not valid ({@link #isValidTiff()} returns <code>false</code>), this method
     * returns an empty list and does not throw an exception.
     * For a valid TIFF, the result is never empty.</p>
     *
     * @throws TiffException if the file is not a correct TIFF file, but this was not detected while opening it.
     * @throws IOException   in the case of any problems with the input file.
     * @see #mainIFDs()
     */
    public List<TiffIFD> allIFDs() throws IOException {
        long t1 = debugTime();
        List<TiffIFD> allIFDs;
        synchronized (fileLock()) {
            // - this synchronization is necessary for correct work of resetCache(),
            // and also it helps to be sure that the client will not try to read TIFF images
            // when all IFD are not fully loaded and checked
            allIFDs = this.allIFDs;
            if (cachingIFDs && allIFDs != null) {
                return allIFDs;
            }

            final long[] offsets = readIFDOffsets();
            allIFDs = new ArrayList<>();
            final ArrayList<TiffIFD> mainIFDs = new ArrayList<>();

            for (int i = 0; i < offsets.length; i++) {
                final TiffIFD ifd = readIFD(offsets[i]);
                assert ifd != null;
                ifd.setGlobalIndexes(allIFDs.size(), i);
                allIFDs.add(ifd);
                mainIFDs.add(ifd);
                long[] subOffsets = null;
                try {
                    // Deprecated solution: "fillInIFD" technique is no longer used
                    // if (!cachingIFDs && ifd.containsKey(IFD.SUB_IFD)) {
                    //     fillInIFD(ifd);
                    // }
                    subOffsets = ifd.getLongArray(Tags.SUB_IFD);
                } catch (TiffException ignored) {
                }
                if (subOffsets != null) {
                    for (long subOffset : subOffsets) {
                        final TiffIFD subIFD = readIFD(subOffset, false);
                        subIFD.setSubIFDType(Tags.SUB_IFD);
                        subIFD.setGlobalIndexes(allIFDs.size(), null);
                        allIFDs.add(subIFD);
                    }
                }
            }
            this.allIFDs = Collections.unmodifiableList(allIFDs);
            this.mainIFDs = Collections.unmodifiableList(mainIFDs);
            // note: storing it in any case, regardless caching is enabled or not
        }
        if (BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t2 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s read %d IFDs: %.3f ms",
                    getClass().getSimpleName(), allIFDs.size(),
                    (t2 - t1) * 1e-6));
        }
        return allIFDs;
    }

    public List<TiffIFD> mainIFDs() throws IOException {
        synchronized (fileLock()) {
            allIFDs();
            return Collections.unmodifiableList(mainIFDs);
        }
    }

    /**
     * Reads 1st IFD (#0).
     *
     * <p>Note: this method <i>does not</i> use {@link #allIFDs()} method.
     * If you really need access only to the first IFD,
     * this method may work faster than {@link #ifd(int)}.
     */
    public TiffIFD firstIFD() throws IOException {
        TiffIFD firstIFD = this.firstIFD;
        if (cachingIFDs && firstIFD != null) {
            return this.firstIFD;
        }
        final long offset = readFirstIFDOffset();
        firstIFD = readIFD(offset);
        firstIFD.setGlobalIndex(0);
        if (cachingIFDs) {
            this.firstIFD = firstIFD;
        }
        return firstIFD;
    }


    public Optional<TiffIFD> exifIFD(TiffIFD ifd) throws IOException {
        return linkedIFD(ifd, Tags.EXIF_IFD);
    }

    public Optional<TiffIFD> gpsIFD(TiffIFD ifd) throws IOException {
        return linkedIFD(ifd, Tags.GPS_IFD);
    }

    public Optional<TiffIFD> interoperabilityIFD(TiffIFD ifd) throws IOException {
        return linkedIFD(ifd, Tags.INTEROPERABILITY_IFD);
    }

    public Optional<TiffIFD> linkedIFD(TiffIFD ifd, int linkedIFDTag) throws IOException {
        Objects.requireNonNull(ifd, "Null IFD");
        return ifd.hasTag(linkedIFDTag) ?
                Optional.of(readIFD(ifd.reqLong(linkedIFDTag), false).setSubIFDType(linkedIFDTag)) :
                Optional.empty();
    }

    /**
     * Gets offset to the first IFD.
     * Updates {@link #fileOffsetOfLastIFDOffset()} to the position of first offset (4, for Bit-TIFF 8).
     */
    public long readFirstIFDOffset() throws IOException {
        synchronized (fileLock()) {
            return readFirstIFDOffset(true);
        }
    }

    /**
     * Returns the file offset of the regular IFD with given index
     * or throws an exception if the index is too high.
     * Updates {@link #fileOffsetOfLastIFDOffset()} to position of this offset.
     *
     * <p>This method works only with {@link TiffIFD#isMainIFD() regular IFDs} (main, not sub-IFDs).
     * So, this index must be in the range <code>0..{@link #numberOfMainIFDs()}-1</code>.</p>
     *
     * @param mainIFDIndex index of regular IFD (0, 1, ...).
     * @return offset of this IFD in the file.
     * @throws IllegalArgumentException if the index is negative.
     * @throws TiffException            if the index is too high.
     */
    public long readMainIFDOffset(final int mainIFDIndex) throws IOException {
        long result = tryToReadMainIFDOffset(mainIFDIndex);
        if (result == -1) {
            throw new TiffException("No main IFD #" + mainIFDIndex + " in TIFF" + spacedStreamName()
                    + ": too large index");
        }
        return result;
    }

    /**
     * Equivalent to {@link #readMainIFDOffset(int)} besides that this method returns {@code -1}
     * instead of throwing exception when the IFD index is too high.
     *
     * @param mainIFDIndex index of regular IFD (0, 1, ...).
     * @return offset of this IFD in the file.
     * @throws IllegalArgumentException if the index is negative.
     */
    public long tryToReadMainIFDOffset(final int mainIFDIndex) throws IOException {
        if (mainIFDIndex < 0) {
            throw new IllegalArgumentException("Negative IFD index = " + mainIFDIndex);
        }
        synchronized (fileLock()) {
            final long fileLength = stream.length();
            long offset = readFirstIFDOffset();

            int index = mainIFDIndex;
            while (offset > 0 && offset < fileLength) {
                if (index-- <= 0) {
                    return offset;
                }
                skipIFDEntries(offset, fileLength);
                final long newOffset = readIFDNextOffset(true);
                if (newOffset == offset) {
                    throw new TiffException("TIFF file is broken - infinite loop of IFD offsets is detected " +
                            "for offset " + offset);
                }
                offset = newOffset;
            }
            return -1;
        }
    }

    /**
     * Gets the offsets to every IFD in the file (without child sub-IFDs).
     * The length of the returned array is equal to {@link #numberOfMainIFDs()}.
     *
     * <p>Note: if this TIFF file is not valid ({@link #isValidTiff()} returns <code>false</code>), this method
     * returns an empty array and does not throw an exception.
     * For a valid TIFF, the result cannot be empty.
     */
    public long[] readIFDOffsets() throws IOException {
        synchronized (fileLock()) {
            if (!validTiff) {
                return new long[0];
            }
            final long fileLength = stream.length();
            final LinkedHashSet<Long> ifdOffsets = new LinkedHashSet<>();
            long offset = readFirstIFDOffset();

            while (offset > 0 && offset < fileLength) {
                final boolean wasNotPresent = ifdOffsets.add(offset);
                if (!wasNotPresent) {
                    throw new TiffException("TIFF file is broken - infinite loop of IFD offsets is detected " +
                            "for offset " + offset + " (the stored ifdOffsets sequence is " +
                            ifdOffsets.stream().map(Object::toString).collect(Collectors.joining(", ")) +
                            ", " + offset + ", ...)");
                }
                skipIFDEntries(offset, fileLength);
                offset = readIFDNextOffset(true);
            }
            return ifdOffsets.stream().mapToLong(v -> v).toArray();
        }
    }

    /**
     * Returns the IFD with given index or throws an exception if the index is too high.
     * Updates {@link #fileOffsetOfLastIFDOffset()} to position of this offset.
     *
     * <p>This method works only with {@link TiffIFD#isMainIFD() regular IFDs} (not sub-IFDs).
     * So, this index must be in the range <code>0..{@link #numberOfMainIFDs()}-1</code>.</p>
     *
     * @param mainIFDIndex index of regular IFD (0, 1, ...).
     * @return the selected IFD.
     * @throws IllegalArgumentException if the index is negative.
     * @throws TiffException            if the index is too high.
     */
    public TiffIFD readMainIFD(int mainIFDIndex) throws IOException {
        long startOffset = readMainIFDOffset(mainIFDIndex);
        assert startOffset >= 0;
        // - note: we do not call setIndexInList(mainIFDIndex),
        // becase this index will DIFFER from the index inside the allIFDs() list
        return readIFD(startOffset);
    }

    /**
     * Reads the IFD stored at the given offset.
     * Never returns {@code null}.
     */
    public TiffIFD readIFD(long ifdOffset) throws IOException {
        return readIFD(ifdOffset, true);
    }

    public TiffIFD readIFD(long ifdOffset, boolean readNextOffset) throws IOException {
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
        synchronized (fileLock()) {
            final long tiffFileLength = stream.length();
            final Map<Integer, Object> map = new LinkedHashMap<>();
            final LinkedHashMap<Integer, TiffIFD.Entry> detailedEntries = new LinkedHashMap<>();

            final int numberOfEntries = readNumberOfIFDEntriesAt(ifdOffset);
            final long ifdStreamOffset = ifdOffset + sizeOfNumberOfIFDEntries();

            final int sizeOfEntry = sizeOfIFDEntry();
            final int sizeOfAllEntries = sizeOfEntry * numberOfEntries;
            if (ifdStreamOffset > tiffFileLength - sizeOfAllEntries) {
                throw new TiffException("%d IFD entries at the offset %d exceeds the file length %d".formatted(
                        numberOfEntries, ifdStreamOffset, tiffFileLength));
            }
            final DataHandle<?> ifdStream;
            if (READ_IFD_WITH_BUFFERING) {
                final byte[] ifdBytes = new byte[sizeOfAllEntries];
                stream.readFully(ifdBytes);
                ifdStream = getBytesHandle(ifdBytes, stream.isLittleEndian());
            } else {
                ifdStream = stream;
            }
            for (int i = 0, disp = 0; i < numberOfEntries; i++, disp += sizeOfEntry) {
                long tEntry1 = debugTime();
                final TiffIFD.Entry entry = readIFDEntry(
                        ifdStream,
                        READ_IFD_WITH_BUFFERING ? disp : disp + ifdStreamOffset,
                        READ_IFD_WITH_BUFFERING ? ifdStreamOffset : 0,
                        tiffFileLength);
                final int tag = entry.tag();
                long tEntry2 = debugTime();
                timeEntries += tEntry2 - tEntry1;

                final Object value = readIFDValueAtEntryOffset(
                        READ_IFD_WITH_BUFFERING ? ifdStream : stream,
                        stream,
                        READ_IFD_WITH_BUFFERING && entry.isDataEmbeddedInEntry(),
                        ifdStreamOffset,
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
            final long fileOffsetOfNextIFDOffset = ifdStreamOffset + sizeOfAllEntries;
            stream.seek(fileOffsetOfNextIFDOffset);

            ifd = new TiffIFD(map, detailedEntries);
            ifd.setLoadedFromFile(true);
            ifd.setLittleEndian(stream.isLittleEndian());
            ifd.setBigTiff(bigTiff);
            ifd.setFileOffsetOfIFD(ifdOffset);
            if (readNextOffset) {
                final long nextOffset = readIFDNextOffset(false);
                ifd.setFileOffsetOfNextIFDOffset(fileOffsetOfNextIFDOffset);
                ifd.setNextIFDOffset(nextOffset);
                stream.seek(fileOffsetOfNextIFDOffset);
                // - this "in.seek" provides maximal compatibility with old code (which did not read next IFD offset)
                // and also with the behavior of this method, when readNextOffset is not requested
            }
        }

        if (BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t2 = debugTime();
            LOG.log(System.Logger.Level.TRACE, String.format(Locale.US,
                    "%s read IFD at offset %d: %.3f ms, including %.6f entries + %.6f arrays",
                    getClass().getSimpleName(), ifdOffset,
                    (t2 - t1) * 1e-6, timeEntries * 1e-6, timeArrays * 1e-6));
        }
        return ifd;
    }

    /**
     * Calls {@link #readTile(TiffTileIndex)} with the same argument with caching, if this was enabled
     * by {@link #setCaching(boolean)} method.
     *
     * @param tileIndex coordinates of the tile.
     * @return loaded tile.
     * @throws IOException in the case of any problems with the input file.
     */
    public TiffTile readCachedTile(TiffTileIndex tileIndex) throws IOException {
        if (!caching || maxCacheMemory == 0) {
            return readTile(tileIndex);
        }
        return getCachedTile(tileIndex).readIfNecessary();
    }

    /**
     * Reads and decodes the tile at the specified position.
     * <p>Note: the loaded tile is always {@link TiffTile#isSeparated() separated}.
     * <p>Note: this method does not cache tiles.
     *
     * @param tileIndex coordinates of the tile.
     * @return loaded tile.
     * @throws IOException in the case of any problems with the input file.
     * @see #readCachedTile(TiffTileIndex)
     */
    public TiffTile readTile(TiffTileIndex tileIndex) throws IOException {
        final TiffTile tile = readEncodedTile(tileIndex);
        if (tile.isEmpty()) {
            return tile;
        }
        decode(tile);
        return tile;
    }

    public TiffTile readEncodedTile(TiffTileIndex tileIndex) throws IOException {
        return readEncodedTile(tileIndex, true);
    }

    public TiffTile readEncodedTile(TiffTileIndex tileIndex, boolean resolveDuplicates) throws IOException {
        Objects.requireNonNull(tileIndex, "Null tileIndex");
        long t1 = debugTime();
        final TiffIFD ifd = tileIndex.ifd();
        final int index = tileIndex.linearIndex();
        // - also checks that the tile index is not out of image bounds
        long offset;
        int byteCount;
        int referenceToSource;
        final TiffTile existingTile = tileIndex.existingTile();
        final boolean alreadyStored = existingTile != null && existingTile.isStoredInFile();
        if (alreadyStored) {
            // - can be true when using TiffWriteMap for re-reading already written tiles;
            // without this, we'll read the previously written tile instead of the actual data!
            offset = existingTile.getStoredInFileDataOffset();
            byteCount = existingTile.getStoredInFileDataLength();
            referenceToSource = existingTile.optReferenceToOriginalOfDuplicate().orElse(-1);
            assert offset >= 0 && byteCount >= 0;
        } else {
            offset = ifd.cachedTileOrStripOffset(index);
            assert offset >= 0 : "offset " + offset + " was not checked in TiffIFD";
            byteCount = cachedByteCountWithCompatibilityTrick(ifd, index);
            byteCount = correctZeroByteCount(tileIndex, byteCount, offset);
            referenceToSource = ifd.cachedIndexOfFirstSameOffset(index);
        }

        final TiffTile result = new TiffTile(tileIndex);
        // - No reasons to put it into the map: this class does not provide access to a temporarily created map.

        if (cropTilesToImageBoundaries) {
            result.cropStripToMap();
        }
        // If cropping is disabled, we should not avoid reading extra content of the last strip.
        // Note the last encoded strip can have actually full strip sizes,
        // i.e., larger than necessary; this situation is quite possible.

        if (byteCount == -1) {
            // - possible when the missingTilesAllowed flag is set
            return result;
        }

        synchronized (fileLock()) {
            if (offset >= stream.length()) {
                throw new TiffException("Offset of TIFF tile/strip " + offset + " is out of file length (tile " +
                        tileIndex + ")");
                // - note: old SCIFIO code allowed such offsets and returned zero-filled tile
            }
            if (!resolveDuplicates && referenceToSource != -1) {
                result.freeData();
                result.setReferenceToOriginalOfDuplicate(referenceToSource);
            } else {
                TiffTileIO.readAt(result, stream, offset, byteCount);
                if (alreadyStored) {
                    result.expandStoredInFileDataCapacity(existingTile.getStoredInFileDataCapacity());
                }
            }
        }
        long t2 = debugTime();
        timeReading += t2 - t1;
        return result;
    }

    // Note: the result is usually interleaved (RGBRGB...) or monochrome; it is always so in UNCOMPRESSED, LZW, DEFLATE
    public boolean decode(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        if (!tile.isEncoded()) {
            return false;
        }
        long t1 = debugTime();
        prepareDecoding(tile);

        final byte[] encodedData = tile.getEncodedData();
        final TagCompression compression = tile.compressionOrNoneForMissing().orElse(null);
        // - tile.compressionOrNoneForMissing() returns Optional.of(TagCompression.NONE) if this tag is absent!
        TiffCodec codec = null;
        if (!enforceUseExternalCodec && compression != null) {
            codec = compression.codec();
            // - we are sure that this codec does not require SCIFIO context
        }
        TiffCodec.Options options = buildOptions(tile);
        long t2 = debugTime();

        if (codec != null) {
            // assert compression != null;
            options = compression.customizeReading(tile, options);
            if (USE_LEGACY_UNPACK_BYTES) {
                options.setInterleaved(true);
                // - old-style unpackBytes does not "understand" already-separated tiles
            }
            if (codec instanceof TiffCodec.Timing timing) {
                timing.setTiming(BUILT_IN_TIMING && LOGGABLE_DEBUG);
                timing.resetTiming();
            }
            final byte[] decodedData = codec.decompress(encodedData, options);
            setLastCodecReport(options.getReport());
            tile.setPartiallyDecodedData(decodedData);
            tile.setReport(options.getReport());
        } else {
            final Optional<byte[]> decodedData = decodeByExternalCodec(tile, encodedData, options);
            if (decodedData.isEmpty()) {
                throw new UnsupportedTiffFormatException("TIFF compression with code " +
                        tile.compressionCode() +
                        (tile.compressionOrNoneForMissing().isPresent() ?
                                " (" + tile.compressionOrNoneForMissing().get().prettyName() + ")" :
                                "") +
                        " is not supported and cannot be decoded (even by an external codec)");
            }
            tile.setPartiallyDecodedData(decodedData.get());
        }
        tile.setInterleaved(options.isInterleaved());
        long t3 = debugTime();

        completeDecoding(tile);
        long t4 = debugTime();

        timeCustomizingDecoding += t2 - t1;
        timeDecoding += t3 - t2;
        if (codec instanceof TiffCodec.Timing timing) {
            timeDecodingMain += timing.timeMain();
            timeDecodingBridge += timing.timeBridge();
            timeDecodingAdditional += timing.timeAdditional();
        } else {
            timeDecodingMain += t3 - t2;
        }
        timeCompleteDecoding += t4 - t3;
        return true;
    }

    public void prepareDecoding(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        if (tile.isEmpty()) {
            // - unlike full decoding, here it is better not to throw exception for an empty tile
            return;
        }
        if (tile.ifd().isReversedFillOrder()) {
            PackedBitArraysPer8.reverseBitOrderInPlace(tile.getEncodedData());
        }
        TiffJPEGDecodingHelper.embedJPEGTableInDataIfRequested(tile);
    }

    /**
     * Completes decoding tile after decoding by some {@link TiffCodec}. This method is automatically called
     * at the end of {@link #decode(TiffTile)} method.
     *
     * <p>First, this method always rearranges data in the file: if the codec returned
     * {@link TiffTile#isInterleaved() interleaved} data, this method
     * {@link TiffTile#separateSamplesIfNecessary() separates} them.
     * Interleaved data are the standard for internal pixel storage for simple formats like
     * {@link TagCompression#LZW} and may be returned by complex codecs like {@link TagCompression#JPEG_2000}.
     *
     * <p>This method does 3 other corrections for some standard compression algorithms:
     * <ul>
     * <li>{@link TagCompression#NONE},</li>
     * <li>{@link TagCompression#LZW},</li>
     * <li>{@link TagCompression#DEFLATE},</li>
     * <li>{@link TagCompression#PACK_BITS}.</li>
     * </ul>
     *
     * <p>First correction: unpacking. TIFF supports storing pixel samples in any number of bits,
     * not always divisible by 8; in other words, one pixel sample can occupy a non-integer number of bytes.
     * Most useful in these cases is a 1-bit monochrome picture, where 8 pixels are packed into 1 byte.
     * Sometimes the pictures with 4 bits/pixels (monochrome or with palette) or 3*4=12 bits/pixels (RGB) appear.
     * You can also meet old RGB 32-bit images with 5+5+5 or 5+6+5 bits/channel.</p>
     *
     * <p>However, the API of this class represents any image as a sequence of <i>bytes</i>.
     * This method performs all necessary unpacking so that the result image uses
     * an integer number of bytes per each pixel sample (channel).
     * For example, 1-bit binary image is converted to 8-bit: 0 transformed to 0, 1 to 255;
     * 4-bit grayscale image is converted by multiplying by 17 (so that maximal possible 4-bit value, 15,
     * will become maximal possible 8-bit value 255=17*15); 12-bit RGB image is converted to 16-bit
     * by multiplying each channel by (2<sup>16</sup>&minus;1)/(2<sup>12</sup>&minus;1), etc.</p>
     *
     * <p>Second correction: inversion. Some TIFF files use CMYK color space or WhiteIsZero interpretation of grayscale
     * images, where dark colors are represented by smaller values and bright colors by higher, in particular,
     * 0 is white, maximal value (255 for 8-bit) is black.</p>
     *
     * <p>However, the API of this class suppose that all returned images are RGB, RGBA or usual monochrome.
     * Complex codecs like JPEG perform necessary conversion themselves, but the simple codecs like
     * {@link TagCompression#NONE} do not this correction. This is performed by this method.
     * For CMYK or WhiteIsZero it means inversion of the pixel samples: v is transformed to MAX&minus;v,
     * where MAX is the maximal possible value (255 for 8-bit).</p>
     *
     * <p>Third correction: conversion of YCbCr color space to usual RGB.
     * It is a rare situation when the image is stored as YCbCr,
     * however, not in JPEG format, but uncompressed or, for example, as LZW.
     * This method performs the necessary conversion to RGB (but only if the image is exactly 8-bit).</p>
     *
     * <p>Note: this method never increases the number of <i>bytes</i>,
     * necessary for representing a single pixel sample.
     * It can increase the number of <i>bits</i> per sample, but only to the nearest greater integer number of
     * bytes: 1..7 bits are transformed to 8 bits/sample, 9..15 to 16 bits/sample, 17..23 to 24 bits/sample etc.
     * Thus, this method <b>does not unpack 3-byte samples</b> (to 4-byte) and
     * <b>does not unpack 16- or 24-bit</b> floating-point formats. These cases
     * are processed after reading all tiles inside {@link #readSampleBytes}
     * method, if {@link #getUnusualPrecisions()} mode is {@link UnusualPrecisions#UNPACK},
     * or may be performed by external
     * code with help of {@link TiffUnusualPrecisions#unpackUnusualPrecisions(byte[], TiffIFD, int, long, boolean)}
     * method.
     * See {@link TiffReader#setUnusualPrecisions(UnusualPrecisions)}.
     *
     * <p>This method does not allow 5, 6, 7 or greater than 8 bytes/sample
     * (but 8 bytes/sample is allowed: it is probably <code>double</code> precision).</p>
     *
     * @param tile the tile that should be corrected.
     */
    public void completeDecoding(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        // scifio.tiff().undifference(tile.getDecodedData(), tile.ifd());
        // - this solution requires using SCIFIO context class; it is better to avoid this
        tile.checkDecodedData();
        TiffPrediction.unsubtractPredictionIfRequested(tile);

        if (USE_LEGACY_UNPACK_BYTES) {
            byte[] samples = new byte[tile.map().tileSizeInBytes()];
            // TiffTools.unpackBytesLegacy(samples, 0, tile.getDecodedData(), tile.ifd());
            // - uncomment this to perform debugging
            tile.setDecodedData(samples);
            tile.setInterleaved(false);
        } else {
            if (!TiffUnpacking.separateUnpackedSamples(tile)) {
                if (!TiffUnpacking.separateYCbCrToRGB(tile)) {
                    TiffUnpacking.unpackTiffBitsAndInvertValues(tile, scaleWhenIncreasingBitDepth, colorCorrection);
                }
            }
        }
        tile.checkDataLengthAlignment();
        // - Fully unpacked and separated data must be correctly aligned

        /*
        // The following code is equivalent to the analogous code from SCIFIO 0.46.0 and earlier version,
        // which performed correction of channels with different precision in a rare case PLANAR_CONFIG_SEPARATE.
        // But it is deprecated: we do not support different numbers of BYTES in different samples,
        // and it is checked inside getBytesPerSampleBasedOnBits() method.

        byte[] samples = tile.getDecodedData();
        final TiffTileIndex tileIndex = tile.tileIndex();
        final int tileSizeX = tileIndex.tileSizeX();
        final int tileSizeY = tileIndex.tileSizeY();
        TiffIFD ifd = tile.ifd();
        final int samplesPerPixel = ifd.getSamplesPerPixel();
        final int planarConfig = ifd.getPlanarConfiguration();
        final int bytesPerSample = ifd.getBytesPerSampleBasedOnBits();
        final int effectiveChannels = planarConfig == TiffIFD.PLANAR_CONFIG_SEPARATE ? 1 : samplesPerPixel;
        assert samples.length == ((long) tileSizeX * (long) tileSizeY * bytesPerSample * effectiveChannels);
        if (planarConfig == TiffIFD.PLANAR_CONFIG_SEPARATE && !ifd.isTiled() && ifd.getSamplesPerPixel() > 1) {
            final OnDemandLongArray onDemandOffsets = ifd.getOnDemandStripOffsets();
            final long[] offsets = onDemandOffsets != null ? null : ifd.getStripOffsets();
            final long numberOfStrips = onDemandOffsets != null ? onDemandOffsets.size() : offsets.length;
            final int channel = (int) (tileIndex.y() % numberOfStrips);
            int[] differentBytesPerSample = ifd.getBytesPerSample();
            if (channel < differentBytesPerSample.length) {
                final int realBytes = differentBytesPerSample[channel];
                if (realBytes != bytesPerSample) {
                    // re-pack pixels to account for differing bits per sample
                    final boolean littleEndian = ifd.isLittleEndian();
                    final int[] newSamples = new int[samples.length / bytesPerSample];
                    for (int i = 0; i < newSamples.length; i++) {
                        newSamples[i] = Bytes.toInt(samples, i * realBytes, realBytes,
                                littleEndian);
                    }

                    for (int i = 0; i < newSamples.length; i++) {
                        Bytes.unpack(newSamples[i], samples, i * bytesPerSample, bytesPerSample, littleEndian);
                    }
                }
            }
        }
        tile.setDecodedData(samples);
        */
    }

    public TiffReadMap map(TiffIFD ifd) throws TiffException {
        return map(ifd, true);
    }

    public TiffReadMap map(TiffIFD ifd, boolean builtTileGrid) throws TiffException {
        Objects.requireNonNull(ifd, "Null IFD");
        final TiffReadMap map = new TiffReadMap(this, ifd);
        unusualPrecisions.throwIfDisabled(map);
        if (builtTileGrid) {
            map.buildTileGrid();
        }
        // - building grid is necessary, for example, to perform loops in TiffWriter.copyImage
        this.lastMap = map;
        return map;
    }

    /**
     * Returns a reference to the map, created by last call of {@link #map(TiffIFD)}
     * or {@link #map(int)} methods.
     * Returns <code>null</code> if no maps were created yet or after {@link #close()} method.
     *
     * @return last map, created by this object.
     */
    public TiffReadMap lastMap() {
        return lastMap;
    }

    /**
     * Equivalent to
     * <code>{@link #map(int) map}(ifdIndex).{@link TiffReadMap#readSampleBytes() readSampleBytes()}</code>.
     *
     * @return the samples as a raw byte array.
     * @throws TiffException            if <code>ifdIndex</code> is too large,
     *                                  or if the file is not a correct TIFF file,
     *                                  and this was not detected while opening it.
     * @throws IOException              in the case of any problems with the input file.
     * @throws IllegalArgumentException if <code>ifdIndex&lt;0</code>.
     */
    public byte[] readSampleBytes(int ifdIndex) throws IOException {
        return map(ifdIndex).readSampleBytes();
    }

    public byte[] readSampleBytes(
            TiffIOMap<?> map,
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            UnusualPrecisions unusualPrecisions,
            boolean storeTilesInMap,
            TiffIOMap.TileSupplier tileSupplier)
            throws IOException {
        // Note: this method is implemented here, not in TiffIOMap, to provide additional logging information
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(unusualPrecisions, "Null unusualPrecisions");
        Objects.requireNonNull(tileSupplier, "Null tileSupplier");
        long t1 = debugTime();
        resetTiming();
        TiffMap.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        // - note: we allow this area to be outside the image

        byte[] samples = map.loadSampleBytes(
                fromX, fromY, sizeX, sizeY, unusualPrecisions, storeTilesInMap, tileSupplier);
        final int sizeInBytes = samples.length;
        final long sizeInPixels = (long) sizeX * (long) sizeY;
        // - can be >2^31 for bits

        long t2 = debugTime();
        // Deprecated since 1.4.0: use readInterleavedMatrix instead of this flag
        // boolean interleave = false;
        // if (interleaveResults) {
        //     byte[] newSamples = map.toInterleavedSamples(samples, numberOfChannels, sizeInPixels);
        //     interleave = newSamples != samples;
        //     samples = newSamples;
        // }
        boolean unpackingBits = false;
        if (autoUnpackBits.isEnabled() && map.isBinary()) {
            unpackingBits = true;
            samples = PackedBitArraysPer8.unpackBitsToBytes(
                    samples,
                    0,
                    sizeInPixels,
                    (byte) 0,
                    autoUnpackBits.bit1Value());
        }
        if (BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t3 = debugTime();
            long timeNonClassified = t2 - t1 - (timeReading + timeDecoding);
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s read %dx%dx%d samples (%.3f MB) in %.3f ms = " +
                            "%.3f read/decode " +
                            "(%.3f read; %.3f customize/bit-order, %.3f decode%s, " +
                            "%.3f complete; %.3f tiles->array and other)" +
                            "%s, %.3f MB/s",
                    getClass().getSimpleName(),
                    sizeX, sizeY, map.numberOfChannels(), sizeInBytes / 1048576.0,
                    (t3 - t1) * 1e-6,
                    (t2 - t1) * 1e-6,
                    timeReading * 1e-6,
                    timeCustomizingDecoding * 1e-6,
                    timeDecoding * 1e-6,
                    timeDecoding != timeDecodingMain || timeDecodingBridge + timeDecodingAdditional > 0 ?
                            String.format(Locale.US, " [= %.3f main%s]",
                                    timeDecodingMain * 1e-6,
                                    timeDecodingBridge + timeDecodingAdditional > 0 ?
                                            String.format(Locale.US,
                                                    " + %.3f decode-bridge + %.3f decode-additional",
                                                    timeDecodingBridge * 1e-6,
                                                    timeDecodingAdditional * 1e-6) :
                                    "") : "",
                    timeCompleteDecoding * 1e-6,
                    timeNonClassified * 1e-6,
                    unpackingBits ?
                            String.format(Locale.US, " + %.3f unpacking %d-bit",
                                    (t3 - t2) * 1e-6,
                                    map.alignedBitDepth()) :
                            "",
                    sizeInBytes / 1048576.0 / ((t3 - t1) * 1e-9)));
        }
        return samples;
    }

    public Object readJavaArray(int ifdIndex) throws IOException {
        return map(ifdIndex).readJavaArray();
    }

    public Matrix<UpdatablePArray> readMatrix(int ifdIndex) throws IOException {
        return map(ifdIndex).readMatrix();
    }

    public Matrix<UpdatablePArray> readInterleavedMatrix(int ifdIndex) throws IOException {
        return map(ifdIndex).readInterleavedMatrix();
    }

    public List<Matrix<UpdatablePArray>> readChannels(int ifdIndex) throws IOException {
        return map(ifdIndex).readChannels();
    }

    public BufferedImage readBufferedImage(int ifdIndex) throws IOException {
        return map(ifdIndex).readBufferedImage();
    }

    public final byte[] decompressBySCIFIOCodec(TiffIFD ifd, byte[] encodedData, Object scifioCodecOptions)
            throws TiffException {
        final Object scifio = requireScifio(ifd);
        final int compressionCode = ifd.getCompressionCode();
        final Object compression;
        try {
            compression = SCIFIOBridge.createTiffCompression(compressionCode);
        } catch (InvocationTargetException e) {
            throw new UnsupportedTiffFormatException("TIFF compression code " + compressionCode +
                    " is unknown and is not correctly recognized by the external SCIFIO subsystem", e);
        }
        try {
            return SCIFIOBridge.callDecompress(scifio, compression, encodedData, scifioCodecOptions);
        } catch (InvocationTargetException e) {
            throw new TiffException(e.getMessage(), e.getCause());
        }
    }

    @Override
    public void close() throws IOException {
        lastMap = null;
        super.close();
    }

    @Override
    public String toString() {
        return "TIFF reader";
    }

    protected Optional<byte[]> decodeByExternalCodec(TiffTile tile, byte[] encodedData, TiffCodec.Options options)
            throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(encodedData, "Null encoded data");
        Objects.requireNonNull(options, "Null codec options");
        if (!SCIFIOBridge.isScifioInstalled()) {
            return Optional.empty();
        }
        final Object scifioCodecOptions = options.toSCIFIOStyleOptions(SCIFIOBridge.codecOptionsClass());
        final byte[] decodedData = decompressBySCIFIOCodec(tile.ifd(), encodedData, scifioCodecOptions);
        return Optional.of(decodedData);
    }

    private void resetTiming() {
        timeReading = 0;
        timeCustomizingDecoding = 0;
        timeDecoding = 0;
        timeDecodingMain = 0;
        timeDecodingBridge = 0;
        timeDecodingAdditional = 0;
        timeCompleteDecoding = 0;
    }

    private IOException startReading() {
        synchronized (fileLock()) {
            try {
                this.tiff = false;
                this.bigTiff = false;
                this.validTiff = false;
                this.existingFile = stream.exists();
                if (!existingFile) {
                    return new FileNotFoundException("File not found:" + spacedStreamName());
                }
                testHeader();
                assert this.tiff;
                this.validTiff = true;
                return null;
            } catch (IOException e) {
                return e;
            }
        }
    }

    private void testHeader() throws IOException {
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
        } finally {
            stream.seek(savedOffset);
            // - for maximal compatibility: in old versions, the constructor of this class
            // guaranteed that file position in the input stream will not change
            // (that is illogical, because "little-endian" mode was still changed)
        }
    }

    @SuppressWarnings("RedundantThrows")
    private TiffCodec.Options buildOptions(TiffTile tile) throws TiffException {
        TiffCodec.Options options = this.codecOptions.clone();
        options.setMainOptions(tile);
        // - Note: codecs in SCIFIO did not use the options above, but some new codes like CCITTFaxCodec need them
        options.setIo(this);

        options.setMaxSizeInBytes(tile.getSizeInBytesInsideTIFF());
        // - Note: this may be LESS than the usual number of samples in the tile/strip.
        // Current readEncodedTile() can return full-size tile without cropping
        // (see comments inside that method), but usually it CROPS the last tile/strip.
        // Old SCIFIO code did not detect this situation, in particular, did not distinguish between
        // last and usual strips in stripped image, and its behavior could be described by the following assignment:
        //      final int samplesLength = tile.map().tileSizeInBytes();
        // For many codecs (like DEFLATE or JPEG) this is not important, but at least
        // LZWCodec creates a result array on the base of options.maxSizeInBytes.
        // If it is invalid (too large value), the returned decoded data will be too large,
        // and that is not too good: this class could throw an exception "data may be lost" in further
        // tile.adjustNumberOfPixels() call if it called it without allowDecreasing=true argument.
        return options;
    }

    private CachedTile getCachedTile(TiffTileIndex tileIndex) {
        synchronized (tileCacheLock) {
            CachedTile tile = tileCacheMap.get(tileIndex);
            if (tile == null) {
                tile = new CachedTile(tileIndex);
                tileCacheMap.put(tileIndex, tile);
            }
            return tile;
            // So, we store (without an ability to remove) all CachedTile objects in the cache tileMap.
            // It is not a problem because CachedTile is a very lightweight object.
            // In any case, "this.ifds" already contains a comparable amount of data:
            // strip offsets and strip byte counts for all tiles.
        }
    }

    private static int cachedByteCountWithCompatibilityTrick(TiffIFD ifd, int index) throws TiffException {
        final boolean tiled = ifd.hasTileInformation();
        final int tag = tiled ? Tags.TILE_BYTE_COUNTS : Tags.STRIP_BYTE_COUNTS;
        Object value = ifd.get(tag);
        if (value instanceof long[] byteCounts && byteCounts.length == 1) {
            // - Here we process a rare case of using TiffParser compatibility class:
            // we call TiffParser.getIFD to read this IFD,
            // and this file is BigTIFF (TileByteCounts or StripByteCounts is IFDType.LONG8),
            // and if we set "equal-strip" mode by TiffParser.setAssumeEqualStrips.
            long result = byteCounts[0];
            if (result >= 0 && result < Integer.MAX_VALUE) {
                // In another case, let's call cachedTileOrStripByteCount which will produce the necessary exception.
                return (int) result;
                // Note that this is also possible in a normal situation, when we REALLY have only one tile/strip;
                // in this case, byteCounts[0] is a correct result equal to the result of cachedTileOrStripByteCount.
            }
        }
        return ifd.cachedTileOrStripByteCount(index);
    }

    private int correctZeroByteCount(TiffTileIndex tileIndex, int byteCount, long offset) throws IOException {
        if (byteCount == 0 || offset == 0) {
            if (missingTilesAllowed) {
                return -1;
            }
            final TiffIFD ifd = tileIndex.ifd();
            if (offset > 0 && ifd.cachedTileOrStripByteCountLength() == 1 && ifd.isLastIFD()) {
                // (so, byteCount == 0): a rare case:
                // some TIFF files have only one IFD with one tile with zero StripByteCounts,
                // that means that we must use all space in the file
                final long left = stream.length() - offset;
                if (left <= Math.min(Integer.MAX_VALUE, 2L * tileIndex.map().tileSizeInBytes() + 1000L)) {
                    // - Additional check that we are really not too far from the file end
                    // (it is improbable that a compressed tile requires > 2*N+1000 bytes,
                    // where N is the length of unpacked tile in bytes).
                    byteCount = (int) left;
                }
            }
        }
        if (byteCount == 0 || offset == 0) {
            throw new TiffException("Zero tile/strip " + (byteCount == 0 ? "byte-count" : "offset")
                    + " is not allowed in a valid TIFF file (tile " + tileIndex + ")");
        }
        return byteCount;
    }

    public void checkFirstOffset() throws IOException {
        synchronized (fileLock()) {
            final long savedOffset = stream.offset();
            try {
                readFirstIFDOffset(false);
            } finally {
                stream.seek(savedOffset);
            }
        }
    }

    private long readFirstIFDOffset(boolean updateFileOffsetOfLastOffset) throws IOException {
        stream.seek(fileOffsetOfFirstIFDOffset());
        final long offset = readIFDNextOffset(updateFileOffsetOfLastOffset);
        if (offset == 0) {
            throw new TiffException("Uncompleted TIFF" + spacedStreamName() +
                    ": the file does not contain any images; " +
                    "probably the TIFF writing process was not completed normally");
        }
        return offset;
    }

    /**
     * Reads a file offset.
     * For BigTIFF files, a 64-bit number is read.
     * For other Tiffs, a 32-bit number is read and possibly adjusted for a possible carry-over
     * from the previous offset.
     */
    private long readIFDNextOffset(boolean updateFileOffsetOfLastOffset) throws IOException {
        final long fileOffsetOfNextOffset = stream.offset();
        long offset = readOffset(stream, bigTiff, 0, stream.length(), this::streamName);
        if (updateFileOffsetOfLastOffset) {
            this.fileOffsetOfLastIFDOffset = fileOffsetOfNextOffset;
        }
        return offset;
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

    private static DataHandle<?> checkNonNull(DataHandle<?> inputStream, TiffOpenMode openMode) {
        Objects.requireNonNull(inputStream, "Null input stream");
        Objects.requireNonNull(openMode, "Null open mode");
        return inputStream;
    }

    class CachedTile {
        private final TiffTileIndex tileIndex;

        private final Object onlyThisTileLock = new Object();
        private Reference<TiffTile> cachedTile = null;
        // - we use SoftReference to be on the safe side in addition to our own memory control
        private long cachedDataLength;

        CachedTile(TiffTileIndex tileIndex) {
            this.tileIndex = Objects.requireNonNull(tileIndex, "Null tileIndex");
        }

        TiffTile readIfNecessary() throws IOException {
            synchronized (onlyThisTileLock) {
                final TiffTile cachedData = cached();
                if (cachedData != null) {
                    LOG.log(System.Logger.Level.TRACE, () -> "CACHED tile: " + tileIndex);
                    return cachedData;
                } else {
                    final TiffTile result = readTile(tileIndex);
                    saveCache(result);
                    return result;
                }
            }
        }

        private TiffTile cached() {
            synchronized (tileCacheLock) {
                if (cachedTile == null) {
                    return null;
                }
                final TiffTile tile = cachedTile.get();
                if (tile == null) {
                    LOG.log(System.Logger.Level.DEBUG,
                            () -> "CACHED tile is freed by garbage collector due to " +
                                    "insufficiency of memory: " + tileIndex);
                }
                return tile;
            }
        }

        private void saveCache(TiffTile tile) {
            Objects.requireNonNull(tile);
            synchronized (tileCacheLock) {
                if (caching && maxCacheMemory > 0) {
                    this.cachedTile = new SoftReference<>(tile);
                    this.cachedDataLength = tile.getDecodedDataLength();
                    currentCacheMemory += this.cachedDataLength;
                    tileCache.add(this);
                    LOG.log(System.Logger.Level.TRACE, () -> "STORING tile in cache: " + tileIndex);
                    while (currentCacheMemory > maxCacheMemory) {
                        final CachedTile cached = tileCache.remove();
                        assert cached != null;
                        currentCacheMemory -= cached.cachedDataLength;
                        cached.cachedTile = null;
                        Runtime runtime = Runtime.getRuntime();
                        LOG.log(System.Logger.Level.TRACE, () -> String.format(Locale.US,
                                "REMOVING tile from cache (limit %.1f MB exceeded, used memory %.1f MB): %s",
                                maxCacheMemory / 1048576.0,
                                (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0,
                                cached.tileIndex));
                    }
                }
            }
        }
    }
}
