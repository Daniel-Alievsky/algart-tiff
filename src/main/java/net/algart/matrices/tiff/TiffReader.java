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

package net.algart.matrices.tiff;

import net.algart.arrays.*;
import net.algart.io.awt.MatrixToImage;
import net.algart.matrices.tiff.codecs.TiffCodec;
import net.algart.matrices.tiff.data.TiffJPEGDecodingHelper;
import net.algart.matrices.tiff.data.TiffPrediction;
import net.algart.matrices.tiff.data.TiffUnpacking;
import net.algart.matrices.tiff.data.TiffUnusualPrecisions;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagRational;
import net.algart.matrices.tiff.tags.TagTypes;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.*;
import org.scijava.Context;
import org.scijava.io.handle.BytesHandle;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.FileHandle;
import org.scijava.io.handle.ReadBufferDataHandle;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.FileLocation;
import org.scijava.io.location.Location;

import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Reads TIFF format.
 *
 * <p>This object is internally synchronized and thread-safe when used in multi-threaded environment.
 * However, you should not modify objects, passed to the methods of this class from a parallel thread;
 * first of all, it concerns the {@link TiffIFD} arguments of many methods.
 * The same is true for the result of {@link #stream()} method.</p>
 */
public class TiffReader implements Closeable {
    public static final long DEFAULT_MAX_CACHING_MEMORY = Math.max(0,
            net.algart.arrays.Arrays.SystemSettings.getLongProperty(
                    "net.algart.matrices.tiff.defaultMaxCachingMemory", 256 * 1048576L));
    // - 256 MB maximal cache by default


    /**
     * IFD with number of entries, greater than this limit, is not allowed:
     * it is mostly probable that it is a corrupted file.
     */
    public static final int MAX_NUMBER_OF_IFD_ENTRIES = 1_000_000;
    /**
     * The number of bytes in each IFD entry.
     */
    public static final int BYTES_PER_ENTRY = 12;
    /**
     * The number of bytes in each IFD entry of a BigTIFF file.
     */
    public static final int BIG_TIFF_BYTES_PER_ENTRY = 20;
    // TIFF header constants
    public static final int FILE_USUAL_MAGIC_NUMBER = 42;
    public static final int FILE_BIG_TIFF_MAGIC_NUMBER = 43;
    public static final int FILE_PREFIX_LITTLE_ENDIAN = 0x49;
    public static final int FILE_PREFIX_BIG_ENDIAN = 0x4d;

    private static final boolean OPTIMIZE_READING_IFD_ARRAYS = true;
    // - Note: this optimization allows speeding up reading a large array of offsets.
    // If we use simple FileHandle for reading files (based on RandomAccessFile),
    // acceleration is up to 100 and more times:
    // on my computer, 23220 int32 values were loaded in 0.2 ms instead of 570 ms.
    // Since scijava-common 2.95.1, we use optimized ReadBufferDataHandle for reading file;
    // now acceleration for 23220 int32 values is 0.2 ms instead of 0.4 ms.

    static final boolean USE_LEGACY_UNPACK_BYTES = false;
    // - Should be false for better performance; necessary for debugging needs only
    // (together with uncommenting unpackBytesLegacy call)

    static final boolean BUILT_IN_TIMING = getBooleanProperty("net.algart.matrices.tiff.timing");

    private static final int MINIMAL_ALLOWED_TIFF_FILE_LENGTH = 8 + 2 + 12 + 4;
    // - 8 bytes header + at least 1 IFD entry (usually at least 2 entries required: ImageWidth + ImageLength);
    // this constant should be > 16 to detect a "fake" BigTIFF file, containing header only

    private static final System.Logger LOG = System.getLogger(TiffReader.class.getName());
    private static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);

    private boolean requireValidTiff;
    private boolean caching = false;
    private long maxCachingMemory = DEFAULT_MAX_CACHING_MEMORY;
    private boolean interleaveResults = false;
    private boolean autoUnpackBitsToBytes = false;
    private boolean autoUnpackUnusualPrecisions = true;
    private boolean autoScaleWhenIncreasingBitDepth = true;
    private boolean autoCorrectInvertedBrightness = false;
    private boolean enforceUseExternalCodec = false;
    private boolean cropTilesToImageBoundaries = true;
    private boolean cachingIFDs = true;
    private boolean missingTilesAllowed = false;
    private byte byteFiller = 0;
    private volatile Context context = null;

    private final Exception openingException;
    private final DataHandle<Location> in;
    private final boolean valid;
    private final boolean bigTiff;

    /**
     * Cached list of IFDs in the current file.
     */
    private volatile List<TiffIFD> ifds;

    /**
     * Cached first IFD in the current file.
     */
    private volatile TiffIFD firstIFD;
    private volatile Object scifio = null;

    private final Object fileLock = new Object();

    private TiffCodec.Options codecOptions = new TiffCodec.Options();

    private volatile long positionOfLastIFDOffset = -1;

    private final Map<TiffTileIndex, CachedTile> tileCacheMap = new HashMap<>();
    private final Queue<CachedTile> tileCache = new LinkedList<>();
    private long currentCacheMemory = 0;
    private final Object tileCacheLock = new Object();

    private volatile TiffMapForReading lastMap = null;

    private long timeReading = 0;
    private long timeCustomizingDecoding = 0;
    private long timeDecoding = 0;
    private long timeDecodingMain = 0;
    private long timeDecodingBridge = 0;
    private long timeDecodingAdditional = 0;
    private long timeCompleteDecoding = 0;

    public TiffReader(Path file) throws IOException {
        this(file, true);
    }

    public TiffReader(Path file, boolean requireValidTiff) throws IOException {
        this(getExistingFileHandle(file), requireValidTiff, true);
    }

    /**
     * Equivalent to {@link #TiffReader(DataHandle, boolean, boolean)} with <code>false</code> last argument.
     * Note that you <b>should not</b> call this constructor from another constructor, creating this
     * <code>DataHandle</code>: in this case, the handle will never be closed!
     *
     * @param inputStream      input stream; automatically replaced (wrapped) with {@link ReadBufferDataHandle},
     *                         if this stream is still not an instance of this class.
     * @param requireValidTiff whether the input file must exist and be a readable TIFF-file
     *                         with a correct header.
     * @throws TiffException if the file is not a correct TIFF file
     * @throws IOException   in the case of any problems with the input file
     */
    public TiffReader(DataHandle<Location> inputStream, boolean requireValidTiff) throws IOException {
        this(inputStream, requireValidTiff, false);
    }

    /**
     * Constructs new reader.
     *
     * <p>If <code>requireValidTiff</code> is <code>true</code> (standard variant), it will throw an exception
     * in the case of incorrect TIFF header or some other I/O errors.
     * In this case, <code>closeStreamOnException</code> flag specifies, whether this function
     * must close the input stream or no. It <b>should</b> be true when you call this constructor
     * from another constructor, which creates <code>DataHandle</code>: it is the only way to close
     * an invalid file. In other situation this flag may be <code>false</code>, then you must close
     * the input stream yourself.
     *
     * <p>If <code>requireValidTiff</code> is <code>false</code>, all exceptions are caught and not thrown,
     * and {@link #isValid()} method will return <code>false</code>.
     * In this case, you can know the occurred exception by {@link #openingException()} method.
     *
     * @param inputStream            input stream; automatically replaced (wrapped) with {@link ReadBufferDataHandle},
     *                               if this stream is still not an instance of this class.
     * @param requireValidTiff       whether the input file must exist and be a readable TIFF-file
     *                               with a correct header.
     * @param closeStreamOnException if <code>true</code>, the input stream is closed in the case of any exception;
     *                               ignored if <code>requireValidTiff</code> is <code>false</code>.
     * @throws TiffException if the file is not a correct TIFF file.
     * @throws IOException   in the case of any problems with the input file.
     */
    public TiffReader(DataHandle<Location> inputStream, boolean requireValidTiff, boolean closeStreamOnException)
            throws IOException {
        this(inputStream, null);
        this.requireValidTiff = requireValidTiff;
        if (requireValidTiff && openingException != null) {
            if (closeStreamOnException) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {
                }
            }
            if (openingException instanceof IOException e) {
                throw e;
            }
            if (openingException instanceof RuntimeException e) {
                throw e;
            }
            throw new TiffException(openingException);
        }
    }

    /**
     * Universal constructor, called from other constructors.
     *
     * <p>Unlike other constructors, this one never throws an exception.
     * This is helpful because it allows
     * making constructors in subclasses, which do not declare any exceptions to be thrown.
     *
     * <p>If the file is not a correct TIFF or in the case of any other I/O problem,
     * the information about the problem is stored in an exception, which can be retrieved later
     * by {@link #openingException()} method and which is passed to <code>exceptionHandler</code>
     * (if it is not {@code null}).
     *
     * @param inputStream      input stream; automatically replaced (wrapped) with {@link ReadBufferDataHandle},
     *                         if this stream is still not an instance of this class.
     * @param exceptionHandler if not {@code null}, it will be called in the case of some checked exception;
     *                         for example, it may log it. But usually it is better idea to use the main
     *                         constructor {@link #TiffReader(DataHandle, boolean, boolean)} with catching exception.
     */
    public TiffReader(DataHandle<Location> inputStream, Consumer<Exception> exceptionHandler) {
        Objects.requireNonNull(inputStream, "Null in stream");
        this.requireValidTiff = false;
        this.in = inputStream instanceof ReadBufferDataHandle ?
                inputStream :
                new ReadBufferDataHandle<>(inputStream);
        AtomicBoolean bigTiff = new AtomicBoolean(false);
        this.openingException = startReading(bigTiff);
        this.valid = openingException == null;
        this.bigTiff = bigTiff.get();
        if (exceptionHandler != null) {
            exceptionHandler.accept(openingException);
        }
    }

    public boolean isRequireValidTiff() {
        return requireValidTiff;
    }

    /**
     * Sets whether the parser should always require a file with the valid TIFF format.
     * Default value is specified in the constructor or is <code>false</code>
     * when using a constructor without such argument.
     *
     * @param requireValidTiff whether a TIFF file should be correct.
     * @return a reference to this object.
     */
    public TiffReader setRequireValidTiff(boolean requireValidTiff) {
        this.requireValidTiff = requireValidTiff;
        return this;
    }

    public boolean isCaching() {
        return caching;
    }

    /**
     * Enables or disables caching tile. If caching is enabled, {@link #readCachedTile(TiffTileIndex)} method
     * works very quickly when the tile is found in the cache.
     *
     * <p>By default, the caching is disabled.</p>
     *
     * @param caching whether reading data from the fill should be cached.
     * @return a reference to this object.
     */
    public TiffReader setCaching(boolean caching) {
        this.caching = caching;
        return this;
    }

    public long getMaxCachingMemory() {
        return maxCachingMemory;
    }

    public TiffReader setMaxCachingMemory(long maxCachingMemory) {
        if (maxCachingMemory < 0) {
            throw new IllegalArgumentException("Negative maxCachingMemory = " + maxCachingMemory);
        }
        this.maxCachingMemory = maxCachingMemory;
        return this;
    }

    public boolean isInterleaveResults() {
        return interleaveResults;
    }

    /**
     * Sets the interleaving mode: the loaded samples will be returned in chunked form, for example, RGBRGBRGB...
     * in the case of RGB image. If not set (default behavior), the samples are returned in unpacked form:
     * RRR...GGG...BBB...
     *
     * <p>Note that this mode affects only the data returned by methods {@link #readMatrix} and other methods,
     * reading an image from TIFF file. The tiles, returned by {@link #readTile(TiffTileIndex)} method,
     * are always {@link TiffTile#isSeparated() separated}.</p>
     *
     * @param interleaveResults new interleaving mode.
     * @return a reference to this object.
     */
    public TiffReader setInterleaveResults(boolean interleaveResults) {
        this.interleaveResults = interleaveResults;
        return this;
    }

    public boolean isAutoUnpackBitsToBytes() {
        return autoUnpackBitsToBytes;
    }

    /**
     * Sets the flag, whether do we need to unpack binary images (one bit/pixel, black-and-white images)
     * into <code>byte</code> matrices: black pixels to the value 0, white pixels to value 255.
     *
     * <p>By default this flag is cleared. In this case, {@link #readMatrix(TiffMapForReading)}
     * and similar methods return binary AlgART matrices.</p>
     *
     * <p>Note that TIFF images, using <i>m</i>&gt;1 bit per pixel where <i>m</i> is not divisible by 8,
     * for example 4-bit indexed images with a palette or 15-bit RGB image, 5+5+5 bits/channel,
     * are always unpacked to format with an integer number of bytes per channel (<i>m</i>=8*<i>k</i>).
     * The only exception is 1-bit monochrome images: in this case, unpacking into bytes depends
     * is controlled by this method.</p>
     *
     * @param autoUnpackBitsToBytes whether do we need to unpack bit matrices to byte ones (0->0, 1->255).
     * @return a reference to this object.
     */
    public TiffReader setAutoUnpackBitsToBytes(boolean autoUnpackBitsToBytes) {
        this.autoUnpackBitsToBytes = autoUnpackBitsToBytes;
        return this;
    }

    public boolean isAutoUnpackUnusualPrecisions() {
        return autoUnpackUnusualPrecisions;
    }

    /**
     * Sets the flag, whether do we need to automatically unpack precisions (bits/sample), differ than all precisions
     * supported by {@link TiffSampleType} class. These are 3-byte samples (17..24 bits/sample;
     * this flag enforces unpacking them to 32 bit) and 16- or 24-bit floating-point formats
     * (this flag enforces unpacking them to 32 bit {@code float} values).
     *
     * <p>This flag is used after reading all tiles inside {@link #readSamples(TiffMapForReading, int, int, int, int)}
     * method. Note that the data in {@link TiffTile}, in the case of unusual precision, are never unpacked.
     * On the other hand, all other precisions, like 4-bit or 12-bit (but not 1-channel 1-bit case),
     * are always unpacked into the nearest bit depth divided by 8 while decoding tiles.</p>
     *
     * <p>If this flag is {@code false}, you cannot used high-level reading methods as
     * {@link #readMatrix} and {@link #readJavaArray}; you must use {@link #readSamples} methods,
     * which return pixels as a sequences of bytes.</p>
     *
     * <p>This flag is {@code true} by default. Usually there are not reasons to set it to {@code false},
     * besides compatibility reasons or requirement to maximally save memory while processing 16/24-bit
     * float values.</p>
     *
     * @param autoUnpackUnusualPrecisions whether do we need to unpack unusual precisions.
     * @return a reference to this object.
     * @see #completeDecoding(TiffTile)
     * @see TiffMap#bitsPerUnpackedSample()
     */
    public TiffReader setAutoUnpackUnusualPrecisions(boolean autoUnpackUnusualPrecisions) {
        this.autoUnpackUnusualPrecisions = autoUnpackUnusualPrecisions;
        return this;
    }

    public boolean isAutoScaleWhenIncreasingBitDepth() {
        return autoScaleWhenIncreasingBitDepth;
    }

    /**
     * Sets the flag, whether do we need to scale pixel sample values when automatic increasing bit depths,
     * for example, when we decode 12-bit grayscale image into 16-bit result.
     *
     * <p>This class can successfully read TIFF with bit depths not divided by 8, such as 4-bit, 12-bit images or
     * 5+5+5 "HiRes" RGB images. But the data returned by this class is always represented by 8-bit, 16-bit,
     * 32-bit integer values (signed or unsigned) or by 32- or 64-bit floating-point values
     * (these bit depths correspond to Java primitive types). If the source pixel values have another bit depth,
     * they are automatically converted to the nearest "larger" type, for example, 4-bit integer is converted
     * to 8-bit, 12-bit integer is converted to 16-bit, 24-bit to 32-bit.</p>
     *
     * <p>If this flag is <code>false</code>, this conversion is performed "as-is", so, values 0..15 in 4-bit source
     * data will be converted to the same values 0..15 with 8-bit precision.
     * This is good if you need to process these values using some kind of algorithm.
     * However, if you need to show the real picture to the end user, then values 0..15 with 8-bit
     * precisions (or 0..4095 with 16-bit precision) will look almost black. To avoid this,
     * you can use <code>true</code>
     * value of this flag, which causes automatic scaling returned values: multiplying by
     * (2<sup><i>n</i></sup>&minus;1)/(2<sup><i>k</i></sup>&minus;1), where <i>n</i> is the result bit depth
     * and <i>k</i> is the source one (for example, for 12-bit image <i>k</i>=12 and <i>n</i>=16).
     * As the result, the returned picture will look alike the source one.</p>
     *
     * <p>Default value is <code>true</code>. However, the scaling is still not performed if
     * PhotometricInterpretation TIFF tag is "Palette" (3) or "Transparency Mask" (4): in these cases,
     * scaling has no sense.
     *
     * @param autoScaleWhenIncreasingBitDepth whether do we need to scale pixel samples, represented with <i>k</i>
     *                                        bits/sample, <i>k</i>%8&nbsp;&ne;&nbsp;0, when increasing bit depth
     *                                        to nearest <i>n</i> bits/sample, where
     *                                        <i>n</i>&nbsp;&gt;&nbsp;<i>k</i> and <i>n</i> is divided by 8.
     * @return a reference to this object.
     */
    public TiffReader setAutoScaleWhenIncreasingBitDepth(boolean autoScaleWhenIncreasingBitDepth) {
        this.autoScaleWhenIncreasingBitDepth = autoScaleWhenIncreasingBitDepth;
        return this;
    }

    public boolean isAutoCorrectInvertedBrightness() {
        return autoCorrectInvertedBrightness;
    }

    /**
     * Sets the flag, whether do we need to automatically correct (invert) pixel sample values in color spaces
     * with inverted sense of pixel brightness, i.e., when PhotometricInterpretation TIFF tag is "WhiteIsZero" (0)
     * or "Separated" (CMYK, 5). (In these color spaces, white color is encoded as zero, and black color is encoded
     * as maximal allowed value like 255 for 8-bit samples.)
     * Note that this flag <b>do not provide</b> correct processing CMYK color model
     * and absolutely useless for more complex color spaces like CIELAB, but for PhotometricInterpretation=0 or 5
     * it helps to provide RGB results more similar to the correct picture.
     *
     * <p>Default value is <code>false</code>. You may set it to <code>true</code> if the only goal of reading TIFF
     * is to show the image to a user.
     *
     * @param autoCorrectInvertedBrightness whether do we need to invert samples for "WhiteIsZero" and "CMYK"
     *                                      photometric interpretations.
     * @return a reference to this object.
     */
    public TiffReader setAutoCorrectInvertedBrightness(boolean autoCorrectInvertedBrightness) {
        this.autoCorrectInvertedBrightness = autoCorrectInvertedBrightness;
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
    public TiffReader setCachingIFDs(final boolean cachingIFDs) {
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

    public Context getContext() {
        return context;
    }

    public TiffReader setContext(Context context) {
        this.scifio = null;
        this.context = context;
        return this;
    }

    /**
     * Gets the stream from which TIFF data is being parsed.
     */
    public DataHandle<Location> stream() {
        synchronized (fileLock) {
            // - we prefer not to return this stream in the middle of I/O operations
            return in;
        }
    }

    public boolean isValid() {
        return valid;
    }

    public Exception openingException() {
        return openingException;
    }

    /**
     * Returns whether the current TIFF file contains BigTIFF data.
     */
    public boolean isBigTiff() {
        return bigTiff;
    }

    /**
     * Returns whether we are reading little-endian data.
     */
    public boolean isLittleEndian() {
        return in.isLittleEndian();
    }

    /**
     * Returns <code>{@link #isLittleEndian()} ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN</code>.
     */
    public ByteOrder getByteOrder() {
        return isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    }

    /**
     * Returns position in the file of the last IFD offset, loaded by {@link #readIFDOffsets()},
     * {@link #readSingleIFDOffset(int)} or {@link #readFirstIFDOffset()} methods.
     * Usually it is just a position of the last IFD offset, because
     * popular {@link #allIFDs()} method calls {@link #readIFDOffsets()} inside.
     *
     * <p>Immediately after creating a new object this position is <code>-1</code>.
     *
     * @return file position of the last IFD offset.
     */
    public long positionOfLastIFDOffset() {
        return positionOfLastIFDOffset;
    }

    /**
     * Returns <code>{@link #allIFDs()}.size()</code>.
     *
     * @return number of existing IFDs.
     * @throws TiffException if the file is not a correct TIFF file.
     * @throws IOException   in the case of any problems with the input file.
     */
    public int numberOfImages() throws IOException {
        return allIFDs().size();
    }

    /**
     * Equivalent to <code>{@link #newMap(TiffIFD) newMap}({@link #ifd(int) ifd}(ifdIndex))</code>.
     *
     * @param ifdIndex index of IFD.
     * @return TIFF map, allowing to read this IFD
     * @throws TiffException            if <code>ifdIndex</code> is too large,
     *                                  or if the file is not a correct TIFF file,
     *                                  and this was not detected while opening it.
     * @throws IOException              in the case of any problems with the input file.
     * @throws IllegalArgumentException if <code>ifdIndex&lt;0</code>.
     */
    public TiffMapForReading newMap(int ifdIndex) throws IOException {
        return newMap(ifd(ifdIndex));
    }

    /**
     * Calls {@link #allIFDs()} and returns IFD with the specified index.
     * If <code>ifdIndex</code> is too big (&ge;{@link #numberOfImages()}), this method throws
     * {@link TiffException}.</p>
     *
     * @param ifdIndex index of IFD.
     * @return the IFD with the specified index.
     * @throws TiffException            if <code>ifdIndex</code> is too large,
     *                                  or if the file is not a correct TIFF file,
     *                                  and this was not detected while opening it.
     * @throws IOException              in the case of any problems with the input file.
     * @throws IllegalArgumentException if <code>ifdIndex&lt;0</code>.
     */
    public TiffIFD ifd(int ifdIndex) throws IOException {
        List<TiffIFD> ifdList = allIFDs();
        if (ifdIndex < 0) {
            throw new IllegalArgumentException("Negative IFD index " + ifdIndex);
        }
        if (ifdIndex >= ifdList.size()) {
            throw new TiffException(
                    "IFD index " + ifdIndex + " is out of bounds 0 <= index < " + ifdList.size());
        }
        return ifdList.get(ifdIndex);
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
        firstIFD = readIFDAt(offset);
        if (cachingIFDs) {
            this.firstIFD = firstIFD;
        }
        return firstIFD;
    }


    public List<TiffMapForReading> allMaps() throws IOException {
        return allIFDs().stream().map(this::newMap).toList();
    }

    /**
     * Returns all IFDs in the file in unmodifiable list.
     * When first called, reads all IFD from the file
     * (but this can be disabled using {@link #setCachingIFDs(boolean)} method).
     *
     * <p>Note: if this TIFF file is not valid ({@link #isValid()} returns <code>false</code>), this method
     * returns an empty list and does not throw an exception.
     * For a valid TIFF, the result cannot be empty.
     *
     * @throws TiffException if the file is not a correct TIFF file, but this was not detected while opening it.
     * @throws IOException   in the case of any problems with the input file.
     */
    public List<TiffIFD> allIFDs() throws IOException {
        long t1 = debugTime();
        List<TiffIFD> ifds;
        synchronized (fileLock) {
            // - this synchronization is not necessary, but helps
            // to be sure that the client will not try to read TIFF images
            // when all IFD are not fully loaded and checked
            ifds = this.ifds;
            if (cachingIFDs && ifds != null) {
                return ifds;
            }

            final long[] offsets = readIFDOffsets();
            ifds = new ArrayList<>();

            for (final long offset : offsets) {
                final TiffIFD ifd = readIFDAt(offset);
                assert ifd != null;
                ifds.add(ifd);
                long[] subOffsets = null;
                try {
                    // Deprecated solution: "fillInIFD" technique is no longer used
                    // if (!cachingIFDs && ifd.containsKey(IFD.SUB_IFD)) {
                    //     fillInIFD(ifd);
                    // }
                    subOffsets = ifd.getLongArray(Tags.SUB_IFD);
                } catch (final TiffException ignored) {
                }
                if (subOffsets != null) {
                    for (final long subOffset : subOffsets) {
                        final TiffIFD sub = readIFDAt(subOffset, Tags.SUB_IFD, false);
                        if (sub != null) {
                            ifds.add(sub);
                        }
                    }
                }
            }
            if (cachingIFDs) {
                this.ifds = Collections.unmodifiableList(ifds);
            }
        }
        if (BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t2 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s read %d IFDs: %.3f ms",
                    getClass().getSimpleName(), ifds.size(),
                    (t2 - t1) * 1e-6));
        }
        return ifds;
    }

    /**
     * Returns EXIF IFDs.
     */
    public List<TiffIFD> exifIFDs() throws IOException {
        final List<TiffIFD> ifds = allIFDs();
        final List<TiffIFD> result = new ArrayList<>();
        for (final TiffIFD ifd : ifds) {
            final long offset = ifd.getLong(Tags.EXIF, 0);
            if (offset != 0) {
                final TiffIFD exifIFD = readIFDAt(offset, Tags.EXIF, false);
                if (exifIFD != null) {
                    result.add(exifIFD);
                }
            }
        }
        return result;
    }

    /**
     * Gets offset to the first IFD.
     * Updates {@link #positionOfLastIFDOffset()} to the position of first offset (4, for Bit-TIFF 8).
     */
    public long readFirstIFDOffset() throws IOException {
        synchronized (fileLock) {
            in.seek(bigTiff ? 8 : 4);
            return readFirstOffsetFromCurrentPosition(true, this.bigTiff);
        }
    }

    /**
     * Returns the file offset of IFD with given index or <code>-1</code> if the index is too high.
     * Updates {@link #positionOfLastIFDOffset()} to position of this offset.
     *
     * @param ifdIndex index of IFD (0, 1, ...).
     * @return offset of this IFD in the file or <code>-1</code> if the index is too high.
     */
    public long readSingleIFDOffset(int ifdIndex) throws IOException {
        if (ifdIndex < 0) {
            throw new IllegalArgumentException("Negative ifdIndex = " + ifdIndex);
        }
        synchronized (fileLock) {
            final long fileLength = in.length();
            long offset = readFirstIFDOffset();

            while (offset > 0 && offset < fileLength) {
                if (ifdIndex-- <= 0) {
                    return offset;
                }
                in.seek(offset);
                skipIFDEntries(fileLength);
                final long newOffset = readNextOffset(true);
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
     * Gets the offsets to every IFD in the file.
     *
     * <p>Note: if this TIFF file is not valid ({@link #isValid()} returns <code>false</code>), this method
     * returns an empty array and does not throw an exception.
     * For a valid TIFF, the result cannot be empty.
     */
    public long[] readIFDOffsets() throws IOException {
        synchronized (fileLock) {
            if (!requireValidTiff && !valid) {
                return new long[0];
            }
            final long fileLength = in.length();
            final LinkedHashSet<Long> ifdOffsets = new LinkedHashSet<>();
            long offset = readFirstIFDOffset();

            while (offset > 0 && offset < fileLength) {
                in.seek(offset);
                final boolean wasNotPresent = ifdOffsets.add(offset);
                if (!wasNotPresent) {
                    throw new TiffException("TIFF file is broken - infinite loop of IFD offsets is detected " +
                            "for offset " + offset + " (the stored ifdOffsets sequence is " +
                            ifdOffsets.stream().map(Object::toString).collect(Collectors.joining(", ")) +
                            ", " + offset + ", ...)");
                }
                skipIFDEntries(fileLength);
                offset = readNextOffset(true);
            }
            if (requireValidTiff && ifdOffsets.isEmpty()) {
                throw new AssertionError("No IFDs, but it was not checked in readFirstIFDOffset");
            }
            return ifdOffsets.stream().mapToLong(v -> v).toArray();
        }
    }

    public TiffIFD readSingleIFD(int ifdIndex) throws IOException, NoSuchElementException {
        long startOffset = readSingleIFDOffset(ifdIndex);
        if (startOffset < 0) {
            throw new NoSuchElementException("No IFD #" + ifdIndex + " in TIFF" + prettyInName()
                    + ": too large index");
        }
        return readIFDAt(startOffset);
    }

    /**
     * Reads the IFD stored at the given offset.
     * Never returns {@code null}.
     */
    public TiffIFD readIFDAt(long startOffset) throws IOException {
        return readIFDAt(startOffset, null, true);
    }

    public TiffIFD readIFDAt(final long startOffset, Integer subIFDType, boolean readNextOffset) throws IOException {
        if (startOffset < 0) {
            throw new IllegalArgumentException("Negative file offset = " + startOffset);
        }
        if (startOffset < (bigTiff ? 16 : 8)) {
            throw new IllegalArgumentException("Attempt to read IFD from too small start offset " + startOffset);
        }
        long t1 = debugTime();
        long timeEntries = 0;
        long timeArrays = 0;
        final TiffIFD ifd;
        synchronized (fileLock) {
            if (startOffset >= in.length()) {
                throw new TiffException("TIFF IFD offset " + startOffset + " is outside the file");
            }
            final Map<Integer, Object> map = new LinkedHashMap<>();
            final Map<Integer, TiffIFD.TiffEntry> detailedEntries = new LinkedHashMap<>();

            // read in directory entries for this IFD
            in.seek(startOffset);
            final long numberOfEntries = bigTiff ? in.readLong() : in.readUnsignedShort();
            if (numberOfEntries < 0 || numberOfEntries > MAX_NUMBER_OF_IFD_ENTRIES) {
                throw new TiffException("Too large number of IFD entries: " +
                        (numberOfEntries < 0 ? ">= 2^63" : numberOfEntries + " > " + MAX_NUMBER_OF_IFD_ENTRIES));
                // - theoretically BigTIFF allows having more entries, but we prefer to make some restriction;
                // in any case, billions if detailedEntries will probably lead to OutOfMemoryError or integer overflow
            }

            final int bytesPerEntry = bigTiff ? BIG_TIFF_BYTES_PER_ENTRY : BYTES_PER_ENTRY;
            final int baseOffset = bigTiff ? 8 : 2;

            for (long i = 0; i < numberOfEntries; i++) {
                long tEntry1 = debugTime();
                in.seek(startOffset + baseOffset + bytesPerEntry * i);

                final TiffIFD.TiffEntry entry = readIFDEntry();
                final int tag = entry.tag();
                long tEntry2 = debugTime();
                timeEntries += tEntry2 - tEntry1;

                final Object value = readIFDValueAtEntryOffset(in, entry);
                long tEntry3 = debugTime();
                timeArrays += tEntry3 - tEntry2;
//            System.err.printf("%d values from %d: %.6f ms%n", valueCount, valueOffset, (tEntry3 - tEntry2) * 1e-6);

                if (value != null && !map.containsKey(tag)) {
                    // - null value should not occur in the current version;
                    // if this tag is present twice (strange mistake if a TIFF file),
                    // we do not throw exception and just use the 1st entry
                    map.put(tag, value);
                    detailedEntries.put(tag, entry);
                }
            }
            final long positionOfNextOffset = startOffset + baseOffset + bytesPerEntry * numberOfEntries;
            in.seek(positionOfNextOffset);

            ifd = new TiffIFD(map, detailedEntries);
            ifd.setLittleEndian(in.isLittleEndian());
            ifd.setBigTiff(bigTiff);
            ifd.setFileOffsetForReading(startOffset);
            ifd.setSubIFDType(subIFDType);
            if (readNextOffset) {
                final long nextOffset = readNextOffset(false);
                ifd.setNextIFDOffset(nextOffset);
                in.seek(positionOfNextOffset);
                // - this "in.seek" provides maximal compatibility with old code (which did not read next IFD offset)
                // and also with the behavior of this method, when readNextOffset is not requested
            }
        }

        if (BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t2 = debugTime();
            LOG.log(System.Logger.Level.TRACE, String.format(Locale.US,
                    "%s read IFD at offset %d: %.3f ms, including %.6f entries + %.6f arrays",
                    getClass().getSimpleName(), startOffset,
                    (t2 - t1) * 1e-6, timeEntries * 1e-6, timeArrays * 1e-6));
        }
        return ifd;
    }

    /**
     * Calls {@link #readTile(TiffTileIndex)} with the same argument with caching, if this was enabled
     * by {@link #setCaching(boolean)} method.
     *
     * @param tileIndex position of the file.
     * @return loaded tile.
     * @throws IOException in the case of any problems with the input file.
     */
    public TiffTile readCachedTile(TiffTileIndex tileIndex) throws IOException {
        if (!caching || maxCachingMemory == 0) {
            return readTile(tileIndex);
        }
        return getCachedTile(tileIndex).readIfNecessary();
    }

    /**
     * Reads and decodes the tile at the specified position.
     * <p>Note: the loaded tile is always {@link TiffTile#isSeparated() separated}.
     * <p>Note: this method does not cache tiles.
     *
     * @param tileIndex position of the file.
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
        Objects.requireNonNull(tileIndex, "Null tileIndex");
        long t1 = debugTime();
        final TiffIFD ifd = tileIndex.ifd();
        final int index = tileIndex.linearIndex();
        // - also checks that tile index is not out of image bounds
        final long offset = ifd.cachedTileOrStripOffset(index);
        assert offset >= 0 : "offset " + offset + " was not checked in TiffIFD";
        int byteCount = cachedByteCountWithCompatibilityTrick(ifd, index);

        final TiffTile result = new TiffTile(tileIndex);
        // - No reasons to put it into the map: this class does not provide access to a temporary created map.

        if (cropTilesToImageBoundaries) {
            result.cropToMap();
        }
        // If cropping is disabled, we should not avoid reading extra content of the last strip.
        // Note the last encoded strip can have actually full strip sizes,
        // i.e., larger than necessary; this situation is quite possible.

        byteCount = correctZeroByteCount(tileIndex, byteCount, offset);
        if (byteCount == -1) {
            // - possible when missingTilesAllowed flag is set
            return result;
        }

        synchronized (fileLock) {
            if (offset >= in.length()) {
                throw new TiffException("Offset of TIFF tile/strip " + offset + " is out of file length (tile " +
                        tileIndex + ")");
                // - note: old SCIFIO code allowed such offsets and returned zero-filled tile
            }
            TiffTileIO.read(result, in, offset, byteCount);
        }
        long t2 = debugTime();
        timeReading += t2 - t1;
        return result;
    }

    // Note: result is usually interleaved (RGBRGB...) or monochrome; it is always so in UNCOMPRESSED, LZW, DEFLATE
    public boolean decode(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        if (!tile.isEncoded()) {
            return false;
        }
        long t1 = debugTime();
        prepareDecoding(tile);

        final byte[] encodedData = tile.getEncodedData();
        final TagCompression compression = TagCompression.valueOfCodeOrNull(tile.compressionCode());
        TiffCodec codec = null;
        if (!enforceUseExternalCodec && compression != null) {
            codec = compression.codec();
            // - we are sure that this codec does not require SCIFIO context
        }
        TiffCodec.Options options = buildOptions(tile);
        long t2 = debugTime();

        if (codec != null) {
            options = compression.customizeReading(tile, options);
            if (USE_LEGACY_UNPACK_BYTES) {
                options.setInterleaved(true);
                // - old-style unpackBytes does not "understand" already-separated tiles
            }
            if (codec instanceof TiffCodec.Timing timing) {
                timing.setTiming(BUILT_IN_TIMING && LOGGABLE_DEBUG);
                timing.clearTiming();
            }
            final byte[] decodedData = codec.decompress(encodedData, options);
            tile.setPartiallyDecodedData(decodedData);
        } else {
            final Optional<byte[]> decodedData = decodeByExternalCodec(tile, encodedData, options);
            if (decodedData.isEmpty()) {
                throw new UnsupportedTiffFormatException("TIFF compression with code " +
                        tile.compressionCode() + " cannot be decoded: " + tile.ifd());
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
            // - unlike full decoding, here it is better not to throw exception for empty tile
            return;
        }
        if (tile.ifd().isReversedFillOrder()) {
            PackedBitArraysPer8.reverseBitOrderInPlace(tile.getEncodedData());
        }
        boolean throwExceptionForStrangeDataStream = context != null;
        // - if context == null, there are no available external codecs; later we will throw a correct exception
        TiffJPEGDecodingHelper.embedJPEGTableInDataIfRequested(tile, throwExceptionForStrangeDataStream);
    }

    /**
     * Completes decoding tile after decoding by some {@link TiffCodec}. This method is automatically called
     * at the end of {@link #decode(TiffTile)} method.
     *
     * <p>First of all, this method always rearranges data in the file: if the codec returned
     * {@link TiffTile#isInterleaved() interleaved} data, this method
     * {@link TiffTile#separateSamplesIfNecessary() separates} them.
     * Interleaved data are the standard for internal pixel storage for simple formats like
     * {@link TagCompression#LZW} and may be returned by complex codecs like {@link TagCompression#JPEG_2000_LOSSLESS}.
     *
     * <p>This method does 3 other corrections for some standard compression algorithms:
     * <ul>
     * <li>{@link TagCompression#NONE},</li>
     * <li>{@link TagCompression#LZW},</li>
     * <li>{@link TagCompression#DEFLATE},</li>
     * <li>{@link TagCompression#PACK_BITS}.</li>
     * </ul>
     *
     * <p>1st correction: unpacking. TIFF supports storing pixel samples in any number of bits,
     * not always divisible by 8, in other words, one pixel sample can occupy non-integer number of bytes.
     * Most useful from these cases is 1-bit monochrome picture, where 8 pixels are packed into 1 byte.
     * Sometimes the pictures with 4 bits/pixels (monochrome or with palette) or 3*4=12 bits/pixels (RGB) appear.
     * You can also meet old RGB 32-bit images with 5+5+5 or 5+6+5 bits/channel.</p>
     *
     * <p>However, the API of this class represents any image as a sequence of <i>bytes</i>. This method performs
     * all necessary unpacking so that the result image use an integer number of bytes per each pixel sample (channel).
     * For example, 1-bit binary image is converted to 8-bit: 0 transformed to 0, 1 to 255;
     * 4-bit grayscale image is converted by multiplying by 17 (so that maximal possible 4-bit value, 15,
     * will become maximal possible 8-bit value 255=17*15); 12-bit RGB image is converted to 16-bit
     * by multiplying each channel by (2<sup>16</sup>&minus;1)/(2<sup>12</sup>&minus;1), etc.</p>
     *
     * <p>2nd correction: inversion. Some TIFF files use CMYK color space or WhiteIsZero interpretation of grayscale
     * images, where dark colors are represented by smaller values and bright colors by higher, in particular,
     * 0 is white, maximal value (255 for 8-bit) is black.</p>
     *
     * <p>However, the API of this class suppose that all returned images are RGB, RGBA or usual monochrome.
     * Complex codecs like JPEG perform necessary conversion themselves, but the simple codecs like
     * {@link TagCompression#NONE} do not this correction. This is performed by this method.
     * For CMYK or WhiteIsZero it means inversion of the pixel samples: v is transformed to MAX&minus;v,
     * where MAX is the maximal possible value (255 for 8-bit).</p>
     *
     * <p>3rd correction: conversion of YCbCr color space to usual RGB. It is a rare situation, when
     * the image is stored as YCbCr, however not in JPEG format, but uncompressed or, for example, as LZW.
     * This method performs necessary conversion to RGB (but only if the image is exactly 8-bit).</p>
     *
     * <p>Note: this method never increases number of <i>bytes</i>, necessary for representing a single pixel sample.
     * It can increase the number of <i>bits</i> per sample, but only to the nearest greater integer number of
     * bytes: 1..7 bits are transformed to 8 bits/sample, 9..15 to 16 bits/sample, 17..23 to 24 bits/sample etc.
     * Thus, this method <b>does not unpack 3-byte samples</b> (to 4-byte) and
     * <b>does not unpack 16- or 24-bit</b> floating-point formats. These cases
     * are processed after reading all tiles inside {@link #readSamples(TiffMapForReading, int, int, int, int)}
     * method, if {@link #isAutoUnpackUnusualPrecisions()} flag is set, or may be performed by external
     * code with help of {@link TiffUnusualPrecisions#unpackUnusualPrecisions(byte[], TiffIFD, int, long, boolean)}
     * method.
     * See {@link TiffReader#setAutoUnpackUnusualPrecisions(boolean)}.
     *
     * <p>This method does not allow 5, 6, 7 or greater than 8 bytes/sample
     * (but 8 bytes/sample is allowed: it is probably <code>double</code> precision).</p>
     *
     * @param tile the tile that should be corrected.
     */
    public void completeDecoding(TiffTile tile) throws TiffException {
        // scifio.tiff().undifference(tile.getDecodedData(), tile.ifd());
        // - this solution requires using SCIFIO context class; it is better to avoid this
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
                    TiffUnpacking.unpackTiffBitsAndInvertValues(tile,
                            autoScaleWhenIncreasingBitDepth,
                            autoCorrectInvertedBrightness);
                }
            }
        }
        tile.checkDataLengthAlignment();
        // - Fully unpacked and separated data must be correctly aligned

        /*
        // The following code is equivalent to the analogous code from SCIFIO 0.46.0 and earlier version,
        // which performed correction of channels with different precision in a rare case PLANAR_CONFIG_SEPARATE.
        // But it is deprecated: we do not support different number of BYTES in different samples,
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

    public TiffMapForReading newMap(TiffIFD ifd) {
        final TiffMapForReading map = new TiffMapForReading(this, ifd);
        map.buildTileGrid();
        // - building grid is useful to perform loops on all tiles
        this.lastMap = map;
        return map;
    }

    /**
     * Returns a reference to the map, created by last call of {@link #newMap(TiffIFD)}
     * or {@link #newMap(int)} methods.
     * Returns <code>null</code> if no maps were created yet or after {@link #close()} method.
     *
     * @return last map, created by this object.
     */
    public TiffMapForReading lastMap() {
        return lastMap;
    }

    public byte[] readSamples(TiffMapForReading map) throws IOException {
        return readSamples(map, false);
    }

    public byte[] readSamples(TiffMapForReading map, boolean storeTilesInMap) throws IOException {
        return readSamples(map, 0, 0, map.dimX(), map.dimY(), storeTilesInMap);
    }

    /**
     * Reads samples in <code>byte[]</code> array.
     *
     * <p>Note: you should not change IFD in a parallel thread while calling this method.
     *
     * @return loaded samples in a normalized form of byte sequence.
     */
    public byte[] readSamples(TiffMapForReading map, int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return readSamples(map, fromX, fromY, sizeX, sizeY, false);
    }

    public byte[] readSamples(
            TiffMapForReading map,
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap)
            throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        long t1 = debugTime();
        clearTiming();
        checkRequestedArea(fromX, fromY, sizeX, sizeY);
        // - note: we allow this area to be outside the image
        final int numberOfChannels = map.numberOfChannels();
        final TiffIFD ifd = map.ifd();
        final long sizeInPixels = (long) sizeX * (long) sizeY;
        // - can be >2^31 for bits

        byte[] samples = map.copySamplesFromMap(this::readCachedTile, fromX, fromY, sizeX, sizeY, storeTilesInMap);
        final int sizeInBytes = samples.length;

        long t2 = debugTime();
        boolean interleave = false;
        if (interleaveResults) {
            byte[] newSamples = map.toInterleavedSamples(samples, numberOfChannels, sizeInPixels);
            interleave = newSamples != samples;
            samples = newSamples;
        }
        long t3 = debugTime();
        boolean unpackingPrecision = false;
        if (autoUnpackUnusualPrecisions) {
            byte[] newSamples = TiffUnusualPrecisions.unpackUnusualPrecisions(
                    samples, ifd, numberOfChannels, sizeInPixels, autoScaleWhenIncreasingBitDepth);
            unpackingPrecision = newSamples != samples;
            samples = newSamples;
            // - note: the size of sample array can be increased here!
        }
        if (autoUnpackBitsToBytes && map.isBinary()) {
            unpackingPrecision = true;
            samples = PackedBitArraysPer8.unpackBitsToBytes(samples, 0, sizeInPixels, (byte) 0, (byte) 255);
        }
        if (BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t4 = debugTime();
            long timeNonClassified = t2 - t1 - (timeReading + timeDecoding);
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s read %dx%dx%d samples (%.3f MB) in %.3f ms = " +
                            "%.3f read/decode " +
                            "(%.3f read; %.3f customize/bit-order, %.3f decode%s, " +
                            "%.3f complete; %.3f tiles->array and other)" +
                            "%s%s, %.3f MB/s",
                    getClass().getSimpleName(),
                    sizeX, sizeY, numberOfChannels, sizeInBytes / 1048576.0,
                    (t4 - t1) * 1e-6,
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
                    interleave ?
                            String.format(Locale.US, " + %.3f interleave", (t3 - t2) * 1e-6) : "",
                    unpackingPrecision ?
                            String.format(Locale.US, " + %.3f unpacking %d-bit %s",
                                    (t4 - t3) * 1e-6,
                                    map.alignedBitsPerSample(),
                                    map.sampleType().isFloatingPoint() ? "float values" : "integer values") :
                            "",
                    sizeInBytes / 1048576.0 / ((t4 - t1) * 1e-9)));
        }
        return samples;
    }

    public Object readJavaArray(TiffMapForReading map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        return readJavaArray(map, 0, 0, map.dimX(), map.dimY());
    }

    public Object readJavaArray(TiffMapForReading map, int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return readJavaArray(map, fromX, fromY, sizeX, sizeY, false);
    }

    public Object readJavaArray(
            TiffMapForReading map,
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap)
            throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        final byte[] samples = readSamples(map, fromX, fromY, sizeX, sizeY, storeTilesInMap);
        long t1 = debugTime();
        final TiffSampleType sampleType = map.sampleType();
        if (!autoUnpackUnusualPrecisions && map.alignedBitsPerSample() != sampleType.bitsPerSample()) {
            throw new IllegalStateException("Cannot convert TIFF pixels, " + map.alignedBitsPerSample() +
                    " bits/sample, to \"" + sampleType.elementType() + "\" " + sampleType.bitsPerSample() +
                    "-bit Java type: unpacking unusual prevision mode is disabled");
        }
        final Object samplesArray = autoUnpackBitsToBytes && map.isBinary() ?
                samples :
                sampleType.javaArray(samples, getByteOrder());
        if (BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t2 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s converted %d bytes (%.3f MB) to %s[] in %.3f ms%s",
                    getClass().getSimpleName(),
                    samples.length, samples.length / 1048576.0,
                    samplesArray.getClass().getComponentType().getSimpleName(),
                    (t2 - t1) * 1e-6,
                    samples == samplesArray ?
                            "" :
                            String.format(Locale.US, " %.3f MB/s",
                                    samples.length / 1048576.0 / ((t2 - t1) * 1e-9))));
        }
        return samplesArray;
    }

    /**
     * Reads the full image with the specified TIFF map.
     * The result is a 3-dimensional matrix, where each 2-dimensional {@link Matrices#asLayers(Matrix) layer}
     * contains one of color channels.
     * However, if {@link #setInterleaveResults interleave results} flag is cleared, the result will an interleaved
     * 3D matrix, which can be separated to channels by {@link Matrices#separate(ArrayContext, Matrix)} method.
     *
     * <p>The necessary TIFF map can be obtained, for example, by calling
     * <code>{@link #newMap(int) reader.newMap}(ifdIndex)</code>.</p>
     *
     * @param map TIFF map, constructed from one of the IFDs of this TIFF file.
     * @return content of the IFD image.
     * @throws TiffException            if <code>ifdIndex</code> is too large,
     *                                  or if the file is not a correct TIFF file,
     *                                  and this was not detected while opening it.
     * @throws IOException              in the case of any problems with the input file.
     * @throws IllegalArgumentException if <code>ifdIndex&lt;0</code>.
     */
    public Matrix<UpdatablePArray> readMatrix(TiffMapForReading map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        return readMatrix(map, 0, 0, map.dimX(), map.dimY());
    }

    public Matrix<UpdatablePArray> readMatrix(TiffMapForReading map, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return readMatrix(map, fromX, fromY, sizeX, sizeY, false);
    }

    public Matrix<UpdatablePArray> readMatrix(
            TiffMapForReading map,
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap)
            throws IOException {
        final Object samplesArray = readJavaArray(map, fromX, fromY, sizeX, sizeY, storeTilesInMap);
        return TiffSampleType.asMatrix(samplesArray, sizeX, sizeY, map.numberOfChannels(), interleaveResults);
    }

    /**
     * Reads the full image with the specified TIFF map as a list of 2-dimensional matrices containing color channels.
     * For example, for RGB image, the result will be a list of three matrices R, G, B.
     *
     * <p>The necessary TIFF map can be obtained, for example, by calling
     * <code>{@link #newMap(int) reader.newMap}(ifdIndex)</code>.</p>
     *
     * @param map TIFF map, constructed from one of the IFDs of this TIFF file.
     * @return content of the TIFF image.
     * @throws TiffException if the file is not a correct TIFF file,
     *                       and this was not detected while opening it.
     * @throws IOException   in the case of any other problems with the input file.
     */
    public List<Matrix<UpdatablePArray>> readChannels(TiffMapForReading map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        return readChannels(map, 0, 0, map.dimX(), map.dimY());
    }

    public List<Matrix<UpdatablePArray>> readChannels(TiffMapForReading map, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return readChannels(map, fromX, fromY, sizeX, sizeY, false);
    }

    public List<Matrix<UpdatablePArray>> readChannels(
            TiffMapForReading map,
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap)
            throws IOException {
        final Matrix<UpdatablePArray> mergedChannels = readMatrix(map, fromX, fromY, sizeX, sizeY, storeTilesInMap);
        return interleaveResults ?
                Matrices.separate(null, mergedChannels, TiffIFD.MAX_NUMBER_OF_CHANNELS) :
                Matrices.asLayers(mergedChannels, TiffIFD.MAX_NUMBER_OF_CHANNELS);
    }

    /**
     * Reads the full image with the specified TIFF map as <code>BufferedImage</code>.
     * For example, for RGB image, the result will be a list of three matrices R, G, B.
     *
     * <p>The necessary TIFF map can be obtained, for example, by calling
     * <code>{@link #newMap(int) reader.newMap}(ifdIndex)</code>.</p>
     *
     * @param map TIFF map, constructed from one of the IFDs of this TIFF file.
     * @return content of the TIFF image.
     * @throws TiffException if the file is not a correct TIFF file,
     *                       and this was not detected while opening it.
     * @throws IOException   in the case of any other problems with the input file.
     */
    public BufferedImage readBufferedImage(TiffMapForReading map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        return readBufferedImage(map, 0, 0, map.dimX(), map.dimY());
    }

    public BufferedImage readBufferedImage(TiffMapForReading map, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return readBufferedImage(map, fromX, fromY, sizeX, sizeY, false);
    }

    public BufferedImage readBufferedImage(
            TiffMapForReading map,
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap)
            throws IOException {
        final Matrix<UpdatablePArray> mergedChannels = readMatrix(map, fromX, fromY, sizeX, sizeY, storeTilesInMap);
        final Matrix<? extends PArray> interleavedChannels = interleaveResults ?
                mergedChannels :
                Matrices.interleave(mergedChannels.asLayers());
        // Note: we do not call MatrixToImage.toBufferedImage, because we need to call setUnsignedInt32
        return new MatrixToImage.InterleavedRGBToInterleaved()
                .setUnsignedInt32(true)
                .toBufferedImage(interleavedChannels);
    }

    public final byte[] decompressByScifioCodec(TiffIFD ifd, byte[] encodedData, Object scifioCodecOptions)
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
        synchronized (fileLock) {
            in.close();
        }
    }

    @Override
    public String toString() {
        return "TIFF reader";
    }

    public static long checkRequestedArea(long fromX, long fromY, long sizeX, long sizeY) {
        final long result = TiffIFD.multiplySizes(sizeX, sizeY);
        if (fromX != (int) fromX || fromY != (int) fromY) {
            throw new IllegalArgumentException("Too large absolute values of fromX = " + fromX +
                    " or fromY = " + fromY + " (out of -2^31..2^31-1 ranges)");
        }
        if (sizeX >= Integer.MAX_VALUE - fromX || sizeY >= Integer.MAX_VALUE - fromY) {
            // - Note: ">=" instead of ">"! This allows to use "toX = fromX + sizeX" without overflow
            throw new IllegalArgumentException("Requested area [" + fromX + ".." + (fromX + sizeX - 1) +
                    " x " + fromY + ".." + (fromY + sizeY - 1) + "] is outside the 0..2^31-2 ranges");
        }
        return result;
    }

    public static Context newSCIFIOContext() {
        return SCIFIOBridge.getDefaultScifioContext();
    }

    public static DataHandle<Location> getExistingFileHandle(Path file) throws FileNotFoundException {
        if (!Files.isRegularFile(file)) {
            throw new FileNotFoundException("File " + file
                    + (Files.exists(file) ? " is not a regular file" : " does not exist"));
        }
        return getFileHandle(file);
    }

    protected Optional<byte[]> decodeByExternalCodec(TiffTile tile, byte[] encodedData, TiffCodec.Options options)
            throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(encodedData, "Null encoded data");
        Objects.requireNonNull(options, "Null options");
        if (!SCIFIOBridge.isScifioInstalled()) {
            return Optional.empty();
        }
        final Object scifioCodecOptions = options.toScifioStyleOptions(SCIFIOBridge.codecOptionsClass());
        final byte[] decodedData = decompressByScifioCodec(tile.ifd(), encodedData, scifioCodecOptions);
        return Optional.of(decodedData);
    }

    static DataHandle<Location> getFileHandle(Path file) {
        Objects.requireNonNull(file, "Null file");
        return getFileHandle(new FileLocation(file.toFile()));
    }

    /**
     * Warning: you should never call {@link DataHandle#set(Object)} method of the returned result!
     * It can lead to unpredictable <code>ClassCastException</code>.
     */
    @SuppressWarnings("rawtypes, unchecked")
    static DataHandle<Location> getBytesHandle(BytesLocation bytesLocation) {
        Objects.requireNonNull(bytesLocation, "Null bytesLocation");
        BytesHandle bytesHandle = new BytesHandle(bytesLocation);
        return (DataHandle) bytesHandle;
    }


    /**
     * Warning: you should never call {@link DataHandle#set(Object)} method of the returned result!
     * It can lead to unpredictable <code>ClassCastException</code>.
     */
    @SuppressWarnings("rawtypes, unchecked")
    static DataHandle<Location> getFileHandle(FileLocation fileLocation) {
        Objects.requireNonNull(fileLocation, "Null fileLocation");
        FileHandle fileHandle = new FileHandle(fileLocation);
        fileHandle.setLittleEndian(false);
        // - in the current implementation it is an extra operator: BigEndian is defaulted in scijava;
        // but we want to be sure that this behavior will be the same in all future versions
        return (DataHandle) fileHandle;
    }

    Object scifio() {
        Object scifio = this.scifio;
        if (scifio == null) {
            this.scifio = scifio = SCIFIOBridge.createScifioFromContext(context);
        }
        return scifio;
    }

    private Object requireScifio(TiffIFD ifd) throws UnsupportedTiffFormatException {
        Object scifio = scifio();
        if (scifio == null) {
            // - in other words, this.context is not set
            throw new UnsupportedTiffFormatException("Reading with TIFF compression " +
                    TagCompression.toPrettyString(ifd.optInt(Tags.COMPRESSION, TiffIFD.COMPRESSION_NONE)) +
                    " is not supported without external codecs");
        }
        return scifio;
    }

    private void clearTiming() {
        timeReading = 0;
        timeCustomizingDecoding = 0;
        timeDecoding = 0;
        timeDecodingMain = 0;
        timeDecodingBridge = 0;
        timeDecodingAdditional = 0;
        timeCompleteDecoding = 0;
    }

    // We prefer make this.bigTiff a final field, so we cannot set it outside the constructor
    private Exception startReading(AtomicBoolean bigTiffReference) {
        try {
            synchronized (fileLock) {
                // - this synchronization is extra, but may become useful
                // if we will decide to make this method public and called not only from the constructor
                if (!in.exists()) {
                    return new FileNotFoundException("Input TIFF file" + prettyInName() + " does not exist");
                }
                testHeader(bigTiffReference);
                return null;
            }
        } catch (IOException e) {
            return e;
        }
    }

    private void testHeader(AtomicBoolean bigTiffReference) throws IOException {
        final long savedOffset = in.offset();
        try {
            in.seek(0);
            final long length = in.length();
            if (length < MINIMAL_ALLOWED_TIFF_FILE_LENGTH) {
                // - sometimes we can meet 8-byte "TIFF-files" (or 16-byte "Big-TIFF"), containing only header
                // and no actual data (for example, results of debugging writing algorithm)
                throw new TiffException("Too short TIFF file" + prettyInName() + ": only " + length +
                        " bytes (minimum " + MINIMAL_ALLOWED_TIFF_FILE_LENGTH + " bytes are required for valid TIFF)");
            }
            final int endianOne = in.read();
            final int endianTwo = in.read();
            // byte order must be II or MM
            final boolean littleEndian = endianOne == FILE_PREFIX_LITTLE_ENDIAN &&
                    endianTwo == FILE_PREFIX_LITTLE_ENDIAN; // II
            final boolean bigEndian = endianOne == FILE_PREFIX_BIG_ENDIAN &&
                    endianTwo == FILE_PREFIX_BIG_ENDIAN; // MM
            if (!littleEndian && !bigEndian) {
                throw new TiffException("The file" + prettyInName() + " is not TIFF");
            }

            // check magic number (42)
            in.setLittleEndian(littleEndian);
            final short magic = in.readShort();
            final boolean bigTiff = magic == FILE_BIG_TIFF_MAGIC_NUMBER;
            bigTiffReference.set(bigTiff);
            if (magic != FILE_USUAL_MAGIC_NUMBER && magic != FILE_BIG_TIFF_MAGIC_NUMBER) {
                throw new TiffException("The file" + prettyInName() + " is not TIFF");
            }
            if (bigTiff) {
                in.seek(8);
            }
            readFirstOffsetFromCurrentPosition(false, bigTiff);
            // - additional check, filling positionOfLastOffset
        } finally {
            in.seek(savedOffset);
            // - for maximal compatibility: in old versions, constructor of this class
            // guaranteed that file position in the input stream will not change
            // (that is illogical, because "little-endian" mode was still changed)
        }
    }

    @SuppressWarnings("RedundantThrows")
    private TiffCodec.Options buildOptions(TiffTile tile) throws TiffException {
        TiffCodec.Options options = this.codecOptions.clone();
        options.setSizes(tile.getSizeX(), tile.getSizeY());
        options.setBitsPerSample(tile.bitsPerSample());
        options.setNumberOfChannels(tile.samplesPerPixel());
        options.setSigned(tile.sampleType().isSigned());
        options.setFloatingPoint(tile.sampleType().isFloatingPoint());
        options.setCompressionCode(tile.compressionCode());
        options.setByteOrder(tile.byteOrder());
        // - Note: codecs in SCIFIO did not use the options above, but some new codes like CCITTFaxCodec need them

        options.setMaxSizeInBytes(tile.getSizeInBytesInsideTIFF());
        // - Note: this may be LESS than the usual number of samples in the tile/strip.
        // Current readEncodedTile() can return full-size tile without cropping
        // (see comments inside that method), but usually it CROPS the last tile/strip.
        // Old SCIFIO code did not detect this situation, in particular, did not distinguish between
        // last and usual strips in stripped image, and its behavior could be described by the following assignment:
        //      final int samplesLength = tile.map().tileSizeInBytes();
        // For many codecs (like DEFLATE or JPEG) this is not important, but at least
        // LZWCodec creates result array on the base of options.maxSizeInBytes.
        // If it is invalid (too large value), the returned decoded data will be too large,
        // and that is not too good: this class could throw an exception "data may be lost" in further
        // tile.adjustNumberOfPixels() call if it called it without allowDecreasing=true argument.
        options.setInterleaved(true);
        // - Value "true" is necessary for most codecs, that work with high-level classes (like JPEG or JPEG-2000) and
        // need to be instructed to interleave results (unlike LZW or DECOMPRESSED, which work with data "as-is"
        // and suppose that data are interleaved according TIFF format specification).
        // For JPEG, TagCompression overrides this value to false because it works faster in this mode.
        options.setIfd(tile.ifd());
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
            // It is not a problem, because CachedTile is a very lightweight object.
            // In any case, this.ifds already contains comparable amount of data:
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
            // and this file is Big-TIFF (TileByteCounts or StripByteCounts is IFDType.LONG8),
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
                final long left = in.length() - offset;
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


    // Unlike AbstractCodec.decompress, this method does not require using "handles" field, annotated as @Parameter
    // This function is not universal, it cannot be applied to any codec!

    private String prettyInName() {
        return prettyFileName(" %s", in);
    }

    private long readFirstOffsetFromCurrentPosition(boolean updatePositionOfLastOffset, boolean bigTiff)
            throws IOException {
        final long offset = readNextOffset(updatePositionOfLastOffset, true, bigTiff);
        if (offset == 0) {
            throw new TiffException("Invalid TIFF" + prettyInName() +
                    ": zero first offset (TIFF must contain at least one IFD!)");
        }
        return offset;
    }

    private void skipIFDEntries(long fileLength) throws IOException {
        final long offset = in.offset();
        final int bytesPerEntry = bigTiff ? BIG_TIFF_BYTES_PER_ENTRY : BYTES_PER_ENTRY;
        final long numberOfEntries = bigTiff ? in.readLong() : in.readUnsignedShort();
        if (numberOfEntries < 0 || numberOfEntries > Integer.MAX_VALUE / bytesPerEntry) {
            throw new TiffException(
                    "Too large number of IFD entries in Big TIFF: " +
                            (numberOfEntries < 0 ? ">= 2^63" : numberOfEntries + "") +
                            " (it is not supported, probably file is broken)");
        }
        final long skippedIFDBytes = numberOfEntries * bytesPerEntry;
        if (offset + skippedIFDBytes >= fileLength) {
            throw new TiffException(
                    "Invalid TIFF" + prettyInName() + ": position of next IFD offset " +
                            (offset + skippedIFDBytes) + " after " + numberOfEntries +
                            " entries is outside the file (probably file is broken)");
        }
        in.skipBytes((int) skippedIFDBytes);
    }

    private long readNextOffset(boolean updatePositionOfLastOffset) throws IOException {
        return readNextOffset(updatePositionOfLastOffset, this.requireValidTiff, this.bigTiff);
    }

    /**
     * Read a file offset.
     * For BigTIFF files, a 64-bit number is read.
     * For other Tiffs, a 32-bit number is read and possibly adjusted for a possible carry-over
     * from the previous offset.
     */
    private long readNextOffset(boolean updatePositionOfLastOffset, boolean requireValidTiff, boolean bigTiff)
            throws IOException {
        final long fileLength = in.length();
        final long filePosition = in.offset();
        long offset;
        if (bigTiff) {
            offset = in.readLong();
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

            offset = (long) in.readInt() & 0xffffffffL;
            // - in usual TIFF format, offset if 32-bit UNSIGNED value
        }
        if (requireValidTiff) {
            if (offset < 0) {
                // - possibly in Big-TIFF only
                throw new TiffException("Invalid TIFF" + prettyInName() +
                        ": negative 64-bit offset " + offset + " at file position " + filePosition +
                        ", probably the file is corrupted");
            }
            if (offset >= fileLength) {
                throw new TiffException("Invalid TIFF" + prettyInName() + ": offset " + offset +
                        " at file position " + filePosition + " is outside the file, probably the is corrupted");
            }
        }
        if (updatePositionOfLastOffset) {
            this.positionOfLastIFDOffset = filePosition;
        }
        return offset;
    }

    private static Object readIFDValueAtEntryOffset(DataHandle<?> in, TiffIFD.TiffEntry entry) throws IOException {
        final int type = entry.type();
        final int count = entry.valueCount();
        final long offset = entry.valueOffset();
        final ByteOrder byteOrder = in.isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;

        LOG.log(System.Logger.Level.TRACE, () ->
                "Reading entry " + entry.tag() + " from " + offset + "; type=" + type + ", count=" + count);

        in.seek(offset);
        switch (type) {
            case TagTypes.BYTE -> {
                // 8-bit unsigned integer
                if (count == 1) {
                    return (short) in.readByte();
                }
                final byte[] bytes = new byte[count];
                in.readFully(bytes);
                // bytes are unsigned, so use shorts
                final short[] shorts = new short[count];
                for (int j = 0; j < count; j++) {
                    shorts[j] = (short) (bytes[j] & 0xff);
                }
                return shorts;
            }
            case TagTypes.ASCII -> {
                // 8-bit byte that contains a 7-bit ASCII code;
                // the last byte must be NUL (binary zero)
                final byte[] ascii = new byte[count];
                in.read(ascii);

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
            }
            case TagTypes.SHORT -> {
                // 16-bit (2-byte) unsigned integer
                if (count == 1) {
                    return in.readUnsignedShort();
                }
                if (OPTIMIZE_READING_IFD_ARRAYS) {
                    final byte[] bytes = readIFDBytes(in, 2 * (long) count);
                    final short[] shorts = JArrays.bytesToShortArray(bytes, byteOrder);
                    final int[] result = new int[count];
                    for (int j = 0; j < count; j++) {
                        result[j] = shorts[j] & 0xFFFF;
                    }
                    return result;
                } else {
                    final int[] shorts = new int[count];
                    for (int j = 0; j < count; j++) {
                        shorts[j] = in.readUnsignedShort();
                    }
                    return shorts;
                }
            }
            case TagTypes.LONG, TagTypes.IFD -> {
                // 32-bit (4-byte) unsigned integer
                if (count == 1) {
                    return in.readInt() & 0xFFFFFFFFL;
                }
                if (OPTIMIZE_READING_IFD_ARRAYS) {
                    final byte[] bytes = readIFDBytes(in, 4 * (long) count);
                    final int[] ints = JArrays.bytesToIntArray(bytes, byteOrder);
                    return Arrays.stream(ints).mapToLong(anInt -> anInt & 0xFFFFFFFFL).toArray();
                    // note: TIFF_LONG is UNSIGNED long
                } else {
                    final long[] longs = new long[count];
                    for (int j = 0; j < count; j++) {
                        longs[j] = in.readInt() & 0xFFFFFFFFL;
                    }
                    return longs;
                }
            }
            case TagTypes.LONG8, TagTypes.SLONG8, TagTypes.IFD8 -> {
                if (count == 1) {
                    return in.readLong();
                }
                if (OPTIMIZE_READING_IFD_ARRAYS) {
                    final byte[] bytes = readIFDBytes(in, 8 * (long) count);
                    return JArrays.bytesToLongArray(bytes, byteOrder);
                } else {
                    long[] longs = new long[count];
                    for (int j = 0; j < count; j++) {
                        longs[j] = in.readLong();
                    }
                    return longs;
                }
            }
            case TagTypes.RATIONAL, TagTypes.SRATIONAL -> {
                // Two LONGs or SLONGs: the first represents the numerator of a fraction; the second, the denominator
                if (count == 1) {
                    return new TagRational(in.readInt(), in.readInt());
                }
                final TagRational[] rationals = new TagRational[count];
                for (int j = 0; j < count; j++) {
                    rationals[j] = new TagRational(in.readInt(), in.readInt());
                }
                return rationals;
            }
            case TagTypes.SBYTE, TagTypes.UNDEFINED -> {
                // SBYTE: An 8-bit signed (twos-complement) integer
                // UNDEFINED: An 8-bit byte that may contain anything,
                // depending on the definition of the field
                if (count == 1) {
                    return in.readByte();
                }
                final byte[] sbytes = new byte[count];
                in.read(sbytes);
                return sbytes;
            }
            case TagTypes.SSHORT -> {
                // A 16-bit (2-byte) signed (twos-complement) integer
                if (count == 1) {
                    return in.readShort();
                }
                final short[] sshorts = new short[count];
                for (int j = 0; j < count; j++) {
                    sshorts[j] = in.readShort();
                }
                return sshorts;
            }
            case TagTypes.SLONG -> {
                // A 32-bit (4-byte) signed (twos-complement) integer
                if (count == 1) {
                    return in.readInt();
                }
                final int[] slongs = new int[count];
                for (int j = 0; j < count; j++) {
                    slongs[j] = in.readInt();
                }
                return slongs;
            }
            case TagTypes.FLOAT -> {
                // Single precision (4-byte) IEEE format
                if (count == 1) {
                    return in.readFloat();
                }
                final float[] floats = new float[count];
                for (int j = 0; j < count; j++) {
                    floats[j] = in.readFloat();
                }
                return floats;
            }
            case TagTypes.DOUBLE -> {
                // Double precision (8-byte) IEEE format
                if (count == 1) {
                    return in.readDouble();
                }
                final double[] doubles = new double[count];
                for (int j = 0; j < count; j++) {
                    doubles[j] = in.readDouble();
                }
                return doubles;
            }
            default -> {
                final long valueOrOffset = in.readLong();
                return new TiffIFD.UnsupportedTypeValue(type, count, valueOrOffset);
            }
        }
    }

    private static byte[] readIFDBytes(DataHandle<?> in, long length) throws IOException {
        if (length > Integer.MAX_VALUE) {
            throw new TiffException("Too large IFD value: " + length + " >= 2^31 bytes");
        }
        byte[] bytes = new byte[(int) length];
        in.readFully(bytes);
        return bytes;
    }

    private TiffIFD.TiffEntry readIFDEntry() throws IOException {
        final int entryTag = in.readUnsignedShort();
        final int entryType = in.readUnsignedShort();

        final long valueCount = bigTiff ? in.readLong() : ((long) in.readInt()) & 0xFFFFFFFFL;
        if (valueCount < 0 || valueCount > Integer.MAX_VALUE) {
            throw new TiffException("Invalid TIFF: very large number of IFD values in array " +
                    (valueCount < 0 ? " >= 2^63" : valueCount + " >= 2^31") + " is not supported");
        }
        final int bytesPerElement = TagTypes.sizeOfType(entryType);
        // - will be zero for an unknown type; in this case we will set valueOffset=in.offset() below
        final long valueLength = valueCount * (long) bytesPerElement;
        final int threshold = bigTiff ? 8 : 4;
        final long valueOffset = valueLength > threshold ?
                readNextOffset(false) :
                in.offset();
        if (valueOffset < 0) {
            throw new TiffException("Invalid TIFF: negative offset of IFD values " + valueOffset);
        }
        if (valueOffset > in.length() - valueLength) {
            throw new TiffException("Invalid TIFF: offset of IFD values " + valueOffset +
                    " + total lengths of values " + valueLength + " = " + valueCount + "*" + bytesPerElement +
                    " is outside the file length " + in.length());
        }
        final TiffIFD.TiffEntry result = new TiffIFD.TiffEntry(entryTag, entryType, (int) valueCount, valueOffset);
        LOG.log(System.Logger.Level.TRACE, () -> String.format(
                "Reading IFD entry: %s - %s", result, Tags.tiffTagName(result.tag(), true)));
        return result;
    }

    static String prettyFileName(String format, DataHandle<Location> handle) {
        if (handle == null) {
            return "";
        }
        Location location = handle.get();
        if (location == null) {
            return "";
        }
        URI uri = location.getURI();
        if (uri == null) {
            return "";
        }
        return format.formatted(uri);
    }

    static boolean getBooleanProperty(String propertyName) {
        try {
            return Boolean.getBoolean(propertyName);
        } catch (Exception e) {
            return false;
        }
    }

    private static long debugTime() {
        return BUILT_IN_TIMING && LOGGABLE_DEBUG ? System.nanoTime() : 0;
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
                if (caching && maxCachingMemory > 0) {
                    this.cachedTile = new SoftReference<>(tile);
                    this.cachedDataLength = tile.getStoredDataLength();
                    currentCacheMemory += this.cachedDataLength;
                    tileCache.add(this);
                    LOG.log(System.Logger.Level.TRACE, () -> "STORING tile in cache: " + tileIndex);
                    while (currentCacheMemory > maxCachingMemory) {
                        CachedTile cached = tileCache.remove();
                        assert cached != null;
                        currentCacheMemory -= cached.cachedDataLength;
                        cached.cachedTile = null;
                        Runtime runtime = Runtime.getRuntime();
                        LOG.log(System.Logger.Level.TRACE, () -> String.format(Locale.US,
                                "REMOVING tile from cache (limit %.1f MB exceeded, used memory %.1f MB): %s",
                                maxCachingMemory / 1048576.0,
                                (runtime.totalMemory() - runtime.freeMemory()) / 1048576.0,
                                cached.tileIndex));
                    }
                }
            }
        }
    }

}
