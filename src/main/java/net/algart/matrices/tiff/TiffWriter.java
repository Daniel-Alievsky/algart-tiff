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

import net.algart.arrays.Matrix;
import net.algart.arrays.PArray;
import net.algart.arrays.PackedBitArraysPer8;
import net.algart.matrices.tiff.TiffIFD.Linkage;
import net.algart.matrices.tiff.bits.TiffPacking;
import net.algart.matrices.tiff.bits.TiffPrediction;
import net.algart.matrices.tiff.codecs.TiffCodec;
import net.algart.matrices.tiff.samples.TiffSampleType;
import net.algart.matrices.tiff.tags.TagCompression;
import net.algart.matrices.tiff.tags.TagPhotometric;
import net.algart.matrices.tiff.tags.TagType;
import net.algart.matrices.tiff.tags.Tags;
import net.algart.matrices.tiff.tiles.*;
import org.scijava.io.handle.BytesHandle;
import org.scijava.io.handle.DataHandle;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;

/**
 * Writes the TIFF format.
 *
 * <p>This object is internally synchronized and thread-safe for concurrent use.
 * However, you should not modify objects, passed to the methods of this class, from a parallel thread;
 * in particular, it concerns the {@link TiffIFD} arguments and Java-arrays with samples.
 * The same is true for the result of {@link #stream()} method.</p>
 */
