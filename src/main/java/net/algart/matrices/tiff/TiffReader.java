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

import io.scif.SCIFIO;
import net.algart.matrices.tiff.codecs.TiffCodec;
import io.scif.codec.CodecOptions;
import net.algart.matrices.tiff.codecs.*;
import io.scif.formats.tiff.TiffCompression;
import io.scif.formats.tiff.TiffConstants;
import io.scif.formats.tiff.TiffRational;
import net.algart.arrays.*;
import net.algart.matrices.tiff.tiles.TiffMap;
import net.algart.matrices.tiff.tiles.TiffTile;
import net.algart.matrices.tiff.tiles.TiffTileIO;
import net.algart.matrices.tiff.tiles.TiffTileIndex;
import org.scijava.AbstractContextual;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.handle.ReadBufferDataHandle;
import org.scijava.io.location.Location;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Reads TIFF format.
 *
 * <p>This object is internally synchronized and thread-safe when used in multi-threaded environment.
 * However, you should not modify objects, passed to the methods of this class from a parallel thread;
 * first of all, it concerns the {@link TiffIFD} arguments of many methods.
 * The same is true for the result of {@link #getStream()} method.</p>
 */
public class TiffReader extends AbstractContextual implements Closeable {
    // Creating this class started from reworking SCIFIO TiffParser class.
    // Below is a copy of list of its authors and of the SCIFIO license for that class.
    // (It is placed here to avoid autocorrection by IntelliJ IDEA)
    /*
     * @author Curtis Rueden
     * @author Eric Kjellman
     * @author Melissa Linkert
     * @author Chris Allan
     *
     * #%L
     * SCIFIO library for reading and converting scientific file formats.
     * %%
     * Copyright (C) 2011 - 2023 SCIFIO developers.
     * %%
     * Redistribution and use in source and binary forms, with or without
     * modification, are permitted provided that the following conditions are met:
     *
     * 1. Redistributions of source code must retain the above copyright notice,
     *    this list of conditions and the following disclaimer.
     * 2. Redistributions in binary form must reproduce the above copyright notice,
     *    this list of conditions and the following disclaimer in the documentation
     *    and/or other materials provided with the distribution.
     *
     * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
     * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
     * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
     * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
     * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
     * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
     * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
     * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
     * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
     * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
     * POSSIBILITY OF SUCH DAMAGE.
     * #L%
     */

    /**
     * IFD with number of entries, greater than this limit, is not allowed:
     * it is mostly probable that it is corrupted file.
     */
    public static int MAX_NUMBER_OF_IFD_ENTRIES = 1_000_000;

    private static final boolean OPTIMIZE_READING_IFD_ARRAYS = true;
    // - Note: this optimization allows to speed up reading large array of offsets.
    // If we use simple FileHandle for reading file (based on RandomAccessFile),
    // acceleration is up to 100 and more times:
    // on my computer, 23220 int32 values were loaded in 0.2 ms instead of 570 ms.
    // Since scijava-common 2.95.1, we use optimized ReadBufferDataHandle for reading file;
    // now acceleration for 23220 int32 values is 0.2 ms instead of 0.4 ms.

    static final boolean USE_LEGACY_UNPACK_BYTES = false;
    // - Should be false for better performance; necessary for debugging needs only.
    static final boolean THOROUGHLY_TEST_Y_CB_CR_LOOP = false;

    private static final int MINIMAL_ALLOWED_TIFF_FILE_LENGTH = 8 + 2 + 12 + 4;
    // - 8 bytes header + at least 1 IFD entry (usually at least 2 entries required: ImageWidth + ImageLength);
    // this constant should be > 16 to detect "dummy" BigTIFF file, containing header only

    private static final System.Logger LOG = System.getLogger(TiffReader.class.getName());
    private static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);

    private boolean requireValidTiff;
    private boolean interleaveResults = false;
    private boolean autoUnpackUnusualPrecisions = true;
    private boolean autoScaleWhenIncreasingBitDepth = true;
    private boolean autoCorrectInvertedBrightness = false;
    private boolean extendedCodec = true;
    private boolean cropTilesToImageBoundaries = true;
    private boolean cachingIFDs = true;
    private boolean missingTilesAllowed = false;
    private byte byteFiller = 0;

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
    private final SCIFIO scifio;

    private final Object fileLock = new Object();

    /**
     * Codec options to be used when decoding compressed pixel data.
     */
    CodecOptions codecOptions = CodecOptions.getDefaultOptions();

    private volatile long positionOfLastIFDOffset = -1;

    private long timeReading = 0;
    private long timeCustomizingDecoding = 0;
    private long timeDecoding = 0;
    private long timeDecodingMain = 0;
    private long timeDecodingBridge = 0;
    private long timeDecodingAdditional = 0;
    private long timeCompleteDecoding = 0;

    public TiffReader(Path file) throws IOException {
        this(null, file);
    }

    public TiffReader(Path file, boolean requireValidTiff) throws IOException {
        this(null, file, requireValidTiff);
    }

    public TiffReader(DataHandle<Location> in) throws IOException {
        this(null, in);
    }

    public TiffReader(DataHandle<Location> in, boolean requireValidTiff) throws IOException {
        this(null, in, requireValidTiff);
    }

    public TiffReader(Context context, Path file) throws IOException {
        this(context, file, true);
    }

    public TiffReader(Context context, Path file, boolean requireValidTiff) throws IOException {
        this(context, TiffTools.getExistingFileHandle(file), requireValidTiff);
    }

    public TiffReader(Context context, DataHandle<Location> in) throws IOException {
        this(context, in, true);
    }

    /**
     * Constructs new reader.
     *
     * <p>If <tt>requireValidTiff</tt> is <tt>true</tt> (standard variant), it will throw an exception
     * in a case of incorrect TIFF header or some other I/O errors.
     * If it is <tt>false</tt>, exceptions will be ignored, but {@link #isValid()} method will return <tt>false</tt>.
     *
     * @param context          SCIFIO context; may be <tt>null</tt>, but in this case some codecs
     *                         will possibly not work.
     * @param in               input stream.
     * @param requireValidTiff whether the input file must exist and be a readable TIFF-file with a correct header.
     * @throws TiffException if the file is not a correct TIFF file
     * @throws IOException   in a case of any problems with the input file
     */
    public TiffReader(Context context, DataHandle<Location> in, boolean requireValidTiff) throws IOException {
        this(context, in, null);
        this.requireValidTiff = requireValidTiff;
        if (requireValidTiff && openingException != null) {
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
     * Universal constructor, helping to make subclasses with constructors, which not throw exceptions.
     *
     * @param context          SCIFIO context; may be <tt>null</tt>, but in this case some codecs
     *                         will possibly not work.
     * @param in               input stream.
     * @param exceptionHandler if not <tt>null</tt>, it will be called in a case of some checked exception;
     *                         for example, it may log it. But usually it is better idea to use the main
     *                         constructor {@link #TiffReader(Context, DataHandle, boolean)} with catching exception.
     */
    protected TiffReader(
            Context context,
            DataHandle<Location> in,
            Consumer<Exception> exceptionHandler) {
        Objects.requireNonNull(in, "Null in stream");
        this.requireValidTiff = false;
        if (context != null) {
            setContext(context);
            scifio = new SCIFIO(context);
        } else {
            scifio = null;
        }
        this.in = in instanceof ReadBufferDataHandle ? in : new ReadBufferDataHandle<>(in);
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
     * Sets whether the parser should always require valid TIFF format.
     * Default value is specified in the constructor or is <tt>false</tt>
     * when using a constructor without such argument.
     *
     * @param requireValidTiff whether TIFF file should be correct.
     * @return a reference to this object.
     */
    public TiffReader setRequireValidTiff(boolean requireValidTiff) {
        this.requireValidTiff = requireValidTiff;
        return this;
    }

    public boolean isInterleaveResults() {
        return interleaveResults;
    }

    /**
     * Sets the interleave mode: the loaded samples will be returned in chunked form, for example, RGBRGBRGB...
     * in a case of RGB image. If not set (default behaviour), the samples are returned in unpacked form:
     * RRR...GGG...BBB...
     *
     * @param interleaveResults new interleaving mode.
     * @return a reference to this object.
     */
    public TiffReader setInterleaveResults(boolean interleaveResults) {
        this.interleaveResults = interleaveResults;
        return this;
    }

    public boolean isAutoUnpackUnusualPrecisions() {
        return autoUnpackUnusualPrecisions;
    }

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
     * <p>If this flag is <tt>false</tt>, this conversion is performed "as-is", so, values 0..15 in 4-bit source data
     * will be converted to the same values 0..15 with 8-bit precision.
     * This is good if you need to process these values using some kind of algorithm.
     * However, if you need to show the real picture to the end user, then values 0..15 with 8-bit
     * precisions (or 0..4095 with 16-bit precision) will look almost black. To avoid this, you may use <tt>true</tt>
     * value of this flag, which causes automatic scaling returned values: multiplying by
     * (2<sup><i>n</i></sup>&minus;1)/(2<sup><i>k</i></sup>&minus;1), where <i>n</i> is the result bit depth
     * and <i>k</i> is the source one (for example, for 12-bit image <i>k</i>=12 and <i>n</i>=16).
     * As the result, the returned picture will look alike the source one.</p>
     *
     * <p>Default value is <tt>true</tt>. However, the scaling is still not performed if
     * PhotometricInterpretation TIFF tag is "Palette" (3) or "Transparency Mask" (4): in these cases
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
     * Sets the flag, whether do we need to automatically correct (invert) pixel sample values in color space
     * with inverted sense of pixel brightness, i.e. when PhotometricInterpretation TIFF tag is "WhiteIsZero" (0)
     * or "Separated" (CMYK, 5). (In these color spaces, white color is encoded as zero, and black color is encoded
     * as maximal allowed value like 255 for 8-bit samples.)
     * Note that this flag <b>do not provide</b> correct processing CMYK color model
     * and absolutely useless for more complex color spaces like CIELAB, but for PhotometricInterpretation=0 or 5
     * it helps to provide RGB results more similar to the correct picture.
     *
     * <p>Default value is <tt>false</tt>. You may set it to <tt>true</tt> if the only goal of reading TIFF
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

    public boolean isExtendedCodec() {
        return extendedCodec;
    }

    public TiffReader setExtendedCodec(boolean extendedCodec) {
        this.extendedCodec = extendedCodec;
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
    public CodecOptions getCodecOptions() {
        return codecOptions;
    }

    /**
     * Sets the codec options to be used when decompressing pixel data.
     *
     * @param codecOptions Codec options to use.
     * @return a reference to this object.
     */
    public TiffReader setCodecOptions(final CodecOptions codecOptions) {
        this.codecOptions = codecOptions;
        return this;
    }

    public boolean isCachingIFDs() {
        return cachingIFDs;
    }

    /**
     * Sets whether IFD entries, returned by {@link #allIFDs()} method, should be cached.
     *
     * <p>Default value is <tt>true</tt>. Possible reason to set is to <tt>false</tt>
     * is reading file which is dynamically modified.
     * In other cases, usually it should be <tt>true</tt>, though <tt>false</tt> value
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
     * Sets the special mode, when TIFF file is allowed to contain "missing" tiles or strips,
     * for which the offset (<tt>TileOffsets</tt> or <tt>StripOffsets</tt> tag) and/or
     * byte count (<tt>TileByteCounts</tt> or <tt>StripByteCounts</tt> tag) contains zero value.
     * In this mode, such tiles/strips will be successfully read as empty rectangles, filled by
     * the {@link #setByteFiller(byte) default filler}.
     *
     * <p>Default value is <tt>false</tt>. In this case, such tiles/strips are not allowed,
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
     * preserve default 0 value, in other case results could be very strange.
     *
     * @param byteFiller new filler.
     * @return a reference to this object.
     */
    public TiffReader setByteFiller(byte byteFiller) {
        this.byteFiller = byteFiller;
        return this;
    }

    /**
     * Gets the stream from which TIFF data is being parsed.
     */
    public DataHandle<Location> getStream() {
        synchronized (fileLock) {
            // - we prefer not to return this stream in the middle of I/O operations
            return in;
        }
    }

    public boolean isValid() {
        return valid;
    }

    /**
     * Returns whether or not the current TIFF file contains BigTIFF data.
     */
    public boolean isBigTiff() {
        return bigTiff;
    }

    /**
     * Returns whether or not we are reading little-endian data.
     * Determined in the constructors.
     */
    public boolean isLittleEndian() {
        return in.isLittleEndian();
    }

    /**
     * Returns position in the file of the last IFD offset, loaded by {@link #readIFDOffsets()},
     * {@link #readSingleIFDOffset(int)} or {@link #readFirstIFDOffset()} methods.
     * Usually it is just a position of the offset of the last IFD, because
     * popular {@link #allIFDs()} method calls {@link #readIFDOffsets()} inside.
     *
     * <p>Immediately after creating new object this position is <tt>-1</tt>.
     *
     * @return file position of the last IFD offset.
     */
    public long positionOfLastIFDOffset() {
        return positionOfLastIFDOffset;
    }

    public TiffMap map(int ifdIndex) throws IOException {
        List<TiffIFD> ifdList = allIFDs();
        if (ifdIndex < 0 || ifdIndex >= ifdList.size()) {
            throw new IndexOutOfBoundsException(
                    "IFD index " + ifdIndex + " is out of bounds 0 <= index < " + ifdList.size());
        }
        return newMap(ifdList.get(ifdIndex));
    }

    /**
     * Reads 1st IFD (#0). If you really needs access only to 1st IFD,
     * this method may work faster than {@link #map(int)}.
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


    public List<TiffMap> allMaps() throws IOException {
        return allIFDs().stream().map(this::newMap).collect(Collectors.toList());
    }

    public int numberOfIFDs() throws IOException {
        return allIFDs().size();
    }

    /**
     * Returns all IFDs in the file.
     *
     * <p>Note: if this TIFF file is not valid ({@link #isValid()} returns <tt>false</tt>), this method
     * returns an empty list and does not throw an exception. For valid TIFF, result cannot be empty.
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
                    subOffsets = ifd.getLongArray(TiffIFD.SUB_IFD);
                } catch (final TiffException ignored) {
                }
                if (subOffsets != null) {
                    for (final long subOffset : subOffsets) {
                        final TiffIFD sub = readIFDAt(subOffset, TiffIFD.SUB_IFD, false);
                        if (sub != null) {
                            ifds.add(sub);
                        }
                    }
                }
            }
            if (cachingIFDs) {
                this.ifds = ifds;
            }
        }
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
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
            final long offset = ifd.getLong(TiffIFD.EXIF, 0);
            if (offset != 0) {
                final TiffIFD exifIFD = readIFDAt(offset, TiffIFD.EXIF, false);
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
     * Returns the file offset of IFD with given index or <tt>-1</tt> if the index is too high.
     * Updates {@link #positionOfLastIFDOffset()} to position of this offset.
     *
     * @param ifdIndex index of IFD (0, 1, ...).
     * @return offset of this IFD in the file or <tt>-1</tt> if the index is too high.
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
     * Never returns <tt>null</tt>.
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
                // - theoretically BigTIFF allows to have more entries, but we prefer to make some restriction;
                // in any case, billions if detailedEntries will probably lead to OutOfMemoryError or integer overflow
            }

            final int bytesPerEntry = bigTiff ? TiffConstants.BIG_TIFF_BYTES_PER_ENTRY : TiffConstants.BYTES_PER_ENTRY;
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
                    // - null value should not occur in current version;
                    // if this tag is present twice (strange mistake if TIFF file),
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
                // and also with behaviour of this method, when readNextOffset is not requested
            }
        }

        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t2 = debugTime();
            LOG.log(System.Logger.Level.TRACE, String.format(Locale.US,
                    "%s read IFD at offset %d: %.3f ms, including %.6f entries + %.6f arrays",
                    getClass().getSimpleName(), startOffset,
                    (t2 - t1) * 1e-6, timeEntries * 1e-6, timeArrays * 1e-6));
        }
        return ifd;
    }

    /**
     * Reads and decodes the tile at the specified position.
     * Note: the loaded tile is always {@link TiffTile#isSeparated() separated}.
     *
     * @param tileIndex position of the file
     * @return loaded tile.
     */
    public TiffTile readTile(TiffTileIndex tileIndex) throws IOException {
        TiffTile tile = readEncodedTile(tileIndex);
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
        assert offset >= 0 : "offset " + offset + " was not checked in DetailedIFD";
        final int byteCount = cachedByteCountWithCompatibilityTrick(ifd, index);

        /*
        // Some strange old code, seems to be useless
        final int rowsPerStrip = ifd.cachedStripSizeY();
        final int bytesPerSample = ifd.getBytesPerSampleBasedOnBits();
        if (byteCount == ((long) rowsPerStrip * tileSizeX) && bytesPerSample > 1) {
            byteCount *= bytesPerSample;
        }
        if (byteCount >= Integer.MAX_VALUE) {
            throw new FormatException("Too large tile/strip #" + index + ": " + byteCount + " bytes > 2^31-1");
        }
        */

        final TiffTile result = new TiffTile(tileIndex);
        // - No reasons to put it into the map: this class do not provide access to temporary created map.

        if (cropTilesToImageBoundaries) {
            result.cropToMap(true);
        }
        // If cropping is disabled, we should not avoid reading extra content of the last strip.
        // Note the last encoded strip can have actually full strip sizes,
        // i.e. larger than necessary; this situation is quite possible.
        if (byteCount == 0 || offset == 0) {
            if (missingTilesAllowed) {
                return result;
            } else {
                throw new TiffException("Zero tile/strip " + (byteCount == 0 ? "byte-count" : "offset")
                        + " is not allowed in a valid TIFF file (tile " + tileIndex + ")");
            }
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
    public void decode(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        long t1 = debugTime();
        prepareEncodedTileForDecoding(tile);

        TiffIFD ifd = tile.ifd();
        byte[] encodedData = tile.getEncodedData();
        final TiffCompression compression = ifd.getCompression();

        final KnownCompression known = KnownCompression.valueOfOrNull(compression);
        TiffCodec codec = null;
        if (extendedCodec && known != null) {
            codec = known.extendedCodec();
            // - we are sure that this codec does not require SCIFIO context
        }
        final CodecOptions codecOptions = buildReadingOptions(tile, codec);

        long t2 = debugTime();
        if (codec != null) {
            if (codec instanceof TiffCodecTiming timing) {
                timing.setTiming(TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG);
                timing.clearTiming();
            }
            tile.setPartiallyDecodedData(codec.decompress(encodedData, codecOptions));
        } else {
            if (scifio == null) {
                throw new IllegalStateException(
                        "Compression type " + compression + " requires specifying non-null SCIFIO context");
            }
            byte[] decodedData;
            try {
                decodedData = compression.decompress(scifio.codec(), encodedData, codecOptions);
            } catch (Exception e) {
                throw new TiffException(e.getMessage(), e);
            }
            tile.setPartiallyDecodedData(decodedData);
        }
        tile.setInterleaved(codecOptions.interleaved);
        long t3 = debugTime();

        completeDecoding(tile);
        long t4 = debugTime();

        timeCustomizingDecoding += t2 - t1;
        timeDecoding += t3 - t2;
        if (codec instanceof TiffCodecTiming timing) {
            timeDecodingMain += timing.timeMain();
            timeDecodingBridge += timing.timeBridge();
            timeDecodingAdditional += timing.timeAdditional();
        } else {
            timeDecodingMain += t3 - t2;
        }
        timeCompleteDecoding += t4 - t3;
    }

    public void prepareEncodedTileForDecoding(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        if (tile.isEmpty()) {
            // - unlike full decoding, here it is better not to throw exception for empty tile
            return;
        }
        TiffTools.invertFillOrderIfRequested(tile);
        TiffIFD ifd = tile.ifd();
        final TiffCompression compression = ifd.getCompression();
        if (TiffIFD.isJpeg(compression)) {
            final byte[] data = tile.getEncodedData();
            final byte[] jpegTable = ifd.getValue(TiffIFD.JPEG_TABLES, byte[].class).orElse(null);
            // Structure of data:
            //      FF D8 (SOI, start of image)
            //      FF C0 (SOF0, start of frame, or some other marker)
            //      ...
            //      FF D9 (EOI, end of image)
            // Structure of jpegTable:
            //      FF D8 (SOI, start of image)
            //      FF DB (DQT, define quantization table(s)
            //      ...
            //      FF D9 (EOI, end of image)
            // From libtiff specification:
            //      When the JPEGTables field is present, it shall contain a valid JPEG
            //      "abbreviated table specification" datastream.  This datastream shall begin
            //      with SOI and end with EOI.
            if (data.length < 2 || data[0] != (byte) 0xFF || data[1] != (byte) 0xD8) {
                // - the same check is performed inside Java API ImageIO (JPEGImageReaderSpi),
                // and we prefer to repeat it here for better diagnostics
                if (compression == TiffCompression.JPEG) {
                    throw new TiffException(
                            "Invalid TIFF image: it is declared as JPEG, but the data are not actually JPEG");
                } else {
                    throw new UnsupportedTiffFormatException(
                            "Unsupported format of TIFF image: it is declared as \"" + compression.getCodecName() +
                                    "\", but the data are not actually JPEG");
                }
            }
            if (jpegTable != null) {
                if (jpegTable.length <= 4) {
                    throw new TiffException("Too short JPEGTables tag: only " + jpegTable.length + " bytes");
                }
                if ((long) jpegTable.length + (long) data.length - 4 >= Integer.MAX_VALUE) {
                    // - very improbable
                    throw new TiffException(
                            "Too large tile/strip at " + tile.index() + ": JPEG table length " +
                                    (jpegTable.length - 2) + " + number of bytes " +
                                    (data.length - 2) + " > 2^31-1");

                }
                final byte[] appended = new byte[jpegTable.length + data.length - 4];
                appended[0] = (byte) 0xFF;
                appended[1] = (byte) 0xD8;
                // - writing SOI
                System.arraycopy(jpegTable, 2, appended, 2, jpegTable.length - 4);
                // - skipping both SOI and EOI (2 first and 2 last bytes) from jpegTable
                System.arraycopy(data, 2, appended, jpegTable.length - 2, data.length - 2);
                // - skipping SOI (2 first bytes) from main data
                tile.setEncodedData(appended);
            }
        }
    }

    /**
     * Completes decoding tile after decoding by some {@link TiffCodec}. This method is automatically called
     * at the end of {@link #decode(TiffTile)} method.
     *
     * <p>First of all, this method always rearranges data in the file: if the codec returned
     * {@link TiffTile#isInterleaved() interleaved} data, this method
     * {@link TiffTile#separateSamplesIfNecessary() separates} them.
     * Interleaved data are the standard for internal pixel storage for simple formats like
     * {@link TiffCompression#LZW} and may be  returned by complex codecs like {@link TiffCompression#JPEG_2000}.
     *
     * <p>This method does 3 other corrections for some standard compression algorithms:
     * <ul>
     * <li>{@link TiffCompression#UNCOMPRESSED},</li>
     * <li>{@link TiffCompression#LZW},</li>
     * <li>{@link TiffCompression#DEFLATE},</li>
     * <li>{@link TiffCompression#PACK_BITS}.</li>
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
     * images, where dark colors are represented by smaller values and bright colors by higher, in particlular,
     * 0 is white, maximal value (255 for 8-bit) is black.</p>
     *
     * <p>However, the API of this class suppose that all returned images are RGB, RGBA or usual monochrome.
     * Complex codecs like JPEG perform necessary conversion themselves, but the simple codecs like
     * {@link TiffCompression#UNCOMPRESSED} do not this correction. This is performed by this method.
     * For CMYK or WhiteIsZero it means inversion of the pixel samples: v is transformed to MAX&minus;v,
     * where MAX is the maximal possible value (255 for 8-bit).</p>
     *
     * <p>3rd correction: conversion of YCbCr color space to usual RGB. It is a rare situation, when
     * the image is stored as YCbCr, however not in JPEG format, but uncompressed or, for example, as LZW.
     * This method performs necessary conversion to RGB (but only if the image is exactly 8-bit).</p>
     *
     * <p>Note: this method never increase number of <i>bytes</i>, necessary for representing a single pixel sample.
     * It can increase the number of <i>bits</i> per sample, but onty to the nearest greater integer number of
     * bytes: 1..7 bits are transformed to 8 bits/sample, 9..15 to 16 bits/sample, 17..23 to 24 bits/sample etc.
     * Thus, this method <b>does not unpack 3-byte samples</b> (to 4-byte) and
     * <b>does not unpack 16- or 24-bit</b> floating-point formats. These cases
     * are processed after reading all tiles inside {@link #readSamples(TiffMap, int, int, int, int)}
     * method, if {@link #isAutoUnpackUnusualPrecisions()} flag is set, or may be performed by external
     * code with help of {@link TiffTools#unpackUnusualPrecisions(byte[], TiffIFD, int, int, boolean)} method.
     *
     * <p>This method does not allow 5, 6, 7 or greater than 8 bytes/sample
     * (but 8 bytes/sample is allowed: it is probably <tt>double</tt> precision).</p>
     *
     * @param tile the tile that should be corrected.
     */
    public void completeDecoding(TiffTile tile) throws TiffException {
        // scifio.tiff().undifference(tile.getDecodedData(), tile.ifd());
        // - this solution requires using SCIFIO context class; it is better to avoid this
        TiffTools.unsubtractPredictionIfRequested(tile);

        if (USE_LEGACY_UNPACK_BYTES) {
            byte[] samples = new byte[tile.map().tileSizeInBytes()];
            TiffTools.unpackBytesLegacy(samples, 0, tile.getDecodedData(), tile.ifd());
            tile.setDecodedData(samples);
            tile.setInterleaved(false);
        } else {
            if (!TiffTools.separateUnpackedSamples(tile)) {
                if (!TiffTools.separateYCbCrToRGB(tile)) {
                    TiffTools.separateBitsAndInvertValues(tile,
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
        DetailedIFD ifd = tile.ifd();
        final int samplesPerPixel = ifd.getSamplesPerPixel();
        final int planarConfig = ifd.getPlanarConfiguration();
        final int bytesPerSample = ifd.getBytesPerSampleBasedOnBits();
        final int effectiveChannels = planarConfig == DetailedIFD.PLANAR_CONFIG_SEPARATE ? 1 : samplesPerPixel;
        assert samples.length == ((long) tileSizeX * (long) tileSizeY * bytesPerSample * effectiveChannels);
        if (planarConfig == DetailedIFD.PLANAR_CONFIG_SEPARATE && !ifd.isTiled() && ifd.getSamplesPerPixel() > 1) {
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

    public TiffMap newMap(TiffIFD ifd) {
        return new TiffMap(ifd).buildGrid();
        // - building grid is useful to perform loops on all tiles
    }

    public byte[] readSamples(TiffMap map) throws IOException {
        return readSamples(map, false);
    }

    public byte[] readSamples(TiffMap map, boolean storeTilesInMap) throws IOException {
        return readSamples(map, 0, 0, map.dimX(), map.dimY(), storeTilesInMap);
    }

    /**
     * Reads samples in <tt>byte[]</tt> array.
     *
     * <p>Note: you should not change IFD in a parallel thread while calling this method.
     *
     * @return loaded samples in a normalized form of byte sequence.
     */
    public byte[] readSamples(TiffMap map, int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return readSamples(map, fromX, fromY, sizeX, sizeY, false);
    }

    public byte[] readSamples(TiffMap map, int fromX, int fromY, int sizeX, int sizeY, boolean storeTilesInMap)
            throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        long t1 = debugTime();
        clearTiming();
        TiffTools.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        // - note: we allow this area to be outside the image
        final int numberOfChannels = map.numberOfChannels();
        final TiffIFD ifd = map.ifd();
        final int size = ifd.sizeOfRegion(sizeX, sizeY);
        assert sizeX >= 0 && sizeY >= 0 : "sizeOfIFDRegion didn't check sizes accurately: " + sizeX + "fromX" + sizeY;
        byte[] samples = new byte[size];

        if (byteFiller != 0) {
            // - samples array is already zero-filled by Java
            Arrays.fill(samples, 0, size, byteFiller);
        }
        // - important for a case when the requested area is outside the image;
        // old SCIFIO code did not check this and could return undefined results
        long t2 = debugTime();

        readTiles(map, samples, fromX, fromY, sizeX, sizeY, storeTilesInMap);

        long t3 = debugTime();
        boolean interleave = false;
        if (interleaveResults) {
            byte[] newSamples = TiffTools.toInterleavedSamples(
                    samples, numberOfChannels, map.bytesPerSample(), sizeX * sizeY);
            interleave = newSamples != samples;
            samples = newSamples;
        }
        long t4 = debugTime();
        boolean unusualPrecision = false;
        if (autoUnpackUnusualPrecisions) {
            byte[] newSamples = TiffTools.unpackUnusualPrecisions(
                    samples, ifd, numberOfChannels, sizeX * sizeY, autoScaleWhenIncreasingBitDepth);
            unusualPrecision = newSamples != samples;
            samples = newSamples;
            // - note: the size of sample array can be increased here!
        }
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t5 = debugTime();
            LOG.log(System.Logger.Level.DEBUG, String.format(Locale.US,
                    "%s read %dx%dx%d samples (%.3f MB) in %.3f ms = " +
                            "%.3f initializing + %.3f read/decode " +
                            "(%.3f read + %.3f customize/bit-order + %.3f decode " +
                            " (= %.3f main + %.3f bridge + %.3f additional) " +
                            "+ %.3f complete)" +
                            "%s%s, %.3f MB/s",
                    getClass().getSimpleName(),
                    numberOfChannels, sizeX, sizeY, size / 1048576.0,
                    (t5 - t1) * 1e-6,
                    (t2 - t1) * 1e-6,
                    (t3 - t2) * 1e-6,
                    timeReading * 1e-6,
                    timeCustomizingDecoding * 1e-6,
                    timeDecoding * 1e-6,
                    timeDecodingMain * 1e-6,
                    timeDecodingBridge * 1e-6,
                    timeDecodingAdditional * 1e-6,
                    timeCompleteDecoding * 1e-6,
                    interleave ?
                            String.format(Locale.US, " + %.3f interleave", (t4 - t3) * 1e-6) : "",
                    unusualPrecision ?
                            String.format(Locale.US, " + %.3f unusual precisions", (t5 - t4) * 1e-6) : "",
                    size / 1048576.0 / ((t5 - t1) * 1e-9)));
        }
        return samples;
    }

    public Object readJavaArray(TiffMap map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        return readJavaArray(map, 0, 0, map.dimX(), map.dimY());
    }

    public Object readJavaArray(TiffMap map, int fromX, int fromY, int sizeX, int sizeY) throws IOException {
        return readJavaArray(map, fromX, fromY, sizeX, sizeY, false);
    }

    public Object readJavaArray(TiffMap map, int fromX, int fromY, int sizeX, int sizeY, boolean storeTilesInMap)
            throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        final byte[] samples = readSamples(map, fromX, fromY, sizeX, sizeY, storeTilesInMap);
        long t1 = debugTime();
        final TiffSampleType sampleType = map.sampleType();
        if (!autoUnpackUnusualPrecisions && map.bytesPerSample() != sampleType.bytesPerSample()) {
            throw new IllegalStateException("Cannot convert TIFF pixels, " + map.bytesPerSample() +
                    " bytes/sample, to \"" + sampleType.elementType() + "\" " + sampleType.bytesPerSample() +
                    "-byte Java type: unpacking unusual prevision mode is disabled");
        }
        final Object samplesArray = TiffTools.bytesToJavaArray(samples, sampleType, isLittleEndian());
        if (TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
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

    public Matrix<UpdatablePArray> readMatrix(TiffMap map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        return readMatrix(map, 0, 0, map.dimX(), map.dimY());
    }

    public Matrix<UpdatablePArray> readMatrix(TiffMap map, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return readMatrix(map, fromX, fromY, sizeX, sizeY, false);
    }

    public Matrix<UpdatablePArray> readMatrix(
            TiffMap map,
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap)
            throws IOException {
        final Object samplesArray = readJavaArray(map, fromX, fromY, sizeX, sizeY, storeTilesInMap);
        return TiffTools.asMatrix(samplesArray, sizeX, sizeY, map.numberOfChannels(), interleaveResults);
    }

    @Override
    public void close() throws IOException {
        synchronized (fileLock) {
            in.close();
        }
    }

    /**
     * This method is called before using codec options for decompression.
     * You can add here some additional customizations.
     */
    protected CodecOptions correctReadingOptions(CodecOptions codecOptions, TiffTile tile, TiffCodec customCodec)
            throws TiffException {
        return codecOptions;
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
                        " bytes (we require minimum " + MINIMAL_ALLOWED_TIFF_FILE_LENGTH + " bytes for valid TIFF)");
            }
            final int endianOne = in.read();
            final int endianTwo = in.read();
            // byte order must be II or MM
            final boolean littleEndian = endianOne == TiffConstants.LITTLE && endianTwo == TiffConstants.LITTLE; // II
            final boolean bigEndian = endianOne == TiffConstants.BIG && endianTwo == TiffConstants.BIG; // MM
            if (!littleEndian && !bigEndian) {
                throw new TiffException("The file" + prettyInName() + " is not TIFF");
            }

            // check magic number (42)
            in.setLittleEndian(littleEndian);
            final short magic = in.readShort();
            final boolean bigTiff = magic == TiffConstants.BIG_TIFF_MAGIC_NUMBER;
            bigTiffReference.set(bigTiff);
            if (magic != TiffConstants.MAGIC_NUMBER && magic != TiffConstants.BIG_TIFF_MAGIC_NUMBER) {
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

    private CodecOptions buildReadingOptions(TiffTile tile, TiffCodec customCodec) throws TiffException {
        TiffIFD ifd = tile.ifd();
        CodecOptions codecOptions = customCodec instanceof JPEGCodec ?
                JPEGCodecOptions.getDefaultOptions(this.codecOptions)
                        .setPhotometricInterpretation(ifd.getPhotometricInterpretation())
                        .setYCbCrSubsampling(ifd.getYCbCrSubsampling()) :
                new CodecOptions(this.codecOptions);
        codecOptions.littleEndian = ifd.isLittleEndian();
        final int samplesLength = tile.getSizeInBytes();
        // - Note: it may be LESS than a usual number of samples in the tile/strip.
        // Current readEncodedTile() always returns full-size tile without cropping
        // (see comments inside that method), but the user CAN crop last tile/strip in an external code.
        // Old SCIFIO code did not detect this situation, in particular, did not distinguish between
        // last and usual strips in stripped image, and its behaviour could be described by the following assignment:
        //      final int samplesLength = tile.map().tileSizeInBytes();
        // For many codecs (like DEFLATE or JPEG) this is not important, but at least
        // LZWCodec creates result array on the base of codecOptions.maxBytes.
        // If it will be invalid (too large) value, returned decoded data will be too large,
        // and this class will throw an exception "data may be lost" in further
        // tile.completeNumberOfPixels() call.
        codecOptions.maxBytes = Math.max(samplesLength, tile.getStoredDataLength());
        if (USE_LEGACY_UNPACK_BYTES) {
            codecOptions.interleaved = true;
            // - old-style unpackBytes does not "understand" already-separated tiles
        } else {
            codecOptions.interleaved =
                    !(customCodec instanceof JPEGCodec || ifd.getCompression() == TiffCompression.JPEG);
        }
        // - ExtendedJPEGCodec or standard codec JPEFCodec (it may be chosen below by scifio.codec(),
        // but we are sure that JPEG compression will be served by it even in future versions):
        // they "understand" this settings well (unlike LZW or DECOMPRESSED codecs,
        // which suppose that data are interleaved according TIFF format specification).
        // Value "true" is necessary for other codecs, that work with high-level classes (like JPEG or JPEG-2000) and
        // need to be instructed to interleave results (unlike LZW or DECOMPRESSED, which work with data "as-is").
        return correctReadingOptions(codecOptions, tile, customCodec);
    }

    // Note: this method does not store tile in the tile map.
    private void readTiles(
            TiffMap map,
            byte[] resultSamples,
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean storeTilesInMap)
            throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(resultSamples, "Null result samples");
        assert sizeX >= 0 && sizeY >= 0;
        // Note: we cannot process image larger than 2^31 x 2^31 pixels,
        // though TIFF supports maximal sizes 2^32 x 2^32
        // (IFD.getImageWidth/getImageLength do not allow so large results)
        if (sizeX == 0 || sizeY == 0) {
            // - if no pixels are updated, no need to expand the map and to check correct expansion
            return;
        }

        final int mapTileSizeX = map.tileSizeX();
        final int mapTileSizeY = map.tileSizeY();
        final int bytesPerSample = map.bytesPerSample();
        final int numberOfSeparatedPlanes = map.numberOfSeparatedPlanes();
        final int samplesPerPixel = map.tileSamplesPerPixel();

        final int toX = Math.min(fromX + sizeX, cropTilesToImageBoundaries ? map.dimX() : Integer.MAX_VALUE);
        final int toY = Math.min(fromY + sizeY, cropTilesToImageBoundaries ? map.dimY() : Integer.MAX_VALUE);
        // - crop by image sizes to avoid reading unpredictable content of the boundary tiles outside the image
        final int minXIndex = Math.max(0, divFloor(fromX, mapTileSizeX));
        final int minYIndex = Math.max(0, divFloor(fromY, mapTileSizeY));
        if (minXIndex >= map.gridTileCountX() || minYIndex >= map.gridTileCountY() || toX < fromX || toY < fromY) {
            return;
        }
        final int maxXIndex = Math.min(map.gridTileCountX() - 1, divFloor(toX - 1, mapTileSizeX));
        final int maxYIndex = Math.min(map.gridTileCountY() - 1, divFloor(toY - 1, mapTileSizeY));
        if (minYIndex > maxYIndex || minXIndex > maxXIndex) {
            // - possible when fromX < 0 or fromY < 0
            return;
        }
        final int tileOneChannelRowSizeInBytes = mapTileSizeX * bytesPerSample;
        final int samplesOneChannelRowSizeInBytes = sizeX * bytesPerSample;

        for (int p = 0; p < numberOfSeparatedPlanes; p++) {
            // - for a rare case PlanarConfiguration=2 (RRR...GGG...BBB...)
            for (int yIndex = minYIndex; yIndex <= maxYIndex; yIndex++) {
                final int tileStartY = Math.max(yIndex * mapTileSizeY, fromY);
                final int fromYInTile = tileStartY % mapTileSizeY;
                final int yDiff = tileStartY - fromY;

                for (int xIndex = minXIndex; xIndex <= maxXIndex; xIndex++) {
                    final int tileStartX = Math.max(xIndex * mapTileSizeX, fromX);
                    final int fromXInTile = tileStartX % mapTileSizeX;
                    final int xDiff = tileStartX - fromX;

                    final TiffTile tile = readTile(map.multiplaneIndex(p, xIndex, yIndex));
                    if (storeTilesInMap) {
                        map.put(tile);
                    }
                    if (tile.isEmpty()) {
                        continue;
                    }
                    if (!tile.isSeparated()) {
                        throw new AssertionError("Illegal behavior of readTile: it returned interleaved tile!");
                        // - theoretically possible in subclasses
                    }
                    byte[] data = tile.getDecodedData();

                    final int tileSizeX = tile.getSizeX();
                    final int tileSizeY = tile.getSizeY();
                    final int sizeXInTile = Math.min(toX - tileStartX, tileSizeX - fromXInTile);
                    assert sizeXInTile > 0 : "sizeXInTile=" + sizeXInTile;
                    final int sizeYInTile = Math.min(toY - tileStartY, tileSizeY - fromYInTile);
                    assert sizeYInTile > 0 : "sizeYInTile=" + sizeYInTile;

                    final int partSizeXInBytes = sizeXInTile * bytesPerSample;
                    for (int s = 0; s < samplesPerPixel; s++) {
                        int tOffset = (((s * tileSizeY) + fromYInTile) * tileSizeX + fromXInTile) * bytesPerSample;
                        int samplesOffset = (((p + s) * sizeY + yDiff) * sizeX + xDiff) * bytesPerSample;
                        for (int i = 0; i < sizeYInTile; i++) {
                            System.arraycopy(data, tOffset, resultSamples, samplesOffset, partSizeXInBytes);
                            tOffset += tileOneChannelRowSizeInBytes;
                            samplesOffset += samplesOneChannelRowSizeInBytes;
                        }
                    }
                }
            }
        }
    }

    private static int cachedByteCountWithCompatibilityTrick(TiffIFD ifd, int index) throws TiffException {
        final boolean tiled = ifd.hasTileInformation();
        final int tag = tiled ? TiffIFD.TILE_BYTE_COUNTS : TiffIFD.STRIP_BYTE_COUNTS;
        Object value = ifd.get(tag);
        if (value instanceof long[] byteCounts &&
                byteCounts.length == 1 &&
                byteCounts[0] == (int) byteCounts[0]) {
            // - possible in a rare case:
            // we use TiffParser.getIFD to read this IFD,
            // and this file is Big-TIFF,
            // and if we set "equal-strip" mode by TiffParser.setAssumeEqualStrips
            return (int) byteCounts[0];
        }
        return ifd.cachedTileOrStripByteCount(index);
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
        final int bytesPerEntry = bigTiff ? TiffConstants.BIG_TIFF_BYTES_PER_ENTRY : TiffConstants.BYTES_PER_ENTRY;
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
     * Read a file offset. For bigTiff, a 64-bit number is read. For other Tiffs,
     * a 32-bit number is read and possibly adjusted for a possible carry-over
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

        LOG.log(System.Logger.Level.TRACE, () ->
                "Reading entry " + entry.tag() + " from " + offset + "; type=" + type + ", count=" + count);

        in.seek(offset);
        switch (type) {
            case TiffIFD.TIFF_BYTE -> {
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
            case TiffIFD.TIFF_ASCII -> {
                // 8-bit byte that contain a 7-bit ASCII code;
                // the last byte must be NUL (binary zero)
                final byte[] ascii = new byte[count];
                in.read(ascii);

                // count number of null terminators
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
            case TiffIFD.TIFF_SHORT -> {
                // 16-bit (2-byte) unsigned integer
                if (count == 1) {
                    return in.readUnsignedShort();
                }
                if (OPTIMIZE_READING_IFD_ARRAYS) {
                    final byte[] bytes = readIFDBytes(in, 2 * (long) count);
                    final short[] shorts = TiffTools.bytesToShortArray(bytes, in.isLittleEndian());
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
            case TiffIFD.TIFF_LONG, TiffIFD.TIFF_IFD -> {
                // 32-bit (4-byte) unsigned integer
                if (count == 1) {
                    return in.readInt() & 0xFFFFFFFFL;
                }
                if (OPTIMIZE_READING_IFD_ARRAYS) {
                    final byte[] bytes = readIFDBytes(in, 4 * (long) count);
                    final int[] ints = TiffTools.bytesToIntArray(bytes, in.isLittleEndian());
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
            case TiffIFD.TIFF_LONG8, TiffIFD.TIFF_SLONG8, TiffIFD.TIFF_IFD8 -> {
                if (count == 1) {
                    return in.readLong();
                }
                if (OPTIMIZE_READING_IFD_ARRAYS) {
                    final byte[] bytes = readIFDBytes(in, 8 * (long) count);
                    return TiffTools.bytesToLongArray(bytes, in.isLittleEndian());
                } else {
                    long[] longs = new long[count];
                    for (int j = 0; j < count; j++) {
                        longs[j] = in.readLong();
                    }
                    return longs;
                }
            }
            case TiffIFD.TIFF_RATIONAL, TiffIFD.TIFF_SRATIONAL -> {
                // Two LONGs or SLONGs: the first represents the numerator of a fraction; the second, the denominator
                if (count == 1) {
                    return new TiffRational(in.readInt(), in.readInt());
                }
                final TiffRational[] rationals = new TiffRational[count];
                for (int j = 0; j < count; j++) {
                    rationals[j] = new TiffRational(in.readInt(), in.readInt());
                }
                return rationals;
            }
            case TiffIFD.TIFF_SBYTE, TiffIFD.TIFF_UNDEFINED -> {
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
            case TiffIFD.TIFF_SSHORT -> {
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
            case TiffIFD.TIFF_SLONG -> {
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
            case TiffIFD.TIFF_FLOAT -> {
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
            case TiffIFD.TIFF_DOUBLE -> {
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
        final int bytesPerElement = TiffIFD.entryTypeSize(entryType);
        // - will be zero for unknown type; in this case we will set valueOffset=in.offset() below
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
                "Reading IFD entry: %s - %s", result, TiffIFD.ifdTagName(result.tag(), true)));
        return result;
    }

    static int divFloor(int a, int b) {
        assert b > 0;
        return a >= 0 ? a / b : (a - b + 1) / b;
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

    private static long debugTime() {
        return TiffTools.BUILT_IN_TIMING && LOGGABLE_DEBUG ? System.nanoTime() : 0;
    }
}
