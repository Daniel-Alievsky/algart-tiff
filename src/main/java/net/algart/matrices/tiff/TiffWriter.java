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

import net.algart.arrays.Matrices;
import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.arrays.PackedBitArraysPer8;
import net.algart.io.awt.ImageToMatrix;
import net.algart.math.IRectangularArea;
import net.algart.matrices.tiff.codecs.TiffCodec;
import net.algart.matrices.tiff.data.TiffPacking;
import net.algart.matrices.tiff.data.TiffPrediction;
import net.algart.matrices.tiff.tags.*;
import net.algart.matrices.tiff.tiles.*;
import org.scijava.Context;
import org.scijava.io.handle.DataHandle;
import org.scijava.io.location.BytesLocation;
import org.scijava.io.location.Location;

import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Writes the TIFF format.
 *
 * <p>This object is internally synchronized and thread-safe for concurrent use.
 * However, you should not modify objects, passed to the methods of this class, from a parallel thread;
 * in particular, it concerns the {@link TiffIFD} arguments and Java-arrays with samples.
 * The same is true for the result of {@link #output()} method.</p>
 */
public class TiffWriter implements Closeable {
    /**
     * If the file grows to about this limit and {@link #setBigTiff(boolean) big-TIFF} mode is not set,
     * attempt to write new IFD at the file end by methods of this class throw IO exception.
     * While writing tiles, an exception will be thrown only while exceeding the limit <code>2^32-1</code>
     * (~280 MB greater than this value {@value}).
     */
    public static final long MAXIMAL_ALLOWED_32BIT_IFD_OFFSET = 4_000_000_000L;

    private static final boolean AVOID_LONG8_FOR_ACTUAL_32_BITS = true;
    // - If was necessary for some old programs (like Aperio Image Viewer), which
    // did not understand LONG8 values for some popular tags like image sizes.
    // In any case, real BigTIFF files usually store most tags in standard LONG type (32 bits), not in LONG8.

    private static final boolean AUTO_INTERLEAVE_SOURCE = true;
    // - Should be true. The alternative mode (false), where the source data may already be interleaved,
    // was implemented in the past for compatibility with TiffSaver class.
    // It is no longer supported since the version 1.4.0, because this makes the behavior
    // of TiffWriter nonpredictable without analyzing this mode; but it still can be enabled by this flag.
    // Note that the alternate mode (false) was never supported in writeChannels and writeBufferedImage methods.
    // IF YOU CHANGE IT, YOU MUST CORRECT ALSO TiffWriteMap.AUTO_INTERLEAVE_SOURCE
    // (and, for testing, the "interleaved" variable in TiffWriterTest)

    private static final System.Logger LOG = System.getLogger(TiffWriter.class.getName());
    private static final boolean LOGGABLE_DEBUG = LOG.isLoggable(System.Logger.Level.DEBUG);

    private boolean bigTiff = false;
    private boolean writingForwardAllowed = true;
    private boolean smartFormatCorrection = false;
    private TiffCodec.Options codecOptions = new TiffCodec.Options();
    private boolean enforceUseExternalCodec = false;
    private Double compressionQuality = null;
    private Double losslessCompressionLevel = null;
    private boolean preferRGB = false;
    private boolean alwaysWriteToFileEnd = false;
    private boolean missingTilesAllowed = false;
    private byte byteFiller = 0;
    private Consumer<TiffTile> tileInitializer = this::fillEmptyTile;
    private volatile Context context = null;

    private final DataHandle<? extends Location> out;
    private volatile TiffReader reader = null;
    private volatile Object scifio = null;

    private final Object fileLock = new Object();

    private final LinkedHashSet<Long> ifdOffsets = new LinkedHashSet<>();
    private volatile long positionOfLastIFDOffset = -1;

    private volatile TiffWriteMap lastMap = null;

    private long timeWriting = 0;
    private long timePreparingEncoding = 0;
    private long timeCustomizingEncoding = 0;
    private long timeEncoding = 0;
    private long timeEncodingMain = 0;
    private long timeEncodingBridge = 0;
    private long timeEncodingAdditional = 0;

    /**
     * Equivalent to <code>new {@link #TiffWriter(Path, TiffCreateMode)
     * TiffWriter}(file, {@link TiffCreateMode#NO_ACTIONS NO_ACTIONS)}</code>.
     *
     * <p>Note: unlike classes like {@link java.io.FileWriter},
     * this constructor <b>does not try to open or create file</b>.
     * If you need, you can use another constructor with the argument {@link TiffCreateMode}, for example:</p>
     * <pre>
     *     var writer = new {@link #TiffWriter(Path, TiffCreateMode)
     *     TiffWriter}(path, {@link TiffCreateMode#CREATE CreateMode.CREATE});
     * </pre>
     *
     * @param file output TIFF tile.
     */
    public TiffWriter(Path file) {
        this(TiffReader.getFileHandle(file));
    }

    /**
     * Creates a new TIFF writer.
     *
     * <p>If the argument <code>createMode</code> is {@link TiffCreateMode#NO_ACTIONS},
     * the constructor does not try to open or create a file and, so, never
     * throw {@link IOException}.
     * This behavior <b>differ</b> from the constructor of {@link java.io.FileWriter#FileWriter(File) FileWriter}
     * and similar classes, which create and open a file.
     * In this case, you may create the file&nbsp;&mdash; or open an existing TIFF file&nbsp;&mdash; later via
     * one of methods {@link #create()} or {@link #open(boolean)}.
     * Before doing so, you can customize this object, for example, with help of
     * {@link #setBigTiff(boolean)}, {@link #setLittleEndian(boolean)} and other methods.
     *
     * <p>If the argument <code>createMode</code> is one of the values
     * {@link TiffCreateMode#CREATE},
     * {@link TiffCreateMode#CREATE_BIG},
     * {@link TiffCreateMode#CREATE_LE},
     * {@link TiffCreateMode#CREATE_LE_BIG},
     * the constructor automatically removes the file at the specified path, if it exists,
     * and calls {@link #create()} method.
     * The variants {@link TiffCreateMode#CREATE_BIG}, {@link TiffCreateMode#CREATE_LE_BIG}
     * create a file written in BigTIFF format (allowing to store &ge;4GB data).
     * The variants {@link TiffCreateMode#CREATE_LE}, {@link TiffCreateMode#CREATE_LE_BIG}
     * create a file with little-endian byte order (the default is big-endian).
     *
     * <p>The other options allow you to open an existing file using the {@link #openForAppend()}
     * or {@link #openExisting()} methods.
     * The variants {@link TiffCreateMode#APPEND}, {@link TiffCreateMode#APPEND_BIG},
     * {@link TiffCreateMode#APPEND_LE}, {@link TiffCreateMode#APPEND_LE_BIG} call the
     * {@link #openForAppend()} method.
     * The difference is what to do if the file does not exist:
     * whether the new TIFF file should be BigTIFF or not, little-endian or big-endian,
     * as in the cases of {@link TiffCreateMode#CREATE}, {@link TiffCreateMode#CREATE_BIG},
     * {@link TiffCreateMode#CREATE_LE}, {@link TiffCreateMode#CREATE_LE_BIG}.
     *
     * <p>The variant {@link TiffCreateMode#OPEN_EXISTING} calls {@link #openExisting()} method;
     * in this case, the BigTIFF mode and the byte order are determined automatically from the existing file.
     *
     * <p>In the case of the I/O exception, the file is automatically closed.
     *
     * <p>In all cases excepting {@link TiffCreateMode#NO_ACTIONS},
     * the behavior is alike {@link java.io.FileWriter#FileWriter(File) FileWriter constructor}.
     *
     * <p>This constructor is the simplest way to create a new TIFF file and automatically open
     * it by writing the standard TIFF header. After that, this object is ready for adding new TIFF images.
     * Instead, you may use the single-argument constructor {@link #TiffWriter(Path)},
     * perform necessary customizing, and then call {@link #create()} or {@link #open(boolean)} method.
     *
     * @param file       output TIFF tile.
     * @param createMode what do you need to do with this file?
     * @throws IOException in the case of any I/O errors.
     */
    public TiffWriter(Path file, TiffCreateMode createMode) throws IOException {
        this(openWithDeletingPreviousFileIfRequested(file, createMode));
        try {
            createMode.configureWriter(this);
        } catch (IOException exception) {
            try {
                out.close();
            } catch (Exception ignored) {
            }
            throw exception;
        }
    }

    /**
     * Universal constructor, called from other constructors.
     *
     * <p>Note: this method does not do anything with the file stream, in particular, does not call
     * {@link #create()} method. You can do this later.
     *
     * <p>This constructor never throws an exception.
     * This is helpful because it allows making constructors in subclasses,
     * which do not declare any exceptions to be thrown.
     *
     * @param outputStream output stream.
     */
    public TiffWriter(DataHandle<? extends Location> outputStream) {
        Objects.requireNonNull(outputStream, "Null data handle (output stream)");
        this.out = outputStream;
        // - we do not use WriteBufferDataHandle here: this is not too important for efficiency
    }

    public TiffReader reader() throws IOException {
        return reader(false);
    }

    /**
     * Returns the TIFF reader for reading the same file stream {@link #output()} used by this object.
     * You <b>don't need</b> to close it: this stream will be closed when closing this writer.
     *
     * <p>This reader is created in {@link TiffOpenMode#NO_CHECKS} mode.
     * Caching in the reader is disabled by
     * {@link TiffReader#setCaching(boolean) setCaching(false)}: usually this reader
     * should be used while you are modifying the TIFF, so the cache may work incorrectly.
     * But you can enable caching when you finish the writing.
     *
     * <p>The returned reference is stored inside this object, and will be returned by further calls
     * of this method, unless you set <code>alwaysCreateNew=true</code>.
     *
     * @param alwaysCreateNew whether you need to ignore the previously created reader (it if exists)
     *                        and create a new one.
     * @return new TIFF reader.
     * @throws IOException in the case of any I/O errors.
     */
    public TiffReader reader(boolean alwaysCreateNew) throws IOException {
        synchronized (fileLock) {
            if (alwaysCreateNew || this.reader == null) {
                this.reader = newReader(TiffOpenMode.NO_CHECKS).setCaching(false);
            }
            return this.reader;
        }
    }

    public TiffReader newReader(TiffOpenMode openMode) throws IOException {
        return new TiffReader(out, openMode, false);
    }

    /**
     * Returns whether we are writing little-endian data.
     */
    public boolean isLittleEndian() {
        synchronized (fileLock) {
            return out.isLittleEndian();
        }
    }

    /**
     * Sets whether little-endian data should be written.
     * This flag must be set before creating the file by {@link #create()} method.
     * The default order is <b>big-endian</b>.
     *
     * @param littleEndian new byte while writing the file: big-endian (<code>false</code>) or
     *                     little-endian (<code>true</code>); default is <code>false</code>.
     */
    public TiffWriter setLittleEndian(final boolean littleEndian) {
        synchronized (fileLock) {
            out.setLittleEndian(littleEndian);
        }
        return this;
    }

    /**
     * Returns <code>{@link #isLittleEndian()} ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN</code>.
     */
    public ByteOrder getByteOrder() {
        return isLittleEndian() ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    }

    /**
     * Equivalent to <code>{@link #setLittleEndian setLittleEndian}(byteOrder == ByteOrder.LITTLE_ENDIAN)</code>.
     * The default order is <b>big-endian</b>.
     *
     * @param byteOrder desired byte order.
     * @throws NullPointerException if the argument is <code>null</code>.
     */
    public TiffWriter setByteOrder(ByteOrder byteOrder) {
        Objects.requireNonNull(byteOrder);
        return setLittleEndian(byteOrder == ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * Returns whether we are writing BigTIFF data.
     */
    public boolean isBigTiff() {
        return bigTiff;
    }

    /**
     * Sets whether a BigTIFF file should be created.
     * This flag must be set before creating the file by {@link #create()} method.
     * Default value is <code>false</code>.
     */
    public TiffWriter setBigTiff(boolean bigTiff) {
        this.bigTiff = bigTiff;
        return this;
    }

    public TiffWriter setFormatLike(TiffReader reader) {
        Objects.requireNonNull(reader, "Null TIFF reader");
        this.setBigTiff(reader.isBigTiff());
        this.setLittleEndian(reader.isLittleEndian());
        return this;
    }

    public boolean isWritingForwardAllowed() {
        return writingForwardAllowed;
    }

    public TiffWriter setWritingForwardAllowed(boolean writingForwardAllowed) {
        this.writingForwardAllowed = writingForwardAllowed;
        return this;
    }

    public boolean isSmartFormatCorrection() {
        return smartFormatCorrection;
    }

    /**
     * Sets smart IFD correction mode.
     *
     * <p>IFD, offered by the user for writing TIFF image (usually with help of {@link #newFixedMap(TiffIFD)} method),
     * may contain specification, which are incorrect or not supported by this class. For example,
     * the user may specify {@link TiffIFD#putPhotometricInterpretation(TagPhotometricInterpretation)
     * photometric interpretation} {@link TagPhotometricInterpretation#RGB_PALETTE}, but not provide actual
     * palette via the corresponding TIFF entry, or may specify 1000000 bits/pixel etc.</p>
     *
     * <p>If the settings in the specified IFD are absolutely incorrect, this class always throws
     * {@link TiffException}. If the settings look possible in principle, but this class does not support
     * writing in this mode, the behavior depends on the flag setting by this method.</p>
     *
     * <p>If this mode is set to <code>true</code> (the "smart" IFD correction), the writer may try to change IFD to
     * some similar settings, so that it will be able to write the image. In particular, if number of bits
     * per sample is not divided by 8 (like 4 bits/sample or "HiRes" RGB image with 5+5+5 bits/pixel),
     * the number of bits will be automatically increased up to the nearest supported bit depth: 8, 16 or 32
     * (for floating-point images, up to 32- or 64-bit <code>float</code>/<code>double</code> precision).
     * If you specified YCbCr photometric interpretation for <i>non-JPEG</i> compression &mdash;
     * for example, uncompressed, LZW, or Deflate compression &mdash; it will be automatically replaced with RGB
     * (this class supports YCbCr encoding for JPEG only). And so on.</p>
     *
     * <p>If this mode is not set (this flag is <code>false</code>), such settings will lead to an exception.
     * In this case, we guarantee that the TIFF writer never changes existing entries in the IFD but may only
     * <i>add</i> some tags if they are necessary.</p>
     *
     * <p>Default value is <code>false</code>. You may set it to <code>true</code>, for example, when you need
     * to encode a new copy of some existing TIFF file.</p>
     *
     * <p>Note that this flag is passed to {@link #correctFormatForEncoding(TiffIFD, boolean)} method,
     * called inside <code>writeSamples</code>/<code>writeJavaArray</code> methods.
     *
     * @param smartFormatCorrection <code>true</code> means that we enable smart correction of the specified IFD
     *                              and do not require strictly accurate, fully supported IFD settings.
     * @return a reference to this object.
     */
    public TiffWriter setSmartFormatCorrection(boolean smartFormatCorrection) {
        this.smartFormatCorrection = smartFormatCorrection;
        return this;
    }

    public TiffCodec.Options getCodecOptions() {
        return codecOptions.clone();
    }

    /**
     * Sets the codec options.
     *
     * @param codecOptions The value to set.
     * @return a reference to this object.
     */
    public TiffWriter setCodecOptions(final TiffCodec.Options codecOptions) {
        this.codecOptions = Objects.requireNonNull(codecOptions, "Null codecOptions").clone();
        return this;
    }

    public boolean isEnforceUseExternalCodec() {
        return enforceUseExternalCodec;
    }

    /**
     * Forces the writer to use an external codec via {@link #encodeByExternalCodec}
     * method even for standard compressions.
     *
     * <p>Note that this only makes sense if:</p>
     * <ul>
     *     <li>you override that method;</li>
     *     <li>you are using this library together with SCIFIO.</li>
     * </ul>
     *
     * @param enforceUseExternalCodec whether external codecs should be used even for standard compressions.
     * @return a reference to this object.
     */
    public TiffWriter setEnforceUseExternalCodec(boolean enforceUseExternalCodec) {
        this.enforceUseExternalCodec = enforceUseExternalCodec;
        return this;
    }

    public boolean hasCompressionQuality() {
        return compressionQuality != null;
    }

    public Double getCompressionQuality() {
        return compressionQuality;
    }

    public TiffWriter setCompressionQuality(Double compressionQuality) {
        return compressionQuality == null ? removeCompressionQuality() :
                setCompressionQuality(compressionQuality.doubleValue());
    }

    /**
     * Sets the compression quality for lossy compression formats.
     *
     * <p>Possible values are format-specific.
     * For JPEG, they should be between 0.0 and 1.0 (1.0 means the best quality).
     * For JPEG-2000, the maximal possible value is <code>Double.MAX_VALUE</code>, that means loss-less compression.
     *
     * <p>If this method was not called or after {@link #removeCompressionQuality()}, the compression quality is
     * not specified.
     * In this case, some default quality will be used. In particular, it will be 1.0 for JPEG (maximal JPEG quality),
     * 10 for JPEG-2000 (compression code 33003) or alternative JPEG-200 (code 33005),
     * <code>Double.MAX_VALUE</code> for lose-less JPEG-2000 ({@link TagCompression#JPEG_2000_LOSSY}, code 33004).
     * Note that the only difference between lose-less JPEG-2000 and the standard JPEG-2000 is these default values:
     * if this method is called, both compressions work identically (but write different TIFF compression tags).
     *
     * <p>Note: the {@link TiffCodec.Options#setCompressionQuality(Double) quality}, that can be set via
     * {@link #setCodecOptions(TiffCodec.Options)} method, is ignored,
     * if this value is set to non-{@code null} value.
     *
     * <p>Please <b>note</b> that this parameter may be different for different IFDs and
     * even for different tiles inside the same IFD.
     * In this case, you need to call this method every time before updating IFD,
     * not only once for the whole TIFF file.
     *
     * @param quality floating-point value: the desired lossy quality level.
     * @return a reference to this object.
     * @throws IllegalArgumentException if the argument is negative.
     */
    public TiffWriter setCompressionQuality(double quality) {
        if (quality < 0.0) {
            throw new IllegalArgumentException("Negative quality " + quality + " is not allowed");
        }
        this.compressionQuality = quality;
        return this;
    }

    public TiffWriter removeCompressionQuality() {
        this.compressionQuality = null;
        return this;
    }

    public boolean hasLosslessCompressionLevel() {
        return losslessCompressionLevel != null;
    }

    public Double getLosslessCompressionLevel() {
        return losslessCompressionLevel;
    }

    public TiffWriter setLosslessCompressionLevel(Double losslessCompressionLevel) {
        return losslessCompressionLevel == null ?
                removeLosslessCompressionLevel() :
                setLosslessCompressionLevel(losslessCompressionLevel.doubleValue());
    }

    /**
     * Sets the compression level for lossless formats.
     * In the current version, the only format that supports this parameter is {@link TagCompression#DEFLATE}.
     *
     * <p>Possible values are format-specific, but usually they should be between 0.0 and 1.0
     * (1.0 means the best quality).
     * Zero values mean no compression if the compression algorithm supports this variant.
     *
     * <p>If this method was not called or after {@link #removeLosslessCompressionLevel()},
     * this level is not specified: some default compression level will be used.
     *
     * <p>Note: the {@link TiffCodec.Options#setLosslessCompressionLevel(Double) lossless compression level},
     * that can be set via
     * {@link #setCodecOptions(TiffCodec.Options)} method, is ignored,
     * if this value is set to non-{@code null} value.
     *
     * <p>Please <b>note</b> that this parameter may be different for different IFDs and
     * even for different tiles inside the same IFD.
     * In this case, you need to call this method every time before updating IFD,
     * not only once for the whole TIFF file.
     *
     * @param losslessCompressionLevel floating-point value: the desired compression level.
     * @return a reference to this object.
     * @throws IllegalArgumentException if the argument is negative.
     */
    public TiffWriter setLosslessCompressionLevel(double losslessCompressionLevel) {
        if (losslessCompressionLevel < 0.0) {
            throw new IllegalArgumentException("Negative losslessCompressionLevel " + losslessCompressionLevel +
                    " is not allowed");
        }
        this.losslessCompressionLevel = losslessCompressionLevel;
        return this;
    }

    public TiffWriter removeLosslessCompressionLevel() {
        this.losslessCompressionLevel = null;
        return this;
    }

    public boolean isPreferRGB() {
        return preferRGB;
    }

    /**
     * Sets whether you need to prefer RGB photometric interpretation even in such formats, where another
     * color space is more traditional. Default value is <code>false</code>.
     *
     * <p>In the current version, it only applies to {@link TagCompression#JPEG} format: this flag enforces the writer
     * to compress JPEG tiles/stripes with photometric interpretation RGB.
     * If this flag is <code>false</code>, the writer uses YCbCr photometric interpretation &mdash;
     * a standard encoding for JPEG, but not so popular in TIFF.
     *
     * <p>This flag is used if a photometric interpretation is not specified in the IFD.
     * Otherwise, this flag is ignored, and the writer uses the photometric interpretation from the IFD
     * (but, for JPEG, only YCbCr and RGB options are allowed).
     *
     * <p>Please <b>remember</b> that this parameter may vary between different IFDs.
     * In this case, you need to call this method every time before creating a new IFD,
     * not just once for the entire TIFF file!
     *
     * <p>This parameter is ignored (as if it is <code>false</code>), if {@link #isEnforceUseExternalCodec()}
     * return <code>true</code>.
     *
     * <p>This flag affects the results of {@link #newMap(TiffIFD, boolean)} method: it helps to choose
     * correct photometric interpretation tag. Once a TIFF map is created and information is stored in an IFD object,
     * this object no longer uses this flag.
     *
     * @param preferRGB whether you want to compress JPEG in RGB encoding.
     * @return a reference to this object.
     */
    public TiffWriter setPreferRGB(boolean preferRGB) {
        this.preferRGB = preferRGB;
        return this;
    }

    public boolean isAlwaysWriteToFileEnd() {
        return alwaysWriteToFileEnd;
    }

    /**
     * If <code>true</code>, any new pixel data will always be written to the end of the TIFF file.
     * If <code>false</code>, a tile loaded from this file and modified will possibly be written
     * to the same position if it does not destroy other data (in particular, if the new encoded data size
     * is the same or smaller than the length of data written in the file).
     *
     * <p>Default value is <code>false</code> (more "smart" mode).
     *
     * @param alwaysWriteToFileEnd whether the new data are always added to the file end.
     * @return a reference to this object.
     */
    public TiffWriter setAlwaysWriteToFileEnd(boolean alwaysWriteToFileEnd) {
        this.alwaysWriteToFileEnd = alwaysWriteToFileEnd;
        return this;
    }

    public boolean isMissingTilesAllowed() {
        return missingTilesAllowed;
    }

    /**
     * Sets the special mode, when a TIFF file is allowed to contain "missing" tiles or strips,
     * for which the offset (<code>TileOffsets</code> or <code>StripOffsets</code> tag) and/or
     * byte count (<code>TileByteCounts</code> or <code>StripByteCounts</code> tag) contains zero value.
     * In this mode, this writer will use zero offset and byte-count if
     * the written tile is actually empty &mdash; no pixels were written in it via
     * {@link TiffWriteMap#updateSamples(byte[], int, int, int, int)} or other methods.
     * In another case, this writer will create a normal tile, filled by
     * the {@link #setByteFiller(byte) default filler}.
     *
     * <p>Default value is <code>false</code>. Note that <code>true</code> value violates requirement of
     * the standard TIFF format (but may be used by some non-standard software, which needs to
     * detect areas, not containing any actual data).
     *
     * @param missingTilesAllowed whether "missing" tiles/strips are allowed.
     * @return a reference to this object.
     */
    public TiffWriter setMissingTilesAllowed(boolean missingTilesAllowed) {
        this.missingTilesAllowed = missingTilesAllowed;
        return this;
    }

    public byte getByteFiller() {
        return byteFiller;
    }

    public TiffWriter setByteFiller(byte byteFiller) {
        this.byteFiller = byteFiller;
        return this;
    }

    public Consumer<TiffTile> getTileInitializer() {
        return tileInitializer;
    }

    public TiffWriter setTileInitializer(Consumer<TiffTile> tileInitializer) {
        this.tileInitializer = Objects.requireNonNull(tileInitializer, "Null tileInitializer");
        return this;
    }

    public Context getContext() {
        return context;
    }

    public TiffWriter setContext(Context context) {
        this.scifio = null;
        this.context = context;
        return this;
    }

    /**
     * Returns the output stream from which TIFF data is being saved.
     */
    public DataHandle<? extends Location> output() {
        synchronized (fileLock) {
            // - we prefer not to return this stream in the middle of I/O operations
            return out;
        }
    }

    /**
     * Returns position in the file of the last IFD offset, written by methods of this object.
     * It is updated by {@link #rewriteIFD(TiffIFD, boolean)}.
     *
     * <p>Immediately after creating a new object this position is <code>-1</code>.
     *
     * @return file position of the last IFD offset.
     */
    public long positionOfLastIFDOffset() {
        return positionOfLastIFDOffset;
    }

    public int numberOfIFDs() {
        return ifdOffsets.size();
    }

    /**
     * Opens an existing TIFF file for appending new images.
     * To use this method, you must not use the constructor with the argument
     * <code>createNewFileAndOpen = true</code>.
     *
     * @throws IOException in the case of any I/O errors.
     */
    public final void openExisting() throws IOException {
        open(false);
    }

    /**
     * Opens the TIFF file for possible appending new images if it exists, or creates a new TIFF file otherwise.
     * To use this method, you must not use the constructor with the argument
     * <code>createNewFileAndOpen = true</code>.
     *
     * @throws IOException in the case of any I/O errors.
     */
    public final void openForAppend() throws IOException {
        open(true);
    }

    /**
     * Equivalent to {@link #openExisting()} if the argument is {@code false}
     * or to {@link #openForAppend()} if the argument is {@code true}.
     *
     * @param createIfNotExists whether you need to create a new TIFF file when there is no existing file.
     * @throws IOException in the case of any I/O errors.
     */
    public final void open(boolean createIfNotExists) throws IOException {
        synchronized (fileLock) {
            clearReader();
            if (!out.exists()) {
                if (createIfNotExists) {
                    create();
                } else {
                    throw new FileNotFoundException("Output TIFF file " +
                            TiffReader.prettyFileName("%s", out) + " does not exist");
                }
                // In this branch, we MUST NOT try to analyze the file: it is not a correct TIFF!
            } else {
                ifdOffsets.clear();
                this.reader = newReader(TiffOpenMode.VALID_TIFF);
                // - The first opening TIFF is the only place when we MUST use VALID_TIFF mode
                // instead of the usual NO_CHECKS used inside reader() method
                // Note: we should NOT close the reader in the case of any problem,
                // because it uses the same stream with this writer
                final long[] offsets = reader.readIFDOffsets();
                final long readerPositionOfLastOffset = reader.positionOfLastIFDOffset();
                this.setFormatLike(reader);
                ifdOffsets.addAll(Arrays.stream(offsets).boxed().toList());
                positionOfLastIFDOffset = readerPositionOfLastOffset;
                seekToEnd();
                // - ready to write after the end of the file
                // (not necessary, but can help to avoid accidental bugs)
            }
        }
    }

    /**
     * Equivalent to {@link #openForAppend()} if the argument is <code>true</code>,
     * or {@link #create()} if the argument is <code>false</code>.
     * <p>If the file does not exist, the argument has no effect: this method
     * creates a new empty TIFF file.
     * </p>If the file already exists, this method opens it for possible appending new images
     * when <code>appendToExistingFile</code> is <code>true</code>,
     * or truncates it to a zero length and writes the TIFF header otherwise.
     *
     * @throws IOException in the case of any I/O errors.
     */
    public final void create(boolean appendToExistingFile) throws IOException {
        if (appendToExistingFile) {
            openForAppend();
        } else {
            create();
        }
    }

    /**
     * Creates a new TIFF file and writes the standard TIFF header in the beginning.
     * If the file already exists before creating this object,
     * this method truncates it to a zero length before writing the header.
     * This method is called automatically in the constructor, when it is called with the argument
     * <code>createNewFileAndOpen = true</code>.
     *
     * @throws IOException in the case of any I/O errors.
     */
    public final void create() throws IOException {
        synchronized (fileLock) {
            clearReader();
            ifdOffsets.clear();
            out.seek(0);
            // - this call actually creates and opens the file if it was not opened before
            if (isLittleEndian()) {
                out.writeByte(TiffReader.FILE_PREFIX_LITTLE_ENDIAN);
                out.writeByte(TiffReader.FILE_PREFIX_LITTLE_ENDIAN);
            } else {
                out.writeByte(TiffReader.FILE_PREFIX_BIG_ENDIAN);
                out.writeByte(TiffReader.FILE_PREFIX_BIG_ENDIAN);
            }
            // Writing the magic number:
            if (bigTiff) {
                out.writeShort(TiffReader.FILE_BIG_TIFF_MAGIC_NUMBER);
            } else {
                out.writeShort(TiffReader.FILE_USUAL_MAGIC_NUMBER);
            }
            if (bigTiff) {
                out.writeShort(8);
                // - 16-bit "8" value means the number of bytes in BigTIFF offsets
                out.writeShort(0);
            }
            // Writing the "last offset" marker:
            positionOfLastIFDOffset = out.offset();
            writeOffset(TiffIFD.LAST_IFD_OFFSET);
            // Truncating the file if it already existed:
            // it is necessary because this class writes all new information
            // to the file end (to avoid damaging existing content)
            out.setLength(out.offset());
        }
    }

    public void rewriteIFD(final TiffIFD ifd, boolean updateIFDLinkages) throws IOException {
        Objects.requireNonNull(ifd, "Null IFD");
        if (!ifd.hasFileOffsetForWriting()) {
            throw new IllegalArgumentException("Offset for writing IFD is not specified");
        }
        final long offset = ifd.getFileOffsetForWriting();
        assert (offset & 0x1) == 0 : "TiffIFD.setFileOffsetForWriting() has not check offset parity: " + offset;

        writeIFDAt(ifd, offset, updateIFDLinkages);
    }

    public void writeIFDAtFileEnd(TiffIFD ifd, boolean updateIFDLinkages) throws IOException {
        writeIFDAt(ifd, null, updateIFDLinkages);
    }

    /**
     * Writes IFD at the position, specified by <code>startOffset</code> argument, or at the file end
     * (aligned to nearest even length) if it is {@code null}.
     *
     * <p>Note: this IFD is automatically marked as last IFD in the file (next IFD offset is 0),
     * unless you explicitly specified other next offset via {@link TiffIFD#setNextIFDOffset(long)}.
     * You also may call {@link #rewritePreviousLastIFDOffset(long)} to correct
     * this mark inside the file in the previously written IFD, but usually there is no necessity to do this.</p>
     *
     * <p>If <code>updateIFDLinkages</code> is <code>true</code>, this method also performs
     * the following two actions.</p>
     *
     * <ol>
     *     <li>It updates the offset, stored in the file at {@link #positionOfLastIFDOffset()}, with start offset of
     *     this IFD (i.e. <code>startOffset</code> or position of the file end). This action is performed <b>only</b>
     *     if this start offset is really new for this file, i.e., if it did not present in an existing file
     *     while opening it by {@link #openExisting()} method and if some IFD was not already written
     *     at this position by methods of this object.</li>
     *     <li>It replaces the internal field, returned by {@link #positionOfLastIFDOffset()}, with
     *     the position of the next IFD offset, written as a part of this IFD.
     *     This action is performed <b>only</b> when this IFD is marked as the last one (see the previos note).</li>
     * </ol>
     *
     * <p>Also note: this method changes position in the output stream.
     * (Actually, it will be a position after the IFD information, including all additional data
     * like arrays of offsets; but you should not use this fact.)</p>
     *
     * <p>Also note: this method is intended for writing usual IFD, not sub-IFD, so it reserves a place for
     * storing the next offset.</p>
     *
     * @param ifd               IFD to write in the output stream.
     * @param updateIFDLinkages see comments above.
     * @throws IOException in the case of any I/O errors.
     */
    public void writeIFDAt(TiffIFD ifd, Long startOffset, boolean updateIFDLinkages) throws IOException {
        synchronized (fileLock) {
            checkVirginFile();
            clearReader();
            if (startOffset == null) {
                appendFileUntilEvenLength();
                startOffset = out.length();
            }
            if (!bigTiff && startOffset > MAXIMAL_ALLOWED_32BIT_IFD_OFFSET) {
                throw new TiffException("Attempt to write too large TIFF file without big-TIFF mode: " +
                        "offset of new IFD will be " + startOffset + " > " + MAXIMAL_ALLOWED_32BIT_IFD_OFFSET);
            }
            ifd.setFileOffsetForWriting(startOffset);
            // - checks that startOffset is even and >= 0

            out.seek(startOffset);
            final Map<Integer, Object> sortedIFD = new TreeMap<>(ifd.map());
            final int numberOfEntries = sortedIFD.size();
            final int mainIFDLength = TiffIFD.sizeOfIFDTable(numberOfEntries, bigTiff, true);
            writeIFDNumberOfEntries(numberOfEntries);

            final long positionOfNextOffset = writeIFDEntries(sortedIFD, startOffset, mainIFDLength);

            final long previousPositionOfLastIFDOffset = positionOfLastIFDOffset;
            // - save it, because it will be updated in writeIFDNextOffsetAt
            writeIFDNextOffsetAt(ifd, positionOfNextOffset, updateIFDLinkages);
            if (updateIFDLinkages && !ifdOffsets.contains(startOffset)) {
                // - Only if it is really newly added IFD!
                // If this offset is already contained in the list, an attempt to link to it
                // will probably lead to an infinite loop of IFDs.
                writeIFDOffsetAt(startOffset, previousPositionOfLastIFDOffset, false);
                ifdOffsets.add(startOffset);
            }
        }
    }

    /**
     * Rewrites the offset, stored in the file at the {@link #positionOfLastIFDOffset()},
     * with the specified value.
     * This method is useful if you want to organize the sequence of IFD inside the file manually,
     * without automatically updating IFD linkage.
     *
     * @param nextLastIFDOffset new last IFD offset.
     * @throws IOException in the case of any I/O errors.
     */
    public void rewritePreviousLastIFDOffset(long nextLastIFDOffset) throws IOException {
        synchronized (fileLock) {
            if (nextLastIFDOffset < 0) {
                throw new IllegalArgumentException("Negative next last IFD offset " + nextLastIFDOffset);
            }
            if (positionOfLastIFDOffset < 0) {
                throw new IllegalStateException("Writing to this TIFF file is not started yet");
            }
            writeIFDOffsetAt(nextLastIFDOffset, positionOfLastIFDOffset, false);
            // - last argument is not important: the positionOfLastIFDOffset will not change in any case
        }
    }

    public void fillEmptyTile(TiffTile tiffTile) {
        if (byteFiller != 0) {
            // - Java arrays are automatically filled by zero
            Arrays.fill(tiffTile.getDecodedData(), byteFiller);
        }
    }

    public void writeTile(TiffTile tile, boolean disposeAfterWriting) throws IOException {
        encode(tile);
        writeEncodedTile(tile, disposeAfterWriting);
    }

    public int writeAllTiles(Collection<TiffTile> tiles) throws IOException {
        return writeTiles(tiles, tile -> true, true);
    }

    public int flushCompletedTiles(Collection<TiffTile> tiles) throws IOException {
        return writeTiles(tiles, TiffTile::isCompleted, true);
    }

    public int writeTiles(Collection<TiffTile> tiles, Predicate<TiffTile> needToWrite, boolean disposeAfterWriting)
            throws IOException {
        Objects.requireNonNull(tiles, "Null tiles");
        Objects.requireNonNull(needToWrite, "Null needToWrite");
        long t1 = debugTime();
        int count = 0;
        long sizeInBytes = 0;
        for (TiffTile tile : tiles) {
            if (needToWrite.test(tile)) {
                writeTile(tile, disposeAfterWriting);
                count++;
                sizeInBytes += tile.getSizeInBytes();
            }
        }
        long t2 = debugTime();
        logTiles(tiles, "middle", "encoded/wrote", count, sizeInBytes, t1, t2);
        return count;
    }

    public void writeEncodedTile(TiffTile tile, boolean disposeAfterWriting) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        if (tile.isEmpty()) {
            return;
        }
        long t1 = debugTime();
        synchronized (fileLock) {
            checkVirginFile();
            clearReader();
            TiffTileIO.write(tile, out, alwaysWriteToFileEnd, !bigTiff);
            if (disposeAfterWriting) {
                tile.dispose();
            }
        }
        long t2 = debugTime();
        timeWriting += t2 - t1;
    }

    public boolean encode(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        if (tile.isEmpty() || tile.isEncoded()) {
            // - note: unlike TiffReader.decode, we do not require that the tile is non-empty
            return false;
        }
        tile.checkStoredNumberOfPixels();
        long t1 = debugTime();
        prepareEncoding(tile);
        long t2 = debugTime();

        final TagCompression compression = TagCompression.ofOrNull(tile.compressionCode());
        TiffCodec codec = null;
        if (!enforceUseExternalCodec && compression != null) {
            codec = compression.codec();
            // - we are sure that this codec does not require SCIFIO context
        }
        TiffCodec.Options options = buildOptions(tile);
        long t3 = debugTime();

        byte[] data = tile.getDecodedData();
        if (codec != null) {
            options = compression.customizeWriting(tile, options);
            if (codec instanceof TiffCodec.Timing timing) {
                timing.setTiming(TiffReader.BUILT_IN_TIMING && LOGGABLE_DEBUG);
                timing.clearTiming();
            }
            final byte[] encodedData = codec.compress(data, options);
            tile.setEncodedData(encodedData);
        } else {
            final Optional<byte[]> encodedData = encodeByExternalCodec(tile, tile.getDecodedData(), options);
            if (encodedData.isEmpty()) {
                throw new UnsupportedTiffFormatException("TIFF compression with code " +
                        tile.compressionCode() + " cannot be encoded: " + tile.ifd());
            }
            tile.setEncodedData(encodedData.get());
        }
        if (tile.ifd().isReversedFillOrder()) {
            PackedBitArraysPer8.reverseBitOrderInPlace(tile.getEncodedData());
        }
        long t4 = debugTime();

        timePreparingEncoding += t2 - t1;
        timeCustomizingEncoding += t3 - t2;
        timeEncoding += t4 - t3;
        if (codec instanceof TiffCodec.Timing timing) {
            timeEncodingMain += timing.timeMain();
            timeEncodingBridge += timing.timeBridge();
            timeEncodingAdditional += timing.timeAdditional();
        } else {
            timeEncodingMain += t4 - t3;
        }
        return true;
    }

    public void prepareEncoding(TiffTile tile) throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        if (AUTO_INTERLEAVE_SOURCE) {
            if (tile.isInterleaved()) {
                throw new IllegalArgumentException("Tile for encoding and writing to TIFF file must not be " +
                        "interleaved:: " + tile);
            }
            tile.interleaveSamples();
        } else {
            tile.setInterleaved(true);
            // - if not autoInterleave, we should suppose that the samples were already interleaved
        }
        TiffPacking.packTiffBits(tile);

        // scifio.tiff().difference(tile.getDecodedData(), ifd);
        // - this solution requires using SCIFIO context class; it is better to avoid this
        TiffPrediction.subtractPredictionIfRequested(tile);
    }

    public void encode(TiffWriteMap map) throws TiffException {
        encode(map, null);
    }

    /**
     * Equivalent to <code>{@link #correctFormatForEncoding(TiffIFD, boolean)
     * correctFormatForEncoding}(ifd, thisObject.{@link #isSmartFormatCorrection() isSmartFormatCorrection()})</code>.
     *
     * @param ifd IFD to be corrected.
     * @throws TiffException in the case of some problems, in particular, if IFD settings are not supported.
     */
    public void correctFormatForEncoding(TiffIFD ifd) throws TiffException {
        correctFormatForEncoding(ifd, isSmartFormatCorrection());
    }

    /**
     * Fixes the IFD so that it can be written by this class or throws an exception if it is impossible.
     * This method may change the following tags:
     * <ul>
     *     <li><code>BitsPerSample</code> (258) and <code>SampleFormat</code> (339) &mdash; in the smart mode,
     *     if the actual number of bits per sample is not 1, 8, 16, 32, 64;</li>
     *     <li><code>Compression</code> (259) &mdash; if it is not specified,
     *     it is set to {@link TiffIFD#COMPRESSION_NONE});</li>
     *     <li><code>PhotometricInterpretation</code> (262) &mdash; if it is not specified or in the smart mode;</li>
     *     <li><code>SubIFD</code> (330), <code>Exif IFD</code> (34665), <code>GPSInfo</code> (34853) &mdash;
     *     these tags are always removed (both by this method and by {@link #correctFormatForEntireTiff(TiffIFD)}),
     *     because this writer does not support writing the corresponding IFD inside the TIFF.
     *     </li>
     * </ul>
     *
     * @param ifd             IFD to be corrected.
     * @param smartCorrection more smart correction.
     * @throws TiffException in the case of some problems, in particular, if IFD settings are not supported.
     */
    public void correctFormatForEncoding(TiffIFD ifd, boolean smartCorrection) throws TiffException {
        final int samplesPerPixel = ifd.getSamplesPerPixel();
        if (!ifd.containsKey(Tags.BITS_PER_SAMPLE)) {
            ifd.put(Tags.BITS_PER_SAMPLE, new int[]{1});
            // - Default value of BitsPerSample is 1 bit/pixel!
            // This is a rare case, however, we CANNOT set here another value:
            // probably we created this IFD as a copy of another IFD, like in copyImage method,
            // and changing the supposed number of bits can lead to unpredictable behavior
            // (for example, passing too short data to TiffTile.setDecodedData() method).
        }
        final TiffSampleType sampleType;
        try {
            sampleType = ifd.sampleType();
        } catch (TiffException e) {
            throw new UnsupportedTiffFormatException("Cannot write TIFF, because " +
                    "requested combination of number of bits per sample and sample format is not supported: " +
                    e.getMessage());
        }
        if (smartCorrection) {
            ifd.putSampleType(sampleType);
        } else {
            final int bits = ifd.checkSupportedBitDepth();
            if (sampleType == TiffSampleType.FLOAT && bits != 32) {
                throw new UnsupportedTiffFormatException("Cannot write TIFF, because " +
                        "requested number of bits per sample is not supported: " +
                        bits + " bits for floating-point precision");
            }
        }

        if (!ifd.containsKey(Tags.COMPRESSION)) {
            ifd.put(Tags.COMPRESSION, TiffIFD.COMPRESSION_NONE);
            // - We prefer to explicitly specify this case
        }
        final TagCompression compression = ifd.optCompression().orElse(null);

        final TagPhotometricInterpretation suggestedPhotometric =
                ifd.containsKey(Tags.PHOTOMETRIC_INTERPRETATION) ? ifd.getPhotometricInterpretation() : null;
        TagPhotometricInterpretation newPhotometric = suggestedPhotometric;
        // - note: it is possible that we DO NOT KNOW this newPhotometric interpretation;
        // in this case, newPhotometric will be UNKNOWN, but we should not prevent writing such an image
        // in simple formats like UNCOMPRESSED or LZW: maybe, the client knows how to process it
        if (compression == TagCompression.JPEG) {
            if (samplesPerPixel != 1 && samplesPerPixel != 3) {
                throw new TiffException("JPEG compression for " + samplesPerPixel + " channels is not supported");
            }
            if (newPhotometric == null) {
                newPhotometric = samplesPerPixel == 1 ? TagPhotometricInterpretation.BLACK_IS_ZERO :
                        (preferRGB || !ifd.isChunked()) ?
                                TagPhotometricInterpretation.RGB : TagPhotometricInterpretation.Y_CB_CR;
            } else {
                checkPhotometricInterpretation(newPhotometric,
                        samplesPerPixel == 1 ? EnumSet.of(TagPhotometricInterpretation.BLACK_IS_ZERO) :
                                !enforceUseExternalCodec ?
                                        EnumSet.of(TagPhotometricInterpretation.Y_CB_CR,
                                                TagPhotometricInterpretation.RGB) :
                                        EnumSet.of(TagPhotometricInterpretation.Y_CB_CR),
                        "JPEG " + samplesPerPixel + "-channel image");
            }
        } else if (samplesPerPixel == 1) {
            final boolean hasColorMap = ifd.containsKey(Tags.COLOR_MAP);
            if (newPhotometric == null) {
                newPhotometric = hasColorMap ?
                        TagPhotometricInterpretation.RGB_PALETTE :
                        TagPhotometricInterpretation.BLACK_IS_ZERO;
            } else {
                // There are no reasons to create custom photometric interpretations for 1 channel,
                // excepting RGB palette, BLACK_IS_ZERO, WHITE_IS_ZERO
                // (we do not support TRANSPARENCY_MASK, that should be used together with other IFD).
                // We have no special support for interpretations other than BLACK_IS_ZERO,
                // but if the user wants, he can prepare correct data for them.
                // We do not try to invert data for WHITE_IS_ZERO,
                // as well as we do not invert values for the CMYK below.
                // This is important because TiffReader (by default) also does not invert brightness in these cases:
                // autoCorrectInvertedBrightness=false, so, TiffReader+TiffWriter can copy such IFD correctly.
                if (newPhotometric == TagPhotometricInterpretation.RGB_PALETTE && !hasColorMap) {
                    throw new TiffException("Cannot write TIFF image: newPhotometric interpretation \"" +
                            newPhotometric.prettyName() + "\" requires also \"ColorMap\" tag");
                }
                checkPhotometricInterpretation(newPhotometric,
                        EnumSet.of(TagPhotometricInterpretation.BLACK_IS_ZERO,
                                TagPhotometricInterpretation.WHITE_IS_ZERO,
                                TagPhotometricInterpretation.RGB_PALETTE),
                        samplesPerPixel + "-channel image");
            }
        } else if (samplesPerPixel == 3) {
            if (newPhotometric == null) {
                newPhotometric = TagPhotometricInterpretation.RGB;
            } else {
                // Unlike 1 channel/pixel (the case above), we do not prevent the user from
                // setting non-standard custom photometric interpretations: maybe he wants
                // to create some LAB or CMYK TIFF, and he prepared all channels correctly.
                if (ifd.isStandardYCbCrNonJpeg()) {
                    if (!smartCorrection) {
                        throw new UnsupportedTiffFormatException("Cannot write TIFF: encoding YCbCr " +
                                "photometric interpretation is not supported for compression \"" +
                                (compression == null ? "??? : " : compression.prettyName()) + "\"");
                    } else {
                        // - TiffReader automatically decodes YCbCr into RGB while reading;
                        // we cannot encode pixels back to YCbCr,
                        // and it would be better to change newPhotometric interpretation to RGB.
                        // Note that for JPEG we have no such problem: we CAN encode JPEG as YCbCr.
                        // For other models (like CMYK or CIE Lab), we ignore newPhotometric interpretation
                        // and suppose that the user herself prepared channels in the necessary model.
                        newPhotometric = TagPhotometricInterpretation.RGB;
                    }
                }
            }
        } else {
            if (newPhotometric == null) {
                if (samplesPerPixel == 4) {
                    // - probably RGBA
                    newPhotometric = TagPhotometricInterpretation.RGB;
                }
                // else we stay IFD without photometric interpretation: incorrect for good TIFF,
                // but better than senseless interpretation
            }
            // But if newPhotometric is specified, we do not anything;
            // for example, the user can prepare correct data for CMYK image.
        }
        if (newPhotometric != suggestedPhotometric) {
            ifd.putPhotometricInterpretation(newPhotometric);
        }
        correctFormatForEntireTiff(ifd);
    }

    public void correctFormatForEntireTiff(TiffIFD ifd) throws TiffException {
        ifd.remove(Tags.SUB_IFD);
        ifd.remove(Tags.EXIF);
        ifd.remove(Tags.GPS_TAG);
        // - These are also pointers (offsets) inside a file, but this class does not provide
        // control over writing such IFDs, so the corresponding offsets will usually have no sense

        ifd.setLittleEndian(out.isLittleEndian());
        // - will be used, for example, in getCompressionCodecOptions
        ifd.setBigTiff(bigTiff);
        // - not used, but helps to provide better TiffIFD.toString
    }

    public TiffIFD newIFD() {
        return newIFD(false);
    }

    public TiffIFD newIFD(boolean tiled) {
        final TiffIFD ifd = new TiffIFD();
        ifd.putCompression(TagCompression.NONE);
        if (tiled) {
            ifd.defaultTileSizes();
        } else {
            ifd.defaultStripSize();
        }
        return ifd;
    }

    /**
     * Creates a new TIFF map for further writing data to the TIFF file by <code>writeXxx</code> methods.
     *
     * <p>The <code>resizable</code> argument specifies the type of the created map: resizable or fixed.
     * For a resizable map, you do not have to set the IFD dimensions at this stage: they will be calculated
     * automatically while {@link #completeWriting(TiffWriteMap) completion} of the image.
     * See also the constructor {@link TiffMap#TiffMap(TiffIFD, boolean)}.</p>
     *
     * <p>If <code>correctFormatForEncoding</code> is <code>true</code>,
     * this method automatically calls {@link #correctFormatForEncoding(TiffIFD)} method for
     * the specified <code>ifd</code> argument.
     * While typical usage, this argument should be <code>true</code>.
     * But you may set it to <code>false</code> if you want to control all IFD settings yourself,
     * in particular if you prefer to call the method {@link #correctFormatForEncoding(TiffIFD, boolean)}
     * with non-standard {@link #setSmartFormatCorrection smartFormatCorrection}</code> flag.
     *
     * <p>Note: this method calls {@link TiffMap#buildTileGrid()} and {@link TiffIFD#freeze() freeze}
     * the passed <code>ifd</code>. So you should use this method after completely building IFD.</p>
     *
     * <p>Note: this method <b>removes</b> tags {@link Tags#SUB_IFD SubIFD}, {@link Tags#EXIF Exif IFD}
     * and {@link Tags#GPS_TAG GPS information}, because this class does not support writing sub-IFDs.
     * If you still need to construct TIFF with such tags, you should use more low-level call of
     * {@link TiffWriteMap} constructor.
     *
     * @param ifd                      newly created and probably customized IFD.
     * @param resizable                if <code>true</code>, IFD dimensions may not be specified yet: this argument is
     *                                 passed to {@link TiffWriteMap#TiffWriteMap(TiffWriter, TiffIFD, boolean)}
     *                                 constructor for creating the new map.
     * @param correctFormatForEncoding whether {@link #correctFormatForEncoding(TiffIFD)} should be called;
     *                                 usually <code>true</code>.
     * @return map for writing further data.
     * @throws TiffException in the case of some problems.
     */
    public TiffWriteMap newMap(TiffIFD ifd, boolean resizable, boolean correctFormatForEncoding)
            throws TiffException {
        Objects.requireNonNull(ifd, "Null IFD");
        if (ifd.isFrozen()) {
            throw new IllegalStateException("IFD is already frozen for usage while writing TIFF; " +
                    "probably you called this method twice");
        }
        if (correctFormatForEncoding) {
            correctFormatForEncoding(ifd);
        } else {
            correctFormatForEntireTiff(ifd);
            // - this is still necessary: ifd and TiffWriteMap must "know" about the actual file format
        }
        final TiffWriteMap map = new TiffWriteMap(this, ifd, resizable);
        prepareNewMap(map);
        this.lastMap = map;
        return map;
    }

    /**
     * Creates a new TIFF map for further writing data into the TIFF file by <code>writeXxx</code> methods.
     *
     * <p>Equivalent to <code>{@link #newMap(TiffIFD, boolean, boolean) newMap}(ifd, resizable, true)</code>.
     *
     * @param ifd       newly created and probably customized IFD.
     * @param resizable if <code>true</code>, IFD dimensions may not be specified yet.
     * @return map for writing further data.
     * @throws TiffException in the case of some problems.
     */
    public TiffWriteMap newMap(TiffIFD ifd, boolean resizable) throws TiffException {
        return newMap(ifd, resizable, true);
    }

    public TiffWriteMap newFixedMap(TiffIFD ifd) throws TiffException {
        return newMap(ifd, false);
    }

    public TiffWriteMap newResizableMap(TiffIFD ifd) throws TiffException {
        return newMap(ifd, true);
    }

    public void prepareNewMap(TiffWriteMap map) {
        Objects.requireNonNull(map, "Null TIFF map");
        map.buildTileGrid();
        // - useful to perform loops on all tiles, especially in non-resizable case
        TiffIFD ifd = map.ifd();
        ifd.removeNextIFDOffset();
        ifd.removeDataPositioning();
        if (map.isResizable()) {
            ifd.removeImageDimensions();
        }
        ifd.freeze();
        // - actually not necessary, but helps to avoid possible bugs

    }

    /**
     * Starts overwriting existing IFD image.
     *
     * <p>Note: this method does not remove information about tile/strip offsets and byte counts. So, you can
     * read some tiles from this IFD via {@link TiffReader} class (it is important for tiles, that you need to
     * partially fill, but partially load from the old file).</p>
     *
     * <p>Note: this method never performs {@link #setSmartFormatCorrection(boolean) "smart correction"}
     * of the specified IFD.</p>
     *
     * <p>This method is used, for example, inside
     * {@link #preloadExistingTiles(int, int, int, int, int, boolean)}.</p>
     *
     * @param ifd IFD of some existing image, probably loaded from the current TIFF file.
     * @return map for writing further data.
     */
    public TiffWriteMap existingMap(TiffIFD ifd) throws TiffException {
        Objects.requireNonNull(ifd, "Null IFD");
        correctFormatForEncoding(ifd, false);
        final TiffWriteMap map = new TiffWriteMap(this, ifd, false);
        final long[] offsets = ifd.cachedTileOrStripOffsets();
        final long[] byteCounts = ifd.cachedTileOrStripByteCounts();
        assert offsets != null;
        assert byteCounts != null;
        map.buildTileGrid();
        if (offsets.length < map.numberOfTiles() || byteCounts.length < map.numberOfTiles()) {
            throw new ConcurrentModificationException("Strange length of tile offsets " + offsets.length +
                    " or byte counts " + byteCounts.length);
            // - should not occur: it is checked in getTileOrStripOffsets/getTileOrStripByteCounts methods
            // (the only possible way is modification from parallel thread)
        }
        ifd.freeze();
        // - actually not necessary, but helps to avoid possible bugs
        int k = 0;
        for (TiffTile tile : map.tiles()) {
            tile.setStoredInFileDataRange(offsets[k], (int) byteCounts[k], true);
            // - we "tell" that all tiles already exist in the file;
            // note we can use index k, because buildGrid() method, called above for an empty map,
            //  provided the correct tiles order
            tile.markWholeTileAsSet();
            // - we "tell" that each tile has no unset areas
            k++;
        }
        this.lastMap = map;
        return map;
    }

    public TiffWriteMap preloadExistingTiles(int ifdIndex, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        return preloadExistingTiles(ifdIndex, fromX, fromY, sizeX, sizeY, true);
    }

    /**
     * Preloads all tiles intersecting the specified rectangle from the image #<code>ifdIndex</code>
     * in the existing TIFF and stores them in the returned map.
     * The map is created by {@link #existingMap(TiffIFD)} method based on the IFD
     * loaded with help of TIFF reader created by {@link #reader()} method.
     * This reader then loads there all tiles intersecting the rectangle
     * <code>fromX&le;x&lt;fromX+sizeX</code>, <code>fromY&le;y&lt;fromY+sizeY</code>,
     * and their content is copied to the corresponding tiles in the returned map.
     *
     * <p>This method helps to overwrite some portion of the existing TIFF:
     * data written to TIFF using the returned map will be placed over the existing image.</p>
     *
     * <p>Generally, you should avoid using this method unnecessarily:
     * although it allows modifying some existing tiles, all newly updated tiles
     * will be written at the file end, and the previously occupied space in the file will be lost.
     * This method may be suitable if you need to make a small correction in 1-2 tiles of
     * a very large TIFF without completely recompressing all its tiles.</p>
     *
     * <p>You can set the argument <code>loadTilesFullyInsideRectangle</code> to <code>false</code>
     * if you are going to fill the entire specified rectangle by some new data: in this case
     * there is no necessity to preload tiles that will be completely rewritten.
     * Otherwise, please set <code>loadTilesFullyInsideRectangle=true</code>.</p>
     *
     * <p>Note: zero sizes <code>sizeX</code> or <code>sizeY</code> are allowed,
     * in this case the method does not load any tiles.</p>
     *
     * @param ifdIndex                      index of IFD.
     * @param fromX                         starting x-coordinate for preloading.
     * @param fromY                         starting y-coordinate for preloading.
     * @param sizeX                         width of the preloaded rectangle.
     * @param sizeY                         height of the preloaded rectangle.
     * @param loadTilesFullyInsideRectangle whether this method should load tiles that are completely
     *                                      inside the specified rectangle.
     * @return map for overwriting TIFF data.
     * @throws IOException in the case of any problems with the TIFF file.
     */
    public TiffWriteMap preloadExistingTiles(
            int ifdIndex,
            int fromX,
            int fromY,
            int sizeX,
            int sizeY,
            boolean loadTilesFullyInsideRectangle)
            throws IOException {
        TiffMap.checkRequestedArea(fromX, fromY, sizeX, sizeY);
        @SuppressWarnings("resource") final TiffReader reader = reader();
        final TiffIFD ifd = reader.readSingleIFD(ifdIndex);
        ifd.setFileOffsetForWriting(ifd.getFileOffsetForReading());
        final TiffWriteMap map = existingMap(ifd);
        if (sizeX > 0 && sizeY > 0) {
            // a zero-size rectangle does not "intersect" anything
            final IRectangularArea areaToWrite = IRectangularArea.valueOf(
                    fromX, fromY, fromX + sizeX - 1, fromY + sizeY - 1);
            for (TiffTile tile : map.tiles()) {
                if (tile.actualRectangle().intersects(areaToWrite) &&
                        (loadTilesFullyInsideRectangle || !areaToWrite.contains(tile.actualRectangle()))) {
                    final TiffTile existing = reader.readCachedTile(tile.index());
                    tile.copyData(existing, false);
                }
            }
        }
        return map;
    }

    /**
     * Returns a reference to the map, created by last call of {@link #newMap(TiffIFD, boolean, boolean)}
     * or {@link #existingMap(TiffIFD)} methods.
     * Returns <code>null</code> if no maps were created yet or after {@link #close()} method.
     *
     * @return last map, created by this object.
     */
    public TiffWriteMap lastMap() {
        return lastMap;
    }

    /**
     * Prepare to write a new image with known fixed sizes.
     * This method writes an image header (IFD) to the end of the TIFF file,
     * so it will be placed before actually written data: it helps
     * to improve the performance of future reading this file.
     *
     * <p>Note: this method does nothing if the image is {@link TiffMap#isResizable() resizable}
     * or if this action is disabled by {@link #setWritingForwardAllowed(boolean) setWritingForwardAllowed(false)}
     * call.
     * In this case, IFD will be written at the final stage ({@link #completeWriting(TiffWriteMap)} method).
     *
     * @param map map, describing the image.
     */
    public void writeForward(TiffWriteMap map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        if (!writingForwardAllowed || map.isResizable()) {
            return;
        }
        final long[] offsets = new long[map.numberOfGridTiles()];
        final long[] byteCounts = new long[map.numberOfGridTiles()];
        // - zero-filled by Java
        map.ifd().updateDataPositioning(offsets, byteCounts);
        final TiffIFD ifd = map.ifd();
        if (!ifd.hasFileOffsetForWriting()) {
            writeIFDAtFileEnd(ifd, false);
        }
    }

    /**
     * Writes the matrix at the position (0,0).
     *
     * <p>Note that then the samples array in all <code>write...</code> methods is always supposed to be separated.
     * For multichannel images it means the samples order like RRR..GGG..BBB...: standard form,
     * returned by {@link TiffReader}. If the desired IFD format is
     * chunked, i.e. {@link Tags#PLANAR_CONFIGURATION} is {@link TiffIFD#PLANAR_CONFIGURATION_CHUNKED}
     * (that is the typical usage), then the passes samples are automatically re-packed into chunked (interleaved)
     * form RGBRGBRGB...
     *
     * @param map     TIFF map.
     * @param samples the samples in unpacked form.
     * @throws TiffException in the case of invalid TIFF IFD.
     * @throws IOException   in the case of any I/O errors.
     */
    public void writeSamples(final TiffWriteMap map, byte[] samples) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        map.checkZeroDimensions();
        writeSamples(map, samples, 0, 0, map.dimX(), map.dimY());
    }

    public void writeSamples(TiffWriteMap map, byte[] samples, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(samples, "Null samples");

        clearTime();
        long t1 = debugTime();
        map.updateSamples(samples, fromX, fromY, sizeX, sizeY);
        long t2 = debugTime();
        writeForward(map);
        long t3 = debugTime();
        encode(map);
        long t4 = debugTime();
        completeWriting(map);
        logWritingMatrix(map, "byte samples", sizeX, sizeY, t1, t2, t3, t4);

    }

    public void writeJavaArray(TiffWriteMap map, Object samplesArray) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        map.checkZeroDimensions();
        writeJavaArray(map, samplesArray, 0, 0, map.dimX(), map.dimY());
    }

    public void writeJavaArray(TiffWriteMap map, Object samplesArray, int fromX, int fromY, int sizeX, int sizeY)
            throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(samplesArray, "Null samplesArray");
        clearTime();
        long t1 = debugTime();
        map.updateJavaArray(samplesArray, fromX, fromY, sizeX, sizeY);
        long t2 = debugTime();
        writeForward(map);
        long t3 = debugTime();
        encode(map);
        long t4 = debugTime();
        completeWriting(map);
        logWritingMatrix(map, "pixel array", sizeX, sizeY, t1, t2, t3, t4);

    }

    /**
     * Writes the matrix at the position (0,0).
     *
     * <p>Note: unlike {@link #writeJavaArray(TiffWriteMap, Object)} and
     * {@link #writeSamples(TiffWriteMap, byte[])},
     * this method always uses the actual sizes of the passed matrix and, so, <i>does not require</i>
     * the map to have correct non-zero dimensions (a situation, possible for resizable maps).</p>
     *
     * @param map    TIFF map.
     * @param matrix 3D-matrix of pixels.
     * @throws TiffException in the case of invalid TIFF IFD.
     * @throws IOException   in the case of any I/O errors.
     */
    public void writeMatrix(TiffWriteMap map, Matrix<? extends PArray> matrix) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        writeMatrix(map, matrix, 0, 0);
    }

    public void writeMatrix(TiffWriteMap map, Matrix<? extends PArray> matrix, int fromX, int fromY) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(matrix, "Null matrix");
        clearTime();
        long t1 = debugTime();
        map.updateMatrix(matrix, fromX, fromY);
        long t2 = debugTime();
        writeForward(map);
        long t3 = debugTime();
        encode(map);
        long t4 = debugTime();
        completeWriting(map);
        logWritingMatrix(map, matrix, t1, t2, t3, t4);
    }

    /**
     * Equivalent to
     * <code>{@link #writeMatrix(TiffWriteMap, Matrix)
     * writeMatrix}(Matrices.mergeLayers(channels))</code>.
     *
     * @param map      TIFF map.
     * @param channels color channels of the image (2-dimensional matrices).
     * @throws TiffException in the case of invalid TIFF IFD.
     * @throws IOException   in the case of any I/O errors.
     */
    public void writeChannels(TiffWriteMap map, List<? extends Matrix<? extends PArray>> channels)
            throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        writeChannels(map, channels, 0, 0);
    }

    public void writeChannels(
            TiffWriteMap map,
            List<? extends Matrix<? extends PArray>> channels,
            int fromX,
            int fromY) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(channels, "Null channels");
        if (!AUTO_INTERLEAVE_SOURCE) {
            throw new IllegalStateException("Cannot write image channels: autoInterleaveSource mode is not set");
        }
        writeMatrix(map, Matrices.mergeLayers(channels), fromX, fromY);
    }

    /**
     * Equivalent to
     * <code>{@link #writeChannels(TiffWriteMap, List)
     * writeChannels}({@link ImageToMatrix#toChannels
     * ImageToMatrix.toChannels}(bufferedImage))</code>.
     *
     * @param map           TIFF map.
     * @param bufferedImage the image.
     * @throws TiffException in the case of invalid TIFF IFD.
     * @throws IOException   in the case of any I/O errors.
     */
    public void writeBufferedImage(TiffWriteMap map, BufferedImage bufferedImage)
            throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        writeBufferedImage(map, bufferedImage, 0, 0);
    }

    public void writeBufferedImage(
            TiffWriteMap map,
            BufferedImage bufferedImage,
            int fromX,
            int fromY) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        Objects.requireNonNull(bufferedImage, "Null bufferedImage");
        writeChannels(map, ImageToMatrix.toChannels(bufferedImage), fromX, fromY);
    }

    public int completeWriting(final TiffWriteMap map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        final boolean resizable = map.isResizable();
        map.checkTooSmallDimensionsForCurrentGrid();

        encode(map, "completion");
        // - encode tiles, which are not encoded yet

        final TiffIFD ifd = map.ifd();
        if (resizable) {
            ifd.updateImageDimensions(map.dimX(), map.dimY(), true);
        }

        final int count = completeWritingMap(map);
        map.cropAllUnset();
        // - We could call here appendFileUntilEvenLength(),
        // but it not a standard behavior and not a good idea
        // (in this case we will need to "teach" TiffIFD.sizeOfImage method to consider this)

        if (ifd.hasFileOffsetForWriting()) {
            // - usually it means that we did call writeForward
            rewriteIFD(ifd, true);
        } else {
            writeIFDAtFileEnd(ifd, true);
        }

        seekToEnd();
        // - This seeking to the file end is not necessary, but can help to avoid accidental bugs
        // (this is much better than keeping file offset in the middle of the last image
        // between IFD and newly written TIFF tiles).
        return count;
    }

    @Override
    public void close() throws IOException {
        synchronized (fileLock) {
            lastMap = null;
            out.close();
            clearReader();
        }
    }

    public int sizeOfHeader() {
        return TiffIFD.sizeOfFileHeader(bigTiff);
    }

    @Override
    public String toString() {
        return "TIFF writer";
    }

    protected Optional<byte[]> encodeByExternalCodec(TiffTile tile, byte[] decodedData, TiffCodec.Options options)
            throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(decodedData, "Null decoded data");
        Objects.requireNonNull(options, "Null options");
        if (!SCIFIOBridge.isScifioInstalled()) {
            return Optional.empty();
        }
        final Object scifioCodecOptions = options.toSCIFIOStyleOptions(SCIFIOBridge.codecOptionsClass());
        final int width = tile.getSizeX();
        final int height = tile.getSizeY();
        final byte[] encodedData = compressByScifioCodec(tile.ifd(), decodedData, width, height, scifioCodecOptions);
        return Optional.of(encodedData);
    }

    Object scifio() {
        Object scifio = this.scifio;
        if (scifio == null) {
            this.scifio = scifio = SCIFIOBridge.createScifioFromContext(context);
        }
        return scifio;
    }

    private byte[] compressByScifioCodec(
            TiffIFD ifd,
            byte[] data,
            int width,
            int height,
            Object scifioCodecOptions) throws TiffException {
        Objects.requireNonNull(ifd, "Null ifd");
        Objects.requireNonNull(data, "Null data");
        Objects.requireNonNull(scifioCodecOptions, "Null scifioCodecOptions");
        final int compressionCode = ifd.getCompressionCode();
        final Object scifio = scifio();
        if (scifio == null) {
            // - in other words, this.context is not set
            throw new UnsupportedTiffFormatException("Writing with TIFF compression " +
                    TagCompression.toPrettyString(ifd.optInt(Tags.COMPRESSION, TiffIFD.COMPRESSION_NONE)) +
                    " is not supported without external codecs");
        }
        final Object compression;
        try {
            compression = SCIFIOBridge.createTiffCompression(compressionCode);
        } catch (InvocationTargetException e) {
            throw new UnsupportedTiffFormatException("TIFF compression code " + compressionCode +
                    " is unknown and is not correctly recognized by the external SCIFIO subsystem", e);
        }
        try {
            final Class<?> scifioIFDClass = SCIFIOBridge.scifioIFDClass();
            final Map<Integer, Object> scifioIFD = SCIFIOBridge.createIFD(scifioIFDClass);
            scifioIFD.putAll(ifd.map());
            scifioIFD.put(0, ifd.isLittleEndian()); // IFD.LITTLE_ENDIAN
            scifioIFD.put(1, ifd.isBigTiff());      // IFD.BIG_TIFF
            scifioIFD.put(Tags.IMAGE_WIDTH, width);
            scifioIFD.put(Tags.IMAGE_LENGTH, height);
            // - some correct dimensions (especially useful for a resizable map, when dimensions are not set yet)
            scifioCodecOptions = SCIFIOBridge.getCompressionCodecOptions(compression, scifioIFD, scifioCodecOptions);
            return SCIFIOBridge.callCompress(scifio, compression, data, scifioCodecOptions);
        } catch (InvocationTargetException e) {
            throw new TiffException("TIFF compression code " + compressionCode + " is unknown and " +
                    "cannot be correctly processed for compression by the external SCIFIO subsystem", e);
        }
    }

    private void clearTime() {
        timeWriting = 0;
        timeCustomizingEncoding = 0;
        timePreparingEncoding = 0;
        timeEncoding = 0;
        timeEncodingMain = 0;
        timeEncodingBridge = 0;
        timeEncodingAdditional = 0;
    }

    private void clearReader() {
        synchronized (fileLock) {
            this.reader = null;
        }
    }

    private void checkVirginFile() throws IOException {
        if (positionOfLastIFDOffset < 0) {
            throw new IllegalStateException("TIFF file is not yet created / opened for writing");
        }
        final boolean exists = out.exists();
        if (!exists || out.length() < sizeOfHeader()) {
            // - very improbable, but can occur as a result of direct operations with the output stream
            throw new IllegalStateException(
                    (exists ?
                            "Existing TIFF file is too short (" + out.length() + " bytes)" :
                            "TIFF file does not exists yet") +
                            ": probably file header was not written correctly by open()/create() methods");
        }
    }

    private void writeIFDNumberOfEntries(int numberOfEntries) throws IOException {
        if (bigTiff) {
            out.writeLong(numberOfEntries);
        } else {
            writeUnsignedShort(out, numberOfEntries);
        }
    }

    private long writeIFDEntries(Map<Integer, Object> ifd, long startOffset, int mainIFDLength) throws IOException {
        final long afterMain = startOffset + mainIFDLength;
        final BytesLocation bytesLocation = new BytesLocation(0, "memory-buffer");
        final long positionOfNextOffset;
        try (final DataHandle<? extends Location> extraBuffer = TiffReader.getBytesHandle(bytesLocation)) {
            extraBuffer.setLittleEndian(isLittleEndian());
            for (final Map.Entry<Integer, Object> e : ifd.entrySet()) {
                writeIFDValueAtCurrentPosition(extraBuffer, afterMain, e.getKey(), e.getValue());
            }

            positionOfNextOffset = out.offset();
            writeOffset(TiffIFD.LAST_IFD_OFFSET);
            // - not too important: will be rewritten in writeIFDNextOffset
            final long extraLength = extraBuffer.length();
            assert extraBuffer.offset() == extraLength;
            extraBuffer.seek(0L);
            copyData(extraBuffer, out, extraLength);
        }
        return positionOfNextOffset;
    }

    /**
     * Writes the given IFD value to the {@link #output() main output stream}, excepting "extra" data,
     * which are written into the specified <code>extraBuffer</code>. After calling this method, you
     * should copy full content of <code>extraBuffer</code> into the main stream at the position,
     * specified by the second argument; {@link #rewriteIFD(TiffIFD, boolean)} method does it automatically.
     *
     * <p>Here "extra" data means all data, for which IFD contains their offsets instead of data itself,
     * like arrays or text strings. The "main" data is a 12-byte IFD record (20-byte for BigTIFF),
     * which is written by this method into the main output stream from its current position.
     *
     * @param extraBuffer              buffer to which "extra" IFD information should be written.
     * @param bufferOffsetInResultFile position of "extra" data in the result TIFF file =
     *                                 bufferOffsetInResultFile +
     *                                 offset of the written "extra" data inside <code>extraBuffer</code>;
     *                                 for example, this argument may be a position directly after
     *                                 the "main" content (sequence of 12/20-byte records).
     * @param tag                      IFD tag to write.
     * @param value                    IFD value to write.
     */
    private void writeIFDValueAtCurrentPosition(
            final DataHandle<? extends Location> extraBuffer,
            final long bufferOffsetInResultFile,
            final int tag,
            Object value) throws IOException {
        // convert singleton objects into arrays, for simplicity
        if (value instanceof Short v) {
            value = new short[]{v};
        } else if (value instanceof Integer v) {
            value = new int[]{v};
        } else if (value instanceof Long v) {
            value = new long[]{v};
        } else if (value instanceof TagRational v) {
            value = new TagRational[]{v};
        } else if (value instanceof Float v) {
            value = new float[]{v};
        } else if (value instanceof Double v) {
            value = new double[]{v};
        }

        boolean emptyStringList = false;
        if (value instanceof String[] list) {
            emptyStringList = list.length == 0;
            value = String.join("\0", list);
        } else if (value instanceof List<?> list) {
            emptyStringList = list.isEmpty();
            value = list.stream().map(String::valueOf).collect(Collectors.joining("\0"));
        }

        final boolean bigTiff = this.bigTiff;
        final int dataLength = bigTiff ? 8 : 4;
        final int dataLengthDiv2 = dataLength >> 1;
        final int dataLengthDiv4 = dataLength >> 2;

        // write directory entry to output buffers
        writeUnsignedShort(out, tag);
        if (value instanceof byte[] q) {
            out.writeShort(TagTypes.UNDEFINED);
            // - Most probable type. Maybe in future we will support here some algorithm,
            // determining the necessary type on the base of the tag value.
            writeIntOrLong(out, q.length);
            if (q.length <= dataLength) {
                for (byte byteValue : q) {
                    out.writeByte(byteValue);
                }
                for (int i = q.length; i < dataLength; i++) {
                    out.writeByte(0);
                }
            } else {
                appendUntilEvenPosition(extraBuffer);
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                extraBuffer.write(q);
            }
        } else if (value instanceof short[] q) { // suppose BYTE (unsigned 8-bit)
            out.writeShort(TagTypes.BYTE);
            writeIntOrLong(out, q.length);
            if (q.length <= dataLength) {
                for (short s : q) {
                    out.writeByte(s);
                }
                for (int i = q.length; i < dataLength; i++) {
                    out.writeByte(0);
                }
            } else {
                appendUntilEvenPosition(extraBuffer);
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (short shortValue : q) {
                    extraBuffer.writeByte(shortValue);
                }
            }
        } else if (value instanceof String) { // suppose ASCII
            out.writeShort(TagTypes.ASCII);
            final char[] q = ((String) value).toCharArray();
            writeIntOrLong(out, emptyStringList ? 0 : q.length + 1);
            // - with concluding zero bytes, excepting an empty string list (produced by ASCII byte[0])
            if (q.length < dataLength) {
                // - this branch is the same for an empty string list (byte[0])
                // and for an empty string (byte[1] which contains 0)
                for (char c : q) {
                    writeUnsignedByte(out, c);
                }
                for (int i = q.length; i < dataLength; i++) {
                    out.writeByte(0);
                }
            } else {
                appendUntilEvenPosition(extraBuffer);
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (char charValue : q) {
                    writeUnsignedByte(extraBuffer, charValue);
                }
                extraBuffer.writeByte(0); // concluding zero bytes
            }
        } else if (value instanceof int[] q) { // suppose SHORT (unsigned 16-bit)
            if (q.length == 1) {
                // - we should allow using usual int values for 32-bit tags to avoid a lot of obvious bugs
                final int v = q[0];
                if (v >= 0xFFFF) {
                    out.writeShort(TagTypes.LONG);
                    writeIntOrLong(out, q.length);
                    writeIntOrLong(out, v);
                    return;
                }
            }
            out.writeShort(TagTypes.SHORT);
            writeIntOrLong(out, q.length);
            if (q.length <= dataLengthDiv2) {
                for (int intValue : q) {
                    writeUnsignedShort(out, intValue);
                }
                for (int i = q.length; i < dataLengthDiv2; i++) {
                    out.writeShort(0);
                }
            } else {
                appendUntilEvenPosition(extraBuffer);
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (int intValue : q) {
                    extraBuffer.writeShort(intValue);
                }
            }
        } else if (value instanceof long[] q) { // suppose LONG (unsigned 32-bit) or LONG8 for BitTIFF
            if (AVOID_LONG8_FOR_ACTUAL_32_BITS && q.length == 1 && bigTiff) {
                // - note: inside TIFF, long[1] is saved in the same way as Long; we have a difference in Java only
                final long v = q[0];
                if (v == (int) v) {
                    // - it is probable for the following tags if they are added
                    // manually via TiffIFD.put with "long" argument
                    switch (tag) {
                        case Tags.IMAGE_WIDTH,
                             Tags.IMAGE_LENGTH,
                             Tags.TILE_WIDTH,
                             Tags.TILE_LENGTH,
                             Tags.IMAGE_DEPTH,
                             Tags.ROWS_PER_STRIP,
                             Tags.NEW_SUBFILE_TYPE -> {
                            out.writeShort(TagTypes.LONG);
                            writeIntOrLong(out, q.length);
                            out.writeInt((int) v);
                            out.writeInt(0);
                            // - 4 bytes of padding until full length 20 bytes
                            return;
                        }
                    }
                }
            }
            final int type = bigTiff ? TagTypes.LONG8 : TagTypes.LONG;
            out.writeShort(type);
            writeIntOrLong(out, q.length);

            if (q.length <= 1) {
                for (int i = 0; i < q.length; i++) {
                    writeIntOrLong(out, q[0]);
                    // - q[0]: it is actually performed 0 or 1 times
                }
                for (int i = q.length; i < 1; i++) {
                    writeIntOrLong(out, 0);
                }
            } else {
                appendUntilEvenPosition(extraBuffer);
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (long longValue : q) {
                    writeIntOrLong(extraBuffer, longValue);
                }
            }
        } else if (value instanceof TagRational[] q) {
            out.writeShort(TagTypes.RATIONAL);
            writeIntOrLong(out, q.length);
            if (bigTiff && q.length == 1) {
                out.writeInt((int) q[0].getNumerator());
                out.writeInt((int) q[0].getDenominator());
            } else {
                appendUntilEvenPosition(extraBuffer);
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (TagRational tagRational : q) {
                    extraBuffer.writeInt((int) tagRational.getNumerator());
                    extraBuffer.writeInt((int) tagRational.getDenominator());
                }
            }
        } else if (value instanceof float[] q) {
            out.writeShort(TagTypes.FLOAT);
            writeIntOrLong(out, q.length);
            if (q.length <= dataLengthDiv4) {
                for (float floatValue : q) {
                    out.writeFloat(floatValue); // value
                    // - in old SCIFIO code, here was a bug (for a case bigTiff): q[0] was always written
                }
                for (int i = q.length; i < dataLengthDiv4; i++) {
                    out.writeInt(0); // padding
                }
            } else {
                appendUntilEvenPosition(extraBuffer);
                writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
                for (float floatValue : q) {
                    extraBuffer.writeFloat(floatValue);
                }
            }
        } else if (value instanceof double[] q) {
            out.writeShort(TagTypes.DOUBLE);
            writeIntOrLong(out, q.length);
            appendUntilEvenPosition(extraBuffer);
            writeOffset(bufferOffsetInResultFile + extraBuffer.offset());
            for (final double doubleValue : q) {
                extraBuffer.writeDouble(doubleValue);
            }
        } else if (!(value instanceof TiffIFD.UnsupportedTypeValue)) {
            // - for unsupported type, we don't know the sense of its valueOrOffset field; it is better to skip it
            throw new UnsupportedOperationException("Unknown IFD tag " + tag + " value type ("
                    + value.getClass().getSimpleName() + "): " + value);
        }
    }


    private void writeIFDNextOffsetAt(TiffIFD ifd, long positionToWrite, boolean updatePositionOfLastIFDOffset)
            throws IOException {
        writeIFDOffsetAt(
                ifd.hasNextIFDOffset() ? ifd.getNextIFDOffset() : TiffIFD.LAST_IFD_OFFSET,
                positionToWrite,
                updatePositionOfLastIFDOffset);
    }

    private void encode(TiffWriteMap map, String stage) throws TiffException {
        Objects.requireNonNull(map, "Null TIFF map");
        long t1 = debugTime();
        int count = 0;
        long sizeInBytes = 0;
        for (TiffTile tile : map.tiles()) {
            final boolean wasNotEncodedYet = encode(tile);
            if (wasNotEncodedYet) {
                count++;
                sizeInBytes += tile.getSizeInBytes();
            }
        }
        long t2 = debugTime();
        logTiles(map, stage, "encoded", count, sizeInBytes, t1, t2);
    }

    private int completeWritingMap(TiffWriteMap map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        final long[] offsets = new long[map.numberOfGridTiles()];
        final long[] byteCounts = new long[map.numberOfGridTiles()];
        // - zero-filled by Java
        TiffTile filler = null;
        final int numberOfSeparatedPlanes = map.numberOfSeparatedPlanes();
        final int gridCountY = map.gridCountY();
        final int gridCountX = map.gridCountX();
        long t1 = debugTime();
        int count = 0;
        long sizeInBytes = 0;
        for (int p = 0, k = 0; p < numberOfSeparatedPlanes; p++) {
            for (int yIndex = 0; yIndex < gridCountY; yIndex++) {
                for (int xIndex = 0; xIndex < gridCountX; xIndex++, k++) {
                    TiffTileIndex tileIndex = map.index(xIndex, yIndex, p);
                    TiffTile tile = map.getOrNew(tileIndex);
                    // - non-existing is created (empty) and saved in the map;
                    // this is necessary to inform the map about the new data file range for this tile
                    // and to avoid twice writing it while twice calling "complete()" method
                    tile.cropStripToMap();
                    // - like in updateSamples
                    if (!tile.isEmpty()) {
                        writeEncodedTile(tile, true);
                        count++;
                        sizeInBytes += tile.getSizeInBytes();
                    }
                    if (tile.isStoredInFile()) {
                        offsets[k] = tile.getStoredInFileDataOffset();
                        byteCounts[k] = tile.getStoredInFileDataLength();
                    } else {
                        assert tile.isEmpty() : "writeEncodedTile() call above did not store data file offset!";
                        if (!missingTilesAllowed) {
                            if (!tile.equalSizes(filler)) {
                                // - usually performed once, maybe twice for stripped image (where the last strip has
                                // smaller height)
                                // or even 2 * numberOfSeparatedPlanes times for plane-separated tiles
                                filler = new TiffTile(tileIndex).setEqualSizes(tile);
                                filler.fillWhenEmpty(tileInitializer);
                                encode(filler);
                                writeEncodedTile(filler, false);
                                // - note: unlike usual tiles, the filler tile is written once,
                                // but its offset/byte-count is used many times!
                            }
                            offsets[k] = filler.getStoredInFileDataOffset();
                            byteCounts[k] = filler.getStoredInFileDataLength();
                            tile.copyStoredInFileDataRange(filler);
                        }
                        // else (if missingTilesAllowed) offsets[k]/byteCounts[k] stay to be zero
                    }
                }
            }
        }
        map.ifd().updateDataPositioning(offsets, byteCounts);
        long t2 = debugTime();
        logTiles(map, "completion", "wrote", count, sizeInBytes, t1, t2);
        return count;
    }

    /**
     * Write the given value to the given RandomAccessOutputStream. If the
     * 'bigTiff' flag is set, then the value will be written as an 8-byte long;
     * otherwise, it will be written as a 4-byte integer.
     */
    private void writeIntOrLong(DataHandle<? extends Location> handle, long value) throws IOException {
        if (bigTiff) {
            handle.writeLong(value);
        } else {
            if (value < Integer.MIN_VALUE || value > 0xFFFFFFFFL) {
                // - note: positive values in range 0x80000000..0xFFFFFFFF are mostly probably unsigned integers,
                // not signed values with overflow
                throw new TiffException("Attempt to write 64-bit value as 32-bit: " + value);
            }
            handle.writeInt((int) value);
        }
    }

    private void writeIntOrLong(DataHandle<? extends Location> handle, int value) throws IOException {
        if (bigTiff) {
            handle.writeLong(value);
        } else {
            handle.writeInt(value);
        }
    }

    private static void writeUnsignedShort(DataHandle<? extends Location> handle, int value) throws IOException {
        if (value < 0 || value > 0xFFFF) {
            throw new TiffException("Attempt to write 32-bit value as 16-bit: " + value);
        }
        handle.writeShort(value);
    }

    private static void writeUnsignedByte(DataHandle<? extends Location> handle, int value) throws IOException {
        if (value < 0 || value > 0xFF) {
            throw new TiffException("Attempt to write 16/32-bit value as 8-bit: " + value);
        }
        handle.writeByte(value);
    }

    private void writeOffset(long offset) throws IOException {
        if (offset < 0) {
            throw new AssertionError("Illegal usage of writeOffset: negative offset " + offset);
        }
        if (bigTiff) {
            out.writeLong(offset);
        } else {
            if (offset > 0xFFFFFFF0L) {
                throw new TiffException("Attempt to write too large 64-bit offset as unsigned 32-bit: " + offset
                        + " > 2^32-16; such large files should be written in BigTIFF mode");
            }
            out.writeInt((int) offset);
            // - masking by 0xFFFFFFFF is unnecessary: cast to (int) works properly also for 32-bit unsigned values
        }
    }

    private void writeIFDOffsetAt(long offset, long positionToWrite, boolean updatePositionOfLastIFDOffset)
            throws IOException {
        synchronized (fileLock) {
            // - to be on the safe side (this synchronization is not necessary)
            clearReader();
            final long savedPosition = out.offset();
            try {
                out.seek(positionToWrite);
                writeOffset(offset);
                if (updatePositionOfLastIFDOffset && offset == TiffIFD.LAST_IFD_OFFSET) {
                    positionOfLastIFDOffset = positionToWrite;
                }
            } finally {
                out.seek(savedPosition);
            }
        }
    }

    private void seekToEnd() throws IOException {
        synchronized (fileLock) {
            out.seek(out.length());
        }
    }

    private void appendFileUntilEvenLength() throws IOException {
        synchronized (fileLock) {
            seekToEnd();
            appendUntilEvenPosition(out);
        }
    }

    private static void appendUntilEvenPosition(DataHandle<? extends Location> handle) throws IOException {
        if ((handle.offset() & 0x1) != 0) {
//            System.out.println("Correction " + handle.offset());
            handle.writeByte(0);
            // - Well-formed IFD requires even offsets
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
        options.setInterleaved(true);
        if (this.compressionQuality != null) {
            options.setCompressionQuality(this.compressionQuality);
        }
        if (this.losslessCompressionLevel != null) {
            options.setLosslessCompressionLevel(this.losslessCompressionLevel);
        }
        options.setIfd(tile.ifd());
        return options;
    }

    private void logWritingMatrix(TiffWriteMap map, Matrix<?> matrix, long t1, long t2, long t3, long t4) {
        if (TiffReader.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            final boolean sourceInterleaved = !map.isPlanarSeparated() && !AUTO_INTERLEAVE_SOURCE;
            final long dimX = matrix.dim(sourceInterleaved ? 1 : 0);
            final long dimY = matrix.dim(sourceInterleaved ? 2 : 1);
            // - already checked that they are actually "int"
            logWritingMatrix(map, "matrix", dimX, dimY, t1, t2, t3, t4);
        }
    }

    private void logTiles(
            Collection<TiffTile> tiles,
            String stage,
            String action,
            int count,
            long sizeInBytes,
            long t1,
            long t2) {
        logTiles(tiles.isEmpty() ? null : tiles.iterator().next().map(), stage, action, count, sizeInBytes, t1, t2);
    }

    private void logTiles(TiffMap map, String stage, String action, int count, long sizeInBytes, long t1, long t2) {
        if (TiffReader.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            LOG.log(System.Logger.Level.TRACE, () ->
                    count == 0 ?
                            String.format(Locale.US,
                                    "%s%s %s no tiles in %.3f ms",
                                    getClass().getSimpleName(),
                                    stage == null ? "" : " (" + stage + " stage)",
                                    action,
                                    (t2 - t1) * 1e-6) :
                            String.format(Locale.US,
                                    "%s%s %s %d tiles %dx%dx%d (%.3f MB) in %.3f ms, %.3f MB/s",
                                    getClass().getSimpleName(),
                                    stage == null ? "" : " (" + stage + " stage)",
                                    action,
                                    count, map.numberOfChannels(), map.tileSizeX(), map.tileSizeY(),
                                    sizeInBytes / 1048576.0,
                                    (t2 - t1) * 1e-6,
                                    sizeInBytes / 1048576.0 / ((t2 - t1) * 1e-9)));
        }
    }

    private void logWritingMatrix(
            TiffWriteMap map,
            String name,
            long dimX,
            long dimY,
            long t1,
            long t2,
            long t3,
            long t4) {
        if (TiffReader.BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            long t5 = debugTime();
            final long sizeInBytes = map.totalSizeInBytes();
            LOG.log(System.Logger.Level.DEBUG, () -> String.format(Locale.US,
                    "%s wrote %dx%dx%d %s (%.3f MB) in %.3f ms = " +
                            "%.3f conversion/copying data + %.3f writing IFD " +
                            "+ %.3f/%.3f encoding/writing " +
                            "(%.3f prepare, " +
                            "%.3f customize, " +
                            "%.3f encode [%.3f main%s], " +
                            "%.3f write), %.3f MB/s",
                    getClass().getSimpleName(),
                    dimX, dimY, map.numberOfChannels(),
                    name,
                    sizeInBytes / 1048576.0,
                    (t5 - t1) * 1e-6,
                    (t2 - t1) * 1e-6, (t3 - t2) * 1e-6,
                    (t4 - t3) * 1e-6, (t5 - t4) * 1e-6,
                    timePreparingEncoding * 1e-6,
                    timeCustomizingEncoding * 1e-6,
                    timeEncoding * 1e-6,
                    timeEncodingMain * 1e-6,
                    timeEncodingBridge + timeEncodingAdditional > 0 ?
                            String.format(Locale.US, " + %.3f encode-bridge + %.3f encode-additional",
                                    timeEncodingBridge * 1e-6,
                                    timeEncodingAdditional * 1e-6) :
                            "",
                    timeWriting * 1e-6,
                    sizeInBytes / 1048576.0 / ((t5 - t1) * 1e-9)));
        }
    }

    // A simplified clone of the function DataHandles.copy without the problem with invalid generic types
    private static long copyData(DataHandle<? extends Location> in, DataHandle<? extends Location> out, long length)
            throws IOException {
        if (length < 0) {
            throw new IllegalArgumentException("Negative length: " + length);
        }
        final byte[] buffer = new byte[64 * 1024];
        long result = 0;
        while (result < length) {
            final int len = (int) Math.min(length - result, buffer.length);
            int actuallyRead = in.read(buffer, 0, len);
            if (actuallyRead <= 0) break; // EOF
            out.write(buffer, 0, actuallyRead);
            result += actuallyRead;
        }
        return result;
    }

    private static void checkPhotometricInterpretation(
            TagPhotometricInterpretation photometricInterpretation,
            EnumSet<TagPhotometricInterpretation> allowed,
            String whatToWrite)
            throws TiffException {
        if (photometricInterpretation != null) {
            if (!allowed.contains(photometricInterpretation)) {
                throw new TiffException("Writing " + whatToWrite + " with photometric interpretation \"" +
                        photometricInterpretation.prettyName() + "\" is not supported (only " +
                        allowed.stream().map(photometric -> "\"" + photometric.prettyName() + "\"")
                                .collect(Collectors.joining(", ")) +
                        " allowed)");
            }
        }
    }

    private static long debugTime() {
        return TiffReader.BUILT_IN_TIMING && LOGGABLE_DEBUG ? System.nanoTime() : 0;
    }

    private static DataHandle<? extends Location> openWithDeletingPreviousFileIfRequested(
            Path file,
            TiffCreateMode createMode)
            throws IOException {
        Objects.requireNonNull(file, "Null file");
        Objects.requireNonNull(createMode, "Null createMode");
        if (createMode.isForceCreateNewFile()) {
            Files.deleteIfExists(file);
        }
        return TiffReader.getFileHandle(file);
    }
}