public non-sealed class TiffWriter extends TiffIO {
    /**
     * If the file grows to about this limit and {@link #setBigTiff(boolean) BigTIFF} mode is not set,
     * attempt to write new IFD at the file end by methods of this class throw IO exception.
     * While writing tiles, an exception will be thrown only while exceeding the limit <code>2^32-1</code>
     * (~280 MB greater than this value {@value}).
     */
    public static final long MAXIMAL_ALLOWED_32BIT_IFD_OFFSET = 4_000_000_000L;

    private static final boolean AUTO_INTERLEAVE_SOURCE = true;
    // - Must be true. The alternative mode (false), where the source data may already be interleaved,
    // was implemented in the past for compatibility with TiffSaver class.
    // It is no longer supported since the version 1.4.0, because this makes the behavior
    // of TiffWriter nonpredictable without analyzing this mode; but it still can be enabled by this flag.
    // Note that the alternate mode (false) was never supported in writeChannels and writeBufferedImage methods
    // and also is not supported in writeMatrix since the version 1.5.2.
    // IF YOU CHANGE IT, YOU MUST CORRECT ALSO TiffWriteMap.AUTO_INTERLEAVE_SOURCE
    // (and, for testing, the AUTO_INTERLEAVE_SOURCE constant in TiffWriterTest)

    private static final boolean ACCURATE_REWRITING_IMAGE_LAYOUT_ON_COMPLETE = true;
    // - Should be true in the new version (since 1.5.2), but false value also should work normally

    private boolean prewritingAllowed = true;
    private boolean smartCorrection = false;
    private TiffCodec.Customizer codecCustomizer = null;
    private boolean enforceUseExternalCodec = false;
    private Double compressionQuality = null;
    private Double losslessCompressionLevel = null;
    private boolean alwaysWriteToFileEnd = false;
    private boolean missingTilesAllowed = false;
    private TiffReaderFactory companionReaderFactory = TiffWriter::defaultCompanionReader;

    private volatile TiffReader reader = null;
    private volatile Linkage linkage = null;

    private volatile TiffWriteMap lastMap = null;
    private volatile boolean lastMapPrewritten = false;

    private long timeWriting = 0;
    private long timePreparingEncoding = 0;
    private long timeCustomizingEncoding = 0;
    private long timeEncoding = 0;
    private long timeEncodingMain = 0;
    private long timeEncodingBridge = 0;
    private long timeEncodingAdditional = 0;

    /**
     * Equivalent to <code>new {@link #TiffWriter(Path, TiffCreateMode)
     * TiffWriter}(file, {@link TiffCreateMode#NO_ACTIONS TiffCreateMode.NO_ACTIONS)}</code>.
     *
     * <p>Note: unlike classes like {@link java.io.FileWriter},
     * this constructor <b>does not try to open or create file</b>.
     * If you need, you can use another constructor with the argument {@link TiffCreateMode}, for example:</p>
     * <pre>
     *     var writer = new {@link #TiffWriter(Path, TiffCreateMode)
     *     TiffWriter}(path, {@link TiffCreateMode#CREATE CreateMode.CREATE});
     * </pre>
     *
     * @param file output TIFF file.
     */
    public TiffWriter(Path file) {
        this(getFileHandle(file), file);
    }

    /**
     * Creates a new TIFF writer.
     *
     * <p>If the argument <code>createMode</code> is {@link TiffCreateMode#NO_ACTIONS},
     * the constructor does not try to open or create a file and, so, never
     * throw {@link IOException}.
     * This behavior <b>differs</b> from the constructor of {@link java.io.FileWriter#FileWriter(File) FileWriter}
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
     * @param file       output TIFF file.
     * @param createMode what do you need to do with this file?
     * @throws IOException if an I/O error occurs.
     */
    public TiffWriter(Path file, TiffCreateMode createMode) throws IOException {
        this(openWithDeletingPreviousFileIfRequested(file, createMode), file);
        try {
            createMode.configureWriter(this);
        } catch (IOException exception) {
            try {
                stream.close();
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
    public TiffWriter(DataHandle<?> outputStream) {
        this(outputStream, null);
    }

    private TiffWriter(DataHandle<?> outputStream, Path file) {
        super(outputStream, file);
        // - we do not use WriteBufferDataHandle here: this is not too important for efficiency
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
            stream.setLittleEndian(littleEndian);
        }
        return this;
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
     * Sets whether a BigTIFF file should be created.
     * This flag must be set before creating the file by {@link #create()} method.
     * Default value is <code>false</code>.
     */
    public TiffIO setBigTiff(boolean bigTiff) {
        this.bigTiff = bigTiff;
        return this;
    }

    public TiffWriter setCompatibleFileFormat(TiffReader reader) {
        Objects.requireNonNull(reader, "Null TIFF reader");
        this.setBigTiff(reader.isBigTiff());
        this.setLittleEndian(reader.isLittleEndian());
        return this;
    }

    public boolean isPrewritingAllowed() {
        return prewritingAllowed;
    }

    public TiffWriter setPrewritingAllowed(boolean prewritingAllowed) {
        this.prewritingAllowed = prewritingAllowed;
        return this;
    }

    public boolean isSmartCorrection() {
        return smartCorrection;
    }

    /**
     * Sets smart IFD correction mode.
     *
     * <p>IFD, offered by the user for writing TIFF image (usually with help of {@link #newFixedMap(TiffIFD)} method),
     * may contain specification, which are incorrect or not supported by this class. For example,
     * the user may specify {@link TiffIFD#putPhotometric(TagPhotometric)
     * photometric interpretation} {@link TagPhotometric#RGB_PALETTE}, but not provide actual
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
     * <p>Note that this flag is passed to {@link #correctForEncoding(TiffIFD, boolean)} method,
     * called inside <code>writeSamples</code>/<code>writeJavaArray</code> methods.
     *
     * @param smartCorrection <code>true</code> means that we enable smart correction of the specified IFD
     *                        and do not require strictly accurate, fully supported IFD settings.
     * @return a reference to this object.
     */
    public TiffWriter setSmartCorrection(boolean smartCorrection) {
        this.smartCorrection = smartCorrection;
        return this;
    }

    public TiffCodec.Customizer getCodecCustomizer() {
        return codecCustomizer;
    }

    public TiffWriter setCodecCustomizer(TiffCodec.Customizer codecCustomizer) {
        this.codecCustomizer = codecCustomizer;
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
     * For JPEG-2000, the maximal possible value is <code>Double.MAX_VALUE</code> that means lossless compression.
     *
     * <p>If this method was not called or after {@link #removeCompressionQuality()}, the compression quality is
     * not specified.
     * In this case, some default quality will be used.
     * In particular, it will be 1.0 for JPEG (maximal JPEG quality),
     * 5.0 for usual (lossy) JPEG-2000 formats, <code>Double.MAX_VALUE</code> for lossless JPEG-2000 like
     * {@link TagCompression#JPEG_2000_LOSSLESS}.
     * Note that the only difference between lossless JPEG-2000 and the usual JPEG-2000 is these default values:
     * if this method is called, both compressions work identically (but write different TIFF compression tags).
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

    public boolean isAlwaysWriteToFileEnd() {
        return alwaysWriteToFileEnd;
    }

    /**
     * If <code>true</code>, any new pixel data will always be written to the end of the TIFF file.
     * If <code>false</code>, a tile loaded from this file and modified may potentially be written
     * to the same position, if it does not destroy other data. (For example, the tile can
     * be overwritten in-place if the new encoded data size is the same or smaller than the length
     * of data written in the file.)
     *
     * <p>Default value is <code>false</code> (a more intelligent "smart" mode).
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
     * Configures the special mode allowing the writer to mark "missing" tiles or strips
     * by setting their offset (the {@code TileOffsets} or {@code StripOffsets} tag) and/or
     * the byte count (the {@code TileByteCounts} or {@code StripByteCounts} tag) to zero.
     * In this mode, if a tile or strip is left empty (no pixels were written to it via
     * {@link TiffWriteMap#updateSampleBytes(byte[], int, int, int, int)} or other updating methods),
     * this class writes zero for its offset and byte count. Otherwise,
     * this writer will create a normal tile, filled with zeros or
     * with other values specified via {@link #setByteFiller(byte)} or
     * {@link #setTileInitializer(Consumer)}.
     *
     * <p>The default value is {@code false} (this mode is disabled). When {@code false}, all tiles or strips
     * are physically written and allocated, ensuring compliance with the TIFF specification
     * which does not allow zero values for these fields.</p>
     *
     * <p>Enabling this mode ({@code true}) allows generating TIFF files that follow the "sparse tiling"
     * convention, such as <b>Philips TIFF</b> or <b>ARGOS TIFF</b>, where "missing" tiles are used
     * to represent regions of interest.
     * Note that it does not make sense to use this mode for saving disk space:
     * when this flag is disabled, an empty tile is usually compressed very well,
     * and the writer reuses this tile many times if necessary (all empty background tiles will share
     * the same file offset).</p>
     *
     * @param missingTilesAllowed {@code true} to produce missing tiles/strips with zero offsets and byte counts;
     *                            {@code false} (default) to always physically write and fill all tiles/strips.
     * @return a reference to this object.
     * @see TiffReader#setMissingTilesAllowed(boolean)
     */
    public TiffWriter setMissingTilesAllowed(boolean missingTilesAllowed) {
        this.missingTilesAllowed = missingTilesAllowed;
        return this;
    }

    public TiffReaderFactory getCompanionReaderFactory() {
        return companionReaderFactory;
    }

    public TiffWriter setCompanionReaderFactory(TiffReaderFactory companionReaderFactory) {
        Objects.requireNonNull(companionReaderFactory, "Null companionReaderFactory");
        this.companionReaderFactory = companionReaderFactory;
        return this;
    }

    public TiffWriter setByteFiller(byte byteFiller) {
        super.setByteFiller(byteFiller);
        return this;
    }

    public TiffWriter setTileInitializer(Consumer<TiffTile> tileInitializer) {
        super.setTileInitializer(tileInitializer);
        return this;
    }

    /**
     * Clears the reference to the IFD linkage information stored inside this object
     * and returned by the {@link #linkage()} method.
     * Ensures that the next call to {@link #linkage()} will reload this information from the file.
     *
     * <p>Usually, you do not need to use this method directly: it is called automatically by {@link TiffWriter}
     * whenever there is a risk that this linkage information may become incorrect.
     * <b>The exception</b>: you should use this method if you call the low-level
     * {@link #writeOffsetAt(long, long)} method or perform direct modifications in the file
     * via the {@link #stream()} object or other external means.</p>
     *
     * <p>Performance note: sometimes this library <b>skips</b> calling this method
     * if we are certain the IFD linkage structure remains correct,
     * even if other method documentation states that &ldquo;they invalidate linkage&rdquo;.
     * We are free to add such cases in future versions as part of our ongoing library optimization.
     * In any case, the only way to detect such changes in behavior is to use the
     * {@link #linkageIfPresent()} method: it's possible that it will return a non-empty result
     * more often than the documentation implies.</p>
     *
     * @see #writeIFD(TiffIFD, Linkage.UpdateMode)
     * @see #rewriteIFDStrictlyInPlace(TiffIFD, IntPredicate, Linkage.UpdateMode)
     */
    public void invalidateLinkage() {
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
     * <p>Usually, you do not need to use this method manually: it is called automatically by {@link TiffWriter}
     * whenever it requires up-to-date linkage information. The only situation when you might use it
     * is if you want to inspect this information for your own purposes, for example, for logging.</p>
     *
     * @return the linkage information.
     * @throws IOException if an I/O error occurs while reading the linkage.
     */
    public Linkage linkage() throws IOException {
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
    public Optional<Linkage> linkageIfPresent() {
        return Optional.ofNullable(linkage);
    }

    /**
     * Clears the reference to the "companion" TIFF reader stored inside this object
     * and returned by {@link #companionReader()} method.
     * Ensures that the next call of {@link #companionReader()} will create a new reader.
     * Usually you don't need to call this method because it is called automatically.
     *
     * <p>Note: this method <i>does not</i> clear the information, returned by {@link #readLinkage()}.
     * These offsets are used only for automatic IFD linkage by {@link #writeIFD(TiffIFD, Linkage.UpdateMode)}
     * method and are not important if you perform the linkage manually.</p>
     */
    public void invalidateCompanionReader() {
        synchronized (fileLock) {
            this.reader = null;
        }
    }

    /**
     * Returns the "companion" TIFF reader for reading the same file {@link #stream() stream}
     * used by this object.
     *
     * <p>This reader allows the writer to read images for further editing,
     * for example, in methods such as {@link TiffWriteMap#preloadAndStore} or
     * {@link TiffWriteMap#readMatrixAndStore}. This reader is returned by the
     * {@link TiffWriteMap#reader()} method.</p>
     *
     * <p><b>Do not close</b> this reader independently: the shared stream will be closed
     * automatically when closing this writer.</p>
     *
     * <p>The returned instance is cached inside this object.
     * However, the cached reference is cleared to {@code null} by the {@link #invalidateCompanionReader()}
     * method (triggering re-creation on the next call) in the following cases:</p>
     * <ul>
     *     <li>opening/creating the TIFF file via {@link #create()}, {@link #open(boolean)}
     *     and equivalent methods;</li>
     *     <li>writing an IFD into the file;</li>
     *     <li>writing a tile into the file ({@link #writeEncodedTile(TiffTile, boolean)} method);</li>
     *     <li>correction of the IFD offset by {@link #rewriteIFDOffset(int, long)} or
     *     {@link #rewriteLastIFDOffset(long)} method.</li>
     * </ul>
     *
     * <p>This reader is created by {@link #newCompanionReader()} method.
     * By default, this means {@link TiffOpenMode#NO_CHECKS} creation mode and
     * disabled caching.
     * (The caching usually makes no sense, because,
     * as noted above, any writing to the TIFF will destroy the stored reader together with
     * all cached tiles.)
     *
     * <p>You may change the default behavior with help of
     * {@link #setCompanionReaderFactory(TiffReaderFactory)} method.
     *
     * @return the companion TIFF reader.
     * @throws IOException if an I/O error occurs while creating a new reader.
     */
    public TiffReader companionReader() throws IOException {
        final TiffReader result;
        boolean needToCreate = false;
        synchronized (fileLock) {
            if (this.reader == null) {
                needToCreate = true;
                this.reader = newCompanionReader();
            }
            result = this.reader;
        }
        assert result != null : "all assignments to this.reader are synchronized! null impossible";
        if (needToCreate) {
            LOG.log(System.Logger.Level.DEBUG, () -> "Reloading companion reader: " + result);
        }
        return result;
    }

    /**
     * Creates a new "companion" TIFF reader for reading the same file {@link #stream() stream}
     * used by this object.
     * This reader is created using the {@link #setCompanionReaderFactory(TiffReaderFactory)
     * companion reader factory}:
     *
     * <pre>writer.{@link #getCompanionReaderFactory()}.{@link TiffReaderFactory#newReader(DataHandle)
     * newReader}(writer.{@link #stream()})</pre>
     *
     * <p>By default, this means {@link TiffOpenMode#NO_CHECKS} creation mode and
     * disabled caching.</p>
     *
     * <p><b>Do not close</b> this reader independently: the shared stream will be closed
     * automatically when closing this writer.</p>
     *
     * <p>This method is used inside {@link #companionReader()} for creating a new instance.</p>
     *
     * @return a new TIFF reader.
     * @throws IOException if an I/O error occurs.
     */
    public final TiffReader newCompanionReader() throws IOException {
        return getCompanionReaderFactory().newReader(stream());
    }

    /**
     * The default implementation of the {@link #setCompanionReaderFactory(TiffReaderFactory)
     * companion reader factory}. This method is almost equivalent to:
     *
     * <pre>new {@link TiffReader#TiffReader(DataHandle, TiffOpenMode, boolean)
     * TiffReader}(stream, {@link TiffOpenMode#NO_CHECKS}, false).{@link TiffReader#setCaching(boolean)
     * setCaching(false)}</pre>
     *
     * <p>However, this method catches and suppressed {@link IOException}: such exceptions are impossible
     * in {@link {@link TiffOpenMode#NO_CHECKS} mode.</p>
     *
     * <p>Caching in the reader is disabled: usually this reader
     * should be used while you are modifying the TIFF, so the caching makes no sense.
     * If you want, you can enable caching by calling {@link TiffReader#setCaching(boolean) setCaching(true)}.
     *
     * @param stream input stream.
     * @return a new TIFF reader.
     */
    public static TiffReader defaultCompanionReader(DataHandle<?> stream) {
        TiffReader result;
        try {
            result = new TiffReader(stream, TiffOpenMode.NO_CHECKS, false);
        } catch (IOException e) {
            throw new AssertionError("Impossible in NO_CHECKS mode", e);
        }
        return result.setCaching(false);
    }

    /**
     * Opens an existing TIFF file for appending new images.
     *
     * @throws IOException if an I/O error occurs.
     */
    public void openExisting() throws IOException {
        open(false);
    }

    /**
     * Opens the TIFF file for possible appending new images if it exists, or creates a new TIFF file
     * if there is not such a file or if exists but has a zero length.
     *
     * <p>Note that a zero-length file is processed in the same way as a non-existing file.
     * It is important if you want to create a temporary TIFF file using
     * {@link Files#createTempFile(Path, String, String, java.nio.file.attribute.FileAttribute[])
     * Files.createTempFile} or similar methods.
     * In this case, we should not delete this file and create it again
     * (the behavior of the constructor called with the mode {@link TiffCreateMode#CREATE} or similar parameter):
     * there is a chance that some other process will create the same temporary file
     * between removing and re-creating by this class.</p>
     *
     * @throws IOException if an I/O error occurs.
     */
    public void openForAppend() throws IOException {
        open(true);
    }

    /**
     * Equivalent to {@link #openExisting()} if the argument is {@code false}
     * or to {@link #openForAppend()} if the argument is {@code true}.
     *
     * @param createIfNotExists whether you need to create a new TIFF file when there is no existing file.
     * @throws IOException if an I/O error occurs.
     */
    public void open(boolean createIfNotExists) throws IOException {
        synchronized (fileLock) {
            invalidateCompanionReader();
            if (!stream.exists() || stream.length() == 0L) {
                // - Important: we ALLOW appending to zero-length files.
                // It is necessary when creating temporary zero-length files: we should never remove/recreate it!
                if (createIfNotExists) {
                    create();
                } else {
                    throw new FileNotFoundException("Output TIFF file" + spacedStreamName() + " does not exist");
                }
                // In this branch, we MUST NOT try to analyze the file: it is not a correct TIFF!
            } else {
                final TiffReader reader = newReader(TiffOpenMode.VALID_TIFF);
                // - The first opening TIFF is the only place when we MUST use VALID_TIFF mode
                // instead of the usual NO_CHECKS used inside reader() method
                // Note: we should NOT close the reader in the case of any problem,
                // because it uses the same stream with this writer.
                // Note: no sense here to use companionReader(), we just need to initialize file format
                this.setCompatibleFileFormat(reader);
                fileOpen = true;
                invalidateLinkage(false, null);
                // - forcing the following refresh (just in case)
                linkage("Initial loading linkage for existing file");
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
     * @throws IOException if an I/O error occurs.
     */
    public void create(boolean appendToExistingFile) throws IOException {
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
     *
     * @throws IOException if an I/O error occurs.
     */
    public void create() throws IOException {
        synchronized (fileLock) {
            invalidateCompanionReader();
            invalidateLinkage(false, null);
            stream.seek(0);
            // - this call actually creates and opens the file if it was not opened before
            if (isLittleEndian()) {
                stream.writeByte(FILE_PREFIX_LITTLE_ENDIAN);
                stream.writeByte(FILE_PREFIX_LITTLE_ENDIAN);
            } else {
                stream.writeByte(FILE_PREFIX_BIG_ENDIAN);
                stream.writeByte(FILE_PREFIX_BIG_ENDIAN);
            }
            // Writing the magic number:
            if (bigTiff) {
                stream.writeShort(FILE_BIG_TIFF_MAGIC_NUMBER);
            } else {
                stream.writeShort(FILE_USUAL_MAGIC_NUMBER);
            }
            if (bigTiff) {
                stream.writeShort(8);
                // - 16-bit "8" value means the number of bytes in BigTIFF offsets
                stream.writeShort(0);
            }
            this.linkage = newEmptyLinkage();
            // - silently set to a correct value
            writeOffset(stream, bigTiff, TiffIFD.IFD_CHAIN_TERMINATOR);
            // Truncating the file if it already existed:
            // it is necessary because this class writes all new information
            // to the file end (to avoid damaging existing content)
            stream.setLength(stream.offset());
            fileOpen = true;
        }
    }

    /**
     * Returns <code>{@link #companionReader()}.{@link TiffReader#numberOfMainIFDs() numberOfMainIFDs()}</code>.
     * This is the number of existing regular IFDs that can be read by {@link #existingIFD(int, boolean)} method.
     *
     * @return the number of existing main IFDs (not sub-IFDs).
     * @throws IOException if an I/O error occurs.
     */
    public int numberOfExistingImages() throws IOException {
        //noinspection resource
        return companionReader().numberOfMainIFDs();
    }

    /**
     * Writes the IFD to the file at the exact offset previously assigned by
     * {@link TiffIFD#assignFileOffsetOfIFDForWriting(long)} method.
     *
     * <p>This method simply checks whether the offset was assigned via
     * <code>ifd.{@link TiffIFD#isFileOffsetOfIFDForWritingAssigned() isFileOffsetOfIFDForWritingAssigned()}</code>,
     * throws {@code IllegalStateException} if not,
     * and then calls <code>{@link #writeIFD(TiffIFD, Linkage.UpdateMode)
     * writeIFD}(ifd, {@link Linkage.UpdateMode#NONE})}</code>.
     *
     * <p><b>Be accurate</b>: this method can destroy an existing IFD or other data.
     * We recommend using it <b>only</b> in the case when you are sure that the TIFF was created by {@link TiffWriter}
     * and you are sure that the new IFD is not larger than an IFD which is already written in the file
     * at the same place.
     *
     * @param ifd the IFD with an assigned <i>for-writing</i> file offset.
     * @return the offset where the IFD was written.
     * @throws IOException           if an I/O error occurs.
     * @throws IllegalStateException if no offset was assigned to the IFD.
     * @see TiffIFD#assignFileOffsetOfIFDForWriting(long)
     */

    @SuppressWarnings("UnusedReturnValue")
    public long writeIFDToAssignedOffset(TiffIFD ifd) throws IOException {
        Objects.requireNonNull(ifd, "Null IFD");
        if (!ifd.isFileOffsetOfIFDForWritingAssigned()) {
            throw new IllegalStateException("Offset for writing IFD has not been assigned");
        }
        return writeIFD(ifd, Linkage.UpdateMode.NONE);
    }

    /**
     * Writes the IFD to the end of the file.
     *
     * <p>This method simply removes the <i>for-writing</i> file offset via
     * <code>ifd.{@link TiffIFD#removeFileOffsetOfIFDForWriting() removeFileOffsetOfIFDForWriting()}</code>,
     * and then calls <code>{@link #writeIFD(TiffIFD, Linkage.UpdateMode)
     * writeIFD}(ifd, {@link Linkage.UpdateMode#NONE})}</code>.
     *
     * @param ifd the IFD to write to the output stream.
     * @return the offset where this IFD was actually written.
     * @throws IOException if an I/O error occurs.
     */
    public long writeIFDAtFileEnd(TiffIFD ifd) throws IOException {
        ifd.removeFileOffsetOfIFDForWriting();
        return writeIFD(ifd, Linkage.UpdateMode.NONE);
        // Note: writeIFD will call invalidateLinkage() if this IFD is not actually "virgin"
    }

    /**
     * Writes the IFD at the position specified in the IFD's
     * {@link TiffIFD#assignFileOffsetOfIFDForWriting(long) <i>for-writing</i> file offset},
     * or at the end of the file (aligned to the nearest even length) if that offset is
     * {@link TiffIFD#removeFileOffsetOfIFDForWriting() not set}.
     *
     * <p>Note: if the next IFD offset is not specified in this IFD ({@link TiffIFD#hasNextIFDOffset()}
     * returns {@code false}), the IFD written to disk is automatically marked as the last IFD
     * (next IFD offset is 0; but the {@link TiffIFD#getNextIFDOffset() next IFD offset} field in
     * the {@code ifd} object is not modified).</p>
     *
     * <p>If {@code updateModeForNewIFD} is {@link Linkage.UpdateMode#AUTO_APPEND}, this method attempts to
     * correct the linkage information in the file to append the current IFD to the end of the IFDs chain,
     * if it is possible without the risk of creating incorrect linkage. Specifically, this method
     * gets the current linkage information via {@link #linkage()} method, and then:</p>
     *
     * <ol type="A">
     *     <li>if the {@link TiffIFD#assignFileOffsetOfIFDForWriting(long) <i>for-writing</i> IFD offset}
     *     is absolutely new for this file<br>
     *     (that means: <code>{@link #linkage()}.{@link Linkage#containsIFDOffset(long)
     *     containsIFDOffset(offset)}</code>
     *     for this offset returns {@code false}),<br>
     *     in particular, when it is not assigned (writing to the file end),</li>
     *      <li>and if the next IFD offset will be marked as the last IFD<br>
     *      (that means: {@link TiffIFD#isEffectivelyChainTerminator()
     *      ifd.isEffectivelyChainTerminator()} returns {@code true}, see above),</li>
     * </ol>
     *
     * <p>then this method performs the following two actions:</p>
     *
     * <ol>
     *     <li>rewrites 'next IFD offset' stored <b>in the file</b> at the position
     *     <code>{@link #linkage()}.{@link Linkage#offsetOfIFDChainTerminator()
     *     offsetOfIFDChainTerminator()}</code> with the offset of this newly written IFD;
     *     actually, this means that this IFD is appended to the end of the IFDs chain;</li>
     *     <li>performs the necessary corrections in the existing {@link #linkage()} object
     *     (the {@link #invalidateLinkage()} method is not called).</li>
     * </ol>
     *
     * <p>Note that this is a typical situation when writing a new TIFF image to the end of the file.
     * However, this operation is absolutely <b>senseless</b> if the previously written IFD is not actually
     * the predecessor of this one! If you are not going to add a new image to the TIFF,
     * use the {@link Linkage.UpdateMode#NONE} mode.</p>
     *
     * <p>If at least one of the conditions <b>A</b> or <b>B</b> is not met, or if
     * <code>updateModeForNewIFD={@link Linkage.UpdateMode#NONE}</code>,
     * this method does not try to modify anything in the file besides writing this IFD.
     * Also in this case, if condition <b>A</b> is not fulfilled,
     * i.e., if the start offset of the newly written IFD
     * is already {@link Linkage#containsIFDOffset(long) contained} in the existing {@link #linkage()},
     * this method also calls {@link #invalidateLinkage()}.
     * It is necessary because it rewrites 'next IFD offset' field in an existing IFD:
     * it can lead to changes in the linkage structure.</p>
     *
     * <p>Note: this method changes the position in the output stream.
     * (Actually, the position will be after the IFD information, including all additional data
     * like arrays of offsets; but you should not rely on this fact.)</p>
     *
     * <p>Also note: this method always writes the {@link TiffIFD#getNextIFDOffset() next IFD offset}
     * at the end of the IFD structure,
     * as required by the TIFF specification, even if this field is logically unused
     * (as for sub-IFDs or EXIF IFDs).</p>
     *
     * @param ifd                 the IFD to write to the {@link #stream() output stream}.
     * @param updateModeForNewIFD see comments above.
     * @return the offset where this IFD was actually written
     * (it will be equal to the result of the {@link TiffIFD#assignedFileOffsetOfIFDForWriting()} method
     * called on the {@code ifd} after this method completes).
     * @throws IOException if an I/O error occurs.
     * @see #rewriteIFDStrictlyInPlace(TiffIFD, IntPredicate, Linkage.UpdateMode)
     * @see #rewriteIFDOffset(int, long)
     * @see #rewriteLastIFDOffset(long)
     * @see #invalidateLinkage()
     */
    public long writeIFD(TiffIFD ifd, Linkage.UpdateMode updateModeForNewIFD) throws IOException {
        Objects.requireNonNull(ifd, "Null IFD");
        Objects.requireNonNull(updateModeForNewIFD, "Null updateModeForNewIFD");
        synchronized (fileLock) {
            checkVirginFile();
            invalidateCompanionReader();
            final long ifdOffset;
            if (ifd.isFileOffsetOfIFDForWritingAssigned()) {
                ifdOffset = ifd.assignedFileOffsetOfIFDForWriting();
            } else {
                appendFileUntilEvenLength();
                ifdOffset = stream.length();
                ifd.assignFileOffsetOfIFDForWriting(ifdOffset);
            }
            checkFileOffsetForWriting(ifdOffset);

            final Map<Integer, Object> sortedIFD = new TreeMap<>(ifd.map());
            // -  TIFF 6.0 standard, Sort Order:
            // "The entries in an IFD must be sorted in ascending order by Tag"
            final int numberOfEntries = sortedIFD.size();
            final int mainIFDLength = TiffIFD.sizeOfIFDTable(numberOfEntries, bigTiff);
            stream.seek(ifdOffset);
            writeIFDNumberOfEntries(numberOfEntries);

            // for (int k = 0; k < 500; k++) writeIFDEntries(sortedIFD, ifdOffset, mainIFDLength); // - timing
            final long fileOffsetOfNextOffset = writeIFDEntries(sortedIFD, ifdOffset, mainIFDLength);
            ifd.setFileOffsetOfNextIFDOffset(fileOffsetOfNextOffset);

            writeNextOffsetAndUpdateLinkage(ifd, updateModeForNewIFD, null);
            // - note: we write the next offset even if updateModeForNewIFD=Linkage.UpdateMode.NONE;
            // it is useful, for example, in prewrite(TiffWriteMap) method
            return ifdOffset;
        }
    }

    /**
     * Equivalent to
     * <code>{@link #rewriteIFDStrictlyInPlace(TiffIFD, IntPredicate, Linkage.UpdateMode)
     * rewriteIFDStrictlyInPlace}(ifd, tagsToUpdate, {@link Linkage.UpdateMode#NONE})</code>.
     *
     * @param ifd          the IFD containing the new values to be written.
     * @param tagsToUpdate a predicate to select which tags should be updated in the file.
     * @throws IOException              if an I/O error occurs.
     * @throws TiffIFDMismatchException if the new value for a tag does not match
     *                                  the existing value's type or count;
     *                                  if you still need to write the IFD, you may catch this exception
     *                                  and write it at the end of the file using
     *                                  the {@link #writeIFDAtFileEnd(TiffIFD)} method.
     * @throws IllegalArgumentException if the IFD does not have the <i>for-writing</i> file offset.
     */
    public void rewriteIFDStrictlyInPlace(TiffIFD ifd, IntPredicate tagsToUpdate) throws IOException {
        rewriteIFDStrictlyInPlace(ifd, tagsToUpdate, Linkage.UpdateMode.NONE);
    }

    /**
     * An analog of the {@link #writeIFD(TiffIFD, Linkage.UpdateMode)} method which performs a "surgical"
     * update of specific tags directly at their current positions in the file.
     *
     * <p>Unlike {@link #writeIFD(TiffIFD, Linkage.UpdateMode)}, this method requires a valid IFD to be
     * already present in the file at the position
     * <code>ifd.{@link TiffIFD#assignedFileOffsetOfIFDForWriting() assignedFileOffsetOfIFDForWriting()}</code>.
     * This method rewrites the IFD while strictly preserving the existing
     * TIFF structure and data placement.
     * It reads tags from the file one by one and selects those for which the
     * {@code tagsToUpdate.test(tag)} method returns {@code true} <i>and</i> which also exist
     * in the provided {@code ifd} argument.
     * For these selected tags, it overwrites the values in the file, ensuring that no data is
     * moved and no "gaps" or "holes" are created in the file layout.</p>
     *
     * <p>This method also rewrites the {@link TiffIFD#getNextIFDOffset() next IFD offset} field
     * at the end of the IFD (as required by the TIFF specification).</p>
     *
     * <p>If {@code updateModeForNewIFD} is {@link Linkage.UpdateMode#AUTO_APPEND}, this method attempts to
     * update the linkage using the same logic
     * as {@link #writeIFD(TiffIFD, Linkage.UpdateMode)}.
     * Specifically, it checks the same conditional rules (<b>A</b> and <b>B</b>) and performs the same corrections
     * as described in that method. If conditions are not met, or if
     * <code>updateModeForNewIFD={@link Linkage.UpdateMode#NONE}</code>,
     * this method calls {@link #invalidateLinkage()} in the same situations as
     * {@link #writeIFD(TiffIFD, Linkage.UpdateMode)}.</p>
     *
     * <p>This method is used, for example, by
     * {@link #rewriteImageLayoutStrictlyInPlace(TiffIFD, Linkage.UpdateMode)}
     * method for updating metadata that describes the image layout (such as offsets and byte counts)
     * after the actual pixel data has been written to the stream.</p>
     *
     * <p>Constraints: this method can only be used if the new values have
     * the exact same TIFF type and number of elements (count) as the values
     * currently stored in the file. Any attempt to change the data layout (for example,
     * replacing an array of 16-bit integers with an array of 32-bit values or changing array length)
     * will result in a {@link TiffIFDMismatchException}.
     * The only allowed exception is when the data is embedded into the IFD entry itself;
     * for example, when the value contains only one number and its type is changed
     * from {@link TagType#SHORT} to {@link TagType#LONG} (which may occur for tags
     * such as {@code ImageWidth}, {@code ImageLength}, {@code TileWidth}, {@code TileLength}).</p>
     *
     * @param ifd                 the IFD containing the new values to be written.
     * @param tagsToUpdate        a predicate to select which tags should be updated in the file.
     * @param updateModeForNewIFD if {@link Linkage.UpdateMode#AUTO_APPEND}, the method attempts to
     *                            update the linkage, similar to {@link #writeIFD(TiffIFD, Linkage.UpdateMode)}.
     * @throws IOException              if an I/O error occurs.
     * @throws TiffIFDMismatchException if the new value for a tag does not match
     *                                  the existing value's type or count;
     *                                  if you still need to write the IFD, you may catch this exception
     *                                  and write it at the end of the file using
     *                                  the {@link #writeIFDAtFileEnd(TiffIFD)} method.
     * @throws IllegalArgumentException if the IFD does not have the <i>for-writing</i> file offset.
     * @see #writeIFD(TiffIFD, Linkage.UpdateMode)
     * @see #invalidateLinkage()
     */
    public void rewriteIFDStrictlyInPlace(
            TiffIFD ifd,
            IntPredicate tagsToUpdate,
            Linkage.UpdateMode updateModeForNewIFD)
            throws IOException {
        Objects.requireNonNull(ifd, "Null IFD");
        Objects.requireNonNull(tagsToUpdate, "Null tagsToUpdate");
        Objects.requireNonNull(updateModeForNewIFD, "Null updateModeForNewIFD");
        if (!ifd.isFileOffsetOfIFDForWritingAssigned()) {
            throw new IllegalArgumentException("Offset for writing IFD is not specified");
        }
        synchronized (fileLock) {
            checkVirginFile();
            invalidateCompanionReader();
//            System.out.println("Using accurate rewriting");
            final long ifdOffset = ifd.assignedFileOffsetOfIFDForWriting();
            // - note: unlike writeIFD(), here we DO NOT NEED to call checkFileOffsetForWriting(ifdOffset)
            // for (int k = 0; k < 500; k++) rewriteTagsAt(ifd.map(), tagsToUpdate, ifdOffset); // - timing
            final IFDCommonInformation info = rewriteSelectedTagsAt(ifd.map(), tagsToUpdate, ifdOffset);
            assert info.nextIFDOffset() != null :
                    "prepareReadingIFD with includeNextOffset=true has not read nextIFDOffset";
            ifd.setFileOffsetOfNextIFDOffset(info.offsetOfNextIFDOffset());
            writeNextOffsetAndUpdateLinkage(ifd, updateModeForNewIFD, info.nextIFDOffset());
            // - note: usually there is no sense to write the next offset
            // when updateModeForNewIFD=Linkage.UpdateMode.NONE,
            // but it is not a problem (usually it will be the same)
        }
    }

    /**
     * Equivalent to
     * <code>{@link #rewriteIFDStrictlyInPlace(TiffIFD, IntPredicate, Linkage.UpdateMode)
     * rewriteIFDStrictlyInPlace}(ifd, TiffIFD::{@link TiffIFD#isImageLayoutTag
     * isImageLayoutTag}, updateModeForNewIFD)</code>.
     *
     * @param ifd                 the IFD containing updated layout information.
     * @param updateModeForNewIFD see {@link #writeIFD(TiffIFD, Linkage.UpdateMode)}.
     * @throws IOException              if an I/O error occurs.
     * @throws TiffIFDMismatchException if the layout tag structure has changed.
     * @throws IllegalArgumentException if the IFD does not have the <i>for-writing</i> file offset.
     */
    public void rewriteImageLayoutStrictlyInPlace(TiffIFD ifd, Linkage.UpdateMode updateModeForNewIFD)
            throws IOException {
        rewriteIFDStrictlyInPlace(ifd, TiffIFD::isImageLayoutTag, updateModeForNewIFD);
    }

    /**
     * Rewrites the 'next IFD offset' field in the file, pointing to the IFD with the given index,
     * with the given value.
     *
     * <p>This is a low-level operation for manual adjustment of the IFD chain sequence
     * inside the file. Note that this method always calls {@link #invalidateLinkage()}.</p>
     *
     * @param mainIFDIndex the index of the main IFD (sub-IFDs are ignored).
     * @param newIFDOffset new IFD offset; must be positive.
     * @throws IOException           if an I/O error occurs.
     * @throws IllegalStateException if this file is not yet opened.
     * @see #rewriteLastIFDOffset(long)
     */
    public void rewriteIFDOffset(int mainIFDIndex, long newIFDOffset) throws IOException {
        synchronized (fileLock) {
            if (mainIFDIndex < 0) {
                throw new IllegalArgumentException("Negative IFD index: " + mainIFDIndex);
            }
            if (newIFDOffset <= 0) {
                throw new IllegalArgumentException("Zero or negative IFD offset " + newIFDOffset);
            }
            checkFileOpen();
            invalidateCompanionReader();
            long fileOffset;
            if (mainIFDIndex == 0) {
                fileOffset = offsetOfFirstIFDOffset();
                // - no reasons to read anything
            } else {
                readMainIFDOffset(mainIFDIndex);
                // - sets offsetOfLastScannedIFDOffset and also checks that mainIFDIndex is not too high;
                // a separate call of linkage() is not necessary
                fileOffset = offsetOfLastScannedIFDOffset().orElseThrow(AssertionError::new);
            }
            writeOffsetAt(newIFDOffset, fileOffset);
            // - last argument is not important: the offsetOfLastScannedIFDOffset will not change in any case
            // (because fileOffset == offsetOfLastScannedIFDOffset now)
            invalidateLinkage();
            // - this is low-level correction, we cannot be sure that the IFD chain is still correct
        }
    }

    /**
     * Rewrites the offset, stored in the file at the {@link Linkage#offsetOfIFDChainTerminator()},
     * with the specified value.
     *
     * <p>This is a low-level operation for manual adjustment of the IFD chain sequence
     * inside the file. Note that this method always calls {@link #invalidateLinkage()}.</p>
     *
     * @param newLastIFDOffset new last IFD offset.
     * @throws IOException           if an I/O error occurs.
     * @throws IllegalStateException if this file is not yet opened.
     * @see #rewriteIFDOffset(int, long)
     */
    public void rewriteLastIFDOffset(long newLastIFDOffset) throws IOException {
        synchronized (fileLock) {
            if (newLastIFDOffset < 0) {
                throw new IllegalArgumentException("Negative new last IFD offset " + newLastIFDOffset);
            }
            checkFileOpen();
            invalidateCompanionReader();
            final Linkage linkage = linkage();
            // - necessary for using offsetOfIFDChainTerminator
            writeOffsetAt(newLastIFDOffset, linkage.offsetOfIFDChainTerminator());
            // - last argument is not important: the offsetOfLastScannedIFDOffset will not change in any case
            invalidateLinkage();
            // - this is low-level correction, we cannot be sure that the IFD chain is still correct
        }
    }

    /**
     * Writes the specified offset value into the TIFF file at the given {@code fileOffsetToWrite}.
     *
     * <p>For {@link #isBigTiff() BigTIFF} files, this method writes an 8-byte value via
     * {@link DataHandle#writeLong(long)}. For classic TIFF files, it writes a 4-byte value via
     * {@link DataHandle#writeInt(int)}; in this case, the offset must be a valid 32-bit unsigned value
     * (more exactly, not exceeding {@code 0xFFFFFFF0L = 2^32-16}).
     * Note that this method <i>does not change</i> the current file position ({@link DataHandle#offset()}).</p>
     *
     * <p>This is a low-level method, used internally for writing IFD offsets. Typically, you should
     * not use this method directly. If you choose to call it for low-level file correction,
     * you <i>should</i> call {@link #invalidateLinkage()} and {@link #invalidateCompanionReader()},
     * as this method does not call these invalidations automatically (though they are called automatically
     * in all other cases).</p>
     *
     * @param offsetValue       value to write; must be non-negative.
     * @param fileOffsetToWrite the position in the file where the value should be written.
     * @throws IOException if an I/O error occurs.
     * @see #rewriteIFDOffset(int, long)
     * @see #writeIFD(TiffIFD, Linkage.UpdateMode)
     * @see #rewriteIFDStrictlyInPlace(TiffIFD, IntPredicate, Linkage.UpdateMode)
     */
    public void writeOffsetAt(long offsetValue, long fileOffsetToWrite) throws IOException {
        synchronized (fileLock) {
            final long savedFileOffset = stream.offset();
            try {
                stream.seek(fileOffsetToWrite);
                writeOffset(stream, bigTiff, offsetValue);
            } finally {
                stream.seek(savedFileOffset);
            }
        }
    }

    public void writeTile(TiffTile tile, boolean freeAndFreezeAfterWriting) throws IOException {
        encode(tile);
        writeEncodedTile(tile, freeAndFreezeAfterWriting);
    }

    public int writeTiles(
            Collection<TiffTile> tiles,
            Predicate<TiffTile> needToWrite,
            boolean freeAndFreezeAfterWriting)
            throws IOException {
        Objects.requireNonNull(tiles, "Null tiles");
        Objects.requireNonNull(needToWrite, "Null needToWrite");
        long t1 = debugTime();
        int count = 0;
        long sizeInBytes = 0;
        for (TiffTile tile : tiles) {
            if (needToWrite.test(tile)) {
                writeTile(tile, freeAndFreezeAfterWriting);
                count++;
                sizeInBytes += tile.getSizeInBytes();
            }
        }
        long t2 = debugTime();
        logTiles(tiles, count, sizeInBytes, t1, t2);
        return count;
    }

    public void writeEncodedTile(TiffTile tile, boolean freeAndFreezeAfterWriting) throws IOException {
        Objects.requireNonNull(tile, "Null tile");
        if (tile.isEmpty()) {
            return;
        }
        long t1 = debugTime();
        synchronized (fileLock) {
            checkVirginFile();
            invalidateCompanionReader();
            TiffTileIO.write(tile, stream, alwaysWriteToFileEnd, !bigTiff);
            if (freeAndFreezeAfterWriting) {
                tile.freeAndFreeze();
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
        tile.checkDataLengthMatchesTileSize();
        long t1 = debugTime();
        prepareEncoding(tile);
        long t2 = debugTime();

        final TagCompression compression = tile.compressionOrNoneForMissing().orElse(null);
        // - tile.compressionOrNoneForMissing() returns Optional.of(TagCompression.NONE) if this tag is absent!
        // Note: compression tag is also set to NONE in correctForEncoding() if it was not set before
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
            if (options.getWidth() != tile.getSizeX() || options.getHeight() != tile.getSizeY()) {
                throw new AssertionError("Options were damaged after customization! " + options);
            }
            if (codecCustomizer != null) {
                codecCustomizer.customize(options);
            }
            if (codec instanceof TiffCodec.Timing timing) {
                timing.setTiming(BUILT_IN_TIMING && LOGGABLE_DEBUG);
                timing.resetTiming();
            }
            final byte[] encodedData = codec.compress(data, options);
            setLastCodecReport(options.getReport());
            tile.setEncodedData(encodedData);
            tile.setReport(options.getReport());
        } else {
            final Optional<byte[]> encodedData = encodeByExternalCodec(tile, tile.getDecodedData(), options);
            if (encodedData.isEmpty()) {
                throw new UnsupportedTiffFormatException("TIFF compression with code " +
                        tile.compressionCode() + " is not supported and cannot be encoded: " + tile.ifd());
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
                throw new IllegalArgumentException("Tile for encoding and writing to TIFF file must be " +
                        "separated: " + tile);
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

    /**
     * Equivalent to <code>writer.{@link #correctForEncoding(TiffIFD, boolean)
     * correctForEncoding}(ifd, writer.{@link #isSmartCorrection() isSmartCorrection()})</code>.
     *
     * @param ifd IFD to be corrected.
     * @throws TiffException in the case of some problems, in particular, if IFD settings are not supported.
     */
    public void correctForEncoding(TiffIFD ifd) throws TiffException {
        correctForEncoding(ifd, isSmartCorrection());
    }

    /**
     * Fixes the IFD so that it can be written by this class or throws an exception if it is impossible.
     * This method may change the following tags:
     * <ul>
     *     <li><code>BitsPerSample</code> (258) and <code>SampleFormat</code> (339) &mdash; in the smart mode,
     *     if the actual number of bits per sample is not 1, 8, 16, 32, 64;</li>
     *     <li><code>Compression</code> (259) &mdash; if it is not specified,
     *     it is set to {@link TiffIFD#COMPRESSION_NONE} (1);</li>
     *     <li><code>PhotometricInterpretation</code> (262) &mdash; if it is not specified or in the smart mode;</li>
     *     <li><code>JPEGTables</code> (347) &mdash; this tag is removed for standard JPEG compression (code 7),
     *     because we do not support "abbreviated" JPEG streams: all JPEG tables are always
     *     embedded into each tile/strip;</li>
     *     <li><code>SubIFD</code> (330), <code>Exif IFD</code> (34665), <code>GPSInfo</code> (34853) &mdash;
     *     these tags are always removed (both by this method and by {@link #correctForEntireTiff(TiffIFD)}),
     *     because this writer does not support writing the corresponding IFD inside the TIFF.
     *     </li>
     * </ul>
     *
     * @param ifd             IFD to be corrected.
     * @param smartCorrection more smart correction.
     * @throws TiffException in the case of some problems, in particular, if IFD settings are not supported.
     */
    public void correctForEncoding(TiffIFD ifd, boolean smartCorrection) throws TiffException {
        correctForEncoding(ifd, smartCorrection, false);
    }

    public void correctForEntireTiff(TiffIFD ifd) throws TiffException {
        correctForEntireTiff(ifd, false);
    }

    /**
     * Reads IFD by
     * <code>{@link TiffReader#readMainIFD(int) readMainIFD}(mainIFDIndex)</code>,
     * and, if the second argument is {@code true},
     * assigns its {@link TiffIFD#assignFileOffsetOfIFDForWriting(long) offset-for-writing}
     * to be equal to the {@link TiffIFD#getFileOffsetOfIFD()}.
     *
     * @param mainIFDIndex               index of the {@link TiffIFD#isMainIFD() main IFD} in the TIFF image.
     * @param assignFileOffsetForWriting whether to assign the <i>for-writing</i> file offset.
     * @return the IFD with the specified index.
     * @throws TiffException if <code>mainIFDIndex</code> is too large
     *                       ( &ge;{@link #numberOfExistingImages()} ),
     *                       or if the file is not a correct TIFF file,
     *                       and this was not detected while opening it.
     */
    public TiffIFD existingIFD(int mainIFDIndex, boolean assignFileOffsetForWriting) throws IOException {
        final TiffIFD ifd = readMainIFD(mainIFDIndex);
        if (assignFileOffsetForWriting) {
            ifd.assignFileOffsetOfIFDForWriting(ifd.getFileOffsetOfIFD());
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
     * <p>If <code>correctForEncoding</code> is <code>true</code>,
     * this method automatically calls {@link #correctForEncoding(TiffIFD)} method for
     * the specified <code>ifd</code> argument.
     * While typical usage, this argument should be <code>true</code>.
     * But you may set it to <code>false</code> if you want to control all IFD settings yourself,
     * in particular if you prefer to call the method {@link #correctForEncoding(TiffIFD, boolean)}
     * with non-standard {@link #setSmartCorrection smartCorrection} flag.
     *
     * <p>Note: this method calls {@link TiffMap#buildTileGrid()} and {@link TiffIFD#freeze() freeze}
     * the passed <code>ifd</code>. So you should use this method after completely building IFD.</p>
     *
     * <p>Note: this method forcibly <b>removes</b> tags
     * {@link Tags#SUB_IFD SubIFD} = {@value Tags#SUB_IFD},
     * {@link Tags#EXIF_IFD Exif IFD} = {@value Tags#EXIF_IFD},
     * {@link Tags#GPS_IFD GPS information} = {@value Tags#GPS_IFD GPS} and
     * {@link Tags#INTEROPERABILITY_IFD interoperability IFD} = {@value Tags#INTEROPERABILITY_IFD},
     * because this class does not support writing sub-IFDs or linked IFDs.
     * If you still need to construct TIFF with such tags, you should use more low-level call of
     * {@link TiffWriteMap} constructor.
     *
     * @param ifd                newly created and probably customized IFD.
     * @param resizable          if <code>true</code>, IFD dimensions may not be specified yet: this argument is
     *                           passed to {@link
     *                           TiffWriteMap#TiffWriteMap(TiffWriter, TiffIFD, boolean, boolean)}
     *                           constructor for creating the new map.
     * @param correctForEncoding whether {@link #correctForEncoding(TiffIFD)} should be called;
     *                           usually <code>true</code>.
     * @return map for writing further data.
     * @throws TiffException in the case of some problems.
     */
    public TiffWriteMap newMap(TiffIFD ifd, boolean resizable, boolean correctForEncoding)
            throws TiffException {
        Objects.requireNonNull(ifd, "Null IFD");
        if (ifd.isFrozen()) {
            throw new IllegalStateException("IFD is already frozen for usage while writing TIFF; " +
                    "probably you called this method twice");
        }
        if (correctForEncoding) {
            correctForEncoding(ifd);
        } else {
            correctForEntireTiff(ifd);
            // - this is still necessary: ifd and TiffWriteMap must "know" about the actual file format
        }
        final TiffWriteMap map = new TiffWriteMap(this, ifd, resizable, false);
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

    public TiffWriteMap existingMap(int ifdIndex) throws IOException {
        return existingMap(existingIFD(ifdIndex, true));
    }

    /**
     * Starts overwriting existing IFD image.
     *
     * <p>Note: this method does not remove information about tile/strip offsets and byte counts. So, you can
     * read some tiles from this IFD via {@link TiffReader} class (it is important for tiles, that you need to
     * partially fill, but partially load from the old file).</p>
     *
     * <p>This method builds the grid ({@link TiffMap#buildTileGrid()} method). For each tile in the grid,
     * it calls {@link TiffTile#setStoredInFileDataRange} to set the actual offset and byte counts
     * and calls {@link TiffTile#markWholeTileAsSet()} to indicate
     * that the tile is probably filled with actual data.</p>
     *
     * <p>Note: this method never performs {@link #setSmartCorrection(boolean) "smart correction"}
     * of the specified IFD.</p>
     *
     * @param ifd IFD of some existing image, probably loaded from the current TIFF file.
     * @return map for writing further data.
     */
    public TiffWriteMap existingMap(TiffIFD ifd) throws TiffException {
        Objects.requireNonNull(ifd, "Null IFD");
        if (!ifd.isLoadedFromFile()) {
            throw new IllegalArgumentException("IFD must be read from TIFF file");
        }
        correctForEncoding(ifd, false, true);
        final TiffWriteMap map = new TiffWriteMap(this, ifd, false, true);
        final long[] offsets = ifd.cachedTileOrStripOffsets();
        final long[] byteCounts = ifd.cachedTileOrStripByteCounts();
        final int[] linksToPrevious = ifd.cachedLinksToPreviousSameOffset();
        final int[] linksToNext = ifd.cachedLinksToNextSameOffset();
        assert offsets != null;
        assert byteCounts != null;
        assert linksToPrevious != null;
        assert linksToNext != null;
        map.buildTileGrid();
        final int numberOfTiles = map.numberOfTiles();
        if (offsets.length < numberOfTiles || byteCounts.length < numberOfTiles ||
                linksToPrevious.length < numberOfTiles || linksToNext.length < numberOfTiles) {
            throw new ConcurrentModificationException("Strange length of tile offsets " + offsets.length +
                    ", or byte counts " + byteCounts.length +
                    ", or links to previous/next duplicates " + linksToPrevious.length + "/" + linksToNext.length);
            // - should not occur: it is checked in getTileOrStripOffsets/getTileOrStripByteCounts methods
            // (the only possible way is modification from parallel thread)
        }
        ifd.freeze();
        // - actually not necessary, but helps to avoid possible bugs
        int k = 0;
        for (TiffTile tile : map.tiles()) {
            tile.setStoredInFileDataRange(offsets[k], byteCounts[k], true);
            // - we "tell" that all tiles already exist in the file;
            // note we can use index k, because buildGrid() method, called above for an empty map,
            //  provided the correct tiles order
            tile.setOrClearLinearIndexOfPreviousDuplicate(linksToPrevious[k]);
            tile.setOrClearLinearIndexOfNextDuplicate(linksToNext[k]);
            tile.setDuplicateAutomatically();
            tile.markWholeTileAsSet();
            // - we "tell" that each tile has no unset areas
            k++;
        }
        this.lastMap = map;
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

    public boolean isLastMapPrewritten() {
        return lastMapPrewritten;
    }

    /**
     * Prepares writing a new image with known fixed sizes.
     * This method writes an image header (IFD) to the end of the TIFF file
     * before actual tile/strip data, which helps to improve the performance
     * of future reading of this file.
     *
     * <p>Note: this method does nothing if the image is
     * {@link TiffMap#isResizable() resizable}
     * or if this action is disabled by
     * {@link #setPrewritingAllowed(boolean) setPrewritingAllowed(false)}
     * call.
     * In this case, the IFD will be written at the final stage
     * ({@link #completeWriting(TiffWriteMap)} method).
     *
     * <p>This method sets the {@link #isLastMapPrewritten()} flag
     * to {@code true} if the IFD was actually written,
     * or to {@code false} otherwise.</p>
     *
     * @param map map describing the image.
     */
    public void prewrite(TiffWriteMap map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        lastMapPrewritten = false;
        if (!prewritingAllowed || map.isResizable()) {
            return;
        }
        final long[] offsets = new long[map.numberOfGridTiles()];
        final long[] byteCounts = new long[map.numberOfGridTiles()];
        // - zero-filled by Java
        final TiffIFD ifd = map.ifd();
        ifd.putDataPlacementInFileIgnoringFreeze(offsets, byteCounts);
        if (!ifd.isFileOffsetOfIFDForWritingAssigned()) {
            // - prevents writing in case of a duplicate call
            writeIFD(ifd, Linkage.UpdateMode.NONE);
            lastMapPrewritten = true;
        }
    }

    public int completeWriting(TiffWriteMap map) throws IOException {
        Objects.requireNonNull(map, "Null TIFF map");
        final boolean resizable = map.isResizable();
        map.checkTooSmallDimensionsForCurrentGrid();
        map.encode();
        // - encode tiles, which are not encoded yet

        final TiffIFD ifd = map.ifd();
        if (resizable) {
            ifd.putImageDimensionsIgnoringFreeze(map.dimX(), map.dimY(), true);
        }

        final int count = completeWritingMap(map);
        map.cropAllUnset();
        // - We could call here appendFileUntilEvenLength(),
        // but it not a standard behavior and not a good idea
        // (in this case we will need to "teach" TiffIFD.sizeOfImage method to consider this)
        if (ifd.isFileOffsetOfIFDForWritingAssigned() && ACCURATE_REWRITING_IMAGE_LAYOUT_ON_COMPLETE) {
            // - usually it means that we did call prewrite
            rewriteImageLayoutStrictlyInPlace(ifd, Linkage.UpdateMode.AUTO_APPEND);
        } else {
            writeIFD(ifd, Linkage.UpdateMode.AUTO_APPEND);
        }
        seekToEnd();
        // - This seeking to the file end is not necessary, but can help to avoid accidental bugs
        // (this is much better than keeping file offset in the middle of the last image
        // between IFD and newly written TIFF tiles).
        return count;
    }

    /**
     * Writes a new TIFF image with the specified compression to this TIFF file.
     * For an existing file, the image is appended to the file end.
     * The image is specified as a 3D or 2D matrix of pixels.
     * For a 3D matrix, the first dimension ({@link Matrix#dimX()}) is the width,
     * the second ({@link Matrix#dimY()}) is the height, and
     * the third ({@link Matrix#dimZ()}) is the number of channels.
     * If the image has only {@code 1} channel, a 2D matrix (width and height) is also allowed.
     *
     * <p>This is a high-level convenience method intended for the simplest TIFF writing scenario.
     * It automatically creates a {@link TiffIFD#newStrippedIFD() new stripped IFD},
     * fills it with image information via {@link TiffIFD#putMatrixInformation(Matrix)},
     * sets the specified compression via {@link TiffIFD#putCompression(TagCompression)}
     * and writes the image into the TIFF file by calling:</p>
     * <pre>
     *     {@link TiffWriteMap} map = thisWriter.{@link #newFixedMap newFixedMap}(ifd);
     *     map.{@link TiffWriteMap#writeMatrix(Matrix) writeMatrix}(matrix)</pre>
     * <p>This method is designed for writing the entire image at once.
     * For tiled TIFF images, incremental writing, partial updates,
     * explicit tile flushing or advanced TIFF customization,
     * consider using {@link #newMap(TiffIFD, boolean)} and the returned {@link TiffWriteMap}.</p>
     *
     * @param matrix      3D-matrix of pixels (or 2D-matrix for 1-channel image).
     * @param compression TIFF compression method.
     * @return the created TIFF map used for writing the image.
     * @throws IOException if an I/O error occurs.
     * @see TiffWriteMap
     */
    public TiffWriteMap writeNewMatrix(Matrix<? extends PArray> matrix, TagCompression compression)
            throws IOException {
        Objects.requireNonNull(matrix, "Null matrix");
        Objects.requireNonNull(compression, "Null compression");
        final TiffIFD ifd = TiffIFD.newStrippedIFD(compression, matrix);
        final TiffWriteMap map = newFixedMap(ifd);
        map.writeMatrix(matrix);
        return map;
    }

    /**
     * Writes a new TIFF image with the specified compression to this TIFF file.
     * For an existing file, the image is appended to the file end.
     * The image is specified as a list of 2-dimensional pixel matrices,
     * where each matrix corresponds to one color channel
     * (for example, a list of 3 matrices: red, green and blue for standard RGB images).
     *
     * <p>This is a high-level convenience method intended for the simplest TIFF writing scenario.
     * It automatically creates a {@link TiffIFD#newStrippedIFD() new stripped IFD},
     * fills it with image information via {@link TiffIFD#putChannelsInformation(List)},
     * sets the specified compression via {@link TiffIFD#putCompression(TagCompression)}
     * and writes the image into the TIFF file by calling:</p>
     * <pre>
     *     {@link TiffWriteMap} map = thisWriter.{@link #newFixedMap newFixedMap}(ifd);
     *     map.{@link TiffWriteMap#writeChannels(List) writeChannels}(channels)</pre>
     *
     * <p>This method is designed for writing the entire image at once.
     * For tiled TIFF images, incremental writing, partial updates,
     * explicit tile flushing or advanced TIFF customization,
     * consider using {@link #newMap(TiffIFD, boolean)} and the returned {@link TiffWriteMap}.</p>
     *
     * @param channels    color channels of the image (2-dimensional matrices).
     * @param compression TIFF compression method.
     * @return the created TIFF map used for writing the image.
     * @throws IOException if an I/O error occurs.
     * @see TiffWriteMap
     */
    public TiffWriteMap writeNewChannels(List<? extends Matrix<? extends PArray>> channels, TagCompression compression)
            throws IOException {
        Objects.requireNonNull(channels, "Null channels");
        Objects.requireNonNull(compression, "Null compression");
        final TiffIFD ifd = TiffIFD.newStrippedIFD(compression, channels);
        final TiffWriteMap map = newFixedMap(ifd);
        map.writeChannels(channels);
        return map;
    }

    /**
     * Writes a new TIFF image with the specified compression to this TIFF file.
     * For an existing file, the image is appended to the file end.
     * The image is specified as a Java {@link BufferedImage}.
     *
     * <p>This is a high-level convenience method intended for the simplest TIFF writing scenario.
     * It automatically creates a {@link TiffIFD#newStrippedIFD() new stripped IFD},
     * fills it with image information via {@link TiffIFD#putBufferedImageInformation(BufferedImage)},
     * sets the specified compression via {@link TiffIFD#putCompression(TagCompression)}
     * and writes the image into the TIFF file by calling:</p>
     * <pre>
     *     {@link TiffWriteMap} map = thisWriter.{@link #newFixedMap newFixedMap}(ifd);
     *     map.{@link TiffWriteMap#writeBufferedImage(BufferedImage) writeBufferedImage}(bufferedImage)</pre>
     *
     * <p>This method is designed for writing the entire image at once.
     * For tiled TIFF images, incremental writing, partial updates,
     * explicit tile flushing or advanced TIFF customization,
     * consider using {@link #newMap(TiffIFD, boolean)} and the returned {@link TiffWriteMap}.</p>
     *
     * @param bufferedImage the image to be written.
     * @param compression   TIFF compression method.
     * @return the created TIFF map used for writing the image.
     * @throws IOException if an I/O error occurs.
     * @see TiffWriteMap
     */
    public TiffWriteMap writeNewBufferedImage(BufferedImage bufferedImage, TagCompression compression)
            throws IOException {
        Objects.requireNonNull(bufferedImage, "Null bufferedImage");
        Objects.requireNonNull(compression, "Null compression");
        final TiffIFD ifd = TiffIFD.newStrippedIFD(compression, bufferedImage);
        final TiffWriteMap map = newFixedMap(ifd);
        map.writeBufferedImage(bufferedImage);
        return map;
    }

    public TiffIFD updateDescription(int mainIFDIndex, String description) throws IOException {
        return updateIFD(mainIFDIndex, ifd -> ifd.updateDescription(description));
    }

    public TiffIFD updateIFD(int mainIFDIndex, Function<TiffIFD, TiffIFD.UpdateResult> updater) throws IOException {
        Objects.requireNonNull(updater, "Null updater");
        final TiffIFD ifd = existingIFD(mainIFDIndex, true);
        final TiffIFD changedIFD = ifd.copy();
        final TiffIFD.UpdateResult placement = updater.apply(changedIFD);
        updateIFD(mainIFDIndex, changedIFD, placement);
        return changedIFD;
    }

    public void updateIFD(int mainIFDIndex, TiffIFD changedIFD, TiffIFD.UpdateResult placement) throws IOException {
        Objects.requireNonNull(changedIFD, "Null changed IFD");
        Objects.requireNonNull(placement, "Null IFD placement");
        if (mainIFDIndex < 0) {
            throw new IllegalArgumentException("Negative IFD index: " + mainIFDIndex);
        }
        LOG.log(System.Logger.Level.DEBUG,
                () -> "IFD #%d %s".formatted(
                        mainIFDIndex,
                        switch (placement) {
                            case UNCHANGED -> "unchanged";
                            case OVERWRITE_IN_PLACE -> "updated in place";
                            case CHANGED -> "updated with relocation at the file end";
                        }));
        // - note: we do not try to access numberOfExistingImages to avoid unnecessary loading companionReader()
        if (placement.isUnchanged()) {
            return;
        }
        synchronized (fileLock) {
            if (placement.isRelocationNecessary()) {
                // We must relocate IFD: overwriting in the same place will damage the further image
                // or other embedded data (like Huffman tables in Old-style JPEG).
                // Theoretically, we could provide additional special branch for a case when we REDUCE
                // the size of IFD and DO NOT write anything else (for example, removing a tag);
                // in this case, we could overwrite IFD WITHOUT rewriting any arrays references from IFD tags.
                // But there is no sense to optimize this exotic situation.
                final long p = this.writeIFDAtFileEnd(changedIFD);
                // Note: we ignore sub-IFDs here. So, this method is not absolutely universal.
                this.rewriteIFDOffset(mainIFDIndex, p);
                // - restoring IFD sequence
            } else {
                // System.out.println("In place!");
                this.writeIFDToAssignedOffset(changedIFD);
            }
        }
    }

    public void deleteIFD(int mainIFDIndex) throws IOException {
        if (mainIFDIndex < 0) {
            throw new IllegalArgumentException("Negative IFD index: " + mainIFDIndex);
        }
        synchronized (fileLock) {
            checkFileOpen();
            Linkage linkage = linkage();
            final int numberOfImages = linkage.numberOfMainIFDs();
            if (mainIFDIndex >= numberOfImages) {
                throw new TiffException("No main IFD #" + mainIFDIndex + " in TIFF" + spacedStreamName()
                        + ": index > " + (numberOfImages - 1));
            }
            if (numberOfImages == 1) {
                throw new TiffException("Cannot delete the only existing TIFF image");
            }
            final long nextIFDOffset = mainIFDIndex == numberOfImages - 1 ?
                    TiffIFD.IFD_CHAIN_TERMINATOR :
                    linkage.mainIFDOffset(mainIFDIndex + 1);
            long fileOffsetToWrite = mainIFDIndex == 0 ?
                    offsetOfFirstIFDOffset() :
                    linkage.offsetOfNextIFDOffset(mainIFDIndex - 1);
            invalidateCompanionReader();
            invalidateLinkage();
            writeOffsetAt(nextIFDOffset, fileOffsetToWrite);
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (fileLock) {
            this.lastMap = null;
            this.linkage = null;
            this.reader = null;
            super.close();
        }
    }

    @Override
    public String toString() {
        return "TIFF writer";
    }

    /**
     * Resets all accumulated internal timing statistics used for the
     * {@link #internalTimingReport() timing report}.
     */
    public void resetTiming() {
        timeWriting = 0;
        timeCustomizingEncoding = 0;
        timePreparingEncoding = 0;
        timeEncoding = 0;
        timeEncodingMain = 0;
        timeEncodingBridge = 0;
        timeEncodingAdditional = 0;
    }

    /**
     * Returns detailed internal timing statistics for TIFF encoding and writing.
     */
    public String internalTimingReport() {
        return String.format(Locale.US,
                "%.3f prepare, " +
                        "%.3f customize, " +
                        "%.3f encode [%.3f main%s], " +
                        "%.3f write",
                timePreparingEncoding * 1e-6,
                timeCustomizingEncoding * 1e-6,
                timeEncoding * 1e-6,
                timeEncodingMain * 1e-6,
                timeEncodingBridge + timeEncodingAdditional > 0 ?
                        String.format(Locale.US, " + %.3f encode-bridge + %.3f encode-additional",
                                timeEncodingBridge * 1e-6,
                                timeEncodingAdditional * 1e-6) :
                        "",
                timeWriting * 1e-6);
    }

    protected Optional<byte[]> encodeByExternalCodec(TiffTile tile, byte[] decodedData, TiffCodec.Options options)
            throws TiffException {
        Objects.requireNonNull(tile, "Null tile");
        Objects.requireNonNull(decodedData, "Null decoded data");
        Objects.requireNonNull(options, "Null codec options");
        if (!SCIFIOBridge.isScifioInstalled()) {
            return Optional.empty();
        }
        final Object scifioCodecOptions = options.toSCIFIOStyleOptions(SCIFIOBridge.codecOptionsClass());
        final int width = tile.getSizeX();
        final int height = tile.getSizeY();
        final byte[] encodedData = compressByScifioCodec(tile.ifd(), decodedData, width, height, scifioCodecOptions);
        return Optional.of(encodedData);
    }

    private void invalidateLinkage(boolean logging, Supplier<String> comment) {
        final Linkage linkage = this.linkage;
        this.linkage = null;
        if (logging) {
            LOG.log(System.Logger.Level.DEBUG, () -> "Invalidating linkage" +
                    (comment == null ? "" : comment.get()) +
                    (linkage == null ? " (no linkage)" : ": " + linkage));
        }
    }

    private Linkage linkage(String comment) throws IOException {
        Linkage currentLinkage = this.linkage;
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
                        assert currentLinkage != null;
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

    private void prepareNewMap(TiffWriteMap map) {
        Objects.requireNonNull(map, "Null TIFF map");
        map.buildTileGrid();
        // - useful to perform loops on all tiles, especially in non-resizable case
        TiffIFD ifd = map.ifd();
        ifd.removeNextIFDOffset();
        ifd.removeDataPlacementInFile();
        if (map.isResizable()) {
            ifd.removeImageDimensions();
        }
        ifd.freeze();
        // - actually not necessary, but helps to avoid possible bugs
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
                    TagCompression.toPrettyString(ifd.optCompressionCode(TiffIFD.COMPRESSION_NONE)) +
                    " requires external codecs");
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

    private void checkVirginFile() throws IOException {
        checkFileOpen();
        final boolean exists = stream.exists();
        if (!exists || stream.length() < sizeOfTiffHeader()) {
            // - very improbable, but can occur as a result of direct operations with the output stream
            throw new IllegalStateException(
                    (exists ?
                            "Existing TIFF file is too short (" + stream.length() + " bytes)" :
                            "TIFF file does not exists yet") +
                            ": probably file header was not written correctly by open()/create() methods");
        }
    }

    private void writeIFDNumberOfEntries(int numberOfEntries) throws IOException {
        if (numberOfEntries < 0) {
            throw new IllegalStateException("Number of entries cannot be negative: " + numberOfEntries);
        }
        if (bigTiff) {
            stream.writeLong(numberOfEntries);
        } else {
            if (numberOfEntries > 0xFFFF) {
                throw new TiffException("Too large number of entries " + numberOfEntries +
                        ": it cannot be saved as a 16-bit value");
            }
            stream.writeShort(numberOfEntries);
        }
    }

    private long writeIFDEntries(Map<Integer, Object> ifdMap, long startOffset, int mainIFDLength) throws IOException {
        final long afterMain = startOffset + mainIFDLength;
        final long fileOffsetOfNextOffset;
        try (final BytesHandle ifdStream = newBytesHandle(stream.isLittleEndian());
             final BytesHandle extraBuffer = newBytesHandle(stream.isLittleEndian())) {
            long offset = 0;
            for (final Map.Entry<Integer, Object> e : ifdMap.entrySet()) {
                final Integer tagKey = e.getKey();
                final Object value = e.getValue();
//                System.out.println(">> " + tagKey +
//                        " (value type: " + (value == null ? null : value.getClass().getTypeName()) +
//                        "): " + ifdStream.offset() + ", " + extraBuffer.offset());
                writeIFDValueAtCurrentOffsets(ifdStream, extraBuffer, bigTiff, afterMain, tagKey, value);
                offset += sizeOfIFDEntry();
                if (ifdStream.offset() != offset) {
                    throw new AssertionError("Invalid IFD-stream offset after writing " +
                            Tags.prettyName(tagKey) +
                            " (value type: " + (value == null ? null : value.getClass().getTypeName()) +
                            "): " + ifdStream.offset() + " instead of " + offset);
                }
            }
//            System.out.println(">>>: " + ifdStream.offset() + ", " + extraBuffer.offset());
            copyData(ifdStream, stream);

//            System.out.println(">>>" + stream.offset());
            fileOffsetOfNextOffset = stream.offset();
            writeOffset(stream, bigTiff, TiffIFD.IFD_CHAIN_TERMINATOR);
            // - not too important: will be rewritten in writeIFDNextOffset
            // (but this operator provides a correct TIFF at this stage)
            final long extraLength = extraBuffer.length();
            assert extraBuffer.offset() == extraLength;
            copyData(extraBuffer, stream, true, extraLength);
        }
        return fileOffsetOfNextOffset;
    }

    private IFDCommonInformation rewriteSelectedTagsAt
            (Map<Integer, Object> ifdMap,
             IntPredicate tagsToUpdate,
             long ifdOffset)
            throws IOException {
        Objects.requireNonNull(ifdMap, "Null ifdMap");
        Objects.requireNonNull(tagsToUpdate, "Null tagsToUpdate");
        // You may compare the following code with TiffReader.readIFD
        final IFDCommonInformation info = prepareReadingIFD(ifdOffset, true);

        final byte[] oldIFDBytes = info.ifdBytes();
        final byte[] newIFDBytes = oldIFDBytes.clone();
        // setBigTiff(!isBigTiff()); // - leads to ConcurrentModificationException
        if (oldIFDBytes.length != info.sizeOfAllEntries() + sizeOfOffset()) {
            throw new ConcurrentModificationException("Strange BigTIFF flag change: current size of offsets " +
                    sizeOfOffset() + ", but it was " +
                    (oldIFDBytes.length - info.sizeOfAllEntries()) + " before access to this IFD" +
                    ", probably this occurred due to operations in a parallel thread");
        }
        final DataHandle<?> oldStream = getBytesHandle(oldIFDBytes, info.littleEndian());
        final DataHandle<?> newStream = getBytesHandle(newIFDBytes, info.littleEndian());
        final BytesHandle[] extraBuffers = new BytesHandle[info.n()];
        // - null-filled by Java
        final long[] extraOffsets = new long[info.n()];
        for (int i = 0, disp = 0; i < info.n(); i++, disp += info.sizeOfEntry()) {
            final TiffIFD.Entry oldEntry = readIFDEntry(oldStream, disp, info.offsetOfFirstEntry(), info.fileLength());
            final int tag = oldEntry.tag();
            if (!tagsToUpdate.test(tag) || !ifdMap.containsKey(tag)) {
                continue;
            }
            final Object value = ifdMap.get(tag);
            final BytesHandle extraBuffer = newBytesHandle(info.littleEndian());
            newStream.seek(disp);
            assert extraBuffer.offset() == 0;
            final boolean oldDataEmbedded = oldEntry.isDataEmbeddedInEntry();
            final long addition = oldDataEmbedded ? 0 : oldEntry.valueOffset();
            // - addition + extraBuffer.offset() is a correct offset that should be written to new entry
            writeIFDValueAtCurrentOffsets(newStream, extraBuffer, bigTiff, addition, tag, value);
            final TiffIFD.Entry newEntry = readIFDEntry(newStream, disp, info.offsetOfFirstEntry(), info.fileLength());
            if (newEntry.tag() != oldEntry.tag() || newEntry.isBigTiff() != oldEntry.isBigTiff()) {
                // assertion, not concurrent modification: we have full control over oldStream and newStream
                throw new AssertionError("Write/read tag mismatch");
            }
            final boolean newDataEmbedded = newEntry.isDataEmbeddedInEntry();
            final boolean bothEmbedded = oldDataEmbedded && newDataEmbedded;
            if (newEntry.rawType() != oldEntry.rawType() && !bothEmbedded) {
                throw new TiffIFDMismatchException("Cannot rewrite IFD entry for tag " +
                        Tags.prettyName(tag) + ": the value type " + oldEntry.prettyType() +
                        " changed to " + newEntry.prettyType());
            }
            if (newEntry.valueCount() != oldEntry.valueCount()) {
                throw new TiffIFDMismatchException("Cannot rewrite IFD value for tag " +
                        Tags.prettyName(tag) + ": the number of elements " + oldEntry.valueCount() +
                        " changed to " + newEntry.valueCount());
            }
            if (newDataEmbedded != oldDataEmbedded) {
                // - this flag cannot be different if the types and value counts are equal,
                // or if the value counts are equal and the types are different BUT bothEmbedded=true
                throw new AssertionError("isDataEmbeddedInEntry mismatch");
            }
            if (!oldDataEmbedded) {
                final long valueOffset = newEntry.valueOffset();
                final long valueLength = newEntry.valueLength();
                if (valueOffset != addition) {
                    throw new AssertionError("Offset of the values array was not written " +
                            "correctly: " + valueOffset + " instead of " + addition);
                }
                if (extraBuffer.length() != valueLength) {
                    throw new AssertionError("Extra buffer length " + extraBuffer.length() +
                            " is not equal to the value length " + valueLength);
                }
                if (valueOffset > info.fileLength() - valueLength) {
                    throw new TiffException("Invalid TIFF: offset of IFD values " + valueOffset +
                            " + total lengths of values " + valueLength + " = " +
                            " is outside the file length " + info.fileLength());
                }
                extraOffsets[i] = valueOffset;
                extraBuffers[i] = extraBuffer;
            }
        }
        stream.seek(info.offsetOfFirstEntry());
        copyData(newStream, stream, true, info.sizeOfAllEntries());
        // stream.write(newIFDBytes);
        // - this call does not work in scijava-common 2.99.2: due to a bug in ByteArrayByteBank,
        // writing bytes to BytesHandle ALWAYS reallocates the built-in Java array
        for (int i = 0; i < info.n(); i++) {
            final BytesHandle extraBuffer = extraBuffers[i];
            if (extraBuffer != null) {
                stream.seek(extraOffsets[i]);
                copyData(extraBuffer, stream, true, extraBuffer.length());
            }
        }
        return info;
    }

    private void writeNextOffsetAndUpdateLinkage(
            TiffIFD ifd,
            Linkage.UpdateMode updateModeForNewIFD,
            Long nextIFDOffsetWrittenInFile)
            throws IOException {
        // if (true) { updateNextOffsetAndLinkageAtLegacy(ifd, updateModeForNewIFD); return; }
        final long ifdOffset = ifd.assignedFileOffsetOfIFDForWriting();
        final long fileOffsetOfNextIFDOffset = ifd.getFileOffsetOfNextIFDOffset();
        final long nextIFDOffset = ifd.effectiveNextIFDOffset();
        final boolean appendRequested = updateModeForNewIFD.isAppend();
        final boolean nextIFDOffsetChanged =
                nextIFDOffsetWrittenInFile == null || nextIFDOffset != nextIFDOffsetWrittenInFile;
        if (nextIFDOffsetChanged) {
            writeOffsetAt(nextIFDOffset, fileOffsetOfNextIFDOffset);
        }
        if (appendRequested && ifd.isEffectivelyChainTerminator()) {
            final Linkage linkage = linkage();
            final boolean newIndependentTrailingIFD = !linkage.containsIFDOffset(ifdOffset);
            if (newIndependentTrailingIFD) {
                // - This is the only case when we can safely add new IFD to the chain end:
                // A) newIndependentTrailingIFD - its start is not equal to any of existing IFD offsets;
                // B) isEffectivelyChainTerminator() - it will actually become the chain end.
                writeOffsetAt(ifdOffset, linkage.offsetOfIFDChainTerminator());
                linkage.updateAfterAppendingNewIFD(ifdOffset, fileOffsetOfNextIFDOffset);
                return;
            }
        }
        if (!nextIFDOffsetChanged) {
            // - we didn't change anything
            return;
        }
        final Linkage linkage = this.linkage;
        // - more simple equivalent of: linkageIfPresent().orElse(null);
        if (linkage == null) {
            // - Performance trick! Usually, if there is no linkage (after invalidation), we need to reload it,
            // as the linkage() method does. However, the only goal of the code below is possible INVALIDATION,
            // i.e., clearing to null.
            // So, it makes no sense to use linkage(): if linkage is null, let it stay to be null.
            LOG.log(System.Logger.Level.DEBUG, () ->
                    "Invalidating linkage skipped for IFD with next-IFD-offset " + ifd.nextIFDOffsetToString());
            return;
        }
        if (linkage.containsIFDOffset(ifdOffset)) {
            // - This IFD already exists in the linkage. Rewriting its effectiveNextIFDOffset() above
            // could probably change the chain structure, so the cached linkage cannot be trusted anymore.
            invalidateLinkage(true, () ->
                    " for IFD with next-IFD-offset " + ifd.nextIFDOffsetToString());
        }
        // - If the condition is false, we have a new independent IFD:
        // there are no links to it within the existing linkage in the file.
        // Thus, invalidating linkage does not make sense:
        // reloading linkage later would simply reconstruct the same chain (without this IFD).
        // This situation is typical while using prewrite(TiffWriteMap map) method
    }

    // The algorithm below was used before 1.07.2026. Note that it does not support all functionality
    // of Linkage object: it does not correct offsetPairs list.
    private void updateNextOffsetAndLinkageAtLegacy(TiffIFD ifd, Linkage.UpdateMode updateModeForNewIFD)
            throws IOException {
        final long ifdOffset = ifd.assignedFileOffsetOfIFDForWriting();
        final long fileOffsetOfNextOffset = ifd.getFileOffsetOfNextIFDOffset();
        final boolean update = updateModeForNewIFD.isAppend();
        final long previousOffsetOfIFDChainTerminator;
        final Linkage linkage;
        if (update) {
            linkage = linkage();
            previousOffsetOfIFDChainTerminator = linkage.offsetOfIFDChainTerminator();
        } else {
            linkage = linkageIfPresent().orElse(null);
            previousOffsetOfIFDChainTerminator = -1;
        }
        // - save it, because it will be updated in writeIFDOffsetAt
        final boolean knownIFDOffset = linkage != null && linkage.containsIFDOffset(ifdOffset);
        boolean virginIFDForAppendingNewImages = !ifd.hasNextIFDOffset();
        // - Optimization for writing sequential images.
        // If this IFD is a terminator (either newly created for appending or explicitly marked),
        // we can safely skip invalidateLinkage(): correcting the offsetOfIFDChainTerminator
        // field inside writeIFDOffsetAt() is sufficient to maintain a valid chain state.
        final long nextIFDOffsetIfPresent = virginIFDForAppendingNewImages ? -1 : ifd.getNextIFDOffset();
        // - Used for logging only
        if (knownIFDOffset) {
            virginIFDForAppendingNewImages = false;
        }
        final long nextIFDOffset = ifd.effectiveNextIFDOffset();
        writeOffsetAt(nextIFDOffset, fileOffsetOfNextOffset);
        if (update) {
            updateAfterAppendingNewIFDLegacy(linkage, nextIFDOffset, fileOffsetOfNextOffset);
        }

        // - writes (or rewrites) TiffIFD.IFD_CHAIN_TERMINATOR (or ifd.getNextIFDOffset()
        // to the file at fileOffsetOfNextOffset;
        if (update && !knownIFDOffset) {
            // - Only if it is an absolutely newly added IFD!
            // If this offset is already contained in the list, an attempt to link to it
            // will probably lead to an infinite loop of IFDs.
            // This check is necessary, for example, for overwriting an existing image:
            // without it, the completeWriting method will create an infinite loop.
            writeOffsetAt(ifdOffset, previousOffsetOfIFDChainTerminator);
            // *** However, here is A LITTLE PROBLEM of the old version (before 1.07.2026) ***
            // We are writing ifdOffset at previousOffsetOfIFDChainTerminator even it the case
            // when the new IFD is NOT a terminal IFD.
            // It makes possible to join two series: A1->A2-A3, B1-B2-B3, and we are writing
            // A4 referring to B1.
            // In a new version, this is impossible - manual correction of A3 in the file is necessary.
            updateAfterAppendingNewIFDLegacy(linkage, ifdOffset, previousOffsetOfIFDChainTerminator);
            // - This method adds ifdOffset to allIFDOffsets in UPDATE mode.
            // (In old versions, we passed here an equivalent of the NONE mode, but now
            // UPDATE is necessary for correcting allIFDOffsets and, vice versa,
            // offsetOfIFDChainTerminator will not be changed for offset other than the terminator.)
        }
        if (!virginIFDForAppendingNewImages) {
            invalidateLinkage(true, () -> " for IFD with specified next-IFD-offset=" +
                    nextIFDOffsetIfPresent);
        }
    }

    private static void updateAfterAppendingNewIFDLegacy(
            Linkage linkage, long newIFDOffsetValue, long fileOffsetOfNewIFDOffset) {
        if (newIFDOffsetValue != TiffIFD.IFD_CHAIN_TERMINATOR) {
            linkage.addOffsetToSet(newIFDOffsetValue);
        } else {
            linkage.setOffsetOfIFDChainTerminator(fileOffsetOfNewIFDOffset);
        }
    }

    private void correctForEncoding(TiffIFD ifd, boolean smartCorrection, boolean enableOldJpeg) throws TiffException {
        Objects.requireNonNull(ifd, "Null IFD");
        final int samplesPerPixel = ifd.getSamplesPerPixel();
        if (!ifd.hasTag(Tags.BITS_PER_SAMPLE)) {
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

        if (!ifd.hasTag(Tags.COMPRESSION)) {
            ifd.put(Tags.COMPRESSION, TiffIFD.COMPRESSION_NONE);
            // - We prefer to explicitly specify this case
        }
        TagCompression compression = ifd.optCompressionOrNone();
        // - NONE: we have set it above
        if (!compression.isWritingSupported()) {
            if (smartCorrection && compression == TagCompression.OLD_JPEG) {
                compression = TagCompression.JPEG;
                ifd.putCompression(compression);
                ifd.removeOldJPEGTags();
            } else {
                if (!enableOldJpeg || compression != TagCompression.OLD_JPEG) {
                    throw new UnsupportedTiffFormatException("TIFF compression with code " + compression.code() +
                            " (\"" + compression.prettyName() + "\") is not supported for writing");
                }
            }
        }

        final TagPhotometric suggestedPhotometric = ifd.optPhotometric().orElse(null);
        TagPhotometric newPhotometric = suggestedPhotometric;
        // - note: it is possible that we DO NOT KNOW this newPhotometric interpretation;
        // in this case, newPhotometric will be UNKNOWN, but we should not prevent writing such an image
        // in simple formats like UNCOMPRESSED or LZW: maybe, the client knows how to process it
        if (compression.isStandardJpeg()) {
            if (samplesPerPixel != 1 && samplesPerPixel != 3) {
                throw new TiffException("JPEG compression for " + samplesPerPixel + " channels is not supported");
            }
            if (newPhotometric == null) {
                newPhotometric = samplesPerPixel == 1 ? TagPhotometric.BLACK_IS_ZERO :
                        (compression.isRGBPreferred() || !ifd.isChunked()) ?
                        TagPhotometric.RGB :
                        TagPhotometric.Y_CB_CR;
            } else {
                checkPhotometric(newPhotometric,
                        samplesPerPixel == 1 ? EnumSet.of(TagPhotometric.BLACK_IS_ZERO) :
                                !enforceUseExternalCodec ?
                                        EnumSet.of(TagPhotometric.Y_CB_CR, TagPhotometric.RGB) :
                                        EnumSet.of(TagPhotometric.Y_CB_CR),
                        "JPEG " + samplesPerPixel + "-channel image");
            }
        } else if (samplesPerPixel == 1) {
            final boolean hasColorMap = ifd.hasTag(Tags.COLOR_MAP);
            if (newPhotometric == null) {
                newPhotometric = hasColorMap ?
                        TagPhotometric.RGB_PALETTE :
                        TagPhotometric.BLACK_IS_ZERO;
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
                if (newPhotometric == TagPhotometric.RGB_PALETTE && !hasColorMap) {
                    throw new TiffException("Cannot write TIFF image: newPhotometric interpretation \"" +
                            newPhotometric.prettyName() + "\" requires also \"ColorMap\" tag");
                }
                checkPhotometric(newPhotometric,
                        EnumSet.of(TagPhotometric.BLACK_IS_ZERO,
                                TagPhotometric.WHITE_IS_ZERO,
                                TagPhotometric.RGB_PALETTE),
                        samplesPerPixel + "-channel image");
            }
        } else if (samplesPerPixel == 3) {
            if (newPhotometric == null || compression.isRGBRequired()) {
                newPhotometric = TagPhotometric.RGB;
            } else {
                // Unlike 1 channel/pixel (the case above), we do not prevent the user from
                // setting non-standard custom photometric interpretations: maybe he wants
                // to create some LAB or CMYK TIFF, and he prepared all channels correctly.
                if (ifd.isStandardYCbCrNonJpeg()) {
                    if (!smartCorrection) {
                        throw new UnsupportedTiffFormatException("Cannot write TIFF: encoding YCbCr " +
                                "photometric interpretation is not supported for compression \"" +
                                compression.prettyName() + "\"");
                    } else {
                        // - TiffReader automatically decodes YCbCr into RGB while reading;
                        // we cannot encode pixels back to YCbCr,
                        // and it would be better to change newPhotometric interpretation to RGB.
                        // Note that for JPEG we have no such problem: we CAN encode JPEG as YCbCr.
                        // For other models (like CMYK or CIE Lab), we ignore newPhotometric interpretation
                        // and suppose that the user herself prepared channels in the necessary model.
                        newPhotometric = TagPhotometric.RGB;
                    }
                }
            }
        } else {
            if (newPhotometric == null) {
                if (samplesPerPixel == 4) {
                    // - probably RGBA
                    newPhotometric = TagPhotometric.RGB;
                }
                // else we stay IFD without photometric interpretation: incorrect for good TIFF,
                // but better than senseless interpretation
            }
            // But if newPhotometric is specified, we do not anything;
            // for example, the user can prepare correct data for CMYK image.
        }
        if (newPhotometric != suggestedPhotometric) {
            ifd.putPhotometric(newPhotometric);
        }
        if (compression.isStandardJpeg()) {
            ifd.removeJPEGTables();
        }
        correctForEntireTiff(ifd, enableOldJpeg);
    }

    private void correctForEntireTiff(TiffIFD ifd, boolean enableOldJpeg) throws TiffException {
        Objects.requireNonNull(ifd, "Null IFD");
        final TagCompression compression = ifd.optCompressionOrNone();
        if (compression.hasAdditionalFileEmbeddedMetadata() && !compression.isWritingSupported()) {
            if (!enableOldJpeg || compression != TagCompression.OLD_JPEG) {
                throw new UnsupportedTiffFormatException("TIFF compression with code " + compression.code() +
                        " (\"" + compression.prettyName() + "\") cannot be processed: " +
                        "this format uses file-embedded metadata and requires a full re-encoding, " +
                        "but encoding is not supported");
            }
        }
        ifd.remove(Tags.SUB_IFD);
        ifd.remove(Tags.EXIF_IFD);
        ifd.remove(Tags.GPS_IFD);
        ifd.remove(Tags.INTEROPERABILITY_IFD);
        // - These tags contain offsets to other IFD structures inside the file,
        // but this class does not manage writing such linked/sub IFDs,
        // so the referenced offsets would become invalid.

        ifd.setLittleEndian(stream.isLittleEndian());
        // - will be used, for example, in getCompressionCodecOptions
        ifd.setBigTiff(bigTiff);
        // - not used, but helps to provide better TiffIFD.toString
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
                                filler.fillIfEmpty(getTileInitializer(), getByteFiller());
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
        map.ifd().putDataPlacementInFileIgnoringFreeze(offsets, byteCounts);
        long t2 = debugTime();
        logTiles(map, "completion", "wrote", count, sizeInBytes, t1, t2);
        return count;
    }

    private void seekToEnd() throws IOException {
        synchronized (fileLock) {
            stream.seek(stream.length());
        }
    }

    private void appendFileUntilEvenLength() throws IOException {
        synchronized (fileLock) {
            seekToEnd();
            appendUntilEvenOffset(stream);
        }
    }

    @SuppressWarnings("RedundantThrows")
    private TiffCodec.Options buildOptions(TiffTile tile) throws TiffException {
        final TiffCodec.Options options = new TiffCodec.Options();
        options.setMainOptions(tile);
        options.setIo(this);
        if (this.compressionQuality != null) {
            options.setCompressionQuality(this.compressionQuality);
        }
        if (this.losslessCompressionLevel != null) {
            options.setLosslessCompressionLevel(this.losslessCompressionLevel);
        }
        return options;
    }

    private void logTiles(
            Collection<TiffTile> tiles,
            int count,
            long sizeInBytes,
            long t1,
            long t2) {
        logTiles(tiles.isEmpty() ? null : tiles.iterator().next().map(),
                "middle", "encoded/wrote", count, sizeInBytes, t1, t2);
    }

    private void logMatrix(
            TiffWriteMap map,
            String whatIsWritten,
            long updatingTime,
            long prewriteTime,
            long encodingTime,
            long completingTime) {
        Objects.requireNonNull(map, "Null TIFF map");
        if (BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            LOG.log(System.Logger.Level.DEBUG, () -> {
                final long totalTime = updatingTime + prewriteTime + encodingTime + completingTime;
                final long sizeInBytes = map.totalSizeInBytes();
                return String.format(Locale.US,
                        "%s wrote %s %dx%dx%d (%.3f MB) in %.3f ms = " +
                                "%.3f conversion/copying data%s" +
                                " + %.3f/%.3f encoding/completing " +
                                "(%s), %.3f MB/s",
                        getClass().getSimpleName(),
                        whatIsWritten,
                        map.dimX(), map.dimY(), map.numberOfChannels(),
                        sizeInBytes / 1048576.0,
                        totalTime * 1e-6,
                        updatingTime * 1e-6,
                        (isLastMapPrewritten() ?
                                String.format(Locale.US, " + %.4f prewriting IFD", prewriteTime * 1e-6) :
                                ""),
                        encodingTime * 1e-6, completingTime * 1e-6,
                        internalTimingReport(),
                        sizeInBytes / 1048576.0 / (totalTime * 1e-9));
            });
        }
    }

    // See also the analogous private method in TiffWriteMap
    private static void logTiles(
            TiffMap map, String stage, String action, int count, long sizeInBytes, long t1, long t2) {
        if (BUILT_IN_TIMING && LOGGABLE_DEBUG) {
            LOG.log(System.Logger.Level.TRACE, () ->
                    count == 0 ?
                            String.format(Locale.US,
                                    "%s%s %s no tiles in %.3f ms",
                                    TiffWriter.class.getSimpleName(),
                                    stage == null ? "" : " (" + stage + " stage)",
                                    action,
                                    (t2 - t1) * 1e-6) :
                            String.format(Locale.US,
                                    "%s%s %s %d tiles %dx%dx%d (%.3f MB) in %.3f ms, %.3f MB/s",
                                    TiffWriter.class.getSimpleName(),
                                    stage == null ? "" : " (" + stage + " stage)",
                                    action,
                                    count, map.numberOfChannels(), map.tileSizeX(), map.tileSizeY(),
                                    sizeInBytes / 1048576.0,
                                    (t2 - t1) * 1e-6,
                                    sizeInBytes / 1048576.0 / ((t2 - t1) * 1e-9)));
        }
    }

    private void checkFileOffsetForWriting(long fileOffsetOfIFD) throws TiffException {
        assert fileOffsetOfIFD >= 0 : "negative IFD file offset from assignedFileOffsetOfIFDForWriting";
        assert (fileOffsetOfIFD & 0x1) == 0 :
                "Odd IFD file offset " + fileOffsetOfIFD + " from assignedFileOffsetOfIFDForWriting";
        if (!bigTiff && fileOffsetOfIFD > MAXIMAL_ALLOWED_32BIT_IFD_OFFSET) {
            throw new TiffException("Attempt to write too large TIFF file without BigTIFF mode: " +
                    "offset of new IFD will be " + fileOffsetOfIFD + " > " + MAXIMAL_ALLOWED_32BIT_IFD_OFFSET);
        }
    }

    private static void checkPhotometric(
            TagPhotometric photometric,
            EnumSet<TagPhotometric> allowed,
            String whatToWrite)
            throws TiffException {
        if (photometric != null) {
            if (!allowed.contains(photometric)) {
                throw new TiffException("Writing " + whatToWrite + " with photometric interpretation \"" +
                        photometric.prettyName() + "\" is not supported (only " +
                        allowed.stream().map(ph -> "\"" + ph.prettyName() + "\"")
                                .collect(Collectors.joining(", ")) +
                        " allowed)");
            }
        }
    }

    private static DataHandle<?> openWithDeletingPreviousFileIfRequested(
            Path file,
            TiffCreateMode createMode)
            throws IOException {
        Objects.requireNonNull(file, "Null file");
        Objects.requireNonNull(createMode, "Null createMode");
        if (createMode.isForceCreateNewFile()) {
            Files.deleteIfExists(file);
        }
        return getFileHandle(file);
    }
